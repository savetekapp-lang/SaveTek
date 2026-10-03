package com.nazeeltek.savetek

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * يحوّل فيديو إلى ملف صوت MP3 باستخدام FFmpeg الموجود داخل التطبيق.
 * الفيديو الأصلي لا يُلمس ولا يُحذف.
 */
object AudioConverter {

    /** خطأ خاص عند إلغاء المستخدم للتحويل. */
    class CanceledException : Exception()

    private val processes = ConcurrentHashMap<String, Process>()
    private val canceled: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * يحوّل الفيديو [source] ويرجع ملف MP3 مؤقتاً (يُنقل بعدها إلى Music/SaveTek).
     * onProgress تستقبل نسبة من 0 إلى 1، أو null أثناء التحضير.
     */
    suspend fun convert(
        context: Context,
        id: String,
        source: Uri,
        title: String,
        bitrateKbps: Int,
        onProgress: (Float?) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        // FFmpeg يُفك داخل التطبيق مع محرك التحميل
        Downloader.ensureReady(context)
        canceled.remove(id)

        val work = workDir(context, id).apply { deleteRecursively(); mkdirs() }
        onProgress(null)

        val durationUs = durationMicros(context, source)
        // FFmpeg يحتاج مسار ملف حقيقي: نستخدم مسار الملف مباشرة إن أمكن، وإلا ننسخه مؤقتاً
        val input = directPath(context, source) ?: copyToCache(context, source, File(work, "input"))
        if (id in canceled) throw CanceledException()

        // اسم الملف الناتج نظيف ومحصور داخل مجلد العمل (لا يمكن الخروج منه بـ ../)
        val output = FileNames.childFile(work, title, "mp3")
        val kbps = bitrateKbps.coerceIn(64, 320)

        /*
         * أمان الأمر:
         * - ProcessBuilder يمرّر كل عنصر كمعامل مستقل، بدون Shell، فلا يمكن حقن أوامر عبر الأسماء.
         * - "file:" قبل المسارات تجبر FFmpeg على اعتبارها ملفات محلية فقط (لا تُفهم كخيار أو بروتوكول).
         * - protocol_whitelist=file يمنع FFmpeg من الاتصال بالإنترنت حتى لو كان الملف المختار قائمة تشغيل خبيثة.
         * - العنوان يُمرَّر كقيمة لـ metadata فقط، وننظّف منه رموز التحكم.
         */
        val metaTitle = title.filterNot { it.isISOControl() }.take(200)
        val command = listOf(
            ffmpegBinary(context).absolutePath,
            "-hide_banner", "-y", "-nostdin",
            "-protocol_whitelist", "file",
            "-i", "file:" + input.absolutePath,
            "-vn",                       // بدون صورة
            "-map", "0:a:0",             // أول مسار صوت
            "-c:a", "libmp3lame",
            "-b:a", "${kbps}k",
            "-metadata", "title=$metaTitle",
            "-progress", "pipe:1", "-nostats",
            "file:" + output.absolutePath,
        )
        val builder = ProcessBuilder(command)
        builder.environment()["LD_LIBRARY_PATH"] = ffmpegLibraryPath(context)
        builder.environment()["TMPDIR"] = context.cacheDir.absolutePath

        val process = builder.start()
        processes[id] = process

        // نقرأ رسائل الأخطاء في خيط منفصل (نحتفظ بآخر الأسطر لعرض سبب الفشل)
        val errorTail = ArrayDeque<String>()
        val errReader = thread(name = "ffmpeg-err-$id") {
            process.errorStream.bufferedReader().forEachLine { line ->
                synchronized(errorTail) {
                    errorTail.addLast(line)
                    if (errorTail.size > 15) errorTail.removeFirst()
                }
            }
        }

        // FFmpeg يكتب تقدّمه سطراً سطراً: out_time_us=12345678
        process.inputStream.bufferedReader().forEachLine { line ->
            val value = when {
                line.startsWith("out_time_us=") -> line.substringAfter('=')
                line.startsWith("out_time_ms=") -> line.substringAfter('=')
                else -> null
            }?.toLongOrNull()
            if (value != null && durationUs > 0) {
                onProgress((value.toFloat() / durationUs).coerceIn(0f, 1f))
            }
        }

        val exitCode = process.waitFor()
        errReader.join(2000)
        processes.remove(id)
        if (input.parentFile == work) input.delete()

        if (canceled.remove(id)) throw CanceledException()
        if (exitCode != 0 || !output.exists() || output.length() == 0L) {
            val reason = synchronized(errorTail) { errorTail.lastOrNull { it.isNotBlank() } }
            throw IllegalStateException(reason ?: context.str(R.string.error_convert_failed))
        }
        output
    }

    /** إيقاف تحويل جارٍ. */
    fun cancel(id: String) {
        canceled.add(id)
        processes.remove(id)?.destroy()
    }

    /** مجلد عمل لكل تحويل. المعرّف يُنظَّف حتى لا يحتوي "../" أو رموزاً غريبة. */
    fun workDir(context: Context, id: String) =
        File(context.cacheDir, "convert/" + id.filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "x" })

    /** اسم الملف كما يظهر في مدير الملفات (بدون الامتداد)، لاستخدامه كعنوان. */
    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?.substringBeforeLast('.')
    }.getOrNull()

    private fun ffmpegBinary(context: Context) =
        File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")

    /** مكان مكتبات FFmpeg بعد فكّها (نفس المسار الذي تستخدمه مكتبة yt-dlp). */
    private fun ffmpegLibraryPath(context: Context): String {
        val packages = File(File(context.noBackupFilesDir, "youtubedl-android"), "packages")
        return listOf("ffmpeg", "python")
            .joinToString(":") { File(packages, "$it/usr/lib").absolutePath }
    }

    /** مدة الفيديو بالميكروثانية (لحساب نسبة التقدم). */
    private fun durationMicros(context: Context, uri: Uri): Long = runCatching {
        MediaMetadataRetriever().run {
            try {
                setDataSource(context, uri)
                (extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
            } finally {
                release()
            }
        }
    }.getOrDefault(0L)

    /** إذا كان الملف في المعرض ويمكن قراءته مباشرة، نستخدم مساره بدل نسخه. */
    @Suppress("DEPRECATION")
    private fun directPath(context: Context, uri: Uri): File? = runCatching {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return@runCatching uri.path?.let(::File)
        if (uri.authority != MediaStore.AUTHORITY) return@runCatching null
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?.let(::File)
    }.getOrNull()?.takeIf { it.canRead() }

    private fun copyToCache(context: Context, uri: Uri, dest: File): File {
        context.contentResolver.openInputStream(uri)!!.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
        return dest
    }

}
