package com.nazeeltek.savetek

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nazeeltek.savetek.data.DefaultQuality
import com.nazeeltek.savetek.data.DownloadItem
import com.nazeeltek.savetek.data.DownloadRepository
import com.nazeeltek.savetek.data.DownloadStatus
import com.nazeeltek.savetek.data.QualityOption
import com.nazeeltek.savetek.data.SettingsRepository
import com.nazeeltek.savetek.data.ThemeMode
import com.nazeeltek.savetek.data.VideoPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** أقسام شريط التنقل السفلي. */
enum class Tab { HOME, DOWNLOADS, SETTINGS }

/** حالة الشاشة الرئيسية. */
data class HomeState(
    val url: String = "",
    val loadingPreview: Boolean = false,
    val preview: VideoPreview? = null,
    val selectedKey: String? = null,
    val message: UiText? = null,
    val isError: Boolean = false,
)

/** مراحل نافذة تحديث التطبيق. */
enum class UpdatePhase { PROMPT, DOWNLOADING, NEED_PERMISSION, READY, ERROR }

/** حالة نافذة تحديث التطبيق. */
data class UpdateDialogState(
    val update: AppUpdate,
    val forced: Boolean,
    val phase: UpdatePhase = UpdatePhase.PROMPT,
    val progress: Float? = null,
    val error: UiText? = null,
)

/** "العقل" الذي يدير منطق كل الشاشات، ويبقى حيّاً عند تدوير الشاشة. */
class MainViewModel(private val app: Application) : AndroidViewModel(app) {

    private val _home = MutableStateFlow(HomeState())
    val home: StateFlow<HomeState> = _home.asStateFlow()

    private val _engineReady = MutableStateFlow(false)
    val engineReady: StateFlow<Boolean> = _engineReady.asStateFlow()

    val engineVersion: StateFlow<String?> = Downloader.engineVersion
    val engineUpdatedAt: StateFlow<Long> = SettingsRepository.engineUpdatedAt

    private val _updating = MutableStateFlow(false)
    val updating: StateFlow<Boolean> = _updating.asStateFlow()

    /** طلب للتنقل إلى قسم معيّن (مثلاً عند المشاركة أو الضغط على إشعار). */
    private val _tabRequest = MutableStateFlow<Tab?>(null)
    val tabRequest: StateFlow<Tab?> = _tabRequest.asStateFlow()

    val downloads = DownloadRepository.items
    val defaultQuality = SettingsRepository.defaultQuality
    val wifiOnly = SettingsRepository.wifiOnly
    val themeMode = SettingsRepository.themeMode
    val language = SettingsRepository.language

    private var previewJob: Job? = null

    // ── تحديث التطبيق ──
    val availableUpdate: StateFlow<AppUpdate?> = UpdateManager.available
    private val _updateDialog = MutableStateFlow<UpdateDialogState?>(null)
    val updateDialog: StateFlow<UpdateDialogState?> = _updateDialog.asStateFlow()
    private val _checkingUpdates = MutableStateFlow(false)
    val checkingUpdates: StateFlow<Boolean> = _checkingUpdates.asStateFlow()
    private var updateJob: Job? = null
    private var downloadedApk: File? = null

    init {
        // التحقق من وجود إصدار جديد للتطبيق في الخلفية (لا يبطئ الفتح)
        viewModelScope.launch {
            val update = runCatching { UpdateManager.check(app) }.getOrNull() ?: return@launch
            if (UpdateManager.shouldPrompt(app, update)) {
                _updateDialog.value = UpdateDialogState(update, UpdateManager.isForced(app, update))
            }
        }
    }

    init {
        viewModelScope.launch {
            try {
                Downloader.ensureReady(app)
                _engineReady.value = true
            } catch (e: Exception) {
                showHomeMessage(UiText(R.string.msg_engine_init_failed, listOf(e.message ?: "")), isError = true)
            }
        }
    }

    val engineCheck = SettingsRepository.engineCheck
    val engineUpdatingNow: StateFlow<Boolean> = Downloader.updatingNow

    /**
     * يُستدعى عند كل ظهور للتطبيق (فتحه أو العودة إليه):
     * فحص المحرك في الخلفية، دون إبطاء الفتح أو تعطيل الاستخدام.
     */
    fun onAppOpened() {
        SaveTekApp.scope.launch { Downloader.autoUpdateOnOpen(app) }
    }

    // ───────────── التنقل ─────────────

    fun requestTab(tab: Tab) { _tabRequest.value = tab }
    fun consumeTabRequest() { _tabRequest.value = null }

    // ───────────── الرئيسية ─────────────

    fun onUrlChange(newUrl: String) {
        previewJob?.cancel()
        _home.value = HomeState(url = newUrl)
    }

