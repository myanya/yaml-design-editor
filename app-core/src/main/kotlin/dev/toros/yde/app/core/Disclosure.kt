package dev.toros.yde.app.core

import dev.toros.yde.app.index.DocScanner
import dev.toros.yde.app.index.IdIndex
import dev.toros.yde.semantic.schema.Relations
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.yaml.model.scalarText
import dev.toros.yde.app.validate.Validator
import dev.toros.yde.yaml.YamlIO

/**
 * 段階的開示（R1-03）。エージェント／CLI に「カタログ → 骨子 → ノード全文」の順で
 * 必要な分だけ情報を渡すための読み取りAPI。
 */
object Disclosure {

    /** カタログ 1 行。文書を開かずに内容を判断するための要約。 */
    data class CatalogEntry(
        val file: String,
        val documentId: String,
        val name: String?,
        val description: String?,
        val schemaId: String?,
        val idCount: Int,
        /** 現在フェーズと記入進捗の1行サマリ（`phases` 未宣言のスキーマ・スキーマなし文書では null。architecture/schema.md「フェーズ」） */
        val phase: String? = null,
    )

    /** ワークスペースカタログ: 全文書の `_document` メタデータとスキーマ・ID数の一覧（schemas / runs ゾーン・構成ファイルを除く）。 */
    fun catalog(ws: Workspace): List<CatalogEntry> =
        ws.files.asSequence()
            .filter { !ws.layout.indexExcluded(ws.relPath(it)) }
            .map { file ->
                val rel = ws.relPath(file)
                val root = runCatching { YamlIO.parseFirst(java.nio.file.Files.readString(file)) }.getOrNull()
                val ids = ws.index.entries.values.asSequence().flatten().count { it.document.value == rel }
                val schemaDef = ws.schemaFor(file)
                CatalogEntry(
                    file = rel,
                    documentId = root?.let { IdIndex.documentIdOf(it, rel) } ?: rel,
                    name = root?.let { IdIndex.documentNameOf(it) },
                    description = root?.let { IdIndex.documentDescriptionOf(it) },
                    schemaId = schemaDef?.id,
                    idCount = ids,
                    phase = schemaDef?.let { Validator.phaseSummaryText(root, it) },
                )
            }
            .toList()

    /**
     * 文書の骨子。キー構造・配列件数・ID を保ち、長い本文は「(text, N文字)」に要約する。
     * 配列はスカラー要素なら先頭 [maxSeqItems] 件、複合要素なら全件を出す。
     */
    fun outline(root: YNode, maxValueLength: Int = 60, maxSeqItems: Int = 5): String =
        buildString { renderOutline(this, root, 0, maxValueLength, maxSeqItems) }.trimEnd('\n')

    private fun renderOutline(sb: StringBuilder, node: YNode, indent: Int, maxLen: Int, maxSeq: Int) {
        val pad = "  ".repeat(indent)
        when (node) {
            is YNode.Scalar -> sb.append(pad).append(scalarSummary(node, maxLen)).append('\n')
            is YNode.Map -> {
                if (node.entries.isEmpty()) sb.append(pad).append("{}\n")
                for ((k, v) in node.entries) {
                    when (v) {
                        is YNode.Scalar -> sb.append(pad).append(k).append(": ").append(scalarSummary(v, maxLen)).append('\n')
                        is YNode.Map -> {
                            sb.append(pad).append(k).append(":\n")
                            renderOutline(sb, v, indent + 1, maxLen, maxSeq)
                        }
                        is YNode.Seq -> {
                            sb.append(pad).append(k).append(": (array, ").append(v.items.size).append("件)\n")
                            renderSeq(sb, v, indent + 1, maxLen, maxSeq)
                        }
                    }
                }
            }
            is YNode.Seq -> renderSeq(sb, node, indent, maxLen, maxSeq)
        }
    }

    private fun renderSeq(sb: StringBuilder, seq: YNode.Seq, indent: Int, maxLen: Int, maxSeq: Int) {
        val pad = "  ".repeat(indent)
        val scalarOnly = seq.items.all { it is YNode.Scalar }
        val shown = if (scalarOnly) seq.items.take(maxSeq) else seq.items
        shown.forEachIndexed { i, item ->
            when (item) {
                is YNode.Scalar -> sb.append(pad).append("[").append(i).append("] ").append(scalarSummary(item, maxLen)).append('\n')
                else -> {
                    sb.append(pad).append("[").append(i).append("]:\n")
                    renderOutline(sb, item, indent + 1, maxLen, maxSeq)
                }
            }
        }
        if (shown.size < seq.items.size) {
            sb.append(pad).append("…（残り ").append(seq.items.size - shown.size).append(" 件）\n")
        }
    }

