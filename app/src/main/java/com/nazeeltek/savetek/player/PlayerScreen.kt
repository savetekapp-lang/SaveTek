package com.nazeeltek.savetek.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nazeeltek.savetek.R
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import java.util.Locale

private const val SEEK_STEP_MS = 10_000L

/** ألوان الهوية فوق الفيديو (ثابتة في كل المظاهر لأن خلفية الفيديو سوداء دائماً). */
private val BrightGold = Color(0xFFD4A853)
private val BrandNavy = Color(0xFF1E2A47)

/** يحوّل الميلي ثانية إلى صيغة 3:05 أو 1:02:10. */
private fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000).toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

@Composable
fun PlayerScreen(
    player: ExoPlayer,
    title: String,
    isAudio: Boolean,
    onBack: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as Activity

    // ألوان المشغّل: فوق الفيديو (خلفية سوداء) نستخدم الذهبي الساطع والأبيض دائماً،
    // ولملفات الصوت نتبع المظهر المختار في الإعدادات
    val scheme = MaterialTheme.colorScheme
    val accent = if (isAudio) scheme.primary else BrightGold
    val onAccent = if (isAudio) scheme.onPrimary else BrandNavy
    val fg = if (isAudio) scheme.onBackground else Color.White
    val volumeBg = if (isAudio) scheme.surface else BrandNavy.copy(alpha = 0.9f)

    // ── حالة المشغّل (تُحدَّث باستمرار) ──
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var ended by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var seekDrag by remember { mutableStateOf<Float?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) { ended = state == Player.STATE_ENDED }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // ── الصوت (مستوى صوت الوسائط في الجوال) ──
    val audioManager = remember { context.getSystemService(AudioManager::class.java) }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var volume by remember { mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    var volumeBeforeMute by remember { mutableIntStateOf(maxVolume / 2) }
    var showVolume by remember { mutableStateOf(false) }

    fun setVolume(v: Int) {
        val clamped = v.coerceIn(0, maxVolume)
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0) }
        volume = clamped
    }

    // تحديث الوقت ومستوى الصوت عدة مرات في الثانية
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            if (player.duration > 0) duration = player.duration
            volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            delay(300)
        }
    }

    // ── إظهار وإخفاء الأزرار ──
    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }   // يزيد مع كل لمسة ليعيد عدّاد الإخفاء
    fun touched() { interaction++ }

    LaunchedEffect(controlsVisible, isPlaying, interaction, showVolume) {
        if (!isAudio && controlsVisible && isPlaying) {
            delay(3500)
            controlsVisible = false
            showVolume = false
        }
    }

    // ── ملء الشاشة والتدوير ──
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val immersive = !isAudio && (fullscreen || isLandscape)

    LaunchedEffect(fullscreen) {
        // ملء الشاشة يدوّر الشاشة أفقياً؛ الخروج منه يعيد التدوير الحر
        activity.requestedOrientation = if (fullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    LaunchedEffect(immersive) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (immersive) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    BackHandler(enabled = fullscreen) { fullscreen = false }

    // ── التقديم والترجيع ──
    var seekFeedback by remember { mutableStateOf<Int?>(null) }  // -1 ترجيع، 1 تقديم
    var feedbackKey by remember { mutableIntStateOf(0) }

    fun seekBy(delta: Long) {
        val target = (player.currentPosition + delta).coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE)
        player.seekTo(target)
        position = target
    }

    fun togglePlay() {
        when {
            ended -> { player.seekTo(0); player.play() }
            player.isPlaying -> player.pause()
            else -> player.play()
        }
    }

    LaunchedEffect(feedbackKey) {
        if (seekFeedback != null) {
            delay(700)
            seekFeedback = null
        }
    }

    // واجهة المشغّل دائماً من اليسار لليمين، مثل كل مشغّلات الفيديو (اليسار = ترجيع)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            Modifier
                .fillMaxSize()
                .background(if (isAudio) scheme.background else Color.Black)
        ) {
            // ── سطح الفيديو أو واجهة الصوت ──
            if (isAudio) {
                AudioArtwork(title)
            } else {
                VideoSurface(player)
            }

            // ── طبقة اللمس: نقرة = إظهار/إخفاء، نقرتان = ±10 ثوانٍ ──
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                controlsVisible = !controlsVisible
                                if (!controlsVisible) showVolume = false
                                touched()
                            },
                            onDoubleTap = { offset ->
                                val forward = offset.x > size.width / 2
                                seekBy(if (forward) SEEK_STEP_MS else -SEEK_STEP_MS)
                                seekFeedback = if (forward) 1 else -1
                                feedbackKey++
                                touched()
                            },
                        )
                    }
            )

            // ── مؤشر "±10 ثوانٍ" عند النقر المزدوج ──
            seekFeedback?.let { side ->
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.5f)
                        .align(if (side > 0) Alignment.CenterEnd else Alignment.CenterStart),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f))
                            .padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            if (side > 0) Icons.Filled.Forward10 else Icons.Filled.Replay10,
                            contentDescription = null, tint = accent, modifier = Modifier.size(36.dp),
                        )
                        Text(stringResource(R.string.ten_seconds), color = Color.White, fontSize = 12.sp)
                    }
                }
            }

            // ── الأزرار ──
            AnimatedVisibility(
                visible = controlsVisible || isAudio,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            if (isAudio) Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
                            else Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.6f),
                                0.25f to Color.Transparent,
                                0.7f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.75f),
                            )
                        )
                        .safeDrawingPadding()
                ) {
                    // الشريط العلوي: رجوع + العنوان + فتح بتطبيق آخر
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { if (fullscreen) fullscreen = false else onBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = accent)
                        }
                        Text(
                            title,
                            color = fg,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { touched(); onOpenExternal() }) {
                            Icon(Icons.Filled.OpenInNew, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.open_with_other_app), color = accent, fontSize = 13.sp)
                        }
                    }

                    // الأزرار الوسطى: ترجيع 10 / تشغيل وإيقاف / تقديم 10
                    Row(
                        Modifier.align(if (isAudio) Alignment.BottomCenter else Alignment.Center)
                            .padding(bottom = if (isAudio) 120.dp else 0.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                    ) {
                        IconButton(onClick = { seekBy(-SEEK_STEP_MS); touched() }, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Filled.Replay10, stringResource(R.string.rewind_10), tint = fg, modifier = Modifier.size(36.dp))
                        }
                        IconButton(
                            onClick = { togglePlay(); touched() },
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(accent),
                        ) {
                            Icon(
                                when {
                                    ended -> Icons.Filled.Replay
                                    isPlaying -> Icons.Filled.Pause
                                    else -> Icons.Filled.PlayArrow
                                },
                                contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
                                tint = onAccent,
                                modifier = Modifier.size(42.dp),
                            )
                        }
                        IconButton(onClick = { seekBy(SEEK_STEP_MS); touched() }, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Filled.Forward10, stringResource(R.string.forward_10), tint = fg, modifier = Modifier.size(36.dp))
                        }
                    }

                    // الشريط السفلي: الصوت + شريط التقديم + الوقت + ملء الشاشة
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        if (showVolume) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(CircleShape)
                                    .background(volumeBg)
                                    .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(onClick = {
                                    if (volume > 0) { volumeBeforeMute = volume; setVolume(0) }
                                    else setVolume(volumeBeforeMute.coerceAtLeast(1))
                                    touched()
                                }) {
                                    Icon(
                                        if (volume == 0) Icons.AutoMirrored.Filled.VolumeOff
                                        else Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = stringResource(R.string.mute), tint = accent,
                                    )
                                }
                                Slider(
                                    value = volume.toFloat(),
                                    onValueChange = { setVolume(it.toInt()); touched() },
                                    valueRange = 0f..maxVolume.toFloat(),
                                    colors = goldSliderColors(accent),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                        }

                        val progress = if (duration > 0) position.toFloat() / duration else 0f
                        Slider(
                            value = seekDrag ?: progress.coerceIn(0f, 1f),
                            onValueChange = { seekDrag = it; touched() },
                            onValueChangeFinished = {
                                seekDrag?.let { player.seekTo((it * duration).toLong()) }
                                seekDrag = null
                            },
                            enabled = duration > 0,
                            colors = goldSliderColors(accent),
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val shown = seekDrag?.let { (it * duration).toLong() } ?: position
                            Text(
                                "${formatTime(shown)} / ${formatTime(duration)}",
                                color = fg, fontSize = 13.sp,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { showVolume = !showVolume; touched() }) {
                                Icon(
                                    if (volume == 0) Icons.AutoMirrored.Filled.VolumeOff
                                    else Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = stringResource(R.string.volume),
                                    tint = if (showVolume) accent else fg,
                                )
                            }
                            if (!isAudio) {
                                IconButton(onClick = { fullscreen = !fullscreen; touched() }) {
                                    Icon(
                                        if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                                        contentDescription = stringResource(if (fullscreen) R.string.exit_fullscreen else R.string.fullscreen),
                                        tint = fg,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun goldSliderColors(gold: Color) = SliderDefaults.colors(
    thumbColor = gold,
    activeTrackColor = gold,
    inactiveTrackColor = gold.copy(alpha = 0.3f),
    disabledThumbColor = gold.copy(alpha = 0.4f),
    disabledActiveTrackColor = gold.copy(alpha = 0.3f),
    disabledInactiveTrackColor = gold.copy(alpha = 0.15f),
)

/** سطح عرض الفيديو من مكتبة Media3 (بدون أزرارها الافتراضية، فنحن نرسم أزرارنا). */
@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(player: ExoPlayer) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                this.player = player
            }
        },
        onRelease = { it.player = null },
        modifier = Modifier.fillMaxSize(),
    )
}

/** واجهة ملفات الصوت: رمز التطبيق والعنوان بألوان المظهر المختار. */
@Composable
private fun AudioArtwork(title: String) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(scheme.surface, scheme.background)))
            .safeDrawingPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // رمز التطبيق على دائرة كحلية (لون الهوية) في كل المظاهر
        Box(
            Modifier
                .size(200.dp)
                .clip(CircleShape)
                .background(BrandNavy),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.mipmap.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(260.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            title,
            color = scheme.onBackground,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.audio_file_label), color = scheme.primary, fontSize = 13.sp)
        Spacer(Modifier.height(160.dp))
    }
}
