package com.nazeeltek.savetek.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * قائمة التحميلات (الجارية والمكتملة).
 * المكتملة والفاشلة تُحفظ في ملف لتبقى بعد إغلاق التطبيق.
 */
object DownloadRepository {

    private lateinit var file: File

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    fun init(context: Context) {
        file = File(context.filesDir, "downloads.json")
        _items.value = load()
    }

    fun get(id: String): DownloadItem? = _items.value.firstOrNull { it.id == id }

    fun add(item: DownloadItem) {
        _items.update { listOf(item) + it }
    }

    /** يعدّل عنصراً. persist = true يحفظ القائمة في الملف (عند تغيّر الحالة فقط). */
    fun update(id: String, persist: Boolean = false, change: (DownloadItem) -> DownloadItem) {
        _items.update { list -> list.map { if (it.id == id) change(it) else it } }
        if (persist) save()
    }

    fun remove(id: String) {
        _items.update { list -> list.filterNot { it.id == id } }
        save()
    }

    @Synchronized
    private fun save() {
        val array = JSONArray()
        _items.value.filter { it.status != DownloadStatus.RUNNING }.forEach { array.put(it.toJson()) }
        runCatching { file.writeText(array.toString()) }
    }

    private fun load(): List<DownloadItem> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).map { array.getJSONObject(it).toItem() }
    }.getOrDefault(emptyList())

    private fun DownloadItem.toJson() = JSONObject().apply {
        put("id", id); put("url", url); put("title", title); put("thumbnail", thumbnail)
        put("qualityLabel", qualityLabel); put("maxHeight", maxHeight); put("audioOnly", audioOnly)
        put("status", status.name); put("fileName", fileName); put("uri", uri); put("mime", mime)
        put("error", error); put("createdAt", createdAt)
        put("kind", kind.name); put("sourceUri", sourceUri); put("bitrateKbps", bitrateKbps)
    }

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).ifEmpty { null }

    private fun JSONObject.toItem() = DownloadItem(
        id = getString("id"),
        url = optString("url"),
        title = optString("title"),
        thumbnail = str("thumbnail"),
        qualityLabel = optString("qualityLabel"),
        maxHeight = if (isNull("maxHeight")) null else optInt("maxHeight"),
        audioOnly = optBoolean("audioOnly"),
        status = runCatching { DownloadStatus.valueOf(getString("status")) }
            .getOrDefault(DownloadStatus.FAILED),
        fileName = str("fileName"),
        uri = str("uri"),
        mime = str("mime"),
        error = str("error"),
        createdAt = optLong("createdAt"),
        kind = runCatching { JobKind.valueOf(getString("kind")) }.getOrDefault(JobKind.DOWNLOAD),
        sourceUri = str("sourceUri"),
        bitrateKbps = if (isNull("bitrateKbps")) null else optInt("bitrateKbps"),
    )
}
