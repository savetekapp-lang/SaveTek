# =====================================================================
#  قواعد R8 لنسخة الإصدار (تصغير الكود وتشويشه)
# =====================================================================

# --- مكتبة yt-dlp (youtubedl-android) ---
# تستخدم Jackson لقراءة JSON عبر الانعكاس (reflection)، ونحتفظ بها كاملة حتى لا تتعطل.
-keep class com.yausername.** { *; }
-keepclassmembers class com.yausername.** { *; }

# --- Jackson (تقرأ الحقول بالانعكاس) ---
-keep class com.fasterxml.jackson.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-dontwarn com.fasterxml.jackson.**
-dontwarn java.beans.**
-dontwarn org.w3c.dom.bootstrap.**

# --- Apache Commons (تستخدمها مكتبة yt-dlp لفك الملفات) ---
-keep class org.apache.commons.** { *; }
-dontwarn org.apache.commons.**

# --- خصوصية: حذف كل السجلات (Log) من نسخة الإصدار ---
# حتى لا تُكتب الروابط أو مسارات الملفات في سجلات الجوال، سواء من كودنا أو من المكتبات.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
    public static boolean isLoggable(java.lang.String, int);
}
-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
}
