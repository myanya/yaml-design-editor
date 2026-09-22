package dev.toros.yde.semantic

import dev.toros.yde.yaml.edit.YamlOperation
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.SchemaKind
import dev.toros.yde.semantic.schema.SchemaTree

/**
 * Core Calculus の Rewrite 共有基盤（P29 Phase 4・R29-07。CORE-CALCULUS §11〜§13）。
 * Rewrite ＝ Query（変更する場所の列挙）× 任意の Predicate（場所の絞り込み・中止条件）× Edit*（場所 → 型付き操作）で、
 * 評価結果は文書ごとの操作列（`PlannedDocumentChange`）——ここでは適用しない（適用は workspace-core の
 * トランザクション。§14）。高水準操作（`SemanticSchemaRefactor` の12操作・`SemanticRefactor` のエンティティ操作）が
 * 共有する「場所の列挙器」と「操作の集積器」の単一実装で、操作ごとに走査・文書ループ・集積を書き直さない。
 *
 * 適用戦略は基本形だけを持つ（スナップショット → 全一致を列挙 → 操作列を生成。§13）。場所どうしが独立でない
 * 操作（フィールド移動の「中間生成 → Move → 空になった親の削除」、定義の抽出／インライン化のような1箇所の
 * 構造変換）は、列挙器だけをここから使い、操作列の組み立ては操作側が持つ（無理に Edit* へ押し込まない）。
 */
internal object SemanticRewrite {

    // ================================================================ Query ①: スキーマ誘導の文書走査

    /**
     * 文書ツリーをスキーマ誘導で走査する（object は実効フィールド・array は item・map は value を辿る）。
     * cases は `effectiveFields`（現在の enum 値で実効なフィールドだけ）を通すため、同名キーを持つ別 case の
     * 値を誤って対象にしない。訪問順は決定的（フィールド宣言順の深さ優先）:
     * - onObject: object ノードへ入った時点（その object のパス・マップ・実効フィールド）
     * - onField: 実効フィールドのうち文書に値があるものごと（そのフィールドの子へ降りる**前**。データキーの座標）
     */
    fun walkDocument(
        s: SchemaTree,
        root: YNode,
        onObject: (path: YPath, map: YNode.Map, fields: List<FieldDef>) -> Unit = { _, _, _ -> },
        onField: (path: YPath, field: FieldDef) -> Unit = { _, _ -> },
    ) {
        fun walk(def: FieldDef, node: YNode, path: YPath) {
            val eff = s.resolve(def) ?: def
            when (eff.type) {
                FieldType.OBJECT -> {
                    val m = node as? YNode.Map ?: return
                    val fs = s.effectiveFields(eff.fields, m).fields
                    onObject(path, m, fs)
                    fs.forEach { f ->
                        val k = f.key ?: return@forEach
                        val child = m.entries[k] ?: return@forEach
                        onField(path + k, f)
                        walk(f, child, path + k)
                    }
                }
                FieldType.ARRAY -> {
                    val seq = node as? YNode.Seq ?: return
                    val item = eff.item ?: return
                    seq.items.forEachIndexed { i, child -> walk(item, child, path + i) }
                }
                FieldType.MAP -> {
                    val m = node as? YNode.Map ?: return
                    val value = eff.value ?: return
                    m.entries.forEach { (k, child) -> walk(value, child, path + k) }
                }
                else -> Unit
            }
        }
        walk(s.root, root, emptyList())
    }

    /** 条件に合うフィールドに対応するデータキーの座標（値がある場所だけ）。 */
    fun fieldHits(s: SchemaTree, root: YNode, predicate: (FieldDef) -> Boolean): List<YPath> {
        val hits = mutableListOf<YPath>()
        walkDocument(s, root, onField = { path, f -> if (predicate(f)) hits += path })
        return hits
    }

    /** target を実効フィールドに含む親 object の（パス, マップ）一覧（値が無い場所への充填先の列挙）。 */
    fun parentObjects(s: SchemaTree, root: YNode, target: FieldDef): List<Pair<YPath, YNode.Map>> {
        val out = mutableListOf<Pair<YPath, YNode.Map>>()
        walkDocument(s, root, onObject = { path, m, fs -> if (fs.any { it === target }) out += path to m })
        return out
    }

    // ================================================================ Query ②: 対象フィールドへ到達する文書

    /** 条件に合うフィールドへ到達できる文書スキーマの集合（`$ref` 共有の影響範囲）。 */
    class Reach(val schemas: List<SchemaSemantics>) {
        private val byId = schemas.associateBy { it.id }

        /** 影響範囲のスキーマが割り当たっている文書（入力順）と、その文書スキーマ。 */
        fun documents(documents: List<RefactorDocument>): List<Pair<RefactorDocument, SchemaSemantics>> =
            documents.mapNotNull { doc -> doc.schemaId?.let(byId::get)?.let { doc to it } }
    }

    fun reach(schemas: Map<String, SchemaSemantics>, predicate: (FieldDef) -> Boolean): Reach =
        Reach(schemas.values.filter { it.kind == SchemaKind.DOCUMENT && reaches(it, predicate) })

