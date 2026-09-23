package dev.toros.yde.workspace.validate

import dev.toros.yde.semantic.SemanticIssueKind
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.semantic.schema.RuleOrigin
import dev.toros.yde.semantic.ScannedId
import dev.toros.yde.semantic.SemanticIssue
import dev.toros.yde.yaml.validate.StructuralIssue

/**
 * コードで実装された検査（定義体を通らない指摘）の出どころ（P27 段B の残り。意図的差分 D-29）。
 * 構造の検証・ID の重複・参照の解決・宣言のない関係名などの指摘にも、定義体の検査と同じ形の出どころ（IssueSource）を付ける:
 * - 検査の出どころ: owner は `(code)`、at は検査の名前（structure / id / reference / relation-registry / entity / template / yaml）
 * - kind: 指摘の種類（required・unresolved など）
 * - 対象の要素: 指摘の場所を含む、最も内側の ID を持つ要素（無ければ null＝文書そのもの）
 * - within: その要素の中の場所（配列の番号を除いたキーの並び）
 * - 根拠に関わった要素: 参照の相手
 * 場所の番号と文面を含めないので、要素の挿入で場所がずれても「同じ指摘」として見分けられる（提出ゲート no-new-errors）。
 */
object CodedChecks {
    /** コードで実装された検査の持ち主の名前（組込み定義体の `(builtin)` と並ぶ）。 */
    const val OWNER = "(code)"

    const val STRUCTURE = "structure"
    const val ENTITY = "entity"
    const val TEMPLATE = "template"
    const val EXPRESSION = "expression"
    const val YAML = "yaml"

    fun origin(at: String): RuleOrigin = RuleOrigin(OWNER, at, 0)

    /** 指摘の場所を含む、最も内側の ID を持つ要素（同じ文書の中の ID の一覧から決める）。 */
    fun subjectOf(ids: List<ScannedId>, path: YPath): ScannedId? =
        ids.filter { it.path.size <= path.size && path.subList(0, it.path.size) == it.path }.maxByOrNull { it.path.size }

    /** 要素の中の場所: 配列の番号を除いたキーの並び（例: `fields[].name`）。 */
    fun keyTrail(path: YPath): String {
        val sb = StringBuilder()
        for (segment in path) {
            if (segment is Int) sb.append("[]")
            else {
                if (sb.isNotEmpty()) sb.append('.')
                sb.append(segment)
            }
        }
        return sb.toString()
    }

    /** ids は、指摘の出た文書に書かれた ID の一覧（対象の要素を決めるのに使う）。 */
    fun source(
        at: String,
        kind: String,
        delivery: IssueDelivery,
        ids: List<ScannedId>,
        path: YPath,
        evidence: List<String> = emptyList(),
    ): IssueSource {
        val subject = subjectOf(ids, path)
        val inside = if (subject == null) path else path.subList(subject.path.size, path.size)
        return IssueSource(origin(at), null, kind, subject?.id, delivery, evidence.sorted(), within = keyTrail(inside))
    }

    /** 構造の検証の指摘: 値が書かれた（書かれるはずの）場所への指摘。 */
    fun structural(issue: StructuralIssue, ids: List<ScannedId>): IssueSource =
        source(STRUCTURE, issue.kind.code, IssueDelivery.VALUE, ids, issue.path)

    /** 意味の検証の指摘（ID の重複は要素の場所・それ以外は参照が書かれた場所への指摘）。 */
    fun semantic(issue: SemanticIssue, ids: List<ScannedId>): IssueSource = source(
        issue.kind.at, issue.kind.code,
        if (issue.kind == SemanticIssueKind.DUPLICATE_ID) IssueDelivery.REPORT else IssueDelivery.REF,
        ids, issue.path, listOfNotNull(issue.targetId),
    )
}
