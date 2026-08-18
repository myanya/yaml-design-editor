package semantic

import model.DocumentId
import model.YPath
import model.pretty
import schema.EnforcementDef
import schema.EntityRole
import schema.EntityTypeDef
import schema.RefTarget
import schema.RelationDef
import schema.idGlobToRegex
import validate.Severity

/**
 * ワークスペース単位の意味宣言（3層コア移行 Phase 5・SM-002。計画書§4.2）。
 * `kind: relations` 文書などから上位層（workspace-core）が集約して渡す。
 * 現時点の SemanticEngine は集約・重複・参照解決だけを担い、relations / enforcement を使う
 * グラフ整合検証は後続（GraphIntegrity の移行時）に接続する。
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

/** 意味検証の指摘（構造検証の StructuralIssue と別型。計画書 Phase 3 作業順9）。 */
data class SemanticIssue(
    val severity: Severity,
    val message: String,
    val document: DocumentId,
    val path: YPath,
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
 * 意味解析エンジン（計画書§4.2）。ファイルI/Oを行わず、与えられた文書集合から全再構築する
 * （増分更新は Phase 11。全再構築が正の基準＝oracle になる）。
 * 台帳（ledger）による対象文書の絞り込みは上位層の責務で、ここへは対象文書だけが渡される前提。
 */
object SemanticEngine {

    fun build(input: SemanticInput): SemanticSnapshot {
        // 文書ID順で安定化（ルート IdIndex.derive のパス順ソートと同じ考え方）
        val docs = input.documents.sortedBy { it.id.value }
        val ids = linkedMapOf<String, MutableList<ScannedId>>()
        val refs = mutableListOf<ScannedRef>()
        val issues = mutableListOf<SemanticIssue>()
        val documentIds = mutableSetOf<String>()

        for (d in docs) {
            documentIds += d.documentId
            val (docIds, docRefs) = SemanticScanner.scan(d)
            for (e in docIds) ids.getOrPut(e.id) { mutableListOf() }.add(e)
            refs += docRefs
            // 文書内の ID 重複（ERROR）。文書間の同一 ID は「同一エンティティの複数出現」で合法
            docIds.groupBy { it.id }.filterValues { it.size > 1 }.forEach { (id, list) ->
                list.forEach { e ->
                    issues += SemanticIssue(Severity.ERROR, "ID「$id」がこの文書内で重複しています", d.id, e.path)
                }
            }
        }

        // 参照解決（未解決＝ERROR・存在するがパターン/スコープ対象外＝ERROR・文書参照は別名前空間）
        for (r in refs) {
            if (r.targetKind == RefTarget.DOCUMENT) {
                if (r.targetId !in documentIds) {
                    issues += SemanticIssue(
                        Severity.ERROR,
                        "文書参照「${r.targetId}」を解決できません（_document.id または相対パス）",
                        r.document, r.path,
                    )
                }
                continue
            }
            val occs = ids[r.targetId].orEmpty()
            if (occs.none { it.isAllowedBy(r) }) {
                issues += if (occs.isNotEmpty()) {
                    SemanticIssue(Severity.ERROR, "参照「${r.targetId}」はパターンまたはスコープの対象外です", r.document, r.path)
                } else {
                    SemanticIssue(Severity.ERROR, "参照「${r.targetId}」（${r.path.pretty()}）を解決できません", r.document, r.path)
                }
            }
        }

        return SemanticSnapshot(
            sourceVersion = input.workspaceVersion,
            ids = ids,
            refs = refs,
            documentIds = documentIds,
            issues = issues,
        )
    }
}
