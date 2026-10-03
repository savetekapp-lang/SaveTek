package com.nazeeltek.savetek

import android.content.Context
import android.os.SystemClock
import com.nazeeltek.savetek.data.DownloadItem
import com.nazeeltek.savetek.data.DownloadRepository
import com.nazeeltek.savetek.data.DownloadStatus
import com.nazeeltek.savetek.data.SettingsRepository
import com.nazeeltek.savetek.data.VideoPreview
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * كل ما يخص محرك التحميل yt-dlp موجود هنا:
 * التجهيز، جلب معلومات الفيديو، التحميل، الإلغاء، والتحديث.
 */
object Downloader {

    /**
     * الفحص التلقائي يحدث عند كل فتح للتطبيق. نتجاهل الفتحات المتقاربة جداً
     * (مثل إعادة بناء الشاشة بعد تغيير اللغة) حتى لا نكرر الفحص بلا داعٍ.
     */
    private const val MIN_CHECK_GAP_MS = 10L * 60 * 1000

    private val initLock = Mutex()

    /** قفل يمنع بدء أي تحميل أثناء تحديث المحرك (حتى لا يُستبدل ملف المحرك وهو يعمل). */
    private val updateLock = Mutex()

    @Volatile
    private var ready = false

    @Volatile
    private var lastAutoCheck = 0L

    private val _version = MutableStateFlow<String?>(null)
    /** رقم إصدار yt-dlp الحالي (يتحدّث تلقائياً بعد كل تحديث). */
    val engineVersion: StateFlow<String?> = _version.asStateFlow()

    /** هل يجري تحديث المحرك الآن؟ */
    val isUpdating: Boolean get() = updateLock.isLocked

    /** تجهيز المحرك (فك بايثون و FFmpeg). أول مرة قد تأخذ عدة ثوانٍ. */
    suspend fun ensureReady(context: Context) = withContext(Dispatchers.IO) {
        initLock.withLock {
            if (ready) return@withLock
            YoutubeDL.getInstance().init(context.applicationContext)
            FFmpeg.getInstance().init(context.applicationContext)
            ready = true
            _version.value = YoutubeDL.getInstance().version(context.applicationContext)
        }
    }

    /** ينتظر إذا كان المحرك يُحدَّث الآن، ثم يكمل. */
    private suspend fun waitForUpdate() {
        updateLock.withLock { }
    }

    /** يجلب عنوان الفيديو وصورته ومدته والجودات المتاحة دون تحميله. */
    suspend fun fetchPreview(context: Context, url: String): VideoPreview = withContext(Dispatchers.IO) {
        val safe = requireSafeUrl(url)
        ensureReady(context)
        waitForUpdate()
        // نطلب بيانات yt-dlp الخام كاملة (JSON) ونقرأها بأنفسنا،
        // لأن المكتبة تُسقط بعض الحقول المهمة لحساب الحجم (مثل vbr)
        val request = YoutubeDLRequest(safe).apply {
            addSafetyOptions()
            addOption("--dump-single-json")
            addOption("--skip-download")
            addOption("--no-playlist")
            addOption("--no-warnings")
            endOfOptions()
        }
        val response = YoutubeDL.getInstance().execute(request)
        FormatParser.parse(safe, response.out, context.str(R.string.default_video_title))
    }

    /** يرفض أي رابط غير آمن (حماية إضافية حتى لو وصل رابط من مكان آخر). */
    private fun requireSafeUrl(url: String): String =
        UrlSafety.safeUrl(url) ?: throw IllegalArgumentException("Unsafe URL rejected")

    /**
     * خيارات أمان ثابتة لكل أوامر yt-dlp:
     * - تجاهل أي ملف إعدادات خارجي قد يضيف أوامر.
     * - إلغاء أي أمر --exec.
     */
    private fun YoutubeDLRequest.addSafetyOptions() {
        addOption("--ignore-config")
        addOption("--no-exec")
    }

    /**
     * "--" تعني لـ yt-dlp: انتهت الخيارات، وما بعدها رابط فقط.
     * المكتبة تضع هذه الأوامر مباشرة قبل الرابط وبعد كل الخيارات.
     */
    private fun YoutubeDLRequest.endOfOptions() {
        addCommands(listOf("--"))
    }

