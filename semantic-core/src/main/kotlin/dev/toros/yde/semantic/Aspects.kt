package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.SchemaKind
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.semantic.schema.EntityDecl
import dev.toros.yde.semantic.schema.EntityTypeDef
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.SchemaTree
import dev.toros.yde.semantic.schema.entityFor
import dev.toros.yde.semantic.schema.ownedFieldKeys

/**
 * エンティティ合成のカーネル（R23-01。3層コア移行 Phase 6B で core/Aspects.kt から移動）。
 * 同一論理IDの全出現を「定義出現 → レジストリのアスペクト宣言順」に並べ、所有フィールドを
 * 座標つきでマージした「エンティティ全体像」を決定的に構築する。
 * 合成結果はその場で消費する読み出しモデルであり、スナップショット状態として保持しない
 * （入力の木は読み取り専用。値ノードの参照は表示・編集座標のために持つ）。
 */

/** スキーマ1件の意味束（合成・リファクタリングが必要とする構造コア＋宣言）。上位層が SchemaDef から作る。 */
data class SchemaSemantics(
    val id: String,
    val tree: SchemaTree,
    val entities: List<EntityDecl> = emptyList(),
    /** スキーマ定義文書の座標（スキーマ自身の書き換え先。組込み・derive 派生は null＝編集不可。RF-002b）。 */
    val source: dev.toros.yde.yaml.model.DocumentId? = null,
    val kind: SchemaKind = SchemaKind.DOCUMENT,
)

/** 合成された所有フィールド1件。座標（document, path）は原本上の値の位置（＝編集・パッチの対象）。 */
data class SemanticAspectField(
    val key: String,
    val label: String,
    /** loader のツリーから読んだ現在値（未記入は null）。 */
    val value: YNode?,
    val document: DocumentId,
    val path: YPath,
    /** 先行するアスペクトが同じキーを所有している場合、その座標（重複所有の注記。値は隠さない）。 */
    val duplicateOf: Pair<DocumentId, YPath>? = null,
)

/** 合成の1区分（アスペクト）。1出現に対応する。 */
data class SemanticAspectSection(
    val aspect: String,
    /** 表示名（レジストリ entities 節の aspects.label ＞ アスペクト名）。 */
    val label: String,
    val entry: ScannedId,
    val schemaId: String?,
    /** この出現の entity 宣言が明示的な owns を持つか（重複所有の判定条件）。 */
    val ownsDeclared: Boolean,
    val ownedKeys: List<String>,
    val fields: List<SemanticAspectField>,
)

/** エンティティ全体像（R23-01）。sections の並びは決定的（architecture/graph-query.md「エンティティページ」）。 */
data class SemanticEntityComposite(
    val id: String,
    val entityType: String?,
    /** 型の表示名（レジストリ entities 節の label ＞ 型名）。 */
    val typeLabel: String?,
    val title: String?,
    val defining: ScannedId?,
    val sections: List<SemanticAspectSection>,
    /** 合成に載らない出現（スキーマなし・object に解決できない）。 */
    val unassigned: List<ScannedId>,
)

object SemanticAspects {

    /** ID の全出現からエンティティ全体像を合成する。ID が存在しなければ null。 */
    fun compose(
        id: String,
        graph: SemanticEntityGraph,
        schemas: Map<String, SchemaSemantics>,
        entityTypes: Map<String, EntityTypeDef>,
        loader: (DocumentId) -> YNode?,
    ): SemanticEntityComposite? {
        val node = graph.entities[id] ?: return null
        val typeDef = node.entityType?.let { entityTypes[it] }

        data class Prepared(
            val entry: ScannedId,
            val schema: SchemaSemantics,
            val root: YNode,
            val decl: EntityDecl?,
            val aspect: String,
            val objectField: FieldDef,
        )

        val trees = mutableMapOf<DocumentId, YNode?>()
        fun tree(d: DocumentId): YNode? = trees.getOrPut(d) { loader(d) }

        val prepared = mutableListOf<Prepared>()
        val unassigned = mutableListOf<ScannedId>()
        for (entry in node.occurrences) {
            val sc = entry.schemaId?.let { schemas[it] }
            val root = tree(entry.document)
            val objectField = if (sc != null && root != null) {
                sc.tree.fieldAt(root, entry.path)?.takeIf { it.type == FieldType.OBJECT }
            } else null
            if (sc == null || root == null || objectField == null) {
                unassigned += entry
                continue
            }
            val decl = entityFor(sc.entities, id)
            prepared += Prepared(entry, sc, root, decl, decl?.aspect ?: sc.id, objectField)
        }

        // 並び: 定義出現 → レジストリのアスペクト宣言順 → 文書 → 要素パス → アスペクト名（決定的。architecture/graph-query.md「エンティティページ」）。
        // 宣言のないスキーマ群はスキーマIDのアルファベット順でなく文書ID（パス）順に落とす（UX-80）
        val ordered = prepared.sortedWith(
            compareBy(
                { if (it.entry == node.defining) 0 else 1 },
                { typeDef?.aspectOrder(it.aspect) ?: Int.MAX_VALUE },
                { it.entry.document.value },
                { it.entry.path.joinToString("/") },
                { it.aspect },
            )
        )

        // 所有フィールドを座標つきでマージする。重複所有は隠さず duplicateOf で注記する（architecture/graph-query.md「エンティティページ」）
        val claimed = mutableMapOf<String, Pair<DocumentId, YPath>>()
        val sections = ordered.map { p ->
            val ownedKeys = ownedFieldKeys(p.schema.tree, p.root, p.entry.path, p.decl)
            val element = getAt(p.root, p.entry.path) as? YNode.Map
            val declared = p.schema.tree.effectiveFields(p.objectField.fields, element).fields.associateBy { it.key }
            val fields = ownedKeys.map { key ->
                val fieldPath = p.entry.path + key
                val dup = claimed[key]
                if (dup == null) claimed[key] = p.entry.document to fieldPath
                SemanticAspectField(
                    key = key,
                    label = declared[key]?.let { (p.schema.tree.resolve(it) ?: it).displayLabel } ?: key,
                    value = element?.entries?.get(key),
                    document = p.entry.document,
                    path = fieldPath,
                    duplicateOf = dup,
                )
            }
            SemanticAspectSection(
                aspect = p.aspect,
                label = typeDef?.aspectLabel(p.aspect) ?: p.aspect,
                entry = p.entry,
                schemaId = p.schema.id,
                ownsDeclared = p.decl?.owns != null,
                ownedKeys = ownedKeys,
                fields = fields,
            )
        }

        return SemanticEntityComposite(
            id = id,
            entityType = node.entityType,
            typeLabel = typeDef?.displayLabel ?: node.entityType,
            title = node.title,
            defining = node.defining,
            sections = sections,
            unassigned = unassigned,
        )
    }

    /**
     * 書き先解決: 新しい情報（フィールド key）をどの出現に書くべきか＝そのキーを所有する最初のアスペクト。
     * どのアスペクトも所有しないキーは null（呼び出し側が定義出現などへフォールバックする）。
     */
    fun ownerSection(composite: SemanticEntityComposite, key: String): SemanticAspectSection? =
        composite.sections.firstOrNull { key in it.ownedKeys }
}
