package dev.toros.yde.workspace

import dev.toros.yde.semantic.schema.ActionParams
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.SchemaTree
import dev.toros.yde.yaml.YamlIO

/** テンプレートの 1 ファイル（テンプレートのフォルダからの相対パスと原文）。 */
data class TemplateFile(val path: String, val text: String)

/**
 * 新しいワークスペースの出発点（リポジトリの templates/ の 1 フォルダ。ビルド時に資源 templates/ へ写す）。
 * - `template.yaml` が台帳: `name`（表示名）・`description`・`params`（問い。操作の params と同じ、項目の定義の配列）
 * - それ以外のファイルはワークスペースへ写す。問いを持つテンプレートでは、答えを「いまの場所」として YAML を変換定義のひな型で組み立て、
 *   ファイル名と Markdown の `{名前}` を答えで埋める（計画は `WorkspaceInit`）
 */
class WorkspaceTemplate(
    val id: String,
    val name: String,
    val description: String?,
    val params: List<FieldDef>,
    val files: List<TemplateFile>,
) {
    /** 問いを持つか（答えで組み立てるテンプレートか）。 */
    val hasParams: Boolean get() = params.isNotEmpty()

    /** 答えの点検と入力欄のために、問いを object の項目として持つ木（組込みの型 line / word などはここで解決する）。 */
    val paramTree: SchemaTree by lazy { SchemaTree(id = "template-$id", root = FieldDef(type = FieldType.OBJECT, fields = params)) }

    /** 問いを解決した形で（型の連鎖・形式・選択肢つき）。 */
    fun resolvedParam(field: FieldDef): FieldDef = paramTree.resolve(field) ?: field

    companion object {
        const val MANIFEST = "template.yaml"
        private val MANIFEST_KEYS = setOf("id", "name", "description", "params")

        /** テンプレートのフォルダの中身から読む。`template.yaml` の id はフォルダ名と同じでなければならない。 */
        fun parse(id: String, files: List<TemplateFile>): WorkspaceTemplate {
            val manifest = files.firstOrNull { it.path == MANIFEST } ?: error("テンプレート「$id」に $MANIFEST がありません")
            val raw = YamlIO.parseToRaw(manifest.text) as? Map<*, *> ?: error("テンプレート「$id」の $MANIFEST はobjectである必要があります")
            raw.keys.map { it.toString() }.firstOrNull { it !in MANIFEST_KEYS }
                ?.let { error("テンプレート「$id」の $MANIFEST: 未知のキーです: $it（使えるキー: ${MANIFEST_KEYS.joinToString(", ")}）") }
            raw["id"]?.toString()?.let { declared -> require(declared == id) { "テンプレート「$id」の $MANIFEST の id（$declared）がフォルダ名と違います" } }
            val params = ActionParams.parse(raw["params"], "テンプレート「$id」の params", id)
            return WorkspaceTemplate(
                id = id,
                name = raw["name"]?.toString()?.takeIf { it.isNotBlank() } ?: id,
                description = raw["description"]?.toString(),
                params = params,
                files = files.filter { it.path != MANIFEST },
            )
        }
    }
}

/** 同梱のテンプレート（資源 templates/。一覧は index.txt、各テンプレートのファイルは files.txt）。 */
object WorkspaceTemplates {
    /** 「自分の項目で」の実体（問いを持つテンプレート）。 */
    const val CUSTOM = "basic-list"

    private const val RESOURCE_ROOT = "/templates"

    val all: List<WorkspaceTemplate> by lazy { ids().map(::load) }

    fun get(id: String): WorkspaceTemplate? = all.firstOrNull { it.id == id }

    /** 問いを持たないテンプレート（「テンプレートから」に並べるもの）。 */
    val fixed: List<WorkspaceTemplate> get() = all.filter { !it.hasParams }

    /** 「自分の項目で」のテンプレート。資源に無ければ null。 */
    val custom: WorkspaceTemplate? get() = get(CUSTOM)

    private fun resource(path: String): String? =
        WorkspaceTemplates::class.java.getResourceAsStream("$RESOURCE_ROOT/$path")?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }

    private fun lines(text: String?): List<String> = text?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList().orEmpty()

    private fun ids(): List<String> = lines(resource("index.txt"))

    private fun load(id: String): WorkspaceTemplate {
        val names = lines(resource("$id/files.txt"))
        require(names.isNotEmpty()) { "テンプレート「$id」のファイル一覧（files.txt）が資源にありません" }
        val files = names.map { name -> TemplateFile(name, resource("$id/$name") ?: error("テンプレート「$id」のファイルが資源にありません: $name")) }
        return WorkspaceTemplate.parse(id, files)
    }
}
