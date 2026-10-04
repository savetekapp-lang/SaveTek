package com.nazeeltek.savetek

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nazeeltek.savetek.ads.AdsManager
import com.nazeeltek.savetek.data.DownloadStatus
import com.nazeeltek.savetek.data.SettingsRepository
import com.nazeeltek.savetek.ui.DownloadsScreen
import com.nazeeltek.savetek.ui.HomeScreen
import com.nazeeltek.savetek.ui.SettingsScreen
import com.nazeeltek.savetek.ui.SplashScreen
import com.nazeeltek.savetek.ui.UpdateDialog
import com.nazeeltek.savetek.ui.theme.SaveTekTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    // تطبيق لغة التطبيق المختارة على هذه الشاشة
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        LocaleHelper.applyTo(this, newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // أندرويد 12+: نزيل شاشة البداية الخاصة بالنظام فوراً بدون حركة، لتظهر شاشة الشعار مباشرة
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            splashScreen.setOnExitAnimationListener { it.remove() }
        }
        AdsManager.init(this)   // لا يفعل شيئاً ما دامت الإعلانات مطفأة
        if (savedInstanceState == null) handleIntent(intent)

        val coldStart = savedInstanceState == null
        // نسخة الاختبار فقط: نتيجة فحص التوقيع بعد اختفاء شاشة الشعار
        if (BuildConfig.SIGNATURE_SELFTEST && coldStart) {
            window.decorView.postDelayed({
                if (isFinishing) return@postDelayed
                android.app.AlertDialog.Builder(this)
                    .setTitle("فحص توقيع التحديث (نسخة اختبار)")
                    .setMessage(UpdateManager.signatureSelfTest(this))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }, 2500)
        }
        setContent {
            SaveTekTheme {
                // شاشة البداية بالشعار تظهر فوق التطبيق عند الفتح ثم تختفي تدريجياً
                var showSplash by rememberSaveable { mutableStateOf(coldStart) }
                LaunchedEffect(Unit) {
                    delay(1600)
                    showSplash = false
                }
                Box(Modifier.fillMaxSize()) {
                    AppRoot(viewModel, splashVisible = showSplash)
                    AnimatedVisibility(
                        visible = showSplash,
                        enter = EnterTransition.None,
                        exit = fadeOut(tween(400)),
                    ) {
                        SplashScreen()
                    }
                }
            }
        }
    }

    // عند كل فتح للتطبيق أو عودة إليه: فحص تحديث المحرك في الخلفية
    override fun onStart() {
        super.onStart()
        viewModel.onAppOpened()
    }

    // عند العودة للتطبيق (مثلاً من صفحة إذن التثبيت) نكمل تحديث التطبيق إن لزم
    override fun onResume() {
        super.onResume()
        viewModel.onAppResumed()
    }

    // يُستدعى إذا شارك المستخدم رابطاً أو ضغط إشعاراً والتطبيق مفتوح أصلاً
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * هذه الشاشة مكشوفة لاستقبال "المشاركة" من أي تطبيق، لذلك نتعامل مع المدخلات بحذر:
     * - نقبل فقط نصاً عادياً (text/plain) بطول محدود.
     * - النص يمر عبر UrlSafety: لا يُقبل إلا رابط http/https صحيح.
     * - أي طلب مشوّه يُتجاهل بدل أن يوقف التطبيق.
     */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        runCatching {
            when {
                intent.action == Intent.ACTION_SEND && intent.type == "text/plain" -> {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.take(8192) ?: return
                    viewModel.onSharedText(text)
                }
                intent.getBooleanExtra(DownloadService.EXTRA_OPEN_DOWNLOADS, false) ->
                    viewModel.requestTab(Tab.DOWNLOADS)
            }
        }
    }
}

private data class TabInfo(val tab: Tab, @StringRes val label: Int, val icon: ImageVector)

private val tabs = listOf(
    TabInfo(Tab.HOME, R.string.tab_home, Icons.Filled.Home),
    TabInfo(Tab.DOWNLOADS, R.string.tab_downloads, Icons.Filled.Download),
    TabInfo(Tab.SETTINGS, R.string.tab_settings, Icons.Filled.Settings),
)

