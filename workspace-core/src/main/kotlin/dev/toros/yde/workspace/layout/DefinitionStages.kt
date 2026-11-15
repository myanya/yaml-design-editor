package dev.toros.yde.workspace.layout

import dev.toros.yde.yaml.model.YNode

/** 段 1 つ（`.yde/workspace.yaml` の `stages:` の 1 件。ADR-0034）。 */
data class DefinitionStage(val id: String, val name: String, val description: String?)

/**
 * 段の並び（段階的詳細化。`stages:`）と、宣言の誤り。並びの順に、前の段の項へ規則と補正を当てて次の段の項を作る
 * （最初の段の入力は業務モデルの項）。書かなければ空で、生成は業務モデルの項だけを読む（今までどおり）。
 * 宣言の誤りは、段を使う生成の誤りに出す（[dev.toros.yde.workspace.Stages]）。
 */
data class DefinitionStages(val stages: List<DefinitionStage> = emptyList(), val errors: List<String> = emptyList()) {
    fun byId(id: String): DefinitionStage? = stages.firstOrNull { it.id == id }

    /** その段までの並び（最初の段から、その段を含めて）。無い段なら null。 */
    fun upTo(id: String): List<DefinitionStage>? = stages.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { stages.take(it + 1) }

    companion object {
        val NONE = DefinitionStages()

        private val ID = Regex("""[A-Za-z][A-Za-z0-9_-]*""")
        private val KEYS = setOf("id", "name", "description")

        /** 構成ファイルのルートから `stages:` を読む。読めない書き方は誤りに積み、読めた段だけを持つ。 */
        fun parse(root: YNode.Map?): DefinitionStages {
            val raw = root?.entries?.get("stages") ?: return NONE
            val errors = mutableListOf<String>()
            val items = (raw as? YNode.Seq)?.items ?: run {
                errors += "workspace.yaml の stages は、段の並び（- id: …）で書きます"
                return DefinitionStages(emptyList(), errors)
            }
            val parsed = mutableListOf<DefinitionStage>()
            items.forEachIndexed { i, item ->
                val where = "workspace.yaml の stages[$i]"
                val m = item as? YNode.Map ?: run { errors += "$where は object（id・name・description）で書きます"; return@forEachIndexed }
                m.entries.keys.firstOrNull { it !in KEYS }?.let { errors += "$where: 未知のキーです: $it（使えるキー: ${KEYS.joinToString(", ")}）" }
                val id = (m.entries["id"] as? YNode.Scalar)?.value?.toString()?.trim()
                if (id == null || !ID.matches(id)) {
                    errors += "$where: id は英字で始まる英数字（- と _ を含めてよい）で書きます: ${id ?: "（無し）"}"
                    return@forEachIndexed
                }
                parsed += DefinitionStage(
                    id = id,
                    name = (m.entries["name"] as? YNode.Scalar)?.value?.toString()?.takeIf { it.isNotBlank() } ?: id,
                    description = (m.entries["description"] as? YNode.Scalar)?.value?.toString(),
                )
            }
            parsed.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { errors += "workspace.yaml の stages: 段「$it」が重複しています" }
            return DefinitionStages(parsed.distinctBy { it.id }, errors)
        }
    }
}
