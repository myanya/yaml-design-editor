package dev.toros.yde.server.protocol

import dev.toros.yde.app.service.ReadFailure
import dev.toros.yde.app.service.ReadFailureCode
import dev.toros.yde.server.auth.LocalAccessFailure
import dev.toros.yde.workspace.changeset.ChangeSet
import dev.toros.yde.workspace.changeset.ChangeSets
import kotlinx.serialization.json.*

/** 公開する値の変換と入力形式は API・MCP で共用する。適用規則は共通サービス以降に置く。 */
internal fun externalJson(value: Any?, depth: Int = 0): JsonElement {
    if (depth > 128) throw ReadFailure(ReadFailureCode.LIMIT_EXCEEDED, "値の入れ子が深すぎます")
    return when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Double -> if (value.isFinite()) JsonPrimitive(value) else JsonPrimitive(value.toString())
        is Float -> if (value.isFinite()) JsonPrimitive(value) else JsonPrimitive(value.toString())
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> require(key is String); key to externalJson(item, depth + 1) })
        is List<*> -> JsonArray(value.map { externalJson(it, depth + 1) })
        else -> error("公開できない値の型です")
    }
}

internal fun invalidWrite(): Nothing = throw LocalAccessFailure(400, "要求の JSON 形式・項目を確認してください")
internal fun JsonObject.string(key: String, maxLength: Int = 200): String = (this[key] as? JsonPrimitive)
    ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() && it.length <= maxLength } ?: invalidWrite()
internal fun actionRequestId(id: String): String {
    if (!id.matches(Regex("[A-Za-z0-9_-]{1,180}"))) invalidWrite()
    return "action/$id"
}
internal fun jsonValue(value: JsonElement, depth: Int = 0): Any? {
    if (depth > 64) invalidWrite()
    return when (value) {
        JsonNull -> null
        is JsonObject -> value.mapValues { jsonValue(it.value, depth + 1) }
        is JsonArray -> value.map { jsonValue(it, depth + 1) }
        is JsonPrimitive -> if (value.isString) value.content else value.booleanOrNull ?: value.longOrNull ?: value.doubleOrNull ?: invalidWrite()
    }
}
internal fun inputChanges(body: JsonObject): ChangeSet {
    if (body.keys != setOf("changes")) invalidWrite()
    jsonValue(body)
    checkChanges(body)
    return try { ChangeSets.parse(body.toString()) } catch (_: Exception) { invalidWrite() }
}
private fun checkChanges(body: JsonObject) {
    val changes = body["changes"] as? JsonArray ?: invalidWrite()
    if (changes.isEmpty()) invalidWrite()
    for (item in changes) {
        val file = item as? JsonObject ?: invalidWrite()
        if (file.keys.any { it !in setOf("file", "base", "create", "content", "ops") }) invalidWrite()
        file.string("file", 1024)
        file["base"]?.let { file.string("base") }
        val create = file["create"]?.let { (it as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: invalidWrite() } ?: false
        if (create) {
            if (file["content"] == null || file["content"] == JsonNull || "ops" in file || "base" in file) invalidWrite()
        } else {
            if ("content" in file) invalidWrite()
            for (entry in file["ops"] as? JsonArray ?: invalidWrite()) {
                val op = entry as? JsonObject ?: invalidWrite()
                val keys = when (op.string("op")) {
                    "set", "insert" -> setOf("op", "path", "value")
                    "delete" -> setOf("op", "path")
                    "move" -> setOf("op", "from", "to")
                    "rename" -> setOf("op", "path", "to")
                    "reorder" -> setOf("op", "path", "order")
                    else -> invalidWrite()
                }
                if (op.keys != keys) invalidWrite()
                for (key in keys - setOf("op", "value", "order")) {
                    if ((op[key] as? JsonPrimitive)?.isString != true) invalidWrite()
                }
                op["order"]?.let { order ->
                    if (order !is JsonArray || order.any { (it as? JsonPrimitive)?.isString != true }) invalidWrite()
                }
            }
        }
    }
}
