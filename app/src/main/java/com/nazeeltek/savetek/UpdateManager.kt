package com.nazeeltek.savetek

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** معلومات إصدار جديد كما في ملف version.json. */
data class AppUpdate(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val minSupportedVersionCode: Long,
    /** "ما الجديد" لكل لغة: المفتاح رمز اللغة مثل "ar" أو "en". */
    val whatsNew: Map<String, String>,
)

/** أسباب فشل تحديث التطبيق. */
class UpdateException(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        DOWNLOAD,
        /** البصمة SHA-256 لا تطابق version.json. */
        HASH,
        /** التوقيع قُرئ، لكنه ليس مفتاح SaveTek (أو الملف لتطبيق آخر). */
        SIGNATURE_MISMATCH,
        /** تعذّرت قراءة التوقيع على هذا الجهاز: نرفض احتياطاً ونوجّه المستخدم للموقع. */
        SIGNATURE_UNREADABLE,
        /** الملف ليس رقم الإصدار المعلن، أو أقدم من المثبّت. */
        VERSION,
    }
}

/** نتيجة مقارنة توقيع ملف التحديث بتوقيع التطبيق المثبّت. */
internal enum class SignatureCheck { MATCH, MISMATCH, UNREADABLE }

/**
 * نظام تحديث التطبيق:
 * 1. يقرأ version.json من GitHub Pages.
 * 2. يحمّل ملف APK الجديد.
 * 3. يتحقق من بصمة SHA-256 ومن أن توقيعه مطابق لتوقيع التطبيق الحالي.
 * 4. يفتح شاشة التثبيت الخاصة بالنظام.
 */
object UpdateManager {

    private const val PREFS = "updates"
    private const val KEY_SNOOZE_UNTIL = "snooze_until"
    private const val TIMEOUT_MS = 15_000

    private val _available = MutableStateFlow<AppUpdate?>(null)
    /** الإصدار الأحدث المتاح (null = التطبيق محدّث أو لم نتحقق بعد). */
    val available: StateFlow<AppUpdate?> = _available.asStateFlow()

    fun currentVersionCode(context: Context): Long = runCatching {
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
    }.getOrDefault(0L)

    fun currentVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"

    /** هل إصدار المستخدم أقل من أقل إصدار مدعوم؟ (تحديث إجباري) */
    fun isForced(context: Context, update: AppUpdate): Boolean =
        currentVersionCode(context) < update.minSupportedVersionCode

    // ───────────── التحقق من وجود إصدار جديد ─────────────

