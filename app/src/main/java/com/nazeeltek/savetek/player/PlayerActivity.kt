package com.nazeeltek.savetek.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.nazeeltek.savetek.LocaleHelper
import com.nazeeltek.savetek.R
import com.nazeeltek.savetek.data.DownloadItem
import com.nazeeltek.savetek.str
import com.nazeeltek.savetek.ui.theme.SaveTekTheme

/**
 * شاشة المشغّل الداخلي: تشغّل الفيديو أو ملف MP3 داخل التطبيق.
 * الواجهة نفسها موجودة في PlayerScreen.kt
 */
class PlayerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_URI = "uri"
        private const val EXTRA_MIME = "mime"
        private const val EXTRA_TITLE = "title"

        /** يفتح عنصراً من "تحميلاتي" في المشغّل الداخلي. */
        fun open(context: Context, item: DownloadItem) {
            val intent = intentFor(context, item) ?: run {
                Toast.makeText(context, context.str(R.string.file_unavailable), Toast.LENGTH_SHORT).show()
                return
            }
            context.startActivity(intent)
        }

        /** طلب فتح المشغّل لعنصر معيّن (يُستخدم أيضاً في إشعار "اكتمل"). */
        fun intentFor(context: Context, item: DownloadItem): Intent? {
            val uri = item.uri ?: return null
            return Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URI, uri)
                .putExtra(EXTRA_MIME, item.mime)
                .putExtra(EXTRA_TITLE, item.title)
        }

        /** يفتح الملف بتطبيق آخر يختاره المستخدم. */
        fun openWithOtherApp(context: Context, uri: Uri, mime: String?) {
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime ?: "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                context.startActivity(Intent.createChooser(view, context.str(R.string.open_with_other_app)))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(context, context.str(R.string.no_other_app), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var player: ExoPlayer? = null

    // تطبيق لغة التطبيق المختارة على هذه الشاشة (وتبقى بعد تدوير الشاشة)
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        LocaleHelper.applyTo(this, newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uriText = intent.getStringExtra(EXTRA_URI)
        if (uriText == null) {
            finish()
            return
        }
        val uri = Uri.parse(uriText)
        val mime = intent.getStringExtra(EXTRA_MIME)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val isAudio = mime?.startsWith("audio") == true

        // تجهيز المشغّل. handleAudioFocus = true يوقفه تلقائياً عند ورود مكالمة مثلاً
        val exo = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(if (isAudio) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
        player = exo

        setContent {
            // الفيديو يُعرض دائماً على خلفية سوداء؛ ملفات الصوت تتبع المظهر المختار
            SaveTekTheme(forceDarkSystemBars = !isAudio) {
                PlayerScreen(
                    player = exo,
                    title = title,
                    isAudio = isAudio,
                    onBack = { finish() },
                    onOpenExternal = {
                        exo.pause()
                        openWithOtherApp(this, uri, mime)
                    },
                )
            }
        }
    }

    // عند الخروج من التطبيق أو قفل الشاشة نوقف التشغيل مؤقتاً
    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
