package com.nazeeltek.savetek.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nazeeltek.savetek.HomeState
import com.nazeeltek.savetek.R
import com.nazeeltek.savetek.ads.AdSlotView
import com.nazeeltek.savetek.ads.AdsManager
import com.nazeeltek.savetek.data.QualityOption
import com.nazeeltek.savetek.data.VideoPreview

@Composable
fun HomeScreen(
    state: HomeState,
    engineReady: Boolean,
    onUrlChange: (String) -> Unit,
    onPaste: (String) -> Unit,
    onFetch: () -> Unit,
    onSelect: (String) -> Unit,
    onDownload: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            stringResource(R.string.app_name),
            color = colors.primary,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )

        // ── خانة الرابط مع زر اللصق ──
        OutlinedTextField(
            value = state.url,
            onValueChange = onUrlChange,
            label = { Text(stringResource(R.string.url_label)) },
            placeholder = { Text("https://…") },
            singleLine = true,
            enabled = !state.loadingPreview,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onFetch() }),
            trailingIcon = {
                IconButton(onClick = { clipboard.getText()?.text?.let(onPaste) }) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = stringResource(R.string.paste), tint = colors.primary)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        if (!engineReady) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), color = colors.primary, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                MutedText(stringResource(R.string.engine_preparing))
            }
        }

        // زر "عرض الجودات" يظهر قبل جلب المعاينة
        if (state.preview == null && !state.loadingPreview) {
            OutlinedButton(
                onClick = onFetch,
                enabled = engineReady && state.url.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, colors.primary),
            ) { Text(stringResource(R.string.show_qualities), color = colors.primary) }
        }

        if (state.loadingPreview) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), color = colors.primary, strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.fetching_info), color = colors.onBackground)
            }
        }

        // ── المعاينة + الجودات + زر التحميل ──
        state.preview?.let { preview ->
            PreviewCard(preview)

            SectionTitle(stringResource(R.string.choose_quality))
            QualityChips(preview.options, state.selectedKey, onSelect)

            Button(
                onClick = onDownload,
                enabled = engineReady && state.selectedKey != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.download), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }

        // رسالة (نجاح أو خطأ)
        state.message?.let {
            Card(
                colors = CardDefaults.cardColors(containerColor = colors.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    it.asString(),
                    color = if (state.isError) colors.error else colors.onSurface,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }

        // مكان إعلان محجوز (مخفي تماماً ما دامت الإعلانات مطفأة)
        AdSlotView(AdsManager.AdSlot.HOME_BANNER)

        Spacer(Modifier.height(8.dp))
        MutedText("⚠️ " + stringResource(R.string.disclaimer), Modifier.fillMaxWidth())
    }
}

@Composable
private fun PreviewCard(preview: VideoPreview) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black)
        ) {
            if (preview.thumbnail != null) {
                AsyncImage(
                    model = preview.thumbnail,
                    contentDescription = stringResource(R.string.cd_video_thumbnail),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            val duration = formatDuration(preview.durationSeconds)
            if (duration.isNotEmpty()) {
                Text(
                    duration,
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            preview.title,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/**
 * أزرار الجودة: شبكة منتظمة من عمودين مرتبة من الأعلى للأقل جودة،
 * وزر MP3 في صف كامل بعرض الشاشة في الأسفل.
 */
@Composable
private fun QualityChips(options: List<QualityOption>, selectedKey: String?, onSelect: (String) -> Unit) {
    val videos = options.filterNot { it.audioOnly }
    val audio = options.firstOrNull { it.audioOnly }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        videos.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { option ->
                    QualityChip(option, option.key == selectedKey, onSelect, Modifier.weight(1f))
                }
                // إذا كان عدد الجودات فردياً نترك مكان الزر الأخير فارغاً حتى تبقى الشبكة منتظمة
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (audio != null) {
            QualityChip(audio, audio.key == selectedKey, onSelect, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun QualityChip(
    option: QualityOption,
    selected: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val content = if (selected) colors.onPrimary else colors.onSurface
    Surface(
        onClick = { onSelect(option.key) },
        shape = RoundedCornerShape(12.dp),
        color = if (selected) colors.primary else colors.surface,
        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant),
        modifier = modifier,
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (option.audioOnly) {
                    Icon(
                        Icons.Filled.MusicNote, contentDescription = null,
                        tint = if (selected) colors.onPrimary else colors.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(qualityText(option.label), fontWeight = FontWeight.Bold, color = content)
            }
            Text(sizeText(option.sizeBytes), fontSize = 11.sp, color = content.copy(alpha = 0.75f))
        }
    }
}
