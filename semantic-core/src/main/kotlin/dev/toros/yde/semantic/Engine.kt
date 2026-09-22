package dev.toros.yde.semantic

import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.semantic.schema.EnforcementDef
import dev.toros.yde.semantic.schema.EntityRole
import dev.toros.yde.semantic.schema.EntityTypeDef
import dev.toros.yde.semantic.schema.RefTarget
import dev.toros.yde.semantic.schema.RelationDef
import dev.toros.yde.semantic.schema.idGlobToRegex
import dev.toros.yde.yaml.validate.Severity

/**
 * ワークスペース単位の意味宣言（3層コア移行 Phase 5・SM-002。計画書§4.2）。
 * `kind: relations` 文書などから上位層（workspace-core）が集約して渡す。
 * relations / enforcement はグラフ整合検証（SemanticGraphIntegrity。6A。非循環と、宣言のない関係名）が使う。
 */
data class SemanticDeclarations(
    val relations: Map<String, RelationDef> = emptyMap(),
    val entityTypes: List<EntityTypeDef> = emptyList(),
    val enforcement: EnforcementDef = EnforcementDef(),
)

/** 意味解析の入力（文書集合＋宣言＋入力バージョン。計画書§4.2）。 */
data class SemanticInput(
    /** 入力の版（単一連番。ADR-0007）。semantic-core は転記するだけで採番しない。 */
    val workspaceVersion: Long,
    val documents: List<SemanticDocument>,
    val declarations: SemanticDeclarations = SemanticDeclarations(),
)

/**
 * コードで実装された意味検証の指摘の種類（P27 段B の残り）: どの検査の・どの種類の指摘かを、文面とは別にデータとして持つ。
 * at は検査の名前、code は指摘の種類——指摘の出どころ（workspace-core の validate.IssueSource）の rule.at と kind に写す。
 */
enum class SemanticIssueKind(val at: String, val code: String) {
    /** 同じ文書の中で ID が重複している。 */
    DUPLICATE_ID("id", "duplicate"),

    /** 参照の相手が見つからない。 */
    UNRESOLVED_REFERENCE("reference", "unresolved"),

    /** 参照の相手はあるが、パターンまたはスコープの対象外。 */
    REFERENCE_OUT_OF_SCOPE("reference", "out-of-scope"),

    /** 文書参照の相手が見つからない。 */
    UNRESOLVED_DOCUMENT_REFERENCE("reference", "unresolved-document"),

    /** 台帳外の文書のため、参照を解決しない（案内）。 */
    REFERENCE_OUT_OF_LEDGER("reference", "out-of-ledger"),

    /** 関係レジストリに宣言のない関係名。 */
    UNDECLARED_RELATION("relation-registry", "undeclared-relation"),
}

/** 意味検証の指摘（構造検証の StructuralIssue と別型。計画書 Phase 3 作業順9）。 */
data class SemanticIssue(
    val severity: Severity,
    val message: String,
    val document: DocumentId,
    val path: YPath,
    val kind: SemanticIssueKind,
    /** 参照の指摘では、参照の相手の ID（指摘の出どころの「根拠に関わった要素」になる）。 */
    val targetId: String? = null,
)

/** ID用 glob との一致（走査カーネル側の述語。ルート index.idMatchesPattern と同じ規則）。 */
fun idMatchesPattern(id: String, pattern: String): Boolean =
    runCatching { idGlobToRegex(pattern).matches(id) }.getOrDefault(false)

/** 参照の制約（idPatterns / scope）がこの ID 出現を許すか（ルート index の isAllowedBy と同じ規則）。 */
fun ScannedId.isAllowedBy(ref: ScannedRef): Boolean =
    (ref.idPatterns.isEmpty() || ref.idPatterns.any { idMatchesPattern(id, it) }) &&
        (ref.scope.schemaIds.isEmpty() || schemaId in ref.scope.schemaIds) &&
        (ref.scope.documentIds.isEmpty() || documentId in ref.scope.documentIds)

/**
 * 増分更新の差分入力（計画書 Phase 11 作業2: 文書1件変更時の semantic 差分入力）。
 * changed は再走査する文書（新規・変更）、removed は取り除く文書。
 * 同じ文書が changed と removed の両方にあってもよい（取り除いてから再走査する）。
 */
data class SemanticDelta(
    /** 更新後の版（単一連番。ADR-0007）。semantic-core は転記するだけで採番しない。 */
    val workspaceVersion: Long,
    val changed: List<SemanticDocument>,
    val removed: Set<DocumentId> = emptySet(),
    val declarations: SemanticDeclarations = SemanticDeclarations(),
)

