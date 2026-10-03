package com.nazeeltek.savetek

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ينقل الملف المحمَّل إلى معرض الجوال:
 * الفيديو في Movies/SaveTek والصوت في Music/SaveTek.
 */
object GallerySaver {

    private const val FOLDER = "SaveTek"

    data class Saved(val uri: Uri?, val mime: String)

    fun save(context: Context, file: File, audioOnly: Boolean): Saved {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: if (audioOnly) "audio/mpeg" else "video/mp4"

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(context, file, mime, audioOnly)   // أندرويد 10 فما فوق
        } else {
            saveLegacy(context, file, mime, audioOnly)           // أندرويد 7 إلى 9
        }
        file.delete()
        return Saved(uri, mime)
    }

    private fun saveWithMediaStore(context: Context, file: File, mime: String, audio: Boolean): Uri {
        val resolver = context.contentResolver
        val collection = if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val baseDir = if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$baseDir/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException(context.str(R.string.error_gallery_create))

        try {
            resolver.openOutputStream(uri)!!.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, file: File, mime: String, audio: Boolean): Uri? {
        val baseDir = if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
        val dir = File(Environment.getExternalStoragePublicDirectory(baseDir), FOLDER).apply { mkdirs() }
        val dest = File(dir, file.name)
        file.copyTo(dest, overwrite = true)

        // نخبر المعرض بوجود ملف جديد، وننتظر حتى يعطينا رابطه
        var result: Uri? = null
        val latch = CountDownLatch(1)
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime)) { _, uri ->
            result = uri
            latch.countDown()
        }
        latch.await(10, TimeUnit.SECONDS)
        return result
    }
}
