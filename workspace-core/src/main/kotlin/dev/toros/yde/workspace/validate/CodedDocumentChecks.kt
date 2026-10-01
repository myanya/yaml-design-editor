package dev.toros.yde.workspace.validate

import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.scalarText
import dev.toros.yde.semantic.schema.ExpressionFields
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.ConstraintRule
import dev.toros.yde.semantic.schema.Projection
import dev.toros.yde.semantic.schema.ValueExpr
import dev.toros.yde.semantic.schema.entityFor
import dev.toros.yde.semantic.schema.ownedFieldKeys
import dev.toros.yde.semantic.ScannedId
import dev.toros.yde.workspace.WorkspaceSchema
import dev.toros.yde.yaml.validate.Severity

/**
 * コードで実装された検査のうち、ワークスペースの他の文書も見るもの（P27 段D の D-a で、root の Validator から移した。意図的差分 D-33）。
 * 定義体の検査と同じ入口（WorkspaceChecks.deliver）が、この文書の分を返す——アプリとカーネル側の CLI が同じ結果を返す。
 * - エンティティ型の宣言に関わる点検（R20-01・R23-01。architecture/graph-query.md「エンティティ型と関係レジストリ」・architecture/graph-query.md「エンティティページ」）: ID のパターンからの逸脱と、重複所有
 * - テンプレートの健全性（R16-03a / UX-12）
 * 指摘の出どころは CodedChecks が作る（検査の出どころの owner が `(code)`・at が `entity` / `template`）。
 * tree は、この検証で読む文書の中身（検証中の文書は、渡された root をそのまま使う）。
 */
