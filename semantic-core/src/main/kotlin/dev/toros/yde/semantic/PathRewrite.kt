package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.FilePath
import dev.toros.yde.semantic.schema.FilePathKind
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.SchemaTree
import dev.toros.yde.semantic.schema.globToRegex
import dev.toros.yde.yaml.model.*

/** パスの対応を用いた Rewrite。型付き file 値の列挙は既存のスキーマ誘導走査に委譲する。 */
object PathRewrite {
    data class Move(val from: String, val to: String, val directory: Boolean)
    data class Edit(val path: YPath, val before: String, val after: String, val permission: Boolean = false)
    data class Result(val edits: List<Edit>, val warnings: List<String>)
    fun relocated(value: String, moves: List<Move>): String = moves.firstOrNull {
        value == it.from || (it.directory && value.startsWith(it.from + "/"))
    }?.let { it.to + value.removePrefix(it.from) } ?: value

    /** glob は部分移動なら旧対象も残す。任意式の書換は行わない。 */
    fun pattern(value: String, moves: List<Move>, knownFiles: Collection<String>): String {
        val direct = relocated(value, moves)
        if (direct != value) return direct
        val regex = runCatching { globToRegex(value) }.getOrNull() ?: return value
        val additions = knownFiles.filter(regex::matches).map { relocated(it, moves) }
            .filterNot(regex::matches).distinct().sorted()
        if (additions.isEmpty()) return value
        require(additions.none { it.any { c -> c in "*?{}," } }) { "glob の特殊文字を含む移動先は自動追加できません" }
        return (listOf(value) + additions).joinToString(",", "{", "}")
    }

    fun definition(root: YNode, moves: List<Move>, knownFiles: Collection<String>): Result {
        val edits = mutableListOf<Edit>(); val warnings = mutableListOf<String>()
        val globKeys = setOf("match", "fileGlob", "allowedFiles")
        val pathKeys = setOf("file", "filePattern", "ledger")
        fun walk(node: YNode, path: YPath, key: String?) {
            when (node) {
                is YNode.Map -> node.entries.forEach { (k, v) -> walk(v, path + k, k) }
                is YNode.Seq -> node.items.forEachIndexed { i, v -> walk(v, path + i, key) }
                is YNode.Scalar -> {
                    val before = node.value as? String ?: return
                    if (key !in globKeys && key !in pathKeys) {
                        if (moves.any { before.contains(it.from + "/") || before == it.from })
                            warnings += "${path.pretty()}: パスへの言及があります（自動修正の対象外）"
                        return
                    }
                    // 定義内の default・例・条件の文字列は設定のパスとして解釈しない。
                    if (path.any { it in setOf("default", "examples", "enum", "where", "require") }) return
                    val after = runCatching {
                        if (key in globKeys) pattern(before, moves, knownFiles) else relocated(before, moves)
                    }.getOrElse { warnings += "${path.pretty()}: ${it.message}"; before }
                    if (before != after) edits += Edit(path, before, after, key == "allowedFiles" || key == "ledger")
                    else if (moves.any { before.contains(it.from) }) {
                        warnings += "${path.pretty()}: 式を含むパスは手動確認が必要です"
                    }
                }
            }
        }
        walk(root, emptyList(), null)
        return Result(edits, warnings)
    }

    fun files(schema: SchemaTree, root: YNode, document: DocumentId, moves: List<Move>): List<Edit> {
        val destination = DocumentId(relocated(document.value, moves))
        return SemanticRewrite.fieldHits(schema, root) { schema.resolve(it)?.type == FieldType.FILE || it.type == FieldType.FILE }
            .mapNotNull { path ->
                val value = scalarText(getAt(root, path)) ?: return@mapNotNull null
                val rel = FilePath.toWorkspaceRelative(value, document) ?: return@mapNotNull null
                val target = relocated(rel, moves)
                if (target == rel && destination == document) return@mapNotNull null
                val after = if (FilePath.kindOf(value) == FilePathKind.DOCUMENT) relative(destination.value, target) else target
                if (value == after) null else Edit(path, value, after)
            }
    }

    /** 文書相対を保つ。プラットフォームの Path を意味層に持ち込まない。 */
    fun relative(document: String, target: String): String {
        val parent = document.split('/').dropLast(1)
        val parts = target.split('/')
        val common = parent.zip(parts).takeWhile { it.first == it.second }.size
        val text = (List(parent.size - common) { ".." } + parts.drop(common)).joinToString("/")
        return if (text.startsWith("../")) text else "./$text"
    }
}