/**
 * 意味解析の結果snapshot（計画書§4.2）。YNode 参照を保持しない（ADR-0003 規律4）。
 * sourceVersion は入力の転記で、鮮度判定は上位層が単一連番の比較で行う（ADR-0007）。
 */
class SemanticSnapshot(
    val sourceVersion: Long,
    /** 論理 id → 出現一覧（文書ID順で安定）。 */
    val ids: Map<String, List<ScannedId>>,
    /** 全参照（文書ID順・文書内は出現順）。 */
    val refs: List<ScannedRef>,
    /** 文書参照（targetKind = DOCUMENT）の解決に使う文書の論理ID集合。 */
    val documentIds: Set<String>,
    /** 集約・重複・参照解決の指摘（文書内ID重複・未解決参照・スコープ外参照）。 */
    val issues: List<SemanticIssue>,
    /** 走査結果から導出したエンティティグラフ（R20-04 のカーネル版。SM-003）。 */
    val graph: SemanticEntityGraph,
    /**
     * 文書単位の走査結果（増分更新のキャッシュ。Phase 11）。文書ID順。
     * YNode 参照は含まない（ScannedId / ScannedRef の値オブジェクトのみ）。
     */
    val scans: List<DocumentScan> = emptyList(),
) {
    fun occurrences(id: String): List<ScannedId> = ids[id].orEmpty()

    /** ID の代表出現。定義出現（role = DEFINING。R20-01）があればそれを優先し、なければ先頭。 */
    fun resolve(id: String): ScannedId? =
        occurrences(id).let { occs -> occs.firstOrNull { it.role == EntityRole.DEFINING } ?: occs.firstOrNull() }

    /** 参照の解決先（idPatterns / scope の制約を満たす出現のみ）。 */
    fun resolve(ref: ScannedRef): List<ScannedId> = occurrences(ref.targetId).filter { it.isAllowedBy(ref) }

    /** 指定 ID 群への被参照（バックリンク）。全参照の並び順を保つ。 */
    fun backlinksTo(idSet: Set<String>): List<ScannedRef> =
        refs.filter { it.targetKind == RefTarget.ENTITY && it.targetId in idSet }
}

/**
 * 意味解析エンジン（計画書§4.2）。ファイルI/Oを行わず、与えられた文書集合から構築する。
 * - `build` = 全再構築。**正の基準（oracle）**であり、増分更新の結果はこれと常に一致する（Phase 11）
 * - `update` = 増分更新。変更文書だけ再走査し、集約（参照解決・グラフ・整合検証）は build と
 *   同じ経路（aggregate）で再導出する——走査コストだけが変更文書数に比例し、正しさは共有経路が保証する
 * 台帳（ledger）による対象文書の絞り込みは上位層の責務で、ここへは対象文書だけが渡される前提。
 */
object SemanticEngine {

    /** 全再構築（oracle）。 */
    fun build(input: SemanticInput): SemanticSnapshot {
        // 文書ID順で安定化（ルート IdIndex.derive のパス順ソートと同じ考え方）
        val scans = input.documents.sortedBy { it.id.value }.map(::scanOne)
        return aggregate(input.workspaceVersion, scans, input.declarations)
    }

    /**
     * 増分更新: previous の文書別走査キャッシュ（snapshot.scans）から removed / changed 分を除き、
     * changed だけ再走査して集約し直す。結果は同じ文書集合の build と一致する（PF-001 の oracle テストで固定）。
     */
    fun update(previous: SemanticSnapshot, delta: SemanticDelta): SemanticSnapshot {
        val dropped = delta.removed + delta.changed.map { it.id }
        val kept = previous.scans.filterNot { it.document in dropped }
        val rescanned = delta.changed.map(::scanOne)
        return aggregate(
            delta.workspaceVersion,
            (kept + rescanned).sortedBy { it.document.value },
            delta.declarations,
        )
    }

    /** 1文書の走査（build / update で共有）。 */
    private fun scanOne(d: SemanticDocument): DocumentScan {
        val (docIds, docRefs) = SemanticScanner.scan(d)
        return DocumentScan(d.id, d.documentId, docIds, docRefs)
    }

