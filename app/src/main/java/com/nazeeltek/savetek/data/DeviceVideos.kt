package com.nazeeltek.savetek.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** فيديو موجود في الجوال. */
data class DeviceVideo(
    val id: Long,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val mime: String?,
)

/** مستوى الوصول لفيديوهات الجوال. */
enum class MediaAccess {
    /** كل الفيديوهات. */
    FULL,

    /** أندرويد 14+: فيديوهات اختارها المستخدم فقط. */
    PARTIAL,

    /** لا يوجد إذن. */
    NONE,
}

/**
 * قراءة فيديوهات الجوال من مكتبة الوسائط الخاصة بالنظام.
 * كل شيء يحدث داخل الجوال فقط: لا يُرسل أي فيديو أو معلومة عنه لأي مكان.
 */
object DeviceVideos {

    /** الأذونات المطلوبة حسب إصدار أندرويد. */
    fun permissions(): Array<String> = when {
        // أندرويد 14+: نطلب الإذنين معاً، فيعرض النظام خيار "السماح لفيديوهات محددة فقط"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun access(context: Context): MediaAccess {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && granted(Manifest.permission.READ_MEDIA_VIDEO) ->
                MediaAccess.FULL
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.PARTIAL
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && granted(Manifest.permission.READ_EXTERNAL_STORAGE) ->
                MediaAccess.FULL
            else -> MediaAccess.NONE
        }
    }

    /** كل الفيديوهات التي يسمح الإذن برؤيتها، من الأحدث للأقدم. */
    suspend fun query(context: Context): List<DeviceVideo> = withContext(Dispatchers.IO) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.VideoColumns.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.MIME_TYPE,
        )
        val result = mutableListOf<DeviceVideo>()
        runCatching {
            context.contentResolver.query(
                collection, projection, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DURATION)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    result += DeviceVideo(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        name = (c.getString(nameCol) ?: "").substringBeforeLast('.').ifEmpty { "video" },
                        durationMs = c.getLong(durCol),
                        sizeBytes = c.getLong(sizeCol),
                        mime = c.getString(mimeCol),
                    )
                }
            }
        }
        result
    }

    // ───────────── الصور المصغّرة (من النظام نفسه، مع ذاكرة مؤقتة صغيرة) ─────────────

    private val thumbs = object : LruCache<Long, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }

    @Suppress("DEPRECATION")
    suspend fun thumbnail(context: Context, video: DeviceVideo): Bitmap? = withContext(Dispatchers.IO) {
        thumbs.get(video.id) ?: runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(video.uri, Size(320, 180), null)
            } else {
                MediaStore.Video.Thumbnails.getThumbnail(
                    context.contentResolver, video.id, MediaStore.Video.Thumbnails.MINI_KIND, null,
                )
            }
        }.getOrNull()?.also { thumbs.put(video.id, it) }
    }
}
