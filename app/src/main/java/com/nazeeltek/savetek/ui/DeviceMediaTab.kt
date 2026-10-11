package com.nazeeltek.savetek.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.nazeeltek.savetek.R
import com.nazeeltek.savetek.data.DeviceFile
import com.nazeeltek.savetek.data.DeviceMedia
import com.nazeeltek.savetek.data.MediaAccess
import com.nazeeltek.savetek.data.MediaKind
import com.nazeeltek.savetek.data.SettingsRepository
import com.nazeeltek.savetek.player.PlayerActivity

/** النصوص والأيقونة الخاصة بكل نوع (فيديو / صوت). */
private class KindTexts(
    val rationaleTitle: Int,
    val rationaleText: Int,
    val deniedTitle: Int,
    val deniedText: Int,
    val empty: Int,
    val count: Int,
)

private fun textsFor(kind: MediaKind) = when (kind) {
    MediaKind.VIDEO -> KindTexts(
        R.string.media_rationale_title, R.string.media_rationale_text,
        R.string.media_denied_title, R.string.media_denied_text,
        R.string.no_device_videos, R.string.video_count,
    )
    MediaKind.AUDIO -> KindTexts(
        R.string.audio_rationale_title, R.string.audio_rationale_text,
        R.string.audio_denied_title, R.string.audio_denied_text,
        R.string.no_device_audio, R.string.audio_count,
    )
}

/**
 * قسم "فيديوهات الجهاز" أو "MP3 الجهاز": ملفات الجوال، مع تشغيلها
 * (وتحويل الفيديو إلى MP3، أو مشاركة الملف الصوتي).
 *
 * الإذن لا يُطلب إلا عند فتح القسم أول مرة، وبعد شرح بسيط للسبب.
 * إذا رفض المستخدم، يبقى التطبيق يعمل كالمعتاد ويظهر هنا زر "السماح بالوصول".
 */