    /** يستقبل نصاً (من اللصق أو المشاركة)، يستخرج الرابط ويجلب المعاينة. */
    fun onSharedText(text: String) {
        // نستخرج الرابط ونفحصه؛ إذا لم يكن رابطاً آمناً نعرض النص كما هو ليصحّحه المستخدم
        val link = UrlSafety.extractUrl(text)
        onUrlChange(link ?: text.trim().take(2048))
        requestTab(Tab.HOME)
        if (link != null) fetchPreview()
    }

    fun fetchPreview() {
        // نقبل فقط روابط http/https صحيحة، ولا يمكن أن يُفهم الرابط كخيار لـ yt-dlp
        val url = UrlSafety.safeUrl(_home.value.url) ?: run {
            showHomeMessage(UiText(R.string.msg_invalid_url), isError = true)
            return
        }
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            _home.update { it.copy(loadingPreview = true, preview = null, message = null, isError = false) }
            try {
                val preview = try {
                    Downloader.fetchPreview(app, url)
                } catch (e: Exception) {
                    // خطأ 403: نحدّث المحرك بصمت ونعيد المحاولة مرة واحدة
                    if (!Downloader.isForbiddenError(e) || Downloader.hasRunningJobs()) throw e
                    Downloader.update(app)
                    Downloader.fetchPreview(app, url)
                }
                _home.update {
                    it.copy(preview = preview, selectedKey = pickDefault(preview.options))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showHomeMessage(
                    UiText(R.string.msg_read_failed, listOf(Downloader.friendlyError(app, e))),
                    isError = true,
                )
            } finally {
                _home.update { it.copy(loadingPreview = false) }
            }
        }
    }

    /** يختار الجودة المناسبة حسب "الجودة الافتراضية" في الإعدادات. */
    private fun pickDefault(options: List<QualityOption>): String? {
        val videos = options.filterNot { it.audioOnly }
        val q = defaultQuality.value
        val limit = q.maxShortSide
        return when {
            q == DefaultQuality.AUDIO -> options.firstOrNull { it.audioOnly }?.key
            limit == null -> videos.firstOrNull()?.key
            else -> (videos.firstOrNull { (it.shortSide ?: 0) <= limit } ?: videos.lastOrNull())?.key
        }
    }

    fun selectQuality(key: String) {
        _home.update { it.copy(selectedKey = key) }
    }

    /** يبدأ التحميل في الخلفية. يرجع true إذا بدأ فعلاً. */
    fun startDownload(): Boolean {
        val state = _home.value
        val preview = state.preview ?: return false
        val option = preview.options.firstOrNull { it.key == state.selectedKey } ?: return false

        if (wifiBlocked(app)) {
            showHomeMessage(UiText(R.string.msg_wifi_blocked), isError = true)
            return false
        }

        DownloadService.enqueue(
            app,
            DownloadItem(
                id = UUID.randomUUID().toString(),
                url = preview.url,
                title = preview.title,
                thumbnail = preview.thumbnail,
                qualityLabel = option.label,
                maxHeight = option.maxHeight,
                audioOnly = option.audioOnly,
                status = DownloadStatus.RUNNING,
            ),
        )
        _home.value = HomeState(message = UiText(R.string.msg_download_started))
        return true
    }

    private fun showHomeMessage(message: UiText, isError: Boolean) {
        _home.update { it.copy(message = message, isError = isError) }
    }

    // ───────────── تحميلاتي ─────────────

    fun cancelDownload(id: String) = DownloadService.cancel(id)

    /** يعيد محاولة مهمة فشلت، بنفس الإعدادات. */
    fun retry(item: DownloadItem) {
        if (!DownloadService.retry(app, item)) toast(app.str(R.string.msg_wifi_blocked))
    }

