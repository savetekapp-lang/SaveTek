package com.nazeeltek.savetek

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.app.TaskStackBuilder
import androidx.core.content.ContextCompat
import com.nazeeltek.savetek.data.DEFAULT_MP3_BITRATE
import com.nazeeltek.savetek.data.DownloadItem
import com.nazeeltek.savetek.data.DownloadRepository
import com.nazeeltek.savetek.data.DownloadStatus
import com.nazeeltek.savetek.data.JobKind
import com.nazeeltek.savetek.data.LABEL_MP3
import com.nazeeltek.savetek.player.PlayerActivity
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * خدمة أمامية (Foreground Service): تحمّل الفيديوهات وتحوّلها إلى صوت
 * حتى لو أُغلق التطبيق أو أُطفئت الشاشة، وتعرض إشعاراً فيه شريط التقدم وزر "إلغاء".
 */
class DownloadService : Service() {

    companion object {
        private const val ACTION_START = "com.nazeeltek.savetek.START"
        private const val ACTION_CANCEL = "com.nazeeltek.savetek.CANCEL"
        private const val ACTION_RETRY = "com.nazeeltek.savetek.RETRY"
        private const val EXTRA_ID = "id"
        private const val CHANNEL_PROGRESS = "progress"
        private const val CHANNEL_DONE = "done"
        private const val PROGRESS_NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60 * 60 * 1000   // 6 ساعات كحد أقصى للأمان
        const val EXTRA_OPEN_DOWNLOADS = "open_downloads"

        /** يضيف عنصراً جديداً للقائمة ويبدأ تنفيذه في الخلفية. */
        fun enqueue(context: Context, item: DownloadItem) {
            DownloadRepository.add(item)
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_START).putExtra(EXTRA_ID, item.id)
            ContextCompat.startForegroundService(context, intent)
        }

        /** يبدأ تحويل فيديو إلى MP3. الفيديو الأصلي يبقى كما هو. */
        fun enqueueConversion(
            context: Context,
            source: Uri,
            title: String,
            thumbnail: String?,
            bitrateKbps: Int,
        ) {
            enqueue(
                context,
                DownloadItem(
                    id = UUID.randomUUID().toString(),
                    url = "",
                    title = title,
                    thumbnail = thumbnail,
                    qualityLabel = "$LABEL_MP3 · $bitrateKbps kbps",
                    maxHeight = null,
                    audioOnly = true,
                    status = DownloadStatus.RUNNING,
                    kind = JobKind.CONVERT,
                    sourceUri = source.toString(),
                    bitrateKbps = bitrateKbps,
                ),
            )
        }

        /** يعيد محاولة مهمة فشلت. يرجع false إذا منعها خيار "الواي فاي فقط". */
        fun retry(context: Context, failed: DownloadItem): Boolean {
            if (failed.kind == JobKind.DOWNLOAD && wifiBlocked(context)) return false
            NotificationManagerCompat.from(context).cancel(failed.id.hashCode())
            DownloadRepository.remove(failed.id)
            enqueue(context, freshCopy(failed))
            return true
        }

        fun cancel(id: String) {
            // الإلغاء لا يحتاج تشغيل الخدمة؛ نوقف العملية مباشرة
            Downloader.cancel(id)
            AudioConverter.cancel(id)
        }

