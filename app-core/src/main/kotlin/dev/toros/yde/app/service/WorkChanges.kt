package dev.toros.yde.app.service

import dev.toros.yde.workspace.changeset.ChangeSet
import dev.toros.yde.workspace.changeset.FileChange
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.patch.PatchOp
import kotlinx.serialization.json.*

/** 提出記録専用の形式。値は YAML の文字列にしてコメントを保持し、パスは文字列と添字を区別する。 */
internal object WorkChanges {
    fun encode(changes: ChangeSet): String = JsonArray(changes.changes.map { change -> buildJsonObject {
        put("file", change.file); put("base", change.baseHash?.let(::JsonPrimitive) ?: JsonNull)
        put("create", change.create)
        put("content", change.content?.let { JsonPrimitive(YamlIO.dump(it)) } ?: JsonNull)
        put("ops", JsonArray(change.ops.map { op -> buildJsonObject {
            fun path(key: String, value: YPath) = put(key, JsonArray(value.map {
                when (it) { is Int -> JsonPrimitive(it); is String -> JsonPrimitive(it); else -> error("パスが不正です") }
            }))
            when (op) {
                is PatchOp.Set -> { put("op", "set"); path("path", op.path); put("value", YamlIO.dump(op.value)) }
                is PatchOp.Insert -> { put("op", "insert"); path("path", op.path); put("value", YamlIO.dump(op.value)) }
                is PatchOp.Delete -> { put("op", "delete"); path("path", op.path) }
                is PatchOp.Move -> { put("op", "move"); path("from", op.from); path("to", op.to) }
                is PatchOp.RenameKey -> { put("op", "rename"); path("path", op.path); put("name", op.newName) }
                is PatchOp.ReorderKeys -> { put("op", "reorder"); path("path", op.path); put("order", JsonArray(op.order.map(::JsonPrimitive))) }
            }
        } }))
    } }).toString()

    fun decode(text: String): ChangeSet = ChangeSet(Json.parseToJsonElement(text).jsonArray.map { entry ->
        val c = entry.jsonObject
        val ops = c.getValue("ops").jsonArray.map { entryOp ->
            val op = entryOp.jsonObject
            fun path(key: String): YPath = op.getValue(key).jsonArray.map {
                val p = it.jsonPrimitive
                if (p.isString) p.content else p.int
            }
            fun value() = YamlIO.parse(op.getValue("value").jsonPrimitive.content)
            when (op.getValue("op").jsonPrimitive.content) {
                "set" -> PatchOp.Set(path("path"), value())
                "insert" -> PatchOp.Insert(path("path"), value())
                "delete" -> PatchOp.Delete(path("path"))
                "move" -> PatchOp.Move(path("from"), path("to"))
                "rename" -> PatchOp.RenameKey(path("path"), op.getValue("name").jsonPrimitive.content)
                "reorder" -> PatchOp.ReorderKeys(path("path"), op.getValue("order").jsonArray.map { it.jsonPrimitive.content })
                else -> error("未対応の提出操作です")
            }
        }
        FileChange(c.getValue("file").jsonPrimitive.content, c.getValue("base").jsonPrimitive.contentOrNull,
            ops, c.getValue("create").jsonPrimitive.boolean, c.getValue("content").jsonPrimitive.contentOrNull?.let(YamlIO::parse))
    })
}
