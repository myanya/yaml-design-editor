package dev.toros.yde.workspace

import dev.toros.yde.semantic.TermChangeDoc
import dev.toros.yde.semantic.TermChanges
import dev.toros.yde.workspace.layout.DefinitionStage
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode

/** 段の規則か補正 1 つ（stages ゾーンの文書。メタスキーマ builtin-stage）と、置き場。 */
data class StageChangeDef(val def: TermChangeDoc, val source: DocumentId)

/**
 * 段の項（段階的詳細化。ADR-0034）: 業務モデルの項に、段の並び（workspace.yaml の `stages:`）の順に、その段の規則と補正を当てたもの。
 * 形は業務モデルの項と同じで（生成の定義は段を変えても同じ書き方で読める）、足した要素と置き換えた場所には出どころが付く。
 *
 * - 段の中は、規則（rule）を先に、補正（patch）を後に当てる（どちらも置き場の名前の順）。補正は規則の結果も直せる
 * - 項の頭に `stages`（当てた段と、段ごとの規則・補正の一覧）を足す。設計書が「どの方針を当てたか」を書くのに使う
 * - 出どころ（`origin`）は {stage, stageLabel, by, label, kind}。業務モデルから来たものには付かない
 */
object Stages {

    /** stages ゾーンの規則と補正（下のフォルダも。ID の重なりと、無い段を指すものは誤り）。 */
    fun definitions(session: WorkspaceSession): Pair<List<StageChangeDef>, List<String>> {
        val errors = mutableListOf<String>()
        val defs = session.snapshot.documents.entries
            .filter { session.layout.zoneOf(it.key.value) == WorkspaceLayout.Zone.STAGES }
            .sortedBy { WorkspaceLayout.nameInZone(it.key.value) }
            .mapNotNull { (id, d) ->
                val root = d.document.documents.firstOrNull() ?: return@mapNotNull null
                runCatching { StageChangeDef(TermChanges.parse(YamlIO.toAny(root), WorkspaceLayout.nameInZone(id.value)), id) }
                    .onFailure { errors += it.message ?: "段の規則・補正を読めません" }.getOrNull()
            }
        defs.groupBy { it.def.id }.filterValues { it.size > 1 }.forEach { (id, found) ->
            errors += "段の規則・補正のID「$id」が重複しています（${found.joinToString(", ") { WorkspaceLayout.nameInZone(it.source.value) }}）"
        }
        val declared = session.layout.stages
        defs.filter { declared.byId(it.def.stage) == null }.forEach {
            errors += "${WorkspaceLayout.nameInZone(it.source.value)}: 段「${it.def.stage}」がありません（workspace.yaml の stages: ${declared.stages.joinToString(", ") { s -> s.id }.ifEmpty { "なし" }}）"
        }
        return defs.distinctBy { it.def.id } to errors
    }

    /** 段の項と、作るときの誤り（段の宣言・規則と補正の読み込み・当てたときの誤り）。 */
    class StageTerm(val term: YNode, val errors: List<String>)

    /** 業務モデルの項（model）から、その段の項を作る。 */
    fun term(session: WorkspaceSession, model: YNode, stage: String): StageTerm {
        val declared = session.layout.stages
        val errors = declared.errors.toMutableList()
        val chain = declared.upTo(stage) ?: return StageTerm(model, errors + "段「$stage」がありません（workspace.yaml の stages: ${declared.stages.joinToString(", ") { it.id }.ifEmpty { "なし" }}）")
        val (defs, defErrors) = definitions(session)
        errors += defErrors
        return apply(model, chain, defs.map { it.def }, errors)
    }

    /** 段の並びの順に、段ごとの規則 → 補正を当てる（セッションを持たない純粋な部分。テストからも使う）。 */
    fun apply(model: YNode, chain: List<DefinitionStage>, defs: List<TermChangeDoc>, errors: MutableList<String> = mutableListOf()): StageTerm {
        var term = model
        val applied = mutableListOf<Map<String, Any?>>()
        for (stage in chain) {
            val inStage = defs.filter { it.stage == stage.id }
            val ordered = inStage.filter { it.kind == TermChangeDoc.Kind.RULE } + inStage.filter { it.kind == TermChangeDoc.Kind.PATCH }
            for (doc in ordered) {
                val origin = linkedMapOf<String, Any?>("stage" to stage.id, "stageLabel" to stage.name, "by" to doc.id, "label" to doc.name, "kind" to doc.kind.id)
                val result = TermChanges.apply(term, doc, origin)
                term = result.term
                errors += result.errors
            }
            applied += linkedMapOf<String, Any?>(
                "id" to stage.id, "label" to stage.name, "description" to stage.description,
                "changes" to ordered.map { d ->
                    linkedMapOf<String, Any?>("id" to d.id, "label" to d.name, "kind" to d.kind.id, "kindLabel" to d.kind.label, "description" to d.description)
                        .filterValues { it != null }
                },
            ).filterValues { it != null }
        }
        // 頭のキーだけを写して足す（当てた文書が無いとき、term は渡された業務モデルの項そのもの）
        val top = term as? YNode.Map ?: return StageTerm(term, errors)
        return StageTerm(YNode.Map(LinkedHashMap(top.entries).apply { put("stages", YamlIO.fromAny(applied)) }), errors)
    }
}