        private fun freshCopy(item: DownloadItem) = item.copy(
            id = UUID.randomUUID().toString(), status = DownloadStatus.RUNNING,
            progress = null, error = null, note = null, createdAt = System.currentTimeMillis(),
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** معرّفات المهام الجارية حالياً (مجموعة آمنة للاستخدام من أكثر من خيط). */
    private val active: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** تحميلات أوقفناها بسبب انقطاع الواي فاي (لنعرض السبب الصحيح بدل "أُلغي"). */
    private val stoppedForWifi: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private var lastNotify = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = NotificationManagerCompat.from(this)
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_PROGRESS, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(str(R.string.channel_progress)).build()
        )
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_DONE, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(str(R.string.channel_done)).build()
        )
        watchNetwork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // يجب إظهار الإشعار فوراً عند بدء الخدمة
        startInForeground()
        when (intent?.action) {
            ACTION_START -> intent.getStringExtra(EXTRA_ID)?.let { launchJob(it) }
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_ID)?.let { cancel(it) }
            ACTION_RETRY -> intent.getStringExtra(EXTRA_ID)?.let { id ->
                val failed = DownloadRepository.get(id)
                if (failed != null && failed.status == DownloadStatus.FAILED) {
                    if (failed.kind == JobKind.DOWNLOAD && wifiBlocked(this)) {
                        Toast.makeText(this, str(R.string.msg_wifi_blocked), Toast.LENGTH_LONG).show()
                    } else {
                        NotificationManagerCompat.from(this).cancel(id.hashCode())
                        DownloadRepository.remove(id)
                        val fresh = freshCopy(failed)
                        DownloadRepository.add(fresh)
                        launchJob(fresh.id)
                    }
                }
            }
        }
        stopIfIdle()
        return START_NOT_STICKY
    }

    private fun launchJob(id: String) {
        if (!active.add(id)) return
        acquireWakeLock()
        scope.launch {
            val item = DownloadRepository.get(id)
            if (item?.kind == JobKind.CONVERT) runConversion(id, item) else runDownload(id, item)
        }
    }

    /** يحدّث التقدم في القائمة، ويحدّث الإشعار مرة كل ثانية تقريباً حتى لا نُثقل الجوال. */
    private fun reportProgress(id: String, progress: Float?, eta: Long = 0, note: String? = null) {
        DownloadRepository.update(id) { it.copy(progress = progress, etaSeconds = eta, note = note) }
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotify > 1000) {
            lastNotify = now
            updateProgressNotification()
        }
    }

    // ───────────── التحميل ─────────────

    private suspend fun runDownload(id: String, item: DownloadItem?) {
        try {
            if (item == null) return
            if (wifiBlocked(this)) throw WifiBlockedException()

            var attempt = 1
            while (true) {
                try {
                    val file = Downloader.download(this, item) { percent, eta ->
                        reportProgress(id, if (percent < 0) null else percent / 100f, eta)
                    }
                    finishSuccessfully(id, file, item.audioOnly)
                    break
                } catch (e: YoutubeDL.CanceledException) {
                    throw e
                } catch (e: Exception) {
                    // خطأ 403: غالباً المحرك قديم. نحدّثه ونعيد المحاولة مرة واحدة فقط
                    if (attempt == 1 && Downloader.isForbiddenError(e)) {
                        attempt++
                        DownloadRepository.update(id) {
                            it.copy(progress = null, note = str(R.string.note_403_retry))
                        }
                        updateProgressNotification()
                        // لا نحدّث المحرك إذا كانت هناك مهمة أخرى جارية غير هذه
                        val othersRunning = DownloadRepository.items.value
                            .count { it.status == DownloadStatus.RUNNING } > 1
                        if (!othersRunning) runCatching { Downloader.update(this) }
                        Downloader.tempDir(this, id).deleteRecursively()
                        continue
                    }
                    throw e
                }
            }
        } catch (e: YoutubeDL.CanceledException) {
            if (stoppedForWifi.remove(id)) {
                markFailed(id, item, str(R.string.error_wifi_lost))
            } else {
                DownloadRepository.remove(id)   // ألغاه المستخدم
            }
        } catch (e: WifiBlockedException) {
            markFailed(id, item, str(R.string.msg_wifi_blocked))
        } catch (e: Exception) {
            markFailed(id, item, Downloader.friendlyError(this, e))
        } finally {
            Downloader.tempDir(this, id).deleteRecursively()
            jobFinished(id)
        }
    }

    // ───────────── التحويل إلى صوت ─────────────

    private suspend fun runConversion(id: String, item: DownloadItem) {
        try {
            val source = item.sourceUri?.let(Uri::parse)
                ?: throw IllegalStateException(str(R.string.error_source_missing))
            val file = AudioConverter.convert(
                context = this,
                id = id,
                source = source,
                title = item.title,
                bitrateKbps = item.bitrateKbps ?: DEFAULT_MP3_BITRATE,
            ) { progress ->
                reportProgress(id, progress, note = if (progress == null) str(R.string.note_preparing_file) else null)
            }
            finishSuccessfully(id, file, audioOnly = true)
        } catch (e: AudioConverter.CanceledException) {
            DownloadRepository.remove(id)   // ألغاه المستخدم
        } catch (e: Exception) {
            markFailed(id, item, e.message?.take(300) ?: str(R.string.error_convert_failed))
        } finally {
            AudioConverter.workDir(this, id).deleteRecursively()
            jobFinished(id)
        }
    }

    // ───────────── مشترك ─────────────

    private fun finishSuccessfully(id: String, file: File, audioOnly: Boolean) {
        DownloadRepository.update(id) { it.copy(progress = null, note = str(R.string.note_saving)) }
        val saved = GallerySaver.save(this, file, audioOnly)
        DownloadRepository.update(id, persist = true) {
            it.copy(
                status = DownloadStatus.DONE, progress = 1f, fileName = file.name,
                uri = saved.uri?.toString(), mime = saved.mime, note = null,
            )
        }
        DownloadRepository.get(id)?.let { notifyCompleted(it) }
    }

    private suspend fun jobFinished(id: String) = withContext(Dispatchers.Main) {
        active.remove(id)
        stopIfIdle()
    }

    private class WifiBlockedException : Exception()

    private fun markFailed(id: String, item: DownloadItem?, error: String) {
        DownloadRepository.update(id, persist = true) {
            it.copy(status = DownloadStatus.FAILED, progress = null, error = error, note = null)
        }
        notifyFailed(id, item, error)
    }

    // ───────────── الواي فاي فقط: نوقف التحميلات إذا انقطع الواي فاي ─────────────

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = check()
            override fun onLost(network: Network) = check()
            private fun check() {
                if (!wifiBlocked(this@DownloadService)) return
                // التحويل لا يحتاج إنترنت، فنوقف التحميلات فقط
                DownloadRepository.items.value
                    .filter { it.status == DownloadStatus.RUNNING && it.kind == JobKind.DOWNLOAD && it.id in active }
                    .forEach { item ->
                        stoppedForWifi.add(item.id)
                        Downloader.cancel(item.id)
                    }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
    }

    // ───────────── إبقاء المعالج يعمل والشاشة مطفأة ─────────────

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SaveTek:download").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // ───────────── الإشعارات ─────────────

    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, PROGRESS_NOTIFICATION_ID, buildProgressNotification(), type)
    }

    private fun stopIfIdle() {
        if (active.isEmpty()) {
            releaseWakeLock()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            updateProgressNotification()
        }
    }

    private fun immutable() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun openDownloadsIntent() = Intent(this, MainActivity::class.java)
        .putExtra(EXTRA_OPEN_DOWNLOADS, true)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun openDownloadsPending(requestCode: Int): PendingIntent =
        PendingIntent.getActivity(this, requestCode, openDownloadsIntent(), immutable())

    private fun serviceAction(action: String, id: String): PendingIntent {
        val intent = Intent(this, DownloadService::class.java).setAction(action).putExtra(EXTRA_ID, id)
        val code = (action + id).hashCode()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && action == ACTION_RETRY) {
            PendingIntent.getForegroundService(this, code, intent, immutable())
        } else {
            PendingIntent.getService(this, code, intent, immutable())
        }
    }

    private fun buildProgressNotification(): android.app.Notification {
        val running = DownloadRepository.items.value.filter { it.status == DownloadStatus.RUNNING }
        val first = running.firstOrNull()
        val percent = first?.progress?.let { (it * 100).toInt() }

        val text = when {
            first == null -> str(R.string.preparing)
            first.note != null -> first.note
            percent != null && first.kind == JobKind.CONVERT -> str(R.string.notif_converting_percent, percent)
            percent != null -> str(R.string.percent_value, percent)
            else -> str(R.string.preparing)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(first?.title ?: str(R.string.app_name))
            .setContentText(text)
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openDownloadsPending(0))

        if (running.size > 1) builder.setSubText(str(R.string.notif_running_count, running.size))
        if (first != null) builder.addAction(0, str(R.string.cancel), serviceAction(ACTION_CANCEL, first.id))
        return builder.build()
    }

    private fun canNotify() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun updateProgressNotification() {
        if (!canNotify() || active.isEmpty()) return
        NotificationManagerCompat.from(this).notify(PROGRESS_NOTIFICATION_ID, buildProgressNotification())
    }

    /** إشعار "اكتمل": الضغط عليه يفتح الملف في المشغّل الداخلي. */
    @SuppressLint("MissingPermission")
    private fun notifyCompleted(item: DownloadItem) {
        if (!canNotify()) return
        val playerIntent = PlayerActivity.intentFor(this, item)
        val content = if (playerIntent != null) {
            // عند الرجوع من المشغّل يظهر قسم "تحميلاتي"
            TaskStackBuilder.create(this)
                .addNextIntent(openDownloadsIntent())
                .addNextIntent(playerIntent)
                .getPendingIntent(item.id.hashCode(), immutable())
        } else {
            openDownloadsPending(item.id.hashCode())
        }
        val title = if (item.kind == JobKind.CONVERT) R.string.notif_convert_complete else R.string.notif_download_complete
        val n = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(str(title))
            .setContentText(item.title)
            .setAutoCancel(true)
            .setContentIntent(content)
            .build()
        NotificationManagerCompat.from(this).notify(item.id.hashCode(), n)
    }

    /** إشعار "فشل" مع زر "إعادة المحاولة". */
    @SuppressLint("MissingPermission")
    private fun notifyFailed(id: String, item: DownloadItem?, error: String) {
        if (!canNotify()) return
        val name = item?.title ?: str(R.string.app_name)
        val title = if (item?.kind == JobKind.CONVERT) R.string.notif_convert_failed else R.string.notif_download_failed
        val n = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(str(title))
            .setContentText(name)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$name\n$error"))
            .setAutoCancel(true)
            .setContentIntent(openDownloadsPending(id.hashCode()))
            .addAction(0, str(R.string.retry), serviceAction(ACTION_RETRY, id))
            .build()
        NotificationManagerCompat.from(this).notify(id.hashCode(), n)
    }

    override fun onDestroy() {
        networkCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }
}