    /** スキーマの走査で条件に合うフィールドへ到達できるか（`$ref` の循環は定義単位で打ち切る）。 */
    private fun reaches(s: SchemaSemantics, predicate: (FieldDef) -> Boolean): Boolean {
        var found = false
        val seen = mutableSetOf<String>()
        fun walk(f: FieldDef) {
            if (found) return
            if (predicate(f)) {
                found = true
                return
            }
            val located = s.tree.definitionTarget(f)
            if (located != null && !seen.add(located.first.toString())) return
            val r = s.tree.resolve(f) ?: f
            r.fields.forEach(::walk)
            r.item?.let(::walk)
            r.value?.let(::walk)
            r.cases.values.flatten().forEach(::walk)
        }
        walk(s.tree.root)
        return found
    }

    // ================================================================ Query ③: スキーマ文書の全エントリ

    /** YNode ツリーの全マップエントリを訪問する（親パス・キー・値。宣言順の深さ優先）。 */
    fun walkEntries(root: YNode, visit: (parentPath: YPath, key: String, value: YNode) -> Unit) {
        fun walk(node: YNode, path: YPath) {
            when (node) {
                is YNode.Map -> node.entries.forEach { (k, v) ->
                    visit(path, k, v)
                    walk(v, path + k)
                }
                is YNode.Seq -> node.items.forEachIndexed { i, v -> walk(v, path + i) }
                else -> Unit
            }
        }
        walk(root, emptyList())
    }

    /**
     * 書き換え可能な全スキーマ文書（source を持ち、ツリーが読めるもの）の全マップエントリを訪問する。
     * 宣言の横断書換（定義名・関係名・スキーマIDの改名）と参照の数え上げが共有する列挙器。
     */
    fun forEachSchemaEntry(
        schemas: Map<String, SchemaSemantics>,
        tree: (DocumentId) -> YNode?,
        only: (SchemaSemantics) -> Boolean = { true },
        visit: (schema: SchemaSemantics, source: DocumentId, parentPath: YPath, key: String, value: YNode) -> Unit,
    ) {
        for (s in schemas.values) {
            if (!only(s)) continue
            val source = s.source ?: continue
            val sTree = tree(source) ?: continue
            walkEntries(sTree) { path, key, value -> visit(s, source, path, key, value) }
        }
    }

    // ================================================================ 基本戦略と集積

    /** 文書側の書換の集計（サマリ用）: 操作が1件以上あった文書数と、操作の総数。 */
    class Tally(val files: Int, val edits: Int)

    /**
     * 基本の適用戦略（§13）: 対象文書を順に評価し、rule が返した操作列を文書ごとに積む。
     * rule は「場所の列挙 → 絞り込み → 操作の生成」を1文書分行う。中止条件（衝突など）に当たった文書は
     * 空リストを返し、理由は呼び出し側が集めて全文書の評価後にまとめて中止する（部分計画を返さない）。
     */
    fun rewriteDocuments(
        targets: List<Pair<RefactorDocument, SchemaSemantics>>,
        builder: PlanOps,
        rule: (document: RefactorDocument, schema: SchemaSemantics) -> List<YamlOperation>,
    ): Tally {
        var files = 0
        var edits = 0
        for ((doc, docSchema) in targets) {
            val ops = rule(doc, docSchema)
            if (ops.isEmpty()) continue
            files++
            edits += ops.size
            builder.addAll(doc.id, ops)
        }
        return Tally(files, edits)
    }

    /**
     * 文書単位の操作を集める（結果は文書ID順）。removesLast は文書内で Remove を末尾へ寄せる安定整列
     * （エンティティ操作の「Set 先／Remove 後」規則。値の書換が削除でずれた座標を見ないため）。
     */
    class PlanOps(private val removesLast: Boolean = false) {
        private val byDoc = LinkedHashMap<DocumentId, MutableList<YamlOperation>>()

        fun add(document: DocumentId, op: YamlOperation) {
            byDoc.getOrPut(document) { mutableListOf() } += op
        }

        fun addAll(document: DocumentId, ops: List<YamlOperation>) {
            if (ops.isNotEmpty()) byDoc.getOrPut(document) { mutableListOf() } += ops
        }

        fun toChanges(): List<PlannedDocumentChange> =
            byDoc.entries.sortedBy { it.key.value }.map { (document, ops) ->
                val ordered =
                    if (removesLast) ops.filter { it !is YamlOperation.Remove } + ops.filterIsInstance<YamlOperation.Remove>()
                    else ops
                PlannedDocumentChange(document, ordered)
            }
    }

    /** 同一文書内の削除・改名を深い方／後方から行うためのパス降順（数値セグメントは数値比較）。 */
    val pathDescending = Comparator<YPath> { x, y ->
        for (i in 0 until minOf(x.size, y.size)) {
            val sx = x[i]
            val sy = y[i]
            val c = if (sx is Int && sy is Int) sx.compareTo(sy) else sx.toString().compareTo(sy.toString())
            if (c != 0) return@Comparator -c
        }
        -(x.size.compareTo(y.size))
    }

    /** 中止理由などの列挙（先頭 limit 件＋残数）。 */
    fun listTop(items: List<String>, limit: Int = 5): String =
        items.take(limit).joinToString("、") + (if (items.size > limit) " 他${items.size - limit}件" else "")
}
