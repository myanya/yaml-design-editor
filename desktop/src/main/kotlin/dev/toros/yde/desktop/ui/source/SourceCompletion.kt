package dev.toros.yde.desktop.ui.source

import dev.toros.yde.app.index.idMatchesPattern
import dev.toros.yde.app.schema.effectiveFields
import dev.toros.yde.app.schema.fieldAt
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.getAt
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.relativeTo
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.OpenDocument

/** 補完の候補 1 つ。insert は欄に入れる文字列、label は一覧の表示、detail は添える説明。 */
internal data class Candidate(val insert: String, val label: String, val detail: String?)

/**
 * Ctrl+Space の補完の候補（Compose 非依存）。カーソルの文脈（`SourceContext`）に合わせて、
 * キー名はスキーマの実効フィールド、ID は上書き索引、enum は値（label は添えるだけ）、真偽値、file はワークスペースのファイルから出す。
 * スキーマの無い文書・木の無い文書では何も出さない。
 */
internal object SourceCompletion {
    /** ワークスペースの中のファイル（隠しディレクトリを除く。ルートからの相対・区切りは /）。file 型の補完に使う。 */
    fun listWorkspaceFiles(ws: Path): List<String> {
        val result = mutableListOf<String>()
        runCatching {
            Files.walk(ws).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { p ->
                    val rel = p.relativeTo(ws)
                    if (rel.none { it.name.startsWith(".") }) result += rel.joinToString("/")
                }
            }
        }
        return result.sorted()
    }

    fun candidates(state: AppState, doc: OpenDocument, ctx: SourceContext.Context, files: List<String>): List<Candidate> {
        val schema = doc.schema ?: return emptyList()
        val root = doc.root ?: return emptyList()
        val candidates: List<Candidate> = when (ctx.mode) {
            SourceContext.Mode.NONE -> emptyList()
            SourceContext.Mode.KEY -> {
                val parent = schema.fieldAt(root, ctx.parentPath)
                if (parent == null || parent.type != FieldType.OBJECT) emptyList() else {
                    val node = getAt(root, ctx.parentPath) as? YNode.Map
                    val existing = node?.entries?.keys.orEmpty()
                    schema.effectiveFields(parent.fields, node).fields.mapNotNull { f ->
                        val key = f.key ?: return@mapNotNull null
                        if (key in existing) return@mapNotNull null
                        val eff = schema.resolve(f) ?: f
                        val detail = listOfNotNull(eff.label?.takeIf { it != key }, eff.typeName ?: eff.type.id, if (eff.required) "必須" else null)
                            .joinToString("・")
                        Candidate(if (eff.type.isContainer) "$key:" else "$key: ", key, detail)
                    }
                }
            }
            SourceContext.Mode.VALUE -> {
                val key = ctx.key ?: return emptyList()
                val field = schema.fieldAt(root, ctx.parentPath + key) ?: return emptyList()
                when (field.type) {
                    FieldType.ENUM -> field.values.map { v ->
                        Candidate(v, v, field.hints.options.firstOrNull { it.value == v }?.label)
                    }
                    FieldType.BOOLEAN -> listOf(Candidate("true", "true", null), Candidate("false", "false", null))
                    FieldType.REF -> state.overlayIndex().entries.values.asSequence()
                        .mapNotNull { it.firstOrNull() }
                        .filter { e -> field.idPatterns.isEmpty() || field.idPatterns.any { idMatchesPattern(e.id, it) } }
                        .sortedBy { it.id }
                        .map { Candidate(it.id, it.id, it.title) }
                        .toList()
                    FieldType.FILE -> files
                        .filter { f -> field.extensions.isEmpty() || field.extensions.any { f.lowercase().endsWith(".$it") } }
                        .map { Candidate(it, it, null) }
                    else -> emptyList()
                }
            }
        }
        // 打ちかけの語を含む候補。頭が一致するものを先に
        return candidates.filter { it.label.contains(ctx.prefix, ignoreCase = true) }
            .sortedBy { if (it.label.startsWith(ctx.prefix, ignoreCase = true)) 0 else 1 }
            .take(200)
    }
}
