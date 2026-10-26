package dev.toros.yde.workspace

import dev.toros.yde.semantic.schema.TransformDef
import dev.toros.yde.yaml.model.DocumentId

/**
 * レポートの定義（`.yde/reports` の YAML。R30-04）: 値の一式を作る変換（`params` と `build`）と、文字のひな形（`template`）と、
 * 出力先（`output`）。ひな形のファイルは定義と同じ場所に置く。出力は原本から作り直せる派生物で、YAML には書かない。
 */
data class ReportDef(
    val id: String,
    val name: String,
    val description: String?,
    /** 値の一式を作る変換（`$view` でビューの行を受け取れる）。 */
    val transform: TransformDef,
    /** ひな形のファイル（定義と同じ場所からの相対パス）。 */
    val template: String,
    /** 既定の出力先（ワークスペース相対。穴 `{名前}` は build の値で埋める。null＝表示だけ）。 */
    val output: String?,
    val source: DocumentId,
) {
    val params: List<String> get() = transform.params
}

object ReportDefinitions {
    private val KEYS = setOf("id", "name", "description", "params", "build", "template", "output")
    private val DRIVE = Regex("""^[A-Za-z]:""")

    fun parse(raw: Map<*, *>, source: DocumentId): ReportDef {
        raw.keys.map { it.toString() }.firstOrNull { it !in KEYS }
            ?.let { error("report: 未知のキーです: $it（使えるキー: ${KEYS.joinToString(", ")}）") }
        val id = raw["id"]?.toString()?.takeIf { it.isNotBlank() } ?: error("report.id が必要です")
        val template = raw["template"]?.toString()?.takeIf { it.isNotBlank() }
            ?: error("report.template（ひな形のファイル。定義と同じ場所からの相対パス）が必要です")
        require(!template.startsWith("/") && !template.contains("..") && !DRIVE.containsMatchIn(template)) {
            "report.template は定義と同じ場所からの相対パスで書きます: $template"
        }
        val output = raw["output"]?.toString()?.takeIf { it.isNotBlank() }?.also { o ->
            require(!o.startsWith("/") && !o.contains("..") && !DRIVE.containsMatchIn(o)) { "report.output はワークスペース相対のパスで書きます: $o" }
            require(!o.endsWith(".yaml") && !o.endsWith(".yml")) { "report.output に YAML は書けません（原本は YAML で、レポートは派生物です）: $o" }
        }
        val build = raw["build"] ?: error("report.build（値の一式を作るひな型）が必要です")
        val transform = TransformDef.parse(
            linkedMapOf<String, Any?>("id" to id, "params" to (raw["params"] ?: emptyList<String>()), "build" to build),
            "report",
        )
        return ReportDef(
            id = id,
            name = raw["name"]?.toString()?.takeIf { it.isNotBlank() } ?: id,
            description = raw["description"]?.toString()?.takeIf { it.isNotBlank() },
            transform = transform, template = template, output = output, source = source,
        )
    }
}