    /**
     * يحمّل العنصر ويرجع الملف الناتج.
     * onProgress تستقبل النسبة (0 إلى 100) والوقت المتبقي بالثواني.
     */
    suspend fun download(
        context: Context,
        item: DownloadItem,
        onProgress: (percent: Float, etaSeconds: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val safe = requireSafeUrl(item.url)
        ensureReady(context)
        waitForUpdate()
        val dir = tempDir(context, item.id).apply {
            deleteRecursively()
            mkdirs()
        }

        val request = YoutubeDLRequest(safe).apply {
            addSafetyOptions()
            if (item.audioOnly) {
                // صوت فقط، يُحوَّل إلى MP3 بأعلى جودة
                addOption("-f", "ba/b")
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "0")
            } else {
                val h = item.maxHeight
                addOption(
                    "-f",
                    if (h != null) "bv*[height<=$h]+ba/b[height<=$h]/bv*+ba/b" else "bv*+ba/b"
                )
                // الأولوية للدقة، ثم صيغة mp4 لتعمل في كل المشغلات
                addOption("-S", "res,ext:mp4:m4a")
                // دمج الصوت والصورة في ملف mp4 واحد (يستخدم FFmpeg)
                addOption("--merge-output-format", "mp4")
            }
            addOption("--no-playlist")
            addOption("--no-mtime")
            // اسم ثابت داخل مجلد مؤقت خاص بالتطبيق: العنوان لا يدخل في المسار أبداً هنا
            addOption("-o", "${dir.absolutePath}/download.%(ext)s")
            endOfOptions()
        }

        YoutubeDL.getInstance().execute(request, item.id) { progress, eta, _ ->
            onProgress(progress, eta)
        }

        // نبحث عن الملف النهائي ونتجاهل الملفات المؤقتة
        val downloaded = dir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?.maxByOrNull { it.length() }
            ?: throw IllegalStateException(context.str(R.string.error_file_not_found))

        // نعيد التسمية باسم نظيف مبني على العنوان، ونتأكد أنه داخل المجلد نفسه
        val finalFile = FileNames.childFile(dir, item.title, downloaded.extension)
        if (downloaded.renameTo(finalFile)) finalFile else downloaded
    }

    /** مجلد مؤقت لكل تحميل. المعرّف يُنظَّف حتى لا يحتوي "../" أو رموزاً غريبة. */
    fun tempDir(context: Context, id: String) =
        File(context.cacheDir, "downloads/" + id.filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "x" })

    /** إيقاف تحميل معيّن. */
    fun cancel(id: String) {
        YoutubeDL.getInstance().destroyProcessById(id)
    }

    /** هل توجد مهام جارية (تحميل أو تحويل)؟ لا نحدّث المحرك أثناءها. */
    fun hasRunningJobs(): Boolean =
        DownloadRepository.items.value.any { it.status == DownloadStatus.RUNNING }

    /**
     * يتحقق من وجود إصدار أحدث لـ yt-dlp ويحدّثه إن وُجد.
     * المكتبة تقارن رقم الإصدار أولاً، ولا تحمّل شيئاً إذا كان المحرك محدّثاً.
     * يرجع رسالة توضح النتيجة، ويرمي خطأ إذا فشل الاتصال بالإنترنت.
     */
    suspend fun update(context: Context): String = withContext(Dispatchers.IO) {
        ensureReady(context)
        updateLock.withLock {
            val app = context.applicationContext
            val status = YoutubeDL.getInstance().updateYoutubeDL(app)
            val v = YoutubeDL.getInstance().version(app)
            _version.value = v
            val shown = v ?: context.str(R.string.unknown)
            when (status) {
                YoutubeDL.UpdateStatus.DONE -> {
                    SettingsRepository.setEngineUpdatedAt(System.currentTimeMillis())
                    context.str(R.string.update_done, shown)
                }
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> context.str(R.string.update_latest, shown)
                else -> context.str(R.string.update_finished, shown)
            }
        }
    }

    /**
     * فحص صامت عند فتح التطبيق: يحدّث المحرك فقط إذا وُجد إصدار أحدث.
     * لا يعمل إذا كان خيار "الواي فاي فقط" يمنع الاتصال، أو توجد تحميلات/تحويلات جارية.
     */
    suspend fun autoUpdateOnOpen(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (lastAutoCheck != 0L && now - lastAutoCheck < MIN_CHECK_GAP_MS) return
        if (wifiBlocked(context) || hasRunningJobs() || isUpdating) return
        lastAutoCheck = now
        runCatching { update(context) }
    }

    /** هل الخطأ بسبب رفض الموقع للطلب (HTTP 403)؟ غالباً يُحلّ بتحديث المحرك. */
    fun isForbiddenError(e: Throwable): Boolean {
        val msg = e.message ?: return false
        return msg.contains("HTTP Error 403") || msg.contains("403: Forbidden") ||
            msg.contains("403 Forbidden") || msg.contains("status code 403")
    }

    /** يستخرج سطر الخطأ المفيد من رسالة yt-dlp الطويلة. */
    fun friendlyError(context: Context, e: Throwable): String {
        val raw = e.message ?: return context.str(R.string.error_unknown)
        val line = raw.lines().lastOrNull { it.contains("ERROR") } ?: raw.lines().first()
        return line.take(300)
    }
}
