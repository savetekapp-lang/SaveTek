// ملف البناء الرئيسي: نعرّف فيه الإضافات (Plugins) التي يستخدمها المشروع فقط
// ملاحظة: منذ AGP 9 أصبح دعم Kotlin مدمجاً، فلا نحتاج إضافة kotlin-android
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
