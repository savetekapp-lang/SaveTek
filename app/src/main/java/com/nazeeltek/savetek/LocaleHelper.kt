package com.nazeeltek.savetek

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import java.util.Locale

/**
 * لغة التطبيق:
 * - "حسب الجهاز" (الافتراضي): يستخدم لغة الجوال، وإذا لم تكن مدعومة تظهر الإنجليزية تلقائياً.
 * - أو لغة يختارها المستخدم من الإعدادات.
 * اتجاه الواجهة يتبع اللغة تلقائياً (من اليمين لليسار للعربية والأردية).
 */
object LocaleHelper {

    const val PREFS = "settings"
    const val KEY_LANGUAGE = "language"

    /** اللغات المدعومة بالترتيب الظاهر في الإعدادات. */
    val SUPPORTED = listOf("ar", "en", "ur", "tr", "fr", "es", "id", "hi")

    private var cachedTag: String? = null
    private var cachedContext: Context? = null

    fun savedTag(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, "") ?: ""

    /**
     * إعدادات اللغة التي تُضاف فوق إعدادات الجوال (null = حسب الجهاز).
     * نترك باقي القيم "غير محددة" حتى لا نغيّر حجم الخط أو الوضع الليلي.
     */
    fun overrideConfiguration(context: Context): Configuration? {
        val tag = savedTag(context)
        if (tag.isEmpty()) return null
        val locale = Locale.forLanguageTag(tag)
        return Configuration().apply {
            fontScale = 0f
            setLocale(locale)
            setLayoutDirection(locale)
        }
    }

    /** يُستدعى من attachBaseContext في كل شاشة لتطبيق اللغة المختارة. */
    fun applyTo(activity: Activity, base: Context) {
        overrideConfiguration(base)?.let { activity.applyOverrideConfiguration(it) }
    }

    /** سياق التطبيق باللغة المختارة: للإشعارات والرسائل التي تظهر خارج الشاشات. */
    @Synchronized
    fun appContext(context: Context): Context {
        val app = context.applicationContext
        val tag = savedTag(app)
        cachedContext?.let { if (tag == cachedTag) return it }
        val localized = overrideConfiguration(app)?.let { app.createConfigurationContext(it) } ?: app
        cachedTag = tag
        cachedContext = localized
        return localized
    }
}

/** نص مترجم حسب لغة التطبيق (يعمل من أي مكان، حتى خارج الشاشات). */
fun Context.str(@StringRes id: Int, vararg args: Any): String =
    LocaleHelper.appContext(this).getString(id, *args)
