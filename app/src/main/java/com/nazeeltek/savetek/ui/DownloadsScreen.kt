package com.nazeeltek.savetek.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nazeeltek.savetek.R
import com.nazeeltek.savetek.ads.AdSlotView
import com.nazeeltek.savetek.ads.AdsManager
import com.nazeeltek.savetek.data.DEFAULT_MP3_BITRATE
import com.nazeeltek.savetek.data.DownloadItem
import com.nazeeltek.savetek.data.DownloadStatus
import com.nazeeltek.savetek.data.MP3_BITRATES
import com.nazeeltek.savetek.player.PlayerActivity

/** مصدر التحويل إلى MP3: عنصر من "تحميلاتي" أو ملف من الجوال. */
private sealed interface ConvertSource {
    data class Item(val item: DownloadItem) : ConvertSource
    data class Device(val uri: Uri) : ConvertSource
}

@Composable
fun DownloadsScreen(
    items: List<DownloadItem>,
    onCancel: (String) -> Unit,
    onRetry: (DownloadItem) -> Unit,
    onDelete: (DownloadItem) -> Unit,
    onConvertItem: (DownloadItem, Int) -> Unit,
    onConvertDevice: (Uri, Int) -> Unit,
) {
    val context = LocalContext.current
    val running = items.filter { it.status == DownloadStatus.RUNNING }
    val done = items.filter { it.status == DownloadStatus.DONE }
    val failed = items.filter { it.status == DownloadStatus.FAILED }
    var toDelete by remember { mutableStateOf<DownloadItem?>(null) }
    var toConvert by remember { mutableStateOf<ConvertSource?>(null) }

    // نافذة اختيار الملفات الخاصة بالنظام: لا تحتاج إذن الوصول لكل الملفات
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) toConvert = ConvertSource.Device(uri)
    }
    val openPicker = { pickVideo.launch(arrayOf("video/*")) }

    // تبويبان: "تحميلاتي" (ما حمّلته أو حوّلته) و"فيديوهات الجهاز" (كل فيديوهات الجوال)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        DownloadsTabs(selected = tab, onSelect = { tab = it })
        if (tab == 1) {
            DeviceVideosTab(onConvert = { toConvert = ConvertSource.Device(it) })
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // زر تحويل فيديو من الجهاز (يظهر دائماً)
            item {
                OutlinedButton(
                    onClick = openPicker,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.VideoFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.convert_from_device), color = MaterialTheme.colorScheme.primary)
                }
            }
    
            if (items.isEmpty()) {
                item { EmptyState() }
            }
    
            if (running.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.section_running, running.size)) }
                items(running, key = { it.id }) { RunningRow(it, onCancel) }
            }
            if (done.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.section_completed, done.size), Modifier.padding(top = 8.dp)) }
                items(done, key = { it.id }) { item ->
                    FinishedRow(
                        item = item,
                        // الضغط على الملف يفتحه في المشغّل الداخلي
                        onPlay = { PlayerActivity.open(context, item) },
                        onShare = { share(context, item) },
                        onRetry = {},
                        onConvert = { toConvert = ConvertSource.Item(item) },
                        onDelete = { toDelete = item },
                    )
                }
            }
            if (failed.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.section_failed, failed.size), Modifier.padding(top = 8.dp)) }
                items(failed, key = { it.id }) { item ->
                    FinishedRow(
                        item = item,
                        onPlay = {},
                        onShare = {},
                        onRetry = { onRetry(item) },
                        onConvert = {},
                        onDelete = { toDelete = item },
                    )
                }
            }
            // مكان إعلان محجوز (مخفي تماماً ما دامت الإعلانات مطفأة)
            item { AdSlotView(AdsManager.AdSlot.DOWNLOADS_BANNER) }
        }
    }

    toDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.delete_title)) },
            text = {
                Text(
                    if (item.status == DownloadStatus.DONE) stringResource(R.string.delete_done_message, item.title)
                    else stringResource(R.string.delete_failed_message)
                )
            },
            confirmButton = {
                TextButton(onClick = { onDelete(item); toDelete = null }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) {
                    Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.primary)
                }
            },
        )
    }

    toConvert?.let { source ->
        BitrateDialog(
            onDismiss = { toConvert = null },
            onConfirm = { kbps ->
                when (source) {
                    is ConvertSource.Item -> onConvertItem(source.item, kbps)
                    is ConvertSource.Device -> onConvertDevice(source.uri, kbps)
                }
                toConvert = null
            },
        )
    }
}