    /**
     * يقرأ version.json ويرجع الإصدار الجديد إن وُجد (أو null إذا كان التطبيق محدّثاً).
     * يرمي خطأ إذا تعذّر الاتصال.
     */
    suspend fun check(context: Context): AppUpdate? = withContext(Dispatchers.IO) {
        val connection = (URL(AppConfig.UPDATE_INFO_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
        }
        val text = try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
        val update = parse(text)
        val newer = update.takeIf { it.versionCode > currentVersionCode(context) }
        _available.value = newer
        newer
    }

    /**
     * المصدر الرسمي الوحيد المقبول لملفات التحديث: صفحة إصدارات مستودع التطبيق على GitHub عبر HTTPS.
     * أي رابط آخر في version.json يُرفض، حتى لو عُدّل الملف.
     */
    private val officialReleasePrefix =
        "https://github.com/${AppConfig.GITHUB_USER}/${AppConfig.GITHUB_REPO}/releases/download/"

    /** النطاقات التي يحوّل إليها GitHub روابط التحميل (كلها HTTPS). */
    private val trustedDownloadHosts = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    private fun isOfficialApkUrl(url: String): Boolean =
        url.startsWith(officialReleasePrefix) && !url.contains("..") && url.endsWith(".apk")

    private fun parse(text: String): AppUpdate {
        val json = JSONObject(text.trimStart('﻿'))

        // نختار ملف APK المناسب لمعالج الجوال إن وُجد، وإلا الملف الرئيسي
        var url = json.getString("apkUrl")
        var sha = json.getString("sha256")
        var size = json.optLong("apkSizeBytes")
        json.optJSONObject("abis")?.let { abis ->
            val match = Build.SUPPORTED_ABIS.firstOrNull { abis.has(it) } ?: "universal".takeIf { abis.has(it) }
            match?.let { abi ->
                val entry = abis.getJSONObject(abi)
                url = entry.getString("url")
                sha = entry.getString("sha256")
                size = entry.optLong("sizeBytes")
            }
        }

        val notes = mutableMapOf<String, String>()
        json.optJSONObject("whatsNew")?.let { obj ->
            obj.keys().forEach { key -> notes[key] = obj.optString(key) }
        }

        // رفض أي رابط ليس من صفحة الإصدارات الرسمية، وأي بصمة ليست بصيغة SHA-256 صحيحة
        require(isOfficialApkUrl(url)) { "Untrusted update URL" }
        require(Regex("^[0-9a-fA-F]{64}$").matches(sha)) { "Invalid SHA-256" }

        return AppUpdate(
            versionCode = json.getLong("versionCode"),
            versionName = json.getString("versionName"),
            apkUrl = url,
            sha256 = sha.lowercase(),
            sizeBytes = size,
            minSupportedVersionCode = json.optLong("minSupportedVersionCode", 0),
            whatsNew = notes,
        )
    }

    /** "ما الجديد" بلغة التطبيق الحالية، وإلا بالإنجليزية، وإلا أول لغة متاحة. */
    fun whatsNewFor(update: AppUpdate, languageTag: String): String {
        val lang = when (languageTag) {
            "in" -> "id"   // أندرويد القديم يستخدم "in" للإندونيسية
            else -> languageTag
        }
        return update.whatsNew[lang] ?: update.whatsNew["en"] ?: update.whatsNew.values.firstOrNull() ?: ""
    }

    // ───────────── "لاحقاً" ─────────────

    /** هل نعرض نافذة التحديث الآن؟ (الإجباري يظهر دائماً، والعادي إلا إذا أجّله المستخدم) */
    fun shouldPrompt(context: Context, update: AppUpdate): Boolean =
        isForced(context, update) || System.currentTimeMillis() >= prefs(context).getLong(KEY_SNOOZE_UNTIL, 0)

    fun snooze(context: Context) {
        prefs(context).edit()
            .putLong(KEY_SNOOZE_UNTIL, System.currentTimeMillis() + AppConfig.UPDATE_SNOOZE_MS)
            .apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ───────────── تحميل التحديث والتحقق منه ─────────────

    private fun updatesDir(context: Context) = File(context.cacheDir, "updates")

    /**
     * يحمّل ملف التحديث ويتحقق منه. يرجع الملف الجاهز للتثبيت.
     * إذا لم تتطابق البصمة أو التوقيع: يحذف الملف ويرمي UpdateException.
     */
    suspend fun download(
        context: Context,
        update: AppUpdate,
        onProgress: (Float?) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = updatesDir(context).apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "SaveTek-${update.versionName}.apk")
        val digest = MessageDigest.getInstance("SHA-256")

        try {
            val connection = (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("HTTP ${connection.responseCode}")
                }
                // بعد التحويلات: يجب أن يبقى الاتصال مشفّراً ومن نطاقات GitHub فقط
                val finalUrl = connection.url
                if (finalUrl.protocol != "https" || finalUrl.host !in trustedDownloadHosts) {
                    throw IllegalStateException("Untrusted download host")
                }
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: update.sizeBytes
                var done = 0L
                connection.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (isActive) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            done += read
                            onProgress(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            file.delete()
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw UpdateException(UpdateException.Reason.DOWNLOAD)
        }

        onProgress(null)

        // ١. البصمة: هل الملف هو نفسه المنشور في version.json؟
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (hash != update.sha256) {
            file.delete()
            throw UpdateException(UpdateException.Reason.HASH)
        }

        // ٢. التوقيع: هل الملف موقّع بنفس مفتاح التطبيق المثبّت؟
        val failure = when (checkSignature(context, file)) {
            SignatureCheck.MATCH -> null
            SignatureCheck.MISMATCH -> UpdateException.Reason.SIGNATURE_MISMATCH
            SignatureCheck.UNREADABLE -> UpdateException.Reason.SIGNATURE_UNREADABLE
        }
            // ٣. هل هو فعلاً الإصدار المعلن، وأحدث من المثبّت (لا تراجع)؟
            ?: UpdateException.Reason.VERSION.takeUnless { isExpectedVersion(context, file, update) }
        if (failure != null) {
            file.delete()
            throw UpdateException(failure)
        }
        file
    }

    /**
     * يقارن توقيع ملف APK (غير مثبّت) بتوقيع التطبيق المثبّت.
     *
     * لماذا نطلب GET_SIGNATURES و GET_SIGNING_CERTIFICATES معاً؟
     * في أندرويد 9 إلى 12، getPackageArchiveInfo لا يقرأ شهادات الملف إلا إذا وُجد GET_SIGNATURES
     * ضمن الطلب؛ مع GET_SIGNING_CERTIFICATES وحده يُرجع signingInfo فارغاً (خلل أصلحه أندرويد 13).
     * وفي أندرويد 7 و8 لا يوجد إلا GET_SIGNATURES.
     *
     * نقارن كل طريقة بنظيرتها فقط (signingInfo مع signingInfo، و signatures مع signatures):
     * - إذا قُرئ الطرفان بأي طريقة واختلفا ← MISMATCH (رفض).
     * - إذا لم يمكن قراءة الطرفين بأي طريقة ← UNREADABLE (رفض أيضاً، لكن برسالة مختلفة).
     */
    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    internal fun checkSignature(context: Context, apk: File): SignatureCheck {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNATURES or PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

        val archive = runCatching { pm.getPackageArchiveInfo(apk.absolutePath, flags) }.getOrNull()
            ?: return SignatureCheck.UNREADABLE
        // ملف لتطبيق آخر (اسم حزمة مختلف) يُعدّ توقيعاً غير مطابق
        if (archive.packageName != context.packageName) return SignatureCheck.MISMATCH
        val installed = runCatching { pm.getPackageInfo(context.packageName, flags) }.getOrNull()
            ?: return SignatureCheck.UNREADABLE

        val pairs = listOf(
            signingInfoDigests(installed) to signingInfoDigests(archive),
            legacyDigests(installed) to legacyDigests(archive),
        ).filter { (mine, theirs) -> mine.isNotEmpty() && theirs.isNotEmpty() }

        return when {
            pairs.isEmpty() -> SignatureCheck.UNREADABLE
            pairs.all { (mine, theirs) -> mine == theirs } -> SignatureCheck.MATCH
            else -> SignatureCheck.MISMATCH
        }
    }

    /** الملف المحمَّل يجب أن يكون نفس رقم الإصدار المعلن، وأحدث من المثبّت (منع الرجوع لإصدار قديم). */
    private fun isExpectedVersion(context: Context, apk: File, update: AppUpdate): Boolean = runCatching {
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) ?: return false
        val code = PackageInfoCompat.getLongVersionCode(archive)
        archive.packageName == context.packageName &&
            code == update.versionCode &&
            code > currentVersionCode(context)
    }.getOrDefault(false)

    /**
     * فحص ذاتي لنسخة الاختبار فقط (BuildConfig.SIGNATURE_SELFTEST): يطبّق فحص التوقيع على ملف APK
     * الخاص بالتطبيق نفسه (مثل ملف تحديث موقّع بنفس المفتاح)، ويقارن الطريقة القديمة بالجديدة.
     */
    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    internal fun signatureSelfTest(context: Context): String {
        val apk = File(context.applicationInfo.sourceDir)
        val p = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        // الطريقة القديمة كما كانت في 0.7.3
        val oldWay = runCatching {
            val flags = if (p) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            val sigs = if (p) archive?.signingInfo?.apkContentsSigners else archive?.signatures
            !sigs.isNullOrEmpty()
        }.getOrDefault(false)
        val newWay = checkSignature(context, apk)
        return "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "${Build.MANUFACTURER} ${Build.MODEL}\n\n" +
            "الطريقة القديمة (0.7.3): " + (if (oldWay) "قرأت التوقيع ✓" else "لم تقرأ التوقيع ✗") + "\n" +
            "الطريقة الجديدة (0.7.4): " + when (newWay) {
                SignatureCheck.MATCH -> "التوقيع مطابق ✓"
                SignatureCheck.MISMATCH -> "توقيع مختلف ✗"
                SignatureCheck.UNREADABLE -> "تعذّرت القراءة ✗"
            }
    }

    /** شهادات التوقيع الحالية من signingInfo (أندرويد 9+)، أو مجموعة فارغة إن لم تُقرأ. */
    private fun signingInfoDigests(info: PackageInfo): Set<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) digests(info.signingInfo?.apkContentsSigners) else emptySet()

    /** الشهادات من الحقل القديم signatures (يعمل على كل الإصدارات)، أو مجموعة فارغة. */
    @Suppress("DEPRECATION")
    private fun legacyDigests(info: PackageInfo): Set<String> = digests(info.signatures)

    private fun digests(signatures: Array<android.content.pm.Signature>?): Set<String> {
        if (signatures.isNullOrEmpty()) return emptySet()
        val sha = MessageDigest.getInstance("SHA-256")
        return signatures.map { sig -> sha.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    // ───────────── التثبيت ─────────────

    /** هل يسمح أندرويد لهذا التطبيق بتثبيت التحديثات؟ (إذن "تثبيت التطبيقات غير المعروفة") */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** يفتح شاشة التثبيت الخاصة بالنظام. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** يفتح موقع التطبيق الرسمي في المتصفح. */
    fun openWebsite(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AppConfig.WEBSITE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** يفتح صفحة إذن "تثبيت التطبيقات غير المعروفة" لهذا التطبيق. */
    fun openInstallPermissionSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
