package com.nazeeltek.savetek.data

/** الجودة الافتراضية التي يختارها المستخدم من الإعدادات. */
enum class DefaultQuality(val maxShortSide: Int?) {
    BEST(null),
    P1080(1080),
    P720(720),
    P480(480),
    AUDIO(null),
}

/** مظهر التطبيق. */
enum class ThemeMode { NAVY_GOLD, LIGHT, DARK, SYSTEM }

/** نتيجة آخر فحص لتحديث محرك التحميل (تظهر في الإعدادات). */
enum class EngineCheckResult { UP_TO_DATE, UPDATED, FAILED, SKIPPED_WIFI, SKIPPED_BUSY }

/** آخر فحص للمحرك: وقته ونتيجته وتفاصيلها (رقم الإصدار أو سبب الفشل). */
data class EngineCheck(val time: Long, val result: EngineCheckResult, val detail: String?)

/** نوع المهمة في "تحميلاتي": تحميل من الإنترنت أو تحويل فيديو إلى صوت. */
enum class JobKind { DOWNLOAD, CONVERT }

/** رموز ثابتة لأسماء جودات خاصة؛ تُترجم عند العرض حسب لغة التطبيق. */
const val LABEL_BEST = "BEST"
const val LABEL_MP3 = "MP3"

/** خيارات جودة الصوت عند التحويل إلى MP3 (كيلوبت/ثانية). */
val MP3_BITRATES = listOf(128, 192, 320)
const val DEFAULT_MP3_BITRATE = 192

/** خيار جودة يظهر للمستخدم في الشاشة الرئيسية. */
data class QualityOption(
    /** معرّف فريد للخيار، مثل "h1080" أو "mp3". */
    val key: String,
    /** الاسم، مثل "1080p"، أو أحد الرموز LABEL_BEST / LABEL_MP3. */
    val label: String,
    /** أقصى ارتفاع للصورة يُرسل لـ yt-dlp (null = بدون حد). */
    val maxHeight: Int?,
    /** الضلع الأقصر للصورة (يُستخدم لمطابقة الجودة الافتراضية). */
    val shortSide: Int?,
    val audioOnly: Boolean,
    /** الحجم التقريبي بالبايت (null = غير معروف). */
    val sizeBytes: Long?,
)

/** معاينة الفيديو: الصورة والعنوان والمدة والجودات المتاحة. */
data class VideoPreview(
    val url: String,
    val title: String,
    val thumbnail: String?,
    val durationSeconds: Int,
    val options: List<QualityOption>,
)

enum class DownloadStatus { RUNNING, DONE, FAILED }

/** عنصر واحد في قائمة "تحميلاتي". */
data class DownloadItem(
    val id: String,
    val url: String,
    val title: String,
    val thumbnail: String?,
    val qualityLabel: String,
    val maxHeight: Int?,
    val audioOnly: Boolean,
    val status: DownloadStatus,
    /** من 0 إلى 1، أو null إذا كانت النسبة غير معروفة. */
    val progress: Float? = null,
    val etaSeconds: Long = 0,
    val fileName: String? = null,
    /** رابط الملف داخل المعرض (content://...) */
    val uri: String? = null,
    val mime: String? = null,
    val error: String? = null,
    /** ملاحظة مؤقتة تظهر أثناء التحميل، مثل "جارٍ تحديث المحرك وإعادة المحاولة". */
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** تحميل أم تحويل؟ */
    val kind: JobKind = JobKind.DOWNLOAD,
    /** للتحويل: رابط الفيديو الأصلي (لا يُحذف). */
    val sourceUri: String? = null,
    /** للتحويل: جودة الصوت بالكيلوبت/ثانية. */
    val bitrateKbps: Int? = null,
)