/** شريط التبويبين أعلى "تحميلاتي". */
@Composable
private fun DownloadsTabs(selected: Int, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    PrimaryTabRow(
        selectedTabIndex = selected,
        containerColor = colors.background,
        contentColor = colors.primary,
    ) {
        listOf(R.string.tab_downloads, R.string.tab_device_videos).forEachIndexed { index, label ->
            Tab(
                selected = selected == index,
                onClick = { onSelect(index) },
                text = { Text(stringResource(label), fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal) },
                selectedContentColor = colors.primary,
                unselectedContentColor = colors.onBackground.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Download, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.no_downloads_title), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        MutedText(stringResource(R.string.no_downloads_hint))
    }
}

/** نافذة اختيار جودة الصوت قبل التحويل (الافتراضي 192). */
@Composable
private fun BitrateDialog(onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var selected by remember { mutableIntStateOf(DEFAULT_MP3_BITRATE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(R.string.convert_title)) },
        text = {
            Column {
                Text(stringResource(R.string.audio_quality))
                Spacer(Modifier.height(8.dp))
                MP3_BITRATES.forEach { kbps ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = kbps == selected, role = Role.RadioButton) { selected = kbps }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = kbps == selected, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (kbps == DEFAULT_MP3_BITRATE) stringResource(R.string.bitrate_default, kbps)
                            else stringResource(R.string.bitrate_value, kbps)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                MutedText(stringResource(R.string.convert_note))
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selected) }) { Text(stringResource(R.string.convert)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.primary)
            }
        },
    )
}

@Composable
private fun Thumb(item: DownloadItem) {
    Box(
        Modifier
            .size(width = 96.dp, height = 54.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (item.thumbnail != null) {
            AsyncImage(
                model = item.thumbnail, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
        }
        if (item.audioOnly) {
            Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun RowCard(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) { Column(Modifier.padding(10.dp)) { content() } }
}

@Composable
private fun RunningRow(item: DownloadItem, onCancel: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    RowCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Thumb(item)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, color = colors.onSurface)
                MutedText(qualityText(item.qualityLabel))
            }
            IconButton(onClick = { onCancel(item.id) }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel), tint = colors.error)
            }
        }
        Spacer(Modifier.height(8.dp))
        val p = item.progress
        if (p == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.primary)
            MutedText(item.note ?: stringResource(R.string.preparing))
        } else {
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = colors.primary)
            val percent = (p * 100).toInt()
            MutedText(
                if (item.etaSeconds > 0) stringResource(R.string.progress_with_eta, percent, etaText(item.etaSeconds))
                else stringResource(R.string.percent_value, percent)
            )
        }
    }
}

@Composable
private fun FinishedRow(
    item: DownloadItem,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit,
    onConvert: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val isDone = item.status == DownloadStatus.DONE
    // الضغط على أي مكان في بطاقة الملف المكتمل يفتح المشغّل الداخلي
    RowCard(onClick = if (isDone) onPlay else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Thumb(item)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, color = colors.onSurface)
                if (isDone) {
                    MutedText(stringResource(R.string.done_label, qualityText(item.qualityLabel)))
                } else {
                    Text(
                        stringResource(R.string.failed_label, item.error ?: ""),
                        color = colors.error,
                        fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // زر تحويل الفيديو إلى صوت (للفيديوهات المكتملة فقط)
            if (isDone && !item.audioOnly) {
                TextButton(onClick = onConvert) {
                    Icon(Icons.Filled.MusicNote, null, tint = colors.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.convert_to_mp3), color = colors.primary, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            if (isDone) {
                IconButton(onClick = onPlay) { Icon(Icons.Filled.PlayArrow, stringResource(R.string.play), tint = colors.primary) }
                IconButton(onClick = onShare) { Icon(Icons.Filled.Share, stringResource(R.string.share), tint = colors.primary) }
            } else {
                IconButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, stringResource(R.string.retry), tint = colors.primary) }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, stringResource(R.string.delete), tint = colors.error)
            }
        }
    }
}

private fun share(context: Context, item: DownloadItem) {
    val uri = item.uri ?: run {
        Toast.makeText(context, context.getString(R.string.file_unavailable), Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_SEND)
        .setType(item.mime ?: "video/*")
        .putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_via)))
}
