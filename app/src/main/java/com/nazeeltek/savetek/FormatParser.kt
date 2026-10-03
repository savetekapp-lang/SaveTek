package com.nazeeltek.savetek

import com.nazeeltek.savetek.data.LABEL_BEST
import com.nazeeltek.savetek.data.LABEL_MP3
import com.nazeeltek.savetek.data.QualityOption
import com.nazeeltek.savetek.data.VideoPreview
import org.json.JSONArray
import org.json.JSONObject

/**
 * يقرأ بيانات yt-dlp الخام (JSON) مباشرة ويحسب الجودات وأحجامها.
 *
 * حساب حجم كل صيغة بالترتيب:
 * 1. filesize (الحجم الدقيق)
 * 2. filesize_approx (الحجم التقريبي من الموقع)
 * 3. معدل البت × المدة (نحسبه بأنفسنا إذا لم يرسل الموقع أي حجم)
 *
 * حجم الجودة الكلي = حجم الفيديو + حجم أفضل صوت (إذا كان الفيديو بلا صوت).
 */
object FormatParser {

    /** معدل البت التقريبي لملف MP3 بأعلى جودة (VBR V0) بالكيلوبت/ثانية. */
    private const val MP3_V0_KBPS = 245.0

    /** صيغة واحدة من قائمة yt-dlp، بعد تنظيف قيمها. */
    private data class Format(
        val ext: String?,
        val vcodec: String?,
        val acodec: String?,
        val width: Int,
        val height: Int,
        val filesize: Double?,
        val filesizeApprox: Double?,
        val tbr: Double?,
        val vbr: Double?,
        val abr: Double?,
    ) {
        val hasVideo get() = height > 0 && vcodec != "none"
        val hasAudio get() = acodec != null && acodec != "none"
        val isAudioOnly get() = hasAudio && (vcodec == "none" || height == 0)

        /** الحجم بالبايت حسب الترتيب المطلوب، أو null إذا استحال الحساب. */
        fun sizeBytes(durationSec: Double?): Double? {
            filesize?.let { return it }
            filesizeApprox?.let { return it }
            // معدل البت المناسب: للفيديو بدون صوت vbr، للصوت abr، وإلا tbr (المجموع)
            val kbps = when {
                isAudioOnly -> abr ?: tbr
                hasVideo && !hasAudio -> vbr ?: tbr
                else -> tbr ?: sumOrNull(vbr, abr)
            } ?: return null
            if (durationSec == null) return null
            return kbps * 1000.0 / 8.0 * durationSec
        }

        /** لترتيب الصيغ: أعلى معدل بت = أفضل جودة. */
        val bitrate: Double get() = tbr ?: sumOrNull(vbr, abr) ?: vbr ?: abr ?: 0.0
    }

    private fun sumOrNull(a: Double?, b: Double?): Double? = if (a != null && b != null) a + b else null

    /** يقرأ رقماً موجباً من JSON، أو null إذا كان غير موجود أو null أو صفراً. */
    private fun JSONObject.num(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return optDouble(key).takeIf { !it.isNaN() && it > 0 }
    }

    private fun JSONObject.str(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key)

    private fun JSONObject.toFormat() = Format(
        ext = str("ext"),
        vcodec = str("vcodec"),
        acodec = str("acodec"),
        width = num("width")?.toInt() ?: 0,
        height = num("height")?.toInt() ?: 0,
        filesize = num("filesize"),
        filesizeApprox = num("filesize_approx"),
        tbr = num("tbr"),
        vbr = num("vbr"),
        abr = num("abr"),
    )

    /** يحوّل ناتج "yt-dlp -J" إلى معاينة جاهزة للعرض. */
    fun parse(url: String, rawJson: String, defaultTitle: String): VideoPreview {
        var info = JSONObject(rawJson)
        // بعض الروابط ترجع "قائمة" فيها عنصر واحد أو أكثر: نأخذ الأول
        if (info.optString("_type") == "playlist") {
            info = info.optJSONArray("entries")?.optJSONObject(0) ?: info
        }

        val duration = info.num("duration")
        val formats = (info.optJSONArray("formats") ?: JSONArray())
            .let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.toFormat() } }
            // نستبعد صور المعاينة (storyboards) لأنها ليست فيديو حقيقياً
            .filterNot { it.ext == "mhtml" }

        return VideoPreview(
            url = url,
            title = info.str("title") ?: info.str("fulltitle") ?: defaultTitle,
            // صورة المعاينة عبر https فقط (التطبيق يمنع الاتصالات غير المشفّرة)
            thumbnail = info.str("thumbnail")?.let { t ->
                when {
                    t.startsWith("https://") -> t
                    t.startsWith("http://") -> "https://" + t.removePrefix("http://")
                    else -> null
                }
            },
            durationSeconds = duration?.toInt() ?: 0,
            options = buildOptions(info, formats, duration),
        )
    }

    private fun buildOptions(info: JSONObject, formats: List<Format>, duration: Double?): List<QualityOption> {
        // أفضل صوت منفصل: نفضّل m4a كما يفعل yt-dlp عند التحميل (-S ext:mp4:m4a)
        val audios = formats.filter { it.isAudioOnly }
        val bestAudio = audios.filter { it.ext == "m4a" }.maxByOrNull { it.bitrate }
            ?: audios.maxByOrNull { it.bitrate }
        val audioSize = bestAudio?.sizeBytes(duration)

        val videoOptions = formats
            .filter { it.hasVideo }
            .groupBy { it.height }
            .map { (height, list) ->
                // نفضّل mp4 ثم أعلى معدل بت، كما يفعل yt-dlp عند التحميل
                val pick = list.filter { it.ext == "mp4" }.maxByOrNull { it.bitrate }
                    ?: list.maxByOrNull { it.bitrate }!!
                val shortSide = if (pick.width > 0) minOf(pick.width, height) else height
                val videoSize = pick.sizeBytes(duration)
                val total = when {
                    videoSize == null -> null
                    pick.hasAudio -> videoSize
                    else -> videoSize + (audioSize ?: 0.0)
                }
                QualityOption(
                    key = "h$height",
                    label = qualityLabel(shortSide),
                    maxHeight = height,
                    shortSide = shortSide,
                    audioOnly = false,
                    sizeBytes = total?.toLong(),
                )
            }
            // ترتيب من الأعلى جودة للأقل، وإزالة التكرار (مثل صيغتين بنفس الدقة)
            .sortedByDescending { it.shortSide ?: 0 }
            .distinctBy { it.label }
            .ifEmpty {
                // بعض المواقع لا تذكر الدقة: نعرض خياراً واحداً "أفضل جودة"
                val top = info.toFormat().sizeBytes(duration)
                    ?: formats.maxByOrNull { it.bitrate }?.sizeBytes(duration)
                listOf(QualityOption("best", LABEL_BEST, null, null, false, top?.toLong()))
            }

        // حجم MP3 يعتمد على المدة (لأنه يُعاد ترميزه)، وإلا نستخدم حجم الصوت الأصلي
        val mp3Size = duration?.let { it * MP3_V0_KBPS * 1000.0 / 8.0 } ?: audioSize
        val mp3 = QualityOption("mp3", LABEL_MP3, null, null, true, mp3Size?.toLong())
        return videoOptions + mp3
    }

    private fun qualityLabel(shortSide: Int): String = when {
        shortSide >= 2160 -> "4K"
        shortSide >= 1440 -> "2K"
        else -> "${shortSide}p"
    }
}
