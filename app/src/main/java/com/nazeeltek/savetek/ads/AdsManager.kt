package com.nazeeltek.savetek.ads

import android.app.Activity
import android.content.Context

/**
 * مدير الإعلانات: نقطة التحكم الوحيدة بكل ما يخص الإعلانات في التطبيق.
 *
 * حالياً: لا توجد أي مكتبة إعلانات، والمفتاح مطفأ، فلا يظهر أي شيء للمستخدم.
 *
 * عند إضافة الإعلانات مستقبلاً:
 * 1. أضف مكتبة الإعلانات في app/build.gradle.kts.
 * 2. اكتب كود تجهيزها داخل init().
 * 3. اكتب كود عرض الإعلان داخل AdSlotView (ملف AdSlotView.kt) و showAfterDownload().
 * 4. غيّر ADS_ENABLED إلى true.
 */
object AdsManager {

    /** المفتاح الرئيسي: ما دام false فلن يظهر أي إعلان أو مكان محجوز. */
    const val ADS_ENABLED = false

    /** الأماكن المحجوزة للإعلانات في الواجهة. */
    enum class AdSlot {
        /** شريط في الشاشة الرئيسية تحت زر التحميل. */
        HOME_BANNER,

        /** شريط أسفل قائمة "تحميلاتي". */
        DOWNLOADS_BANNER,
    }

    /** تجهيز مكتبة الإعلانات عند فتح التطبيق. لا يفعل شيئاً حالياً. */
    fun init(context: Context) {
        if (!ADS_ENABLED) return
        // TODO: كود تجهيز مكتبة الإعلانات يوضع هنا مستقبلاً
    }

    /** فرصة لعرض إعلان بعد انتهاء تحميل ناجح. لا يفعل شيئاً حالياً. */
    fun showAfterDownload(activity: Activity) {
        if (!ADS_ENABLED) return
        // TODO: كود الإعلان بملء الشاشة (Interstitial) يوضع هنا مستقبلاً
    }
}
