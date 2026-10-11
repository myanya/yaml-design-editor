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
import dev.toros.yde.semantic.schema.EntityDecl
import dev.toros.yde.semantic.schema.entityFor
import dev.toros.yde.semantic.schema.ownedFieldKeys
import dev.toros.yde.semantic.CheckOutcome
import dev.toros.yde.semantic.CheckReason
import dev.toros.yde.semantic.ScannedId
import dev.toros.yde.semantic.SemanticCheckSide
import dev.toros.yde.semantic.SemanticElementChecks
import dev.toros.yde.semantic.schema.RuleOrigin
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.semantic.schema.FilePath
import dev.toros.yde.workspace.WorkspaceSchema
import dev.toros.yde.workspace.layout.FilePolicy
import dev.toros.yde.workspace.layout.FilePolicyLevel
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
    /** file 型の値の許可（`.yde/workspace.yaml` の `files:`）。 */
    private val files: FilePolicy = FilePolicy.DEFAULT,
    /** ファイルがあるか（ワークスペース相対か絶対のパスの文字）。null なら存在は見ない。 */
    private val fileExists: ((String) -> Boolean)? = null,
) {
    /**
     * file 型の値（ファイルの場所）の検査。書き方は FilePath（接頭辞なし＝ルートから・`./` か `../`＝文書から・絶対）。
     * - 場所として読めない → 違反
     * - 拡張子が項目の `extensions` に無い → 違反
     * - ワークスペースの外（絶対パス・`..` でルートの外へ出る）→ `files.outside`（許す場所 `allowedRoots` に入れば外ではない）
     * - その場所にファイルが無い → `files.missing`（fileExists が無ければ見ない）
     * 形式（pattern）と必須は構造の検証が見る。
     */
    fun fileIssues(document: DocumentId, schema: WorkspaceSchema?, root: YNode, localIds: List<ScannedId>): List<CheckIssue> {
        if (schema == null) return emptyList()
        val tree = schema.tree
        val issues = mutableListOf<CheckIssue>()
        fun severity(level: FilePolicyLevel): Severity? = when (level) {
            FilePolicyLevel.ALLOW -> null
            FilePolicyLevel.WARNING -> Severity.WARNING
            FilePolicyLevel.ERROR -> Severity.ERROR
        }
        fun report(severity: Severity, rule: String, message: String, path: YPath) {
            issues += CheckIssue(severity, message, path, source = CodedChecks.source(CodedChecks.VALUE, rule, IssueDelivery.VALUE, localIds, path))
        }
        fun check(eff: FieldDef, text: String, path: YPath, label: String) {
            if (!FilePath.isWellFormed(text)) {
                report(Severity.ERROR, "file-form", "「$label」のファイルの場所が読めません（現在値: $text）", path)
                return
            }
            if (eff.extensions.isNotEmpty() && FilePath.extensionOf(text) !in eff.extensions) {
                report(Severity.ERROR, "file-extension",
                    "「$label」は ${eff.extensions.joinToString(" / ")} のファイルを指定します（現在値: $text）", path)
            }
            val location = FilePath.toWorkspaceRelative(text, document) ?: run {
                val outside = FilePath.normalize(text)
                if (!FilePath.underAny(outside, files.allowedRoots)) {
                    severity(files.outside)?.let { sev ->
                        report(sev, "file-outside", "「$label」はワークスペースの外のファイルです（現在値: $text）", path)
                    }
                    if (files.outside == FilePolicyLevel.ERROR) return
                }
                outside
            }
            val exists = fileExists ?: return
            if (!exists(location)) {
                severity(files.missing)?.let { sev ->
                    report(sev, "file-missing", "「$label」のファイルがありません（現在値: $text）", path)
                }
            }
        }
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
                FieldType.FILE -> {
                    val text = scalarText(node)?.trim()?.takeIf { it.isNotEmpty() } ?: return
                    check(eff, text, path, label)
                }
                else -> {}
            }
        }
        walk(tree.root, root, emptyList(), tree.root.displayLabel)
        return issues
    }

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

    /**
     * 型の検査（オブジェクト型の要素ごとの検査。P33 第三弾）: 評価は semantic.SemanticElementChecks。ここは文面と配り方——
     * 違反は要素の場所への指摘（出どころの持ち主は検査を書いたスキーマ・at は書かれた場所）、保留はその文書で集める。
     */
    fun elementChecks(document: DocumentId, schema: WorkspaceSchema?, root: YNode, localIds: List<ScannedId>): Pair<List<CheckIssue>, List<WithheldCheck>> {
        if (schema == null) return emptyList<CheckIssue>() to emptyList()
        val issues = mutableListOf<CheckIssue>()
        val withheld = mutableListOf<WithheldCheck>()
        fun side(s: SemanticCheckSide): String = when {
            s.path != null -> "${s.path!!.pretty()}${s.note?.let { " の$it" } ?: ""}: ${s.text}"
            s.raw != null && s.raw != s.text -> "「${s.text}」（= ${s.raw}）"
            else -> "「${s.text}」"
        }
        for (r in SemanticElementChecks.run(schema.tree, root, document, rel)) {
            val rule = r.rule
            val origin = RuleOrigin(schema.id, rule.origin, 0)
            val subject = r.elementId ?: CodedChecks.subjectOf(localIds, r.path)?.id
            val where = r.elementId?.let { "「$it」" } ?: "（${r.path.pretty().ifBlank { "文書" }}）"
            when (r.outcome) {
                CheckOutcome.WITHHELD -> withheld += WithheldCheck(origin, rule.id, rule.description, null, subject ?: r.path.pretty(), r.path, r.reason ?: CheckReason.NO_VALUE)
                CheckOutcome.VIOLATED -> {
                    val term = r.term
                    val left = r.left
                    val right = r.right
                    val evidence = if (term != null && left != null && right != null) {
                        "。反例: ${side(left)} ${term.violationSymbol} ${side(right)}" +
                            (if (rule.check.any { it.terms.size > 1 }) "（「または」のどの条件も満たさない）" else "")
                    } else ""
                    val custom = rule.message?.let { template ->
                        template.replace("{left}", r.left?.text.orEmpty()).replace("{right}", r.right?.text.orEmpty())
                            .replace("{left.raw}", r.left?.raw ?: r.left?.text.orEmpty()).replace("{right.raw}", r.right?.raw ?: r.right?.text.orEmpty())
                            .replace("{id}", r.elementId.orEmpty()).replace("{path}", r.path.pretty())
                    }
                    val text = custom
                        ?: if (rule.absent) "${r.typeLabel} の要素${where}は条件に合います（expect: absent）"
                        else "${r.typeLabel} の要素${where}が検査を満たしません$evidence"
                    issues += CheckIssue(
                        rule.level.toSeverity(),
                        text + " — ${rule.displayName}",
                        r.path,
                        source = IssueSource(origin, rule.id, CodedChecks.ELEMENT, subject, IssueDelivery.VALUE, emptyList(), within = CodedChecks.keyTrail(r.path)),
                    )
                }
                else -> Unit
            }
        }
        return issues to withheld
    }

    /** 出現の要素の型の宣言（オブジェクト型。P33 第三弾）があればそれ、無ければスキーマの entity 宣言（ID の形）。 */
    private fun declOf(schema: WorkspaceSchema, root: YNode, entry: ScannedId): EntityDecl? =
        schema.tree.fieldAt(root, entry.path)?.entity ?: entityFor(schema.entities, entry.id)

    fun entityIssues(document: DocumentId, schema: WorkspaceSchema?, root: YNode, localIds: List<ScannedId>): List<CheckIssue> {
        if (schema == null || localIds.none { it.entityType != null } && schema.entities.isEmpty()) return emptyList()
        val issues = mutableListOf<CheckIssue>()
        fun coded(kind: String, entry: ScannedId) =
            CodedChecks.source(CodedChecks.ENTITY, kind, IssueDelivery.REPORT, localIds, entry.path)
        for (entry in localIds) {
            val decl = declOf(schema, root, entry)
            if (decl == null) {
                // 要素の型の宣言が無く、スキーマに ID の形の宣言も無い要素は、型が無いだけ（指摘しない）
                if (schema.entities.isEmpty()) continue
                // ID の決まり: idPattern はそのまま・idType は「型 名前」（文面の見出しは idPattern だけなら今までどおり）
                val rules = schema.entities.mapNotNull { it.idRuleText }
                val heading = if (schema.entities.any { it.idType != null }) "ID の決まり" else "idPattern"
                issues += CheckIssue(
                    Severity.WARNING,
                    "ID「${entry.id}」はエンティティ型の $heading（${rules.joinToString(", ")}）のいずれにも一致しません",
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
                val otherRoot = (if (other.document == document) root else tree(other.document)) ?: continue
                val otherDecl = declOf(otherSchema, otherRoot, other) ?: continue
                if (decl.owns == null && otherDecl.owns == null) continue
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
