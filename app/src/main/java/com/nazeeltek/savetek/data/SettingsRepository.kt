package com.nazeeltek.savetek.data

import android.content.Context
import android.content.SharedPreferences
import com.nazeeltek.savetek.LocaleHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** يحفظ إعدادات المستخدم في ذاكرة الجهاز لتبقى بعد إغلاق التطبيق. */
object SettingsRepository {

    private lateinit var prefs: SharedPreferences

    /** إذا لم يختر المستخدم جودة افتراضية بعد، نستخدم 1080p. */
    private val FALLBACK_QUALITY = DefaultQuality.P1080

    private val _defaultQuality = MutableStateFlow(FALLBACK_QUALITY)
    val defaultQuality: StateFlow<DefaultQuality> = _defaultQuality.asStateFlow()

    private val _wifiOnly = MutableStateFlow(false)
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    /** وقت آخر تحديث فعلي لمحرك التحميل (0 = لم يُحدَّث بعد). */
    private val _engineUpdatedAt = MutableStateFlow(0L)
    val engineUpdatedAt: StateFlow<Long> = _engineUpdatedAt.asStateFlow()

    /** آخر فحص لتحديث المحرك (null = لم يُفحص بعد). */
    private val _engineCheck = MutableStateFlow<EngineCheck?>(null)
    val engineCheck: StateFlow<EngineCheck?> = _engineCheck.asStateFlow()

    /** المظهر المختار (الافتراضي: كحلي وذهبي). */
    private val _themeMode = MutableStateFlow(ThemeMode.NAVY_GOLD)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /** رمز اللغة المختارة، مثل "ar" أو "en". النص الفارغ = حسب لغة الجهاز. */
    private val _language = MutableStateFlow("")
    val language: StateFlow<String> = _language.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(LocaleHelper.PREFS, Context.MODE_PRIVATE)
        _defaultQuality.value = prefs.getString("default_quality", null)
            ?.let { saved -> DefaultQuality.entries.firstOrNull { it.name == saved } }
            ?: FALLBACK_QUALITY
        _wifiOnly.value = prefs.getBoolean("wifi_only", false)
        _engineUpdatedAt.value = prefs.getLong("engine_updated_at", 0L)
        _themeMode.value = prefs.getString("theme_mode", null)
            ?.let { saved -> ThemeMode.entries.firstOrNull { it.name == saved } }
            ?: ThemeMode.NAVY_GOLD
        _language.value = prefs.getString(LocaleHelper.KEY_LANGUAGE, "") ?: ""
        _engineCheck.value = prefs.getString("engine_check_result", null)
            ?.let { saved -> EngineCheckResult.entries.firstOrNull { it.name == saved } }
            ?.let { EngineCheck(prefs.getLong("engine_check_at", 0L), it, prefs.getString("engine_check_detail", null)) }
    }

    /** يسجّل نتيجة فحص المحرك (تلقائي أو يدوي) لتظهر في الإعدادات. */
    fun recordEngineCheck(result: EngineCheckResult, detail: String? = null) {
        val check = EngineCheck(System.currentTimeMillis(), result, detail?.take(200))
        _engineCheck.value = check
        prefs.edit()
            .putLong("engine_check_at", check.time)
            .putString("engine_check_result", result.name)
            .putString("engine_check_detail", check.detail)
            .apply()
    }

    /**
     * رقم إصدار التطبيق الذي اكتمل له فحص المحرك آخر مرة.
     * إذا اختلف عن الإصدار الحالي، فهذا أول فتح بعد تثبيت أو تحديث التطبيق.
     */
    var engineCheckedForAppVersion: Long
        get() = prefs.getLong("engine_checked_app_version", -1L)
        set(value) = prefs.edit().putLong("engine_checked_app_version", value).apply()

    fun setDefaultQuality(q: DefaultQuality) {
        _defaultQuality.value = q
        prefs.edit().putString("default_quality", q.name).apply()
    }

    fun setWifiOnly(enabled: Boolean) {
        _wifiOnly.value = enabled
        prefs.edit().putBoolean("wifi_only", enabled).apply()
    }

    fun setEngineUpdatedAt(time: Long) {
        _engineUpdatedAt.value = time
        prefs.edit().putLong("engine_updated_at", time).apply()
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString("theme_mode", mode.name).apply()
    }

    /** يحفظ اللغة فوراً (commit) لأن الشاشة تُعاد بناؤها مباشرة بعده. */
    fun setLanguage(tag: String) {
        _language.value = tag
        prefs.edit().putString(LocaleHelper.KEY_LANGUAGE, tag).commit()
    }

    /** هل وافق المستخدم على تنبيه الاستخدام المسؤول؟ */
    var disclaimerAccepted: Boolean
        get() = prefs.getBoolean("disclaimer_ok", false)
        set(value) = prefs.edit().putBoolean("disclaimer_ok", value).apply()

    /** هل طلبنا إذن الوصول للفيديوهات من قبل؟ (لنعرف هل الرفض نهائي فنفتح الإعدادات بدل الطلب) */
    var mediaPermissionAsked: Boolean
        get() = prefs.getBoolean("media_permission_asked", false)
        set(value) = prefs.edit().putBoolean("media_permission_asked", value).apply()

    /** هل عرضنا على المستخدم شرح سبب طلب إذن الإشعارات؟ (نعرضه مرة واحدة فقط) */
    var notificationAsked: Boolean
        get() = prefs.getBoolean("notification_asked", false)
        set(value) = prefs.edit().putBoolean("notification_asked", value).apply()
}
