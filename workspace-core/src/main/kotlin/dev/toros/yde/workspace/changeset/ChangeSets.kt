package dev.toros.yde.workspace.changeset

import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.model.deepCopy
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.patch.PatchOp
import java.security.MessageDigest

/*
 * 変更セット（複数ファイルへのパッチの束）。エージェント・CLI・skill との交換の形式（内部で変更を作るコードは、型付きの操作
 * edit.YamlOperation を使う。R29-08）。単一文書への操作（PatchOp / Patcher）は yaml-core の patch/Patch.kt にあり、ここは、
 * ファイル・baseHash・変更セットYAMLの解析という、ワークスペースの関心を持つ。P27 段D の D-b で、root からパッケージ名のまま
 * workspace-core へ移した（意図的差分 D-35）。適用の本体は workspace.ChangeSetApplier。
 */

/**
 * 1 ファイルへの変更。base は変更の前提とするディスク内容のハッシュ（省略時は競合チェックなし）。
 * create = true は新規ファイル作成（content が初期内容。既存ファイルがあれば競合エラー）。
 */
data class FileChange(
    val file: String,          // ワークスペース相対パス
    val baseHash: String?,     // contentHash() の値
    val ops: List<PatchOp>,
    val create: Boolean = false,
    val content: YNode? = null,
)

/** 複数ファイルにまたがる変更の束。 */
data class ChangeSet(val changes: List<FileChange>)

object ChangeSets {

    /** 背景計画へ渡す独立した入力。YAML との往復でコメントや表記を失わせない。 */
    fun copyOf(changeSet: ChangeSet): ChangeSet = ChangeSet(changeSet.changes.map { change ->
        change.copy(content = change.content?.deepCopy(), ops = change.ops.map(::copyOp))
    })

    /** パッチの値と適用先の木を共有しない。後続の入れ子編集で過去の操作値を変えないための複製。 */
    fun copyOp(op: PatchOp): PatchOp = when (op) {
        is PatchOp.Set -> op.copy(path = op.path.toList(), value = op.value.deepCopy())
        is PatchOp.Insert -> op.copy(path = op.path.toList(), value = op.value.deepCopy())
        is PatchOp.Delete -> op.copy(path = op.path.toList())
        is PatchOp.Move -> op.copy(from = op.from.toList(), to = op.to.toList())
        is PatchOp.RenameKey -> op.copy(path = op.path.toList(), newName = op.newName)
        is PatchOp.ReorderKeys -> op.copy(path = op.path.toList(), order = op.order.toList())
    }

    /** ディスク内容の競合検出に使うハッシュ（SHA-256 の16進表現）。 */
    fun contentHash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * 変更セットの YAML を解析する。
     * ```yaml
     * changes:
     *   - file: requirements/req-001.yaml
     *     base: <sha256>            # 省略可
     *     ops:
     *       - { op: set,    path: title,     value: 新しいタイトル }
     *       - { op: insert, path: tags[0],   value: 重要 }
     *       - { op: delete, path: obsolete }
     *       - { op: move,   from: items[2],  to: items[0] }
     *       - { op: rename, path: title,     to: name }
     * ```
     */
    fun parse(text: String): ChangeSet {
        val raw = YamlIO.parseToRaw(text) as? Map<*, *> ?: error("変更セットのルートはobjectである必要があります")
        val changes = (raw["changes"] as? List<*> ?: error("changes リストが必要です")).mapIndexed { i, entry ->
            val m = entry as? Map<*, *> ?: error("changes[$i]はobjectである必要があります")
            val file = m["file"]?.toString() ?: error("changes[$i].file が必要です")
            val create = (m["create"] as? Boolean) ?: false
            if (create) {
                val content = m["content"] ?: error("changes[$i]: create には content が必要です")
                return@mapIndexed FileChange(
                    file = file, baseHash = null, ops = emptyList(),
                    create = true, content = YamlIO.fromAny(content),
                )
            }
            val ops = (m["ops"] as? List<*> ?: error("changes[$i].ops リストが必要です")).mapIndexed { j, opEntry ->
                parseOp(opEntry, "changes[$i].ops[$j]")
            }
            FileChange(file = file, baseHash = m["base"]?.toString(), ops = ops)
        }
        return ChangeSet(changes)
    }

    private fun parseOp(entry: Any?, where: String): PatchOp {
        val m = entry as? Map<*, *> ?: error("${where}はobjectである必要があります")
        fun path(key: String): YPath {
            val value = m[key]?.toString() ?: error("$where.$key が必要です")
            return parseYPath(value)
        }
        fun value(): YNode {
            if ("value" !in m.keys.map { it.toString() }) error("$where.value が必要です")
            return YamlIO.fromAny(m["value"])
        }
        return when (val op = m["op"]?.toString()) {
            "set" -> PatchOp.Set(path("path"), value())
            "insert" -> PatchOp.Insert(path("path"), value())
            "delete" -> PatchOp.Delete(path("path"))
            "move" -> PatchOp.Move(path("from"), path("to"))
            "rename" -> PatchOp.RenameKey(path("path"), m["to"]?.toString() ?: error("$where.to（改名先のキー）が必要です"))
            "reorder" -> PatchOp.ReorderKeys(
                parseYPath(m["path"]?.toString() ?: ""),
                (m["order"] as? List<*>)?.map { it.toString() } ?: error("$where.order（キーの並び）が必要です"),
            )
            else -> error("$where.op は set / insert / delete / move / rename / reorder のいずれかである必要があります: $op")
        }
    }
}
