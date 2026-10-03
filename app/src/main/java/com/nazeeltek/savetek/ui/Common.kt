package com.nazeeltek.savetek.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nazeeltek.savetek.R
import com.nazeeltek.savetek.UiText
import com.nazeeltek.savetek.data.LABEL_BEST
import com.nazeeltek.savetek.data.LABEL_MP3
import java.util.Locale

/** يعرض رسالة UiText بلغة التطبيق الحالية. */
@Composable
fun UiText.asString(): String = stringResource(id, *args.toTypedArray())

/** عنوان قسم بلون الهوية (الذهبي). */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier,
    )
}

/** نص ثانوي بلون أخف. */
@Composable
fun MutedText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        fontSize = 13.sp,
        modifier = modifier,
    )
}

/** يحوّل الحجم بالبايت إلى نص مقروء مثل "≈ 45.3 MB". */
@Composable
fun sizeText(bytes: Long?): String {
    if (bytes == null || bytes <= 0) return stringResource(R.string.size_unknown)
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format(Locale.US, "≈ %.2f GB", mb / 1024)
    else String.format(Locale.US, "≈ %.1f MB", mb)
}

/** الوقت المتبقي بصيغة مقروءة. */
@Composable
fun etaText(seconds: Long): String = when {
    seconds <= 0 -> stringResource(R.string.eta_moments)
    seconds < 60 -> stringResource(R.string.eta_seconds, seconds.toInt())
    else -> stringResource(R.string.eta_minutes, (seconds / 60).toInt(), (seconds % 60).toInt())
}

/** اسم الجودة بلغة التطبيق (الرموز الخاصة تُترجم، والباقي مثل "1080p" يبقى كما هو). */
@Composable
fun qualityText(label: String): String = when {
    label == LABEL_BEST -> stringResource(R.string.quality_best_available)
    label == LABEL_MP3 -> stringResource(R.string.quality_mp3)
    label.startsWith("$LABEL_MP3 ·") -> label.replaceFirst(LABEL_MP3, stringResource(R.string.quality_mp3))
    else -> label
}

/** يحوّل المدة بالثواني إلى صيغة 3:05 أو 1:02:10. */
fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}
