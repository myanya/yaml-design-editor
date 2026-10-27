package dev.toros.yde.app.core

import dev.toros.yde.workspace.changeset.ChangeSet
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.yaml.patch.PatchOp

/** 変更セットを apply コマンドで再適用できるYAMLへ変換する。 */
fun changeSetToYaml(cs: ChangeSet): String {
    val changes = cs.changes.map { change ->
        if (change.create) linkedMapOf(
            "file" to change.file,
            "create" to true,
            "content" to change.content?.let { YamlIO.toAny(it) },
        ) else linkedMapOf<String, Any?>(
            "file" to change.file,
            "base" to change.baseHash,
            "ops" to change.ops.map { op ->
                when (op) {
                    is PatchOp.Set -> linkedMapOf("op" to "set", "path" to op.path.pretty(), "value" to YamlIO.toAny(op.value))
                    is PatchOp.Insert -> linkedMapOf("op" to "insert", "path" to op.path.pretty(), "value" to YamlIO.toAny(op.value))
                    is PatchOp.Delete -> linkedMapOf("op" to "delete", "path" to op.path.pretty())
                    is PatchOp.Move -> linkedMapOf("op" to "move", "from" to op.from.pretty(), "to" to op.to.pretty())
                    is PatchOp.RenameKey -> linkedMapOf("op" to "rename", "path" to op.path.pretty(), "to" to op.newName)
                    is PatchOp.ReorderKeys -> linkedMapOf("op" to "reorder", "path" to op.path.pretty(), "order" to op.order)
                }
            },
        ).filterValues { it != null }
    }
    return YamlIO.dump(YamlIO.fromAny(linkedMapOf("changes" to changes)))
}