@Composable
fun AppRoot(viewModel: MainViewModel, splashVisible: Boolean) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val home by viewModel.home.collectAsStateWithLifecycle()
    val engineReady by viewModel.engineReady.collectAsStateWithLifecycle()
    val engineVersion by viewModel.engineVersion.collectAsStateWithLifecycle()
    val engineUpdatedAt by viewModel.engineUpdatedAt.collectAsStateWithLifecycle()
    val updating by viewModel.updating.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val defaultQuality by viewModel.defaultQuality.collectAsStateWithLifecycle()
    val wifiOnly by viewModel.wifiOnly.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val tabRequest by viewModel.tabRequest.collectAsStateWithLifecycle()
    val availableUpdate by viewModel.availableUpdate.collectAsStateWithLifecycle()
    val updateDialog by viewModel.updateDialog.collectAsStateWithLifecycle()
    val checkingUpdates by viewModel.checkingUpdates.collectAsStateWithLifecycle()
    val engineCheck by viewModel.engineCheck.collectAsStateWithLifecycle()
    val engineUpdatingNow by viewModel.engineUpdatingNow.collectAsStateWithLifecycle()

    var currentTab by rememberSaveable { mutableStateOf(Tab.HOME) }

    // التنقل المطلوب من المشاركة أو الإشعار
    LaunchedEffect(tabRequest) {
        tabRequest?.let {
            currentTab = it
            viewModel.consumeTabRequest()
        }
    }

    // بعد كل تحميل ناجح جديد نعطي مدير الإعلانات فرصة (لا يفعل شيئاً حالياً)
    val doneCount = downloads.count { it.status == DownloadStatus.DONE }
    var lastDoneCount by remember { mutableIntStateOf(doneCount) }
    LaunchedEffect(doneCount) {
        if (doneCount > lastDoneCount) {
            (context as? Activity)?.let { AdsManager.showAfterDownload(it) }
        }
        lastDoneCount = doneCount
    }

    // ── الأذونات اللازمة قبل التحميل ──
    // الإشعارات (أندرويد 13+): نشرح السبب أولاً في نافذة لطيفة، ولا نطلبها إلا مرة واحدة.
    // التخزين (أندرويد 9 وما قبله فقط): ضروري لحفظ الملف في المعرض.
    var showNotificationRationale by remember { mutableStateOf(false) }

    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun proceedDownload() {
        if (viewModel.startDownload()) currentTab = Tab.DOWNLOADS
    }

    // بعد رد المستخدم على إذن الإشعارات نبدأ التحميل في كل الأحوال (الإذن اختياري)
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { proceedDownload() }

    lateinit var onDownloadClick: () -> Unit

    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        if (ok) onDownloadClick()
        else Toast.makeText(context, context.getString(R.string.storage_needed), Toast.LENGTH_LONG).show()
    }

    onDownloadClick = {
        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE) ->
                storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !granted(Manifest.permission.POST_NOTIFICATIONS) &&
                !SettingsRepository.notificationAsked ->
                showNotificationRationale = true

            else -> proceedDownload()
        }
    }

    var showDisclaimer by remember { mutableStateOf(!SettingsRepository.disclaimerAccepted) }

    Scaffold(
        containerColor = colors.background,
        bottomBar = {
            NavigationBar(containerColor = colors.surface) {
                val running = downloads.count { it.status == DownloadStatus.RUNNING }
                tabs.forEach { info ->
                    NavigationBarItem(
                        selected = currentTab == info.tab,
                        onClick = { currentTab = info.tab },
                        icon = {
                            // نقطة حمراء على أيقونة الإعدادات عند توفر إصدار جديد للتطبيق
                            BadgedBox(badge = {
                                if (info.tab == Tab.SETTINGS && availableUpdate != null) Badge()
                            }) { Icon(info.icon, contentDescription = null) }
                        },
                        label = {
                            Text(
                                if (info.tab == Tab.DOWNLOADS && running > 0)
                                    stringResource(R.string.tab_with_count, stringResource(info.label), running)
                                else stringResource(info.label)
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = colors.onPrimary,
                            selectedTextColor = colors.primary,
                            indicatorColor = colors.primary,
                            unselectedIconColor = colors.onSurface,
                            unselectedTextColor = colors.onSurface,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            when (currentTab) {
                Tab.HOME -> HomeScreen(
                    state = home,
                    engineReady = engineReady,
                    engineUpdating = engineUpdatingNow,
                    onUrlChange = viewModel::onUrlChange,
                    onPaste = viewModel::onSharedText,
                    onFetch = viewModel::fetchPreview,
                    onSelect = viewModel::selectQuality,
                    onDownload = { onDownloadClick() },
                )
                Tab.DOWNLOADS -> DownloadsScreen(
                    items = downloads,
                    onCancel = viewModel::cancelDownload,
                    onRetry = viewModel::retry,
                    onDelete = viewModel::delete,
                    onConvertItem = viewModel::convertItem,
                    onConvertDevice = viewModel::convertFromDevice,
                )
                Tab.SETTINGS -> SettingsScreen(
                    defaultQuality = defaultQuality,
                    wifiOnly = wifiOnly,
                    themeMode = themeMode,
                    language = language,
                    engineVersion = engineVersion,
                    engineUpdatedAt = engineUpdatedAt,
                    engineCheck = engineCheck,
                    updating = updating,
                    engineReady = engineReady,
                    onDefaultQuality = viewModel::setDefaultQuality,
                    onWifiOnly = viewModel::setWifiOnly,
                    onThemeMode = viewModel::setThemeMode,
                    onLanguage = { tag ->
                        if (tag != language) {
                            SettingsRepository.setLanguage(tag)
                            // نعيد بناء الشاشة لتظهر باللغة الجديدة واتجاهها فوراً
                            (context as? Activity)?.recreate()
                        }
                    },
                    onUpdateEngine = viewModel::updateEngine,
                    appVersion = UpdateManager.currentVersionName(context),
                    availableUpdateVersion = availableUpdate?.versionName,
                    checkingUpdates = checkingUpdates,
                    onCheckUpdates = viewModel::checkForUpdatesManually,
                    onOpenUpdate = viewModel::openUpdateDialog,
                )
            }
        }
    }

    // شرح سبب طلب إذن الإشعارات قبل نافذة النظام
    if (showNotificationRationale) {
        AlertDialog(
            onDismissRequest = {},
            icon = { Icon(Icons.Filled.Notifications, contentDescription = null, tint = colors.primary) },
            title = { Text(stringResource(R.string.notif_rationale_title)) },
            text = { Text(stringResource(R.string.notif_rationale_text)) },
            confirmButton = {
                Button(onClick = {
                    SettingsRepository.notificationAsked = true
                    showNotificationRationale = false
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text(stringResource(R.string.allow)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    SettingsRepository.notificationAsked = true
                    showNotificationRationale = false
                    proceedDownload()
                }) { Text(stringResource(R.string.not_now), color = colors.primary) }
            },
        )
    }

    // نافذة تحديث التطبيق: تظهر بعد شاشة الشعار (وبعد الموافقة على التنبيه في أول تشغيل)
    val update = updateDialog
    if (update != null && !splashVisible && !showDisclaimer) {
        UpdateDialog(
            state = update,
            onUpdateNow = viewModel::startUpdate,
            onLater = viewModel::updateLater,
            onDismiss = viewModel::dismissUpdateDialog,
            onOpenPermission = viewModel::openInstallPermission,
            onInstall = viewModel::proceedInstall,
            onOpenWebsite = viewModel::openWebsite,
        )
    }

    // تنبيه الاستخدام المسؤول: يظهر في أول تشغيل فقط (وقبل نافذة التحديث)
    if (showDisclaimer && !splashVisible) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.disclaimer_title)) },
            text = { Text(stringResource(R.string.disclaimer)) },
            confirmButton = {
                Button(onClick = {
                    SettingsRepository.disclaimerAccepted = true
                    showDisclaimer = false
                }) { Text(stringResource(R.string.agree)) }
            },
        )
    }
}
