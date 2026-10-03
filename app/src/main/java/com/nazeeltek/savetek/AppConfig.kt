package com.nazeeltek.savetek

/**
 * الإعدادات العامة للتطبيق في مكان واحد.
 *
 * ⚠️ بعد إنشاء حسابك ومستودعك على GitHub: غيّر GITHUB_USER إلى اسم حسابك
 * (وGITHUB_REPO إذا سمّيت المستودع باسم آخر)، ثم ابنِ التطبيق من جديد.
 * سكربت الإصدار tools/make-release.ps1 يقرأ هذه القيم من هنا تلقائياً.
 */
object AppConfig {

    /** اسم حسابك على GitHub. */
    const val GITHUB_USER = "savetekapp-lang"

    /** اسم المستودع على GitHub. */
    const val GITHUB_REPO = "SaveTek"

    /** رابط ملف معلومات آخر إصدار (يُستضاف على GitHub Pages من مجلد docs). */
    const val UPDATE_INFO_URL = "https://$GITHUB_USER.github.io/$GITHUB_REPO/version.json"

    /** بريد التواصل. */
    const val CONTACT_EMAIL = "savetek.app@gmail.com"

    /** "لاحقاً" تؤجل رسالة التحديث هذه المدة (24 ساعة). */
    const val UPDATE_SNOOZE_MS = 24L * 60 * 60 * 1000
}