    /** يحذف الملف من الجوال ومن القائمة. */
    fun delete(item: DownloadItem) {
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                val uri = item.uri ?: return@withContext true
                runCatching { app.contentResolver.delete(Uri.parse(uri), null, null) >= 0 }
                    .getOrDefault(false)
            }
            DownloadRepository.remove(item.id)
            if (!deleted) toast(app.str(R.string.msg_delete_partial))
        }
    }

    /** تحويل فيديو من "تحميلاتي" إلى MP3. */
    fun convertItem(item: DownloadItem, bitrateKbps: Int) {
        val uri = item.uri ?: return toast(app.str(R.string.file_unavailable))
        DownloadService.enqueueConversion(app, Uri.parse(uri), item.title, item.thumbnail, bitrateKbps)
        toast(app.str(R.string.msg_convert_started))
    }

    /** تحويل فيديو اختاره المستخدم من ملفات جواله إلى MP3. */
    fun convertFromDevice(uri: Uri, bitrateKbps: Int) {
        // نحتفظ بإذن قراءة الملف حتى تعمل "إعادة المحاولة" لاحقاً
        runCatching {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val title = AudioConverter.displayName(app, uri) ?: app.str(R.string.default_video_title)
        DownloadService.enqueueConversion(app, uri, title, null, bitrateKbps)
        toast(app.str(R.string.msg_convert_started))
    }

    // ───────────── الإعدادات ─────────────

    fun setDefaultQuality(q: DefaultQuality) = SettingsRepository.setDefaultQuality(q)
    fun setWifiOnly(enabled: Boolean) = SettingsRepository.setWifiOnly(enabled)
    fun setThemeMode(mode: ThemeMode) = SettingsRepository.setThemeMode(mode)

    fun updateEngine() {
        if (_updating.value || Downloader.isUpdating) return
        if (Downloader.hasRunningJobs()) {
            toast(app.str(R.string.msg_update_wait))
            return
        }
        viewModelScope.launch {
            _updating.value = true
            try {
                toast(Downloader.update(app))
            } catch (e: Exception) {
                toast(app.str(R.string.msg_update_failed))
            } finally {
                _updating.value = false
            }
        }
    }

    // ───────────── تحديث التطبيق ─────────────

    /** زر "التحقق من التحديثات" في الإعدادات. */
    fun checkForUpdatesManually() {
        if (_checkingUpdates.value) return
        viewModelScope.launch {
            _checkingUpdates.value = true
            try {
                val update = UpdateManager.check(app)
                if (update != null) openUpdateDialog() else toast(app.str(R.string.up_to_date))
            } catch (e: Exception) {
                toast(app.str(R.string.update_check_failed))
            } finally {
                _checkingUpdates.value = false
            }
        }
    }

    /** يفتح نافذة التحديث (من زر "تحديث التطبيق" في الإعدادات). */
    fun openUpdateDialog() {
        val update = availableUpdate.value ?: return
        if (_updateDialog.value == null) {
            _updateDialog.value = UpdateDialogState(update, UpdateManager.isForced(app, update))
        }
    }

    /** "لاحقاً": تؤجل الرسالة 24 ساعة، وتبقى النقطة الحمراء. */
    fun updateLater() {
        val state = _updateDialog.value ?: return
        if (state.forced) return
        updateJob?.cancel()
        UpdateManager.snooze(app)
        _updateDialog.value = null
    }

    /** "تحديث الآن": تحميل الملف مع شريط تقدّم، ثم التحقق منه، ثم التثبيت. */
    fun startUpdate() {
        val state = _updateDialog.value ?: return
        updateJob?.cancel()
        _updateDialog.value = state.copy(phase = UpdatePhase.DOWNLOADING, progress = 0f, error = null)
        updateJob = viewModelScope.launch {
            try {
                val file = UpdateManager.download(app, state.update) { progress ->
                    _updateDialog.update { it?.copy(progress = progress) }
                }
                downloadedApk = file
                proceedInstall()
            } catch (e: UpdateException) {
                val message = when (e.reason) {
                    UpdateException.Reason.DOWNLOAD -> R.string.update_error_download
                    UpdateException.Reason.HASH -> R.string.update_error_hash
                    UpdateException.Reason.SIGNATURE -> R.string.update_error_signature
                }
                _updateDialog.update { it?.copy(phase = UpdatePhase.ERROR, error = UiText(message)) }
            }
        }
    }

    /** يفتح شاشة التثبيت، أو يشرح إذن "تثبيت التطبيقات غير المعروفة" إذا لم يكن مفعّلاً. */
    fun proceedInstall() {
        val file = downloadedApk?.takeIf { it.exists() } ?: return startUpdate()
        if (UpdateManager.canInstall(app)) {
            UpdateManager.install(app, file)
            _updateDialog.update { it?.copy(phase = UpdatePhase.READY) }
        } else {
            _updateDialog.update { it?.copy(phase = UpdatePhase.NEED_PERMISSION) }
        }
    }

    fun openInstallPermission() = UpdateManager.openInstallPermissionSettings(app)

    /** عند العودة للتطبيق (مثلاً من صفحة الإذن): نكمل التثبيت إذا صار الإذن مفعّلاً. */
    fun onAppResumed() {
        if (_updateDialog.value?.phase == UpdatePhase.NEED_PERMISSION && UpdateManager.canInstall(app)) {
            proceedInstall()
        }
    }

    /** إغلاق النافذة أو إلغاء التحميل (غير متاح في التحديث الإجباري). */
    fun dismissUpdateDialog() {
        val state = _updateDialog.value ?: return
        updateJob?.cancel()
        _updateDialog.value = if (state.forced) state.copy(phase = UpdatePhase.PROMPT, error = null) else null
    }

    private fun toast(msg: String) {
        Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
    }
}
