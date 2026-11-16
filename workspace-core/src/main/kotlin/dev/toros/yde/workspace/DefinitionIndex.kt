package dev.toros.yde.workspace

import dev.toros.yde.semantic.DocumentMeta
import dev.toros.yde.semantic.DocumentScan
import dev.toros.yde.semantic.ScannedId
import dev.toros.yde.semantic.schema.EntityRole
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.scalarText

/** 定義の出現を 1 つ足す（ID・パス・種類・見出し・書いている項目のキー）。 */
private typealias Add = (id: String, path: YPath, type: String, title: String?, holderKey: String?) -> Unit

/**
 * 業務モデルの定義の出現（定義の索引）。スキーマに書いた操作・State・Lifecycle・エンティティ型・前提を、索引の要素として載せる。
 * - 出現は定義を書いた場所（スキーマの文書と、その中のパス）にあり、役割は定義（DEFINING）。種類はエンティティ型 `yde-*` で表す
 * - 設計書などの文書は、同じ ID を詳細化の出現（`entity: {type: yde-action, defines: false}`）として書くか、参照で指す。
 *   定義の無い詳細化・参照は今までどおり指摘になる（業務モデルから消えた操作を、設計書が書き続けていれば分かる）
 * - ID は業務の ID そのもの（操作 `complete-todo`・型の操作 `todo-task.finish`・State `todo-CLOSED`・Lifecycle `todo-lifecycle`・
 *   エンティティ型 `todo`）。前提は操作の中の名前なので `操作ID.前提ID`（`complete-todo.no-open-task`）
 * - State は組み立てた定義から作る（列挙型から作った State はファイルにその文字が無いので、`source` の宣言の場所に置く）
 * - 読めなかった定義（スキーマの誤り）は載せない。スキーマが変わると索引は全部作り直すので、文書の増分更新では引き継ぐ
 */
object DefinitionIndex {
    /** 操作（スキーマの actions と、型の操作 methods）。 */
    const val ACTION = "yde-action"
    /** State（列挙型から作ったものと、述語・詳細化の親）。 */
    const val STATE = "yde-state"
    /** Lifecycle。 */
    const val LIFECYCLE = "yde-lifecycle"
    /** エンティティ型（`entity: {type: …}` の宣言。定義の宣言を優先して 1 か所）。 */
    const val ENTITY = "yde-entity"
    /** 操作の前提（名前 `id` を付けたものだけ）。 */
    const val REQUIRE = "yde-require"
    /**
     * ビュー（ダッシュボード・表・集計など）。定義の出現はビューの定義の文書のルートで、ここではなく組込みのメタスキーマ
     * builtin-view の `entity` の宣言で載る（ビューの文書はもともと索引に載る）。
     */
    const val VIEW = "yde-view"
    /**
     * 項目（エンティティ型の側面に、項目の並び fields として書いた項目）。ID は `型.キー`（`todo.title`）。出現は項目を書いた場所
     * （名前付きの型の定義の中・文書のルートの中）。同じ型の同じキーを 2 か所に書いていれば、先に見つけた 1 か所。
     * 設計の項目が「どの業務の項目から来たか」を参照で指し、リンクで辿るのに使う。
     */
    const val FIELD = "yde-field"
    /**
     * 型（スキーマの definitions に名前を付けて書いた型。エンティティ型を持つ object は除く）。ID は型の書き方そのもの
     * `スキーマID#型の名前`（`common#一行の文`）。設計の論理型が「どの業務の型から来たか」を参照で指すのに使う。
     */
    const val TYPE = "yde-type"

    /** 定義の索引が使うエンティティ型。利用者のスキーマは、これを定義（defines: true）として宣言できない。 */
    val types: Set<String> = setOf(ACTION, STATE, LIFECYCLE, ENTITY, REQUIRE, VIEW, FIELD, TYPE)

    /** 予約した接頭辞（利用者の型の名前に使わない）。 */
    const val PREFIX = "yde-"