internal class CodedDocumentChecks(
    /** ワークスペースの全スキーマ（空なら、この文書のスキーマだけで判定する）。 */
    private val schemas: List<WorkspaceSchema>,
    private val ids: Map<String, List<ScannedId>>,
    /** 文書のワークスペース相対パス。 */
    private val rel: (DocumentId) -> String,
    private val tree: (DocumentId) -> YNode?,
) {
    /**
     * エンティティ整合 lint のうち、スキーマの構造に依存するもの: idPattern 逸脱（このスキーマのエンティティ型の宣言の一式）と
     * 重複所有（所有するフィールド）。entity 宣言のあるスキーマの ID だけを判定する（宣言のないスキーマは挙動不変＝非破壊）。
     * 浮遊詳細化・定義競合・型競合は「書かれた場所の数」の検査として、組込み定義体（builtin-definitions/entities.yaml）にある。
     */
    /**
     * 式を受け取る項目（expression: true。P31）の値が、式として読めるか。どの項目かはスキーマの印だけが知り、読み取りは
     * ExpressionFields（スキーマ文書・ビュー定義と同じ1実装）、文面は ExpressionMessages。値の式は書いたその場で点検される。
     */
    fun expressionIssues(document: DocumentId, schema: WorkspaceSchema?, root: YNode, localIds: List<ScannedId>): List<CheckIssue> {
        if (schema == null) return emptyList()
        return ExpressionFields.readAll(root, schema.tree).errors.map { (path, message) ->
            CheckIssue(
                Severity.ERROR, message, path,
                source = CodedChecks.source(CodedChecks.EXPRESSION, "unreadable", IssueDelivery.REPORT, localIds, path),
            )
        }
    }

    /**
     * 意味の基底型（date / time / datetime。ValueSemantics）の値が、その値として存在するか（例 2026-02-30 は形式には合うが日付として
     * 読めない）。形式は構造の検証（yaml-core）が見るので、ここは正準の形の値だけを読む（1 つの誤りに 2 つの指摘を出さない）。
     */
    fun valueIssues(document: DocumentId, schema: WorkspaceSchema?, root: YNode, localIds: List<ScannedId>): List<CheckIssue> {
        if (schema == null) return emptyList()
        val tree = schema.tree
        val issues = mutableListOf<CheckIssue>()
        fun walk(def: FieldDef, node: YNode, path: YPath, label: String) {
            val eff = tree.resolve(def) ?: def
            when (eff.type) {
                FieldType.OBJECT -> {
                    val m = node as? YNode.Map ?: return
                    tree.effectiveFields(eff.fields, m).fields.forEach { f ->
                        val k = f.key ?: return@forEach
                        m.entries[k]?.let { walk(f, it, path + k, (tree.resolve(f) ?: f).displayLabel) }
                    }
                }
                FieldType.ARRAY -> {
                    val seq = node as? YNode.Seq ?: return
                    val item = eff.item ?: return
                    seq.items.forEachIndexed { i, child -> walk(item, child, path + i, "$label[$i]") }
                }
                FieldType.MAP -> {
                    val m = node as? YNode.Map ?: return
                    val value = eff.value ?: return
                    m.entries.forEach { (k, child) -> walk(value, child, path + k, "$label.$k") }
                }
                else -> {
                    val value = eff.type.value ?: return
                    val text = scalarText(node) ?: return
                    if (value.isCanonical(text) && value.parse(text) == null) {
                        issues += CheckIssue(
                            Severity.ERROR,
                            "「$label」は${value.label}として読めません（現在値: $text）",
                            path,
                            source = CodedChecks.source(CodedChecks.VALUE, value.id, IssueDelivery.VALUE, localIds, path),
                        )
                    }
                }
            }
        }
        walk(tree.root, root, emptyList(), tree.root.displayLabel)
        return issues
    }

    fun entityIssues(document: DocumentId, schema: WorkspaceSchema?, root: YNode, localIds: List<ScannedId>): List<CheckIssue> {
        if (schema == null || schema.entities.isEmpty()) return emptyList()
        val issues = mutableListOf<CheckIssue>()
        fun coded(kind: String, entry: ScannedId) =
            CodedChecks.source(CodedChecks.ENTITY, kind, IssueDelivery.REPORT, localIds, entry.path)
        for (entry in localIds) {
            val decl = entityFor(schema.entities, entry.id)
            if (decl == null) {
                val patterns = schema.entities.mapNotNull { it.idPattern }
                issues += CheckIssue(
                    Severity.WARNING,
                    "ID「${entry.id}」はエンティティ型の idPattern（${patterns.joinToString(", ")}）のいずれにも一致しません",
                    entry.path,
                    source = coded("id-pattern", entry),
                )
                continue
            }
            // 重複所有（R23-01。architecture/graph-query.md「エンティティページ」）: 同一エンティティの2出現が同じフィールドを
            // 所有していたら WARNING（「重複記載しない」運用規約の機械化）。誤検知回避のため、
            // 双方の出現に entity 宣言があり、少なくとも一方が明示的な owns を持つ組だけを判定する
            val myKeys = ownedFieldKeys(schema.tree, root, entry.path, decl)
            if (myKeys.isEmpty()) continue
            // 他の文書の出現（索引）と、この文書のいまの内容の中の他の出現
            val others = ids[entry.id].orEmpty().filter { it.document != document } +
                localIds.filter { it.id == entry.id && it.path != entry.path }
            for (other in others) {
                val otherSchema = schemas.ifEmpty { listOf(schema) }.firstOrNull { it.id == other.schemaId } ?: continue
                val otherDecl = entityFor(otherSchema.entities, entry.id) ?: continue
                if (decl.owns == null && otherDecl.owns == null) continue
                val otherRoot = (if (other.document == document) root else tree(other.document)) ?: continue
                val overlap = myKeys.filter { it in ownedFieldKeys(otherSchema.tree, otherRoot, other.path, otherDecl) }
                if (overlap.isNotEmpty()) {
                    issues += CheckIssue(
                        Severity.WARNING,
                        "エンティティ「${entry.id}」のフィールド（${overlap.joinToString(", ")}）が" +
                            " ${rel(other.document)} の出現でも所有されています（重複所有。片側の owns を絞ってください）",
                        entry.path,
                        source = coded("duplicate-ownership", entry),
                    )
                }
            }
        }
        return issues
    }

    // 検査がテンプレートとして展開する値の読み出し（検査ごと。ワークスペースの全スキーマの検査から、1回だけ集める）
    private val templateReads: List<Pair<ConstraintRule, ValueExpr.Get>> by lazy {
        schemas.flatMap { it.constraints }.flatMap { rule -> templateReadsOf(rule) }
    }

    private fun templateReadsOf(rule: ConstraintRule): List<Pair<ConstraintRule, ValueExpr.Get>> =
        rule.check.flatMap { c -> c.operands.flatMap { it.templateReads() } }.map { read -> rule to read }

    /**
     * テンプレート健全性（R16-03a / UX-12）: いずれかのスキーマの検査が、この文書に書かれた値を
     * テンプレートとして展開する（値の式 format。整合検証の expected など）とき、テンプレートのプレースホルダが
     * 同じ要素の値（この文書の要素と、同じ ID が書かれた他の文書の要素）から解決できるかを、書いたその場で点検する。
     * 参照元の要素（lookup の起点）から来る値は、この文書だけからは分からないため対象にしない（architecture/transform.md「計算列と決定論的射影」）
     */
    fun templateIssues(document: DocumentId, schema: WorkspaceSchema?, root: YNode, localIds: List<ScannedId>): List<CheckIssue> {
        val relSelf = rel(document)
        val all = if (schemas.isEmpty()) schema?.constraints.orEmpty().flatMap(::templateReadsOf) else templateReads
        val reads = all.filter { (_, read) -> read.inDoc?.matches(schema?.id, relSelf) == true }.distinct()
        if (reads.isEmpty()) return emptyList()
        val issues = mutableListOf<CheckIssue>()
        for ((rule, read) in reads) {
            for (entry in localIds) {
                val element = getAt(root, entry.path) ?: continue
                val raw = scalarText(getAt(element, read.path)) ?: continue
                if ('{' !in raw) continue
                val others = ids[entry.id].orEmpty()
                    .filter { !(it.document == document && it.path == entry.path) }
                    .mapNotNull { e -> (if (e.document == document) root else tree(e.document))?.let { getAt(it, e.path) } }
                val eval = Projection.evaluate(raw, listOf(element) + others)
                eval.resolutions.filter { it.value == null }.forEach { r ->
                    issues += CheckIssue(
                        Severity.WARNING,
                        "プレースホルダ {${r.placeholder}} を導出の入力（値型定義等）から解決できません" +
                            (rule.description?.let { " — $it" } ?: ""),
                        entry.path + read.path,
                        source = CodedChecks.source(
                            CodedChecks.TEMPLATE, "unresolved-placeholder", IssueDelivery.VALUE, localIds, entry.path + read.path,
                        ),
                    )
                }
            }
        }
        return issues
    }
}
