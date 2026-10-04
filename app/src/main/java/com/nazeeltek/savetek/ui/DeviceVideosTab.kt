package com.nazeeltek.savetek.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
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
import com.nazeeltek.savetek.data.DeviceVideo
import com.nazeeltek.savetek.data.DeviceVideos
import com.nazeeltek.savetek.data.MediaAccess
import com.nazeeltek.savetek.data.SettingsRepository
import com.nazeeltek.savetek.player.PlayerActivity

/**
 * تبويب "فيديوهات الجهاز": كل فيديوهات الجوال، مع تشغيلها أو تحويلها إلى MP3.
 *
 * الإذن لا يُطلب إلا عند فتح هذا التبويب أول مرة، وبعد شرح بسيط للسبب.
 * إذا رفض المستخدم، يبقى التطبيق يعمل كالمعتاد ويظهر هنا زر "السماح بالوصول".
 */
@Composable
fun DeviceVideosTab(onConvert: (Uri) -> Unit) {
    val context = LocalContext.current
    val permissions = remember { DeviceVideos.permissions() }

    var access by remember { mutableStateOf(DeviceVideos.access(context)) }
    var reloadKey by remember { mutableIntStateOf(0) }
    // الشرح يظهر تلقائياً عند أول فتح فقط
    var showRationale by remember {
        mutableStateOf(access == MediaAccess.NONE && !SettingsRepository.mediaPermissionAsked)
    }

    // نعيد فحص الإذن وتحميل القائمة كلما عاد المستخدم للتطبيق (مثلاً من الإعدادات)
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    LaunchedEffect(lifecycleState) {
        if (lifecycleState == Lifecycle.State.RESUMED) {
            access = DeviceVideos.access(context)
            reloadKey++
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        SettingsRepository.mediaPermissionAsked = true
        access = DeviceVideos.access(context)
        reloadKey++
    }

    /** زر "السماح بالوصول": يطلب الإذن، أو يفتح إعدادات التطبيق إذا رُفض نهائياً. */
    fun requestAccess() {
        val activity = context as? Activity
        val permanentlyDenied = SettingsRepository.mediaPermissionAsked && activity != null &&
            !activity.shouldShowRequestPermissionRationale(permissions.first())
        if (permanentlyDenied && access == MediaAccess.NONE) openAppSettings(context) else launcher.launch(permissions)
    }

    when (access) {
        MediaAccess.NONE -> NoAccess(onAllow = { requestAccess() })
        else -> VideoList(
            partial = access == MediaAccess.PARTIAL,
            reloadKey = reloadKey,
            onSelectMore = { launcher.launch(permissions) },
            onConvert = onConvert,
        )
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            icon = { Icon(Icons.Filled.VideoLibrary, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(stringResource(R.string.media_rationale_title)) },
            text = { Text(stringResource(R.string.media_rationale_text)) },
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

@Composable
private fun NoAccess(onAllow: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.VideoLibrary, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.media_denied_title), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(4.dp))
        MutedText(stringResource(R.string.media_denied_text), Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAllow) { Text(stringResource(R.string.allow_access)) }
    }
}

@Composable
private fun VideoList(
    partial: Boolean,
    reloadKey: Int,
    onSelectMore: () -> Unit,
    onConvert: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val videos by produceState<List<DeviceVideo>?>(initialValue = null, reloadKey) {
        value = DeviceVideos.query(context)
    }
    val list = videos

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
                MutedText(
                    stringResource(R.string.no_device_videos),
                    Modifier.fillMaxWidth().padding(32.dp),
                )
            }
            else -> {
                item { SectionTitle(stringResource(R.string.video_count, list.size)) }
                items(list, key = { it.id }) { video ->
                    DeviceVideoRow(
                        video = video,
                        onPlay = { PlayerActivity.openUri(context, video.uri, video.mime, video.name) },
                        onConvert = { onConvert(video.uri) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceVideoRow(video: DeviceVideo, onPlay: () -> Unit, onConvert: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val thumb by produceState<android.graphics.Bitmap?>(initialValue = null, video.id) {
        value = DeviceVideos.thumbnail(context, video)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            // الصورة المصغّرة مع مدة الفيديو
            Box(
                Modifier
                    .size(width = 96.dp, height = 54.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black),
            ) {
                thumb?.let {
                    Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                val duration = formatDuration((video.durationMs / 1000).toInt())
                if (duration.isNotEmpty()) {
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
                Text(video.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, color = colors.onSurface)
                MutedText(sizeText(video.sizeBytes))
            }
            IconButton(onClick = onPlay) { Icon(Icons.Filled.PlayArrow, stringResource(R.string.play), tint = colors.primary) }
            IconButton(onClick = onConvert) {
                Icon(Icons.Filled.MusicNote, stringResource(R.string.convert_to_mp3), tint = colors.primary)
            }
        }
    }
}

/** يفتح صفحة هذا التطبيق في إعدادات الجوال (عند رفض الإذن نهائياً). */
private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    runCatching { context.startActivity(intent) }
}