    /** 定義の出現を、スキーマの文書ごとの走査結果として作る（文書の走査結果と同じ形で集約に混ぜる）。 */
    fun scans(documents: Map<DocumentId, WorkspaceDocument>, schemas: List<WorkspaceSchema>, layout: WorkspaceLayout): List<DocumentScan> {
        val bySource = schemas.associateBy { it.source }
        val catalog = runCatching { BehaviorChecks.compile(schemas) }.getOrNull()
        val sources = documents.entries
            .filter { layout.zoneOf(it.key.value) == WorkspaceLayout.Zone.SCHEMAS && it.key in bySource }
            .sortedBy { it.key.value }
            .mapNotNull { (id, d) -> (d.document.documents.firstOrNull() as? YNode.Map)?.let { Source(id, d, it, bySource.getValue(id)) } }
        val entities = entityDeclarations(sources)
        // 項目は型とキーの組ごとに 1 か所（文書をまたいで、先に見つけたもの）
        val seenFields = HashSet<String>()
        return sources.mapNotNull { src ->
            val ids = mutableListOf<ScannedId>()
            fun add(id: String, path: YPath, type: String, title: String?, holderKey: String?) {
                ids += ScannedId(
                    // スキーマは持たない（定義の出現は文書のスキーマに属さない。メタスキーマの割り当ての経路に依らず同じ出現にする）
                    id = id, document = src.id, path = path, title = title, schemaId = null,
                    documentId = DocumentMeta.idOf(src.root, src.id.value), entityType = type, role = EntityRole.DEFINING,
                    holderKey = holderKey,
                )
            }
            actions(src, ::add)
            methods(src, ::add)
            states(src, catalog, ::add)
            lifecycles(src, ::add)
            entities[src.id].orEmpty().forEach { e -> add(e.type, e.path, ENTITY, e.title, null) }
            fields(src, seenFields, ::add)
            namedTypes(src, ::add)
            ids.takeIf { it.isNotEmpty() }?.let { DocumentScan(src.id, DocumentMeta.idOf(src.root, src.id.value), it, emptyList()) }
        }
    }

    private class Source(val id: DocumentId, val document: WorkspaceDocument, val root: YNode.Map, val schema: WorkspaceSchema)

    private class EntityDeclaration(val type: String, val path: YPath, val title: String?, val defines: Boolean)


    /** スキーマの actions（読めたものだけ）と、名前を付けた前提。 */
    private fun actions(src: Source, add: Add) {
        val parsed = src.schema.actions.associateBy { it.id }
        val items = (src.root.entries["actions"] as? YNode.Seq)?.items.orEmpty()
        items.forEachIndexed { i, node ->
            val m = node as? YNode.Map ?: return@forEachIndexed
            val id = scalarText(m.entries["id"]) ?: return@forEachIndexed
            val action = parsed[id] ?: return@forEachIndexed
            add(id, listOf("actions", i), ACTION, action.label, "actions")
            val requires = (m.entries["require"] as? YNode.Seq)?.items.orEmpty()
            requires.forEachIndexed { j, r ->
                val rm = r as? YNode.Map ?: return@forEachIndexed
                val rid = scalarText(rm.entries["id"]) ?: return@forEachIndexed
                if (action.require.getOrNull(j)?.id != rid) return@forEachIndexed
                add("$id.$rid", listOf("actions", i, "require", j), REQUIRE, scalarText(rm.entries["message"]), "require")
            }
        }
    }

    /**
     * 型の操作（`definitions.型.methods.名前`）。名前は操作の一覧と同じ `エンティティ型.操作名`（型にエンティティ型が無ければ載せない）。
     * 文書のルートの object に書いた操作も同じ。
     */
    private fun methods(src: Source, add: Add) {
        fun of(owner: YNode.Map, path: YPath) {
            val type = entityTypeOf(owner) ?: return
            val methods = owner.entries["methods"] as? YNode.Map ?: return
            methods.entries.forEach { (name, def) ->
                add("$type.$name", path + listOf("methods", name), ACTION, scalarText((def as? YNode.Map)?.entries?.get("label")), "methods")
            }
        }
        (src.root.entries["definitions"] as? YNode.Map)?.entries?.forEach { (name, def) ->
            (def as? YNode.Map)?.let { of(it, listOf("definitions", name)) }
        }
        (src.root.entries["root"] as? YNode.Map)?.let { of(it, listOf("root")) }
    }

    /** State: id を書いたもの（述語・詳細化の親）と、列挙型から作ったもの（`source` の宣言の場所に置く）。 */
    private fun states(src: Source, catalog: BehaviorChecks.Catalog?, add: Add) {
        val items = (src.root.entries["states"] as? YNode.Seq)?.items.orEmpty()
        if (items.isEmpty() || catalog == null) return
        val known = catalog.states.associateBy { it.id }
        items.forEachIndexed { i, node ->
            val m = node as? YNode.Map ?: return@forEachIndexed
            val path = listOf("states", i)
            val source = m.entries["source"] as? YNode.Map
            if (source == null) {
                val id = scalarText(m.entries["id"]) ?: return@forEachIndexed
                if (id in known || id in catalog.children) add(id, path, STATE, scalarText(m.entries["label"]), "states")
            } else {
                val origin = listOf("entity", "path", "enum").map { scalarText(source.entries[it]).orEmpty() }.joinToString(":")
                catalog.states.filter { it.enumOrigin == origin }.forEach { s -> add(s.id, path, STATE, s.label, "states") }
            }
        }
    }

    private fun lifecycles(src: Source, add: Add) {
        val parsed = src.schema.lifecycles.map { it.id }.toSet()
        (src.root.entries["lifecycles"] as? YNode.Seq)?.items.orEmpty().forEachIndexed { i, node ->
            val id = scalarText((node as? YNode.Map)?.entries?.get("id")) ?: return@forEachIndexed
            if (id in parsed) add(id, listOf("lifecycles", i), LIFECYCLE, null, "lifecycles")
        }
    }

