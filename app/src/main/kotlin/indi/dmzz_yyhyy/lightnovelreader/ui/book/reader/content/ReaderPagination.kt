package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content

import io.nightfish.lightnovelreader.api.content.component.data.AbstractContentComponentData
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.Locale

/** The effective layout used to identify measured page indices. */
@Serializable
data class ReaderPaginationLayout(
    val width: Int,
    val height: Int,
    val fontSize: Float,
    val fontLineHeight: Float,
    val fontWeight: Float,
    val fontFamilyUri: String,
    val density: Float,
    val fontScale: Float,
    val isRtl: Boolean = false,
    val mode: String = "flip",
    val localeTags: String = Locale.getDefault().toLanguageTag(),
    val styleSignature: String = ""
) {
    val key: String get() = readerDigest("pagination-2:" + Json.encodeToString(this))
}

/** Hash processed component data, not object identity or JSON object key order. */
fun readerContentKey(content: List<AbstractContentComponentData>): String = readerDigest(
    buildJsonArray {
        content.forEach { component ->
            add(buildJsonObject {
                put("id", component.id.toString())
                put("type", component.javaClass.name)
                put("data", canonicalReaderJson(component.toJsonElement()))
            })
        }
    }.toString()
)

private fun canonicalReaderJson(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonicalReaderJson(it.value) })
    is JsonArray -> JsonArray(value.map(::canonicalReaderJson))
    else -> value
}

internal fun readerDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
