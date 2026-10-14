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

/** نوع ملفات الجهاز: فيديو أو صوت. */
enum class MediaKind { VIDEO, AUDIO }

/** ملف فيديو أو صوت موجود في الجوال. */
data class DeviceFile(
    val id: Long,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val mime: String?,
    val kind: MediaKind,
    /** اسم المجلد الذي فيه الملف (مثل Camera أو Download)، أو "" إن لم يُعرف. */
    val folder: String = "",
)

/** مستوى الوصول لملفات الجوال. */
enum class MediaAccess {
    /** كل الملفات. */
    FULL,

    /** أندرويد 14+: فيديوهات اختارها المستخدم فقط (لا يوجد مثله للصوت). */
    PARTIAL,

    /** لا يوجد إذن. */
    NONE,
}

/**
 * قراءة فيديوهات الجوال وملفاته الصوتية من مكتبة الوسائط الخاصة بالنظام.
 * كل شيء يحدث داخل الجوال فقط: لا يُرسل أي ملف أو معلومة عنه لأي مكان.
 */
object DeviceMedia {

    /** الأذونات المطلوبة حسب النوع وإصدار أندرويد. */
    fun permissions(kind: MediaKind): Array<String> = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        kind == MediaKind.AUDIO -> arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        // أندرويد 14+: نطلب الإذنين معاً، فيعرض النظام خيار "السماح لفيديوهات محددة فقط"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        else -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
    }

    fun access(context: Context, kind: MediaKind): MediaAccess {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return if (granted(Manifest.permission.READ_EXTERNAL_STORAGE)) MediaAccess.FULL else MediaAccess.NONE
        }
        return when (kind) {
            MediaKind.AUDIO ->
                if (granted(Manifest.permission.READ_MEDIA_AUDIO)) MediaAccess.FULL else MediaAccess.NONE
            MediaKind.VIDEO -> when {
                granted(Manifest.permission.READ_MEDIA_VIDEO) -> MediaAccess.FULL
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.PARTIAL
                else -> MediaAccess.NONE
            }
        }
    }

    /** كل الملفات التي يسمح الإذن برؤيتها، من الأحدث للأقدم. */
    suspend fun query(context: Context, kind: MediaKind): List<DeviceFile> = withContext(Dispatchers.IO) {
        val q = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val collection = when (kind) {
            MediaKind.VIDEO -> if (q) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            MediaKind.AUDIO -> if (q) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val durationColumn = when (kind) {
            MediaKind.VIDEO -> MediaStore.Video.VideoColumns.DURATION
            MediaKind.AUDIO -> MediaStore.Audio.AudioColumns.DURATION
        }
        // اسم المجلد: أندرويد 10+ يعطيه مباشرة، وما قبله نأخذه من مسار الملف
        @Suppress("DEPRECATION")
        val folderColumn = if (q) MediaStore.MediaColumns.BUCKET_DISPLAY_NAME else MediaStore.MediaColumns.DATA
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            durationColumn,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            folderColumn,
        )
        // للصوت: نستبعد نغمات الإشعارات والمنبّه والرنين
        val selection = if (kind == MediaKind.AUDIO) {
            "${MediaStore.Audio.AudioColumns.IS_NOTIFICATION} = 0 AND " +
                "${MediaStore.Audio.AudioColumns.IS_ALARM} = 0 AND " +
                "${MediaStore.Audio.AudioColumns.IS_RINGTONE} = 0"
        } else null

        val result = mutableListOf<DeviceFile>()
        runCatching {
            context.contentResolver.query(
                collection, projection, selection, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val durCol = c.getColumnIndexOrThrow(durationColumn)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val folderCol = c.getColumnIndex(folderColumn)
                val fallbackName = if (kind == MediaKind.AUDIO) "audio" else "video"
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val rawFolder = if (folderCol >= 0) c.getString(folderCol).orEmpty() else ""
                    val folder = if (q) rawFolder else rawFolder.substringBeforeLast('/', "").substringAfterLast('/')
                    result += DeviceFile(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        name = (c.getString(nameCol) ?: "").substringBeforeLast('.').ifEmpty { fallbackName },
                        durationMs = c.getLong(durCol),
                        sizeBytes = c.getLong(sizeCol),
                        mime = c.getString(mimeCol),
                        kind = kind,
                        folder = folder,
                    )
                }
            }
        }
        result
    }

    // ───────────── الصور المصغّرة (من النظام نفسه، مع ذاكرة مؤقتة صغيرة) ─────────────

    private val thumbs = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** صورة الفيديو المصغّرة، أو غلاف الأغنية للصوت (أندرويد 10+)، أو null إن لم توجد. */
    @Suppress("DEPRECATION")
    suspend fun thumbnail(context: Context, file: DeviceFile): Bitmap? = withContext(Dispatchers.IO) {
        val key = "${file.kind}:${file.id}"
        thumbs.get(key) ?: runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    context.contentResolver.loadThumbnail(file.uri, Size(320, 180), null)
                file.kind == MediaKind.VIDEO -> MediaStore.Video.Thumbnails.getThumbnail(
                    context.contentResolver, file.id, MediaStore.Video.Thumbnails.MINI_KIND, null,
                )
                else -> null
            }
        }.getOrNull()?.also { thumbs.put(key, it) }
    }
}