    /**
     * エンティティ型の宣言（`entity: {type: …}` を値に持つ object）。型ごとに 1 か所: 定義の宣言（defines: true）の
     * 先頭、無ければ最初の宣言。置き場所は宣言を持つ object（オブジェクト型・文書のルート・スキーマ）。
     */
    private fun entityDeclarations(sources: List<Source>): Map<DocumentId, List<EntityDeclaration>> {
        val found = mutableListOf<Pair<DocumentId, EntityDeclaration>>()
        for (src in sources) {
            fun walk(node: YNode, path: YPath) {
                when (node) {
                    is YNode.Map -> {
                        val decl = node.entries["entity"] as? YNode.Map
                        val type = decl?.let { scalarText(it.entries["type"]) }
                        if (decl != null && type != null && !type.startsWith(PREFIX)) {
                            val defines = (decl.entries["defines"] as? YNode.Scalar)?.value == true
                            val title = scalarText(node.entries["label"]) ?: scalarText(node.entries["name"])
                            found += src.id to EntityDeclaration(type, path, title, defines)
                        }
                        node.entries.forEach { (k, v) -> walk(v, path + k) }
                    }
                    is YNode.Seq -> node.items.forEachIndexed { i, v -> walk(v, path + i) }
                    else -> Unit
                }
            }
            walk(src.root, emptyList())
        }
        return found.groupBy { it.second.type }
            .map { (_, all) -> all.firstOrNull { it.second.defines } ?: all.first() }
            .groupBy({ it.first }, { it.second })
    }

    /**
     * 項目: スキーマの木（型を解いたもの）と原文を一緒に歩き、エンティティ型を持つ object（オブジェクト型か、スキーマの entity の
     * 宣言で ID の項目を持つ object。業務モデルの項の側面と同じ見分け方）の、原文に書いた fields の項目を載せる。
     * 名前付きの型を使う所（`type: common#TODO個票`）には原文の fields が無いので、その型を書いた定義の側で載る。
     */
    private fun fields(src: Source, seen: MutableSet<String>, add: Add) {
        val tree = src.schema.tree
        val schemaLevel = src.schema.entities.firstOrNull()
        fun walk(f0: dev.toros.yde.semantic.schema.FieldDef, raw: YNode.Map?, path: YPath, depth: Int) {
            if (raw == null || depth > 12) return
            val f = tree.resolve(f0) ?: f0
            when (f.type) {
                dev.toros.yde.semantic.schema.FieldType.OBJECT -> {
                    val rawFields = (raw.entries["fields"] as? YNode.Seq)?.items ?: return
                    val decl = f.entity ?: schemaLevel?.takeIf {
                        f.fields.any { c -> (tree.resolve(c) ?: c).type == dev.toros.yde.semantic.schema.FieldType.ID }
                    }
                    rawFields.forEachIndexed { i, node ->
                        val m = node as? YNode.Map ?: return@forEachIndexed
                        val key = scalarText(m.entries["key"]) ?: return@forEachIndexed
                        if (decl != null && !decl.type.startsWith(PREFIX) && seen.add("${decl.type}.$key")) {
                            add("${decl.type}.$key", path + listOf("fields", i), FIELD, scalarText(m.entries["label"]), "fields")
                        }
                        val child = f0.fields.firstOrNull { it.key == key } ?: f.fields.firstOrNull { it.key == key } ?: return@forEachIndexed
                        walk(child, m, path + listOf("fields", i), depth + 1)
                    }
                }
                dev.toros.yde.semantic.schema.FieldType.ARRAY -> f.item?.let { walk(it, raw.entries["item"] as? YNode.Map, path + "item", depth + 1) }
                dev.toros.yde.semantic.schema.FieldType.MAP -> f.value?.let { walk(it, raw.entries["value"] as? YNode.Map, path + "value", depth + 1) }
                else -> Unit
            }
        }
        (src.root.entries["definitions"] as? YNode.Map)?.entries?.forEach { (name, def) ->
            tree.definitions[name]?.let { walk(it, def as? YNode.Map, listOf("definitions", name), 0) }
        }
        walk(tree.root, src.root.entries["root"] as? YNode.Map, listOf("root"), 0)
    }

    /** 型: definitions の名前付きの定義（エンティティ型の宣言 `entity:` を持つものは除く。それはエンティティ型の側面）。 */
    private fun namedTypes(src: Source, add: Add) {
        (src.root.entries["definitions"] as? YNode.Map)?.entries?.forEach { (name, def) ->
            val m = def as? YNode.Map ?: return@forEach
            if (m.entries.containsKey("entity")) return@forEach
            add("${src.schema.id}#$name", listOf("definitions", name), TYPE, scalarText(m.entries["label"]) ?: name, "definitions")
        }
    }

    private fun entityTypeOf(owner: YNode.Map): String? =
        scalarText((owner.entries["entity"] as? YNode.Map)?.entries?.get("type"))
}
