package com.nazeeltek.savetek

import java.io.File
import java.net.URI

/**
 * فحص الروابط التي يلصقها المستخدم أو يشاركها من تطبيق آخر.
 *
 * الهدف: لا يمكن لأي نص أن يُفسَّر كخيار لـ yt-dlp (مثل "--exec ...").
 * - نقبل فقط روابط http و https لها اسم موقع صحيح.
 * - نرفض أي مسافات أو رموز تحكم أو نص يبدأ بـ "-".
 * - نحوّل http إلى https حتى تكون كل الاتصالات مشفّرة.
 * - وفي الأوامر نضع "--" قبل الرابط دائماً (في Downloader) كحماية ثانية.
 */
object UrlSafety {

    private const val MAX_LENGTH = 2048

    /** يرجع الرابط الآمن (بـ https)، أو null إذا لم يكن رابط ويب صحيحاً. */
    fun safeUrl(input: String?): String? {
        val text = input?.trim() ?: return null
        if (text.isEmpty() || text.length > MAX_LENGTH) return null
        if (text.startsWith("-")) return null
        // لا مسافات ولا رموز تحكم داخل الرابط
        if (text.any { it.isWhitespace() || it.isISOControl() }) return null

        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host ?: return null
        if (host.isBlank() || host.startsWith("-")) return null
        if (uri.userInfo != null) return null   // نرفض روابط فيها اسم مستخدم/كلمة مرور

        // نجبر التشفير: http يتحول إلى https
        return if (scheme == "http") "https" + text.substring(4) else "https" + text.substring(5)
    }

    /** يستخرج أول رابط من نص مشارَك (قد يحتوي كلاماً مع الرابط) ثم يفحصه. */
    fun extractUrl(sharedText: String?): String? {
        val text = sharedText?.take(8192) ?: return null
        val candidate = Regex("""https?://\S+""", RegexOption.IGNORE_CASE).find(text)?.value ?: text.trim()
        return safeUrl(candidate)
    }
}

/**
 * تنظيف أسماء الملفات: عنوان الفيديو يأتي من الإنترنت ولا نثق به.
 * نحذف الرموز الممنوعة ونمنع أي محاولة للخروج من المجلد مثل "../".
 */
object FileNames {

    private const val MAX_CHARS = 80

    /** اسم آمن (بدون امتداد) مبني على العنوان. */
    fun safeBaseName(title: String?, fallback: String = "SaveTek"): String {
        val cleaned = (title ?: "")
            // رموز التحكم والرموز الممنوعة في أسماء الملفات
            .replace(Regex("""[\u0000-\u001F\u007F/\\:*?"<>|]"""), "_")
            // أي تسلسل نقاط (مثل ..) يصبح نقطة واحدة
            .replace(Regex("""\.{2,}"""), ".")
            .trim()
            // لا يبدأ بنقطة (ملف مخفي) ولا بشرطة (قد يُفهم كخيار في الأوامر)
            .trimStart('.', '-', '_', ' ')
            .trimEnd('.', ' ')
        val limited = cleaned.codePoints().limit(MAX_CHARS.toLong())
            .collect(::StringBuilder, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString().trim()
        return limited.ifEmpty { fallback }
    }

    /**
     * ينشئ ملفاً داخل [dir] فقط، ويتأكد أن مساره النهائي لا يخرج من المجلد.
     * [extension] يُنظَّف أيضاً (حروف وأرقام فقط).
     */
    fun childFile(dir: File, title: String?, extension: String): File {
        val ext = extension.lowercase().filter { it.isLetterOrDigit() }.take(8).ifEmpty { "bin" }
        val file = File(dir, safeBaseName(title) + "." + ext)
        val base = dir.canonicalFile
        val target = file.canonicalFile
        require(target.parentFile == base) { "Invalid file name" }
        return target
    }
}
