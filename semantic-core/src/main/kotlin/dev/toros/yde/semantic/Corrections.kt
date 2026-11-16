package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.SchemaTree
import dev.toros.yde.semantic.schema.Template
import dev.toros.yde.semantic.schema.TransformDef
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.yaml.model.scalarText

/** 補正の参照と set を項の変更へ下ろす。原本を変えず、参照・照合・計算列も同じ補正後の木を読む。 */
object SemanticCorrections {
    data class Replacement(
        val document: DocumentId, val path: YPath, val source: DocumentId, val sourcePath: YPath,
        val id: String?, val before: YNode?,
    )

    class Projection(
        val trees: Map<DocumentId, YNode>, val ids: Map<String, List<ScannedId>>,
        val refs: List<ScannedRef>, val graph: SemanticEntityGraph, val replacements: List<Replacement>,
    ) {
        /** 補正の値へ移れる座標と、元の値の来歴を保つ。複合値と計算の材料に含まれる補正も開示する。 */
        fun cell(cell: VirtualCell): VirtualCell {
            val direct = replacements.lastOrNull { it.document == cell.document && cell.path?.startsWith(it.path) == true }
            val affected = replacements.filter { r ->
                (r.document == cell.document && cell.path?.let { it.startsWith(r.path) || r.path.startsWith(it) } == true) ||
                    cell.trace?.inputs.orEmpty().any { i -> i.document == r.document && i.path?.startsWith(r.path) == true }
            }
            if (affected.isEmpty()) return cell
            val inputs = cell.trace?.inputs.orEmpty() + affected.flatMap { r ->
                listOf(
                    VirtualComputedInput("補正 ${r.id ?: "（ID なし）"}", null, r.source, r.sourcePath),
                    VirtualComputedInput("元の値", scalarText(r.before) ?: r.before?.let { "（値の組）" } ?: "（無し）", r.document, r.path),
                )
            }
            return cell.copy(
                document = direct?.source ?: cell.document,
                path = direct?.let { it.sourcePath + cell.path!!.drop(it.path.size) } ?: cell.path,
                computed = true,
                trace = VirtualComputedTrace("補正 ${affected.mapNotNull { it.id }.distinct().joinToString("・")} で置き換え（元の値: ${scalarText(direct?.before) ?: "来歴を参照"}）", inputs),
            )
        }
    }

    fun project(
        def: VirtualCorrectionsDef, ids: Map<String, List<ScannedId>>, refs: List<ScannedRef>, graph: SemanticEntityGraph,
        tree: (DocumentId) -> YNode?, schemaFor: (DocumentId) -> SchemaTree? = { null },
    ): Projection {
        val changes = linkedMapOf<DocumentId, MutableList<Pair<YPath, TransformDef>>>()
        val replacements = mutableListOf<Replacement>()
        val byDoc = ids.values.flatten().groupBy { it.document }
        refs.filter { it.sourceSchemaId in def.schemaIds && it.path.lastOrNull() == def.target }
            .sortedWith(compareBy({ it.document.value }, { VirtualViews.pathSortKey(it.path) })).forEach { ref ->
                val at = ref.path.dropLast(1)
                val set = tree(ref.document)?.let { getAt(it, at + def.set) } as? YNode.Map
                    ?: error("補正 ${ref.document.value}:${at.pretty()} の ${def.set} は object で書きます")
                val correctionId = byDoc[ref.document].orEmpty().firstOrNull { it.path == at }?.id
                val targets = SemanticExpression.preferDefining(ids[ref.targetId].orEmpty())
                require(targets.isNotEmpty()) { "補正の当たり先「${ref.targetId}」がありません" }
                val target = targets.first()
                for ((key, value) in set.entries) {
                    val path = target.path + parseYPath(key)
                    require(path.size > target.path.size) { "補正の置き換える項目を指定してください" }
                    val sourcePath = at + def.set + key
                    replacements += Replacement(target.document, path, ref.document, sourcePath, correctionId, tree(target.document)?.let { getAt(it, path) })
                    changes.getOrPut(target.document) { mutableListOf() } += path to TransformDef("correction", null, emptyList(), null, literal(value), emptyList())
                }
            }
        if (replacements.isEmpty()) return Projection(emptyMap(), ids, refs, graph, emptyList())
        val trees = changes.mapValues { (document, set) ->
            val change = TermChange("set", null, null, null, set, emptyList(), null, emptyList())
            val result = TermChanges.apply(requireNotNull(tree(document)), TermChangeDoc("corrections", "補正", null, "", TermChangeDoc.Kind.PATCH, listOf(change)), emptyMap())
            require(result.errors.isEmpty()) { result.errors.joinToString("\n") }
            result.term
        }
        val scans = graph.documents.values.distinctBy { it.document }.map { d ->
            val originalIds = byDoc[d.document].orEmpty()
            val originalRefs = refs.filter { it.document == d.document }
            val root = trees[d.document]
            if (root == null) DocumentScan(d.document, d.documentId, originalIds, originalRefs)
            else {
                val schema = schemaFor(d.document)
                val scanned = if (schema == null) originalIds to originalRefs.mapNotNull { r ->
                    scalarText(getAt(root, r.path))?.let { r.copy(targetId = it) }
                } else SemanticScanner.scan(SemanticDocument(d.document, root, schema, schema.id, documentId = d.documentId))
                require(scanned.first.map { it.id to it.path }.toSet() == originalIds.map { it.id to it.path }.toSet()) {
                    "補正では ID の追加・削除・変更はできません（${d.document.value}）"
                }
                val correctedIds = scanned.first.map { found ->
                    originalIds.first { it.id == found.id && it.path == found.path }.copy(title = found.title)
                }
                DocumentScan(d.document, d.documentId, correctedIds, scanned.second)
            }
        }
        val projectedIds = scans.flatMap { it.ids }.groupBy { it.id }
        return Projection(trees, projectedIds, scans.flatMap { it.refs }, SemanticEntityGraph.build(scans, projectedIds), replacements)
    }

    private fun literal(node: YNode): Template = when (node) {
        is YNode.Map -> Template.MapOf(node.entries.map { (k, v) -> k to literal(v) })
        is YNode.Seq -> Template.ListOf(node.items.map(::literal))
        is YNode.Scalar -> Template.Literal(node.value)
    }

    private fun YPath.startsWith(prefix: YPath): Boolean = size >= prefix.size && take(prefix.size) == prefix
}
