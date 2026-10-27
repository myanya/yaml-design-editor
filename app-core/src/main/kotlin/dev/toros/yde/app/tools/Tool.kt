package dev.toros.yde.app.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add

/**
 * 入口（LLM のツール宣言・MCP・CLI）に依らないツール 1 つ（ADR-0013「窓口の形」）。
 * 引数と結果は JSON にできる値だけで、カーネルの型を外へ出さない。入口はこれを自分の形（agent の `ToolSpec`・MCP のツール・命令）に包む。
 */
class Tool(
    val name: String,
    val description: String,
    /** 引数の形（JSON Schema）。 */
    val parameters: JsonObject,
    val kind: ToolKind,
    private val body: (ToolContext, JsonObject) -> JsonElement,
) {
    fun run(ctx: ToolContext, args: JsonObject): JsonElement = body(ctx, args)
}

/** ツールの種類。入口はこれで出すツールを選ぶ（読むだけの実行は READ と VALIDATE だけ、など）。 */
enum class ToolKind { READ, EDIT, VALIDATE, ACTION }

/** ツールの結果を人（や LLM）に見せる文字にする。文字の値はそのまま、それ以外は JSON の文字列。 */
fun JsonElement.toolText(): String = if (this is JsonPrimitive && isString) content else toString()

// ---------------------------------------------------------------- 引数の形（JSON Schema）の組み立ての補助

/** 平らな引数（文字・真偽・整数・文字の配列）の形を短く書く。入れ子の形は `buildJsonObject` で書く。 */
class ToolArgs {
    internal val properties = mutableListOf<Triple<String, String, String>>() // name, type, description
    internal val requiredNames = mutableListOf<String>()
    fun required(name: String, description: String) {
        properties += Triple(name, "string", description); requiredNames += name
    }
    fun optional(name: String, description: String) {
        properties += Triple(name, "string", description)
    }
    fun optionalBool(name: String, description: String) {
        properties += Triple(name, "boolean", description)
    }
    fun optionalInt(name: String, description: String) {
        properties += Triple(name, "integer", description)
    }
    fun optionalStringArray(name: String, description: String) {
        properties += Triple(name, "array", description)
    }
}

fun toolArgs(build: ToolArgs.() -> Unit): JsonObject {
    val builder = ToolArgs().apply(build)
    return buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            builder.properties.forEach { (name, type, description) ->
                putJsonObject(name) {
                    put("type", type)
                    put("description", description)
                    if (type == "array") putJsonObject("items") { put("type", "string") }
                }
            }
        }
        putJsonArray("required") { builder.requiredNames.forEach(::add) }
    }
}