    /** 文書内の ID 重複（ERROR）。文書間の同一 ID は「同一エンティティの複数出現」で合法。 */
    fun duplicateIdIssues(scan: DocumentScan): List<SemanticIssue> =
        scan.ids.groupBy { it.id }.filterValues { it.size > 1 }.flatMap { (id, list) ->
            list.map { e -> SemanticIssue(Severity.ERROR, "ID「$id」がこの文書内で重複しています", scan.document, e.path, SemanticIssueKind.DUPLICATE_ID) }
        }

    /**
     * 参照解決の判定（B-3・RB-06 で aggregate から抽出＝root Validator と単一所有）。
     * 出現・文書IDの供給元（全体集約／索引＋ローカルの二層 §4.5）は上位層が注入する。
     * - ownDocumentId: 検証対象文書自身の論理ID（未保存で索引に無い場合の自己文書参照の解決。null = 供給元に含まれる）
     * - indexed = false（台帳外。R13-03）: 未解決を INFO へ降格する（エディタ層。全体集約では常に true）
     */
    fun referenceIssues(
        refs: List<ScannedRef>,
        ownDocumentId: String?,
        occurrences: (String) -> List<ScannedId>,
        hasDocumentId: (String) -> Boolean,
        indexed: Boolean = true,
    ): List<SemanticIssue> {
        val issues = mutableListOf<SemanticIssue>()
        for (r in refs) {
            if (r.targetKind == RefTarget.DOCUMENT) {
                if (r.targetId == ownDocumentId || hasDocumentId(r.targetId)) continue
                issues += if (!indexed) {
                    SemanticIssue(
                        Severity.INFO, "文書参照「${r.targetId}」は台帳外の文書のため解決されません", r.document, r.path,
                        SemanticIssueKind.REFERENCE_OUT_OF_LEDGER, r.targetId,
                    )
                } else {
                    SemanticIssue(
                        Severity.ERROR,
                        "文書参照「${r.targetId}」を解決できません（_document.id または相対パス）",
                        r.document, r.path,
                        SemanticIssueKind.UNRESOLVED_DOCUMENT_REFERENCE, r.targetId,
                    )
                }
                continue
            }
            val occs = occurrences(r.targetId)
            if (occs.none { it.isAllowedBy(r) }) {
                issues += when {
                    !indexed ->
                        SemanticIssue(
                            Severity.INFO, "参照「${r.targetId}」は台帳外の文書のため解決されません", r.document, r.path,
                            SemanticIssueKind.REFERENCE_OUT_OF_LEDGER, r.targetId,
                        )
                    occs.isNotEmpty() ->
                        SemanticIssue(
                            Severity.ERROR, "参照「${r.targetId}」はパターンまたはスコープの対象外です", r.document, r.path,
                            SemanticIssueKind.REFERENCE_OUT_OF_SCOPE, r.targetId,
                        )
                    else ->
                        SemanticIssue(
                            Severity.ERROR, "参照「${r.targetId}」（${r.path.pretty()}）を解決できません", r.document, r.path,
                            SemanticIssueKind.UNRESOLVED_REFERENCE, r.targetId,
                        )
                }
            }
        }
        return issues
    }

    /** 走査結果集合からの集約（重複検出・参照解決・グラフ・整合検証）。build / update で共有する正の経路。 */
    private fun aggregate(
        version: Long,
        scans: List<DocumentScan>,
        declarations: SemanticDeclarations,
    ): SemanticSnapshot {
        val ids = linkedMapOf<String, MutableList<ScannedId>>()
        val refs = mutableListOf<ScannedRef>()
        val issues = mutableListOf<SemanticIssue>()
        val documentIds = mutableSetOf<String>()

        for (scan in scans) {
            documentIds += scan.documentId
            for (e in scan.ids) ids.getOrPut(e.id) { mutableListOf() }.add(e)
            refs += scan.refs
            issues += duplicateIdIssues(scan)
        }

        // 参照解決（未解決＝ERROR・存在するがパターン/スコープ対象外＝ERROR・文書参照は別名前空間）
        issues += referenceIssues(
            refs = refs,
            ownDocumentId = null,
            occurrences = { ids[it].orEmpty() },
            hasDocumentId = { it in documentIds },
        )

        val graph = SemanticEntityGraph.build(scans, ids)
        // グラフ整合検証（R21-01。6A）: 関係レジストリの宣言があるときだけ判定する
        issues += SemanticGraphIntegrity.issues(refs, graph, declarations)

        return SemanticSnapshot(
            sourceVersion = version,
            ids = ids,
            refs = refs,
            documentIds = documentIds,
            issues = issues,
            graph = graph,
            scans = scans,
        )
    }
}
