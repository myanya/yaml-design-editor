package dev.toros.yde.app.view.flow

import dev.toros.yde.semantic.ExtensionViewDef
import dev.toros.yde.semantic.schema.TransformDef
import dev.toros.yde.semantic.schema.globToRegex
import dev.toros.yde.workspace.DefinitionMentions
import dev.toros.yde.workspace.ViewKind
import dev.toros.yde.workspace.WorkspaceSchema
import dev.toros.yde.yaml.model.DocumentId

/**
 * フロー（`kind: flow`）の差し込み口の実装（ADR-0014）。app-core の組込みで、登録簿（app.ext.ExtensionRegistry）が最初から持つ（ADR-0015）。
 * 定義の形の断片は資源 view/flow/schema.yaml で、ビューのメタスキーマに合成される。文書ビューの区画には `flow: {view}` で埋め込む。
 */
object FlowViewKind : ViewKind {
    override val id: String = "flow"
    override val label: String = "フロー"
    override val sectionKey: String = "flow"

    override val schemaFragment: String by lazy {
        requireNotNull(FlowViewKind::class.java.getResourceAsStream("/view/flow/schema.yaml")) { "フローの定義の形の断片（資源 view/flow/schema.yaml）が見つかりません" }
            .use { it.readBytes().decodeToString() }
    }

    private val keys = setOf("id", "name", "description", "kind", "match", "schemaId", "layout", "projection")

    /** 適用先（match か schemaId）・配置（layout.direction）・射影（projection＝変換定義。from と外部の params は書けない）。 */
    override fun parse(raw: Map<*, *>, source: DocumentId): VirtualFlowDef {
        raw.keys.firstOrNull { it !in keys }?.let { error("kind: flow では使えないキーです: $it") }
        fun text(key: String): String? = if (!raw.containsKey(key)) null else
            (raw[key] as? String)?.takeIf { it.isNotBlank() } ?: error("$key は空でない文字列で書きます")
        val id = text("id") ?: error("id が必要です")
        val match = text("match")
        val schemaId = text("schemaId")
        require(match != null || schemaId != null) { "フロービューには match か schemaId が必要です" }
        match?.let(::globToRegex)
        val layout = if (raw.containsKey("layout")) raw["layout"] as? Map<*, *> ?: error("layout はobjectで書きます") else emptyMap<Any, Any>()
        require(layout.keys.all { it == "direction" }) { "layout に書けるキーは direction だけです" }
        val direction = if (layout.containsKey("direction")) layout["direction"] as? String else "down"
        require(direction in listOf("down", "right")) { "layout.direction は down / right で書きます" }
        val projection = TransformDef.parse(raw["projection"], "projection")
        require(projection.from == null && projection.params.isEmpty()) { "フローのprojectionには from・外部paramsを指定できません" }
        return VirtualFlowDef(id, text("name") ?: id, text("description"), source, match, schemaId, direction!!, projection)
    }

    /** 適用先のスキーマ（schemaId）があること。 */
    override fun lintDefinition(def: ExtensionViewDef, schemas: List<WorkspaceSchema>): List<String> {
        val schemaId = (def as VirtualFlowDef).schemaId ?: return emptyList()
        return if (schemas.any { it.id == schemaId }) emptyList() else listOf("適用先スキーマ「$schemaId」が見つかりません")
    }

    override fun moveWarnings(def: ExtensionViewDef, leaves: (String?) -> Boolean): List<String> {
        val flow = def as VirtualFlowDef
        return if (leaves(flow.match)) listOf("フロービュー「${flow.id}」の match がこのファイルを対象にしなくなります") else emptyList()
    }

    /** 投影の中の固定 ID・関係名・相対パスは参照型の裏付けが無いので、自動で書き換えず場所を開示する。適用先（schemaId・match）も。 */
    override fun mentionSites(raw: Map<*, *>, token: String): List<String> =
        DefinitionMentions.literal(raw["projection"], token, "projection") +
            listOf("schemaId", "match").filter { key -> raw[key]?.toString()?.let { DefinitionMentions.tokenRegex(token).containsMatchIn(it) } == true }
}