    private fun scalarSummary(node: YNode.Scalar, maxLen: Int): String {
        val text = node.value?.toString() ?: "null"
        return if (text.contains('\n') || text.length > maxLen) "(text, ${text.length}文字)" else text
    }

    /** 指定パス配下の全文を YAML で返す。maxChars を超える場合は打ち切りを明示する。 */
    fun nodeYaml(root: YNode, path: YPath, maxChars: Int = 20_000): String {
        val node = getAt(root, path) ?: error("パスが存在しません: ${path.pretty()}")
        val text = YamlIO.dump(node)
        return if (text.length <= maxChars) text
        else text.take(maxChars) + "\n…（${text.length - maxChars} 文字省略。maxChars を増やすか、より深いパスを指定してください）\n"
    }

    /**
     * 対象ノードとその関連情報（参照先・被参照）をまとめた文脈バンドル。
     * budgetChars 内に収まるよう、本文 → 関連の順に割り当てて打ち切る。
     */
    fun bundle(ws: Workspace, rel: String, path: YPath, budgetChars: Int = 8_000): String {
        val doc = ws.load(rel)
        val root = doc.root ?: error("YAML パースエラー: ${doc.parseError}")
        val sb = StringBuilder()
        sb.append("file: ").append(rel).append('\n')
        sb.append("documentId: ").append(IdIndex.documentIdOf(root, rel)).append('\n')
        IdIndex.documentNameOf(root)?.let { sb.append("documentName: ").append(it).append('\n') }
        IdIndex.documentDescriptionOf(root)?.let { sb.append("documentDescription: ").append(it.replace("\n", " ").take(300)).append('\n') }
        doc.schema?.let { sb.append("schema: ").append(it.id).append('\n') }
        doc.schema?.let { sc -> Validator.phaseSummaryText(root, sc) }?.let { sb.append("phase: ").append(it).append('\n') }
        sb.append("path: ").append(path.pretty()).append('\n')

        val bodyBudget = (budgetChars * 6) / 10
        sb.append("target: |\n")
        nodeYaml(root, path, bodyBudget).lineSequence().forEach { sb.append("  ").append(it).append('\n') }

        // 対象サブツリーからの参照と、サブツリー内で定義される ID への被参照
        val documentId = IdIndex.documentIdOf(root, rel)
        val (ids, refs) = DocScanner.scan(root, DocumentId(rel), doc.schema, documentId)
        fun under(p: YPath): Boolean = p.size >= path.size && p.subList(0, path.size) == path
        val refsOut = refs.filter { under(it.path) }
        val idsIn = ids.filter { under(it.path) }.map { it.id }.toSet()
        val backlinks = ws.index.backlinksTo(idsIn).filter { it.document.value != rel }

        if (refsOut.isNotEmpty()) {
            sb.append("refs:\n")
            for (r in refsOut) {
                if (sb.length > budgetChars) break
                val target = ws.index.resolve(r).firstOrNull()
                sb.append("  - id: ").append(r.targetId)
                // 関係レジストリの表示ラベル（R20-02。未宣言は名前のまま）
                Relations.labelOf(ws.relations, r.relation)?.let { sb.append("  relation: ").append(it) }
                target?.let { sb.append("  file: ").append(it.document.value); it.title?.let { t -> sb.append("  title: ").append(t) } }
                sb.append('\n')
            }
        }
        if (backlinks.isNotEmpty() && sb.length < budgetChars) {
            sb.append("backlinks:\n")
            for (b in backlinks) {
                if (sb.length > budgetChars) break
                sb.append("  - id: ").append(b.targetId).append("  from: ").append(b.document.value)
                    .append("  via: ").append(b.viaKey)
                Relations.inverseLabelOf(ws.relations, b.relation)?.let { sb.append("  relation: ").append(it) }
                sb.append('\n')
            }
        }
        if (sb.length > budgetChars) {
            return sb.substring(0, budgetChars) + "\n…（予算 ${budgetChars} 文字で打ち切り）\n"
        }
        return sb.toString()
    }
}
