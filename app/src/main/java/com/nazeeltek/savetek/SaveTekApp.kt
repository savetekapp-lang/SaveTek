package com.nazeeltek.savetek

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.nazeeltek.savetek.data.DownloadRepository
import com.nazeeltek.savetek.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** أول ما يعمل عند تشغيل التطبيق: نجهّز الإعدادات وقائمة التحميلات. */
class SaveTekApp : Application() {

    companion object {
        /**
         * نطاق عمل يعيش طوال عمر التطبيق (لا يرتبط بشاشة معيّنة)،
         * حتى لا يُلغى فحص المحرك إذا أُغلقت الشاشة أثناءه.
         */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    override fun onCreate() {
        super.onCreate()
        SettingsRepository.init(this)
        DownloadRepository.init(this)
    }
}

/**
 * هل الجوال متصل الآن بشبكة واي فاي (أو شبكة سلكية)؟
 * مع VPN لا يظهر نوع الشبكة الأصلي، فنعتمد حينها على أن الشبكة غير محسوبة (غير بيانات جوال).
 */
fun isOnWifi(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
        (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && !cm.isActiveNetworkMetered)
}

/** هل يمنع خيار "الواي فاي فقط" الاتصال الآن؟ */
fun wifiBlocked(context: Context): Boolean =
    SettingsRepository.wifiOnly.value && !isOnWifi(context)
