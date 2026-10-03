package com.nazeeltek.savetek

import androidx.annotation.StringRes

/**
 * رسالة للعرض في الواجهة: نحفظ رقم النص المترجم بدل النص نفسه،
 * حتى تظهر الرسالة بلغة التطبيق الحالية.
 */
data class UiText(@StringRes val id: Int, val args: List<Any> = emptyList())