@Composable
fun DeviceMediaTab(kind: MediaKind, onConvert: (Uri) -> Unit) {
    val context = LocalContext.current
    val texts = remember(kind) { textsFor(kind) }
    val permissions = remember(kind) { DeviceMedia.permissions(kind) }
    fun asked() = if (kind == MediaKind.AUDIO) SettingsRepository.audioPermissionAsked
    else SettingsRepository.mediaPermissionAsked
    fun markAsked() {
        if (kind == MediaKind.AUDIO) SettingsRepository.audioPermissionAsked = true
        else SettingsRepository.mediaPermissionAsked = true
    }

    var access by remember(kind) { mutableStateOf(DeviceMedia.access(context, kind)) }
    var reloadKey by remember(kind) { mutableIntStateOf(0) }
    // الشرح يظهر تلقائياً عند أول فتح فقط
    var showRationale by remember(kind) { mutableStateOf(access == MediaAccess.NONE && !asked()) }

    // نعيد فحص الإذن وتحميل القائمة كلما عاد المستخدم للتطبيق (مثلاً من الإعدادات)
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    LaunchedEffect(lifecycleState, kind) {
        if (lifecycleState == Lifecycle.State.RESUMED) {
            access = DeviceMedia.access(context, kind)
            reloadKey++
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        markAsked()
        access = DeviceMedia.access(context, kind)
        reloadKey++
    }

    /** زر "السماح بالوصول": يطلب الإذن، أو يفتح إعدادات التطبيق إذا رُفض نهائياً. */
    fun requestAccess() {
        val activity = context as? Activity
        val permanentlyDenied = asked() && activity != null &&
            !activity.shouldShowRequestPermissionRationale(permissions.first())
        if (permanentlyDenied && access == MediaAccess.NONE) openAppSettings(context) else launcher.launch(permissions)
    }

    when (access) {
        MediaAccess.NONE -> NoAccess(kind, texts, onAllow = { requestAccess() })
        else -> FileList(
            kind = kind,
            texts = texts,
            partial = access == MediaAccess.PARTIAL,
            reloadKey = reloadKey,
            onSelectMore = { launcher.launch(permissions) },
            onConvert = onConvert,
        )
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            icon = { Icon(kindIcon(kind), null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(stringResource(texts.rationaleTitle)) },
            text = { Text(stringResource(texts.rationaleText)) },
            confirmButton = {
                Button(onClick = { showRationale = false; launcher.launch(permissions) }) {
                    Text(stringResource(R.string.continue_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false }) {
                    Text(stringResource(R.string.not_now), color = MaterialTheme.colorScheme.primary)
                }
            },
        )
    }
}

private fun kindIcon(kind: MediaKind) =
    if (kind == MediaKind.AUDIO) Icons.Filled.LibraryMusic else Icons.Filled.VideoLibrary

@Composable
private fun NoAccess(kind: MediaKind, texts: KindTexts, onAllow: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(kindIcon(kind), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(texts.deniedTitle), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(4.dp))
        MutedText(stringResource(texts.deniedText), Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAllow) { Text(stringResource(R.string.allow_access)) }
    }
}

@Composable
private fun FileList(
    kind: MediaKind,
    texts: KindTexts,
    partial: Boolean,
    reloadKey: Int,
    onSelectMore: () -> Unit,
    onConvert: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val files by produceState<List<DeviceFile>?>(initialValue = null, reloadKey, kind) {
        value = DeviceMedia.query(context, kind)
    }
    val list = files

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // أندرويد 14+: وصول لفيديوهات محددة فقط، مع زر لاختيار المزيد
        if (partial) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        MutedText(stringResource(R.string.media_partial_text))
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = onSelectMore,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.select_more_videos), color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
        }

        when {
            list == null -> item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
            list.isEmpty() -> item {
                MutedText(stringResource(texts.empty), Modifier.fillMaxWidth().padding(32.dp))
            }
            else -> {
                item { SectionTitle(stringResource(texts.count, list.size)) }
                items(list, key = { it.id }) { file ->
                    DeviceFileRow(
                        file = file,
                        onPlay = { PlayerActivity.openUri(context, file.uri, file.mime, file.name) },
                        onConvert = { onConvert(file.uri) },
                        onShare = { shareFile(context, file) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceFileRow(file: DeviceFile, onPlay: () -> Unit, onConvert: () -> Unit, onShare: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val thumb by produceState<android.graphics.Bitmap?>(initialValue = null, file.id, file.kind) {
        value = DeviceMedia.thumbnail(context, file)
    }
    val isAudio = file.kind == MediaKind.AUDIO

    Card(
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            // الصورة المصغّرة (أو غلاف الأغنية) مع المدة
            Box(
                Modifier
                    .size(width = if (isAudio) 54.dp else 96.dp, height = 54.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isAudio) colors.primary.copy(alpha = 0.15f) else Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = thumb
                if (bitmap != null) {
                    Image(bitmap.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else if (isAudio) {
                    Icon(Icons.Filled.MusicNote, null, tint = colors.primary)
                }
                val duration = formatDuration((file.durationMs / 1000).toInt())
                if (duration.isNotEmpty() && !isAudio) {
                    Text(
                        duration,
                        color = Color.White,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(3.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, color = colors.onSurface)
                val duration = formatDuration((file.durationMs / 1000).toInt())
                MutedText(if (isAudio && duration.isNotEmpty()) "$duration · ${sizeText(file.sizeBytes)}" else sizeText(file.sizeBytes))
            }
            IconButton(onClick = onPlay) { Icon(Icons.Filled.PlayArrow, stringResource(R.string.play), tint = colors.primary) }
            if (isAudio) {
                IconButton(onClick = onShare) { Icon(Icons.Filled.Share, stringResource(R.string.share), tint = colors.primary) }
            } else {
                IconButton(onClick = onConvert) {
                    Icon(Icons.Filled.MusicNote, stringResource(R.string.convert_to_mp3), tint = colors.primary)
                }
            }
        }
    }
}

/** مشاركة ملف من الجوال مع تطبيق آخر يختاره المستخدم. */
private fun shareFile(context: Context, file: DeviceFile) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType(file.mime ?: if (file.kind == MediaKind.AUDIO) "audio/*" else "video/*")
        .putExtra(Intent.EXTRA_STREAM, file.uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_via)))
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.file_unavailable), Toast.LENGTH_SHORT).show()
    }
}

/** يفتح صفحة هذا التطبيق في إعدادات الجوال (عند رفض الإذن نهائياً). */
private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    runCatching { context.startActivity(intent) }
}
