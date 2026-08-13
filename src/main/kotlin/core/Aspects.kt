package core

import index.IdEntry
import index.IdIndex
import model.YNode
import model.YPath
import model.getAt
import schema.EntityDecl
import schema.EntityTypeDef
import schema.FieldDef
import schema.FieldType
import schema.SchemaDef
import schema.effectiveFields
import schema.entityFor
import schema.fieldAt
import schema.ownedFieldKeys
import java.nio.file.Path

/**
 * エンティティ合成（R23-01。ARCHITECTURE §4.33）: 同一論理IDの全出現を
 * 「定義出現 → レジストリのアスペクト宣言順」に並べ、所有フィールドを座標つきでマージした
 * 「エンティティ全体像」を決定的に構築する純ロジック。
 * エンティティページ（UI）・`read_entity`（エージェント）・CLI `entity show` が同じ合成を消費する
 * （人間とAIが同じ「エンティティの1枚」を見る共有契約。Display §4.22 と同じ流儀）。
 * 値の読み取りは loader 注入（UI: docCache 優先／エージェント: ステージング／CLI: ディスク。§4.5）。
 */

/** 合成された所有フィールド1件。座標（file, path）は原本上の値の位置（＝編集・パッチの対象）。 */
data class AspectField(
    val key: String,
    val label: String,
    /** loader のツリーから読んだ現在値（未記入は null）。 */
    val value: YNode?,
    val file: Path,
    val path: YPath,
    /** 先行するアスペクトが同じキーを所有している場合、その座標（重複所有の注記。値は隠さない）。 */
    val duplicateOf: Pair<Path, YPath>? = null,
)

/** 合成の1区分（アスペクト）。1出現に対応する。 */
data class AspectSection(
    val aspect: String,
    /** 表示名（レジストリ entities 節の aspects.label ＞ アスペクト名）。 */
    val label: String,
    val entry: IdEntry,
    val schemaId: String?,
    /** この出現の entity 宣言が明示的な owns を持つか（重複所有の判定条件）。 */
    val ownsDeclared: Boolean,
    val ownedKeys: List<String>,
    val fields: List<AspectField>,
)

/** エンティティ全体像（R23-01）。sections の並びは決定的（§4.33）。 */
data class EntityComposite(
    val id: String,
    val entityType: String?,
    /** 型の表示名（レジストリ entities 節の label ＞ 型名）。 */
    val typeLabel: String?,
    val title: String?,
    val defining: IdEntry?,
    val sections: List<AspectSection>,
    /** 合成に載らない出現（スキーマなし・object に解決できない）。UIは従来の出現カードで表示する。 */
    val unassigned: List<IdEntry>,
)

object Aspects {

    /** ID の全出現からエンティティ全体像を合成する。ID が存在しなければ null。 */
    fun compose(
        id: String,
        index: IdIndex,
        schemas: List<SchemaDef>,
        entityTypes: Map<String, EntityTypeDef>,
        loader: (Path) -> YNode?,
    ): EntityComposite? {
        val node = index.graph.entities[id] ?: return null
        val schemasById = schemas.associateBy { it.id }
        val typeDef = node.entityType?.let { entityTypes[it] }

        data class Prepared(
            val entry: IdEntry,
            val schema: SchemaDef,
            val root: YNode,
            val decl: EntityDecl?,
            val aspect: String,
            val objectField: FieldDef,
        )

        val trees = mutableMapOf<Path, YNode?>()
        fun tree(f: Path): YNode? = trees.getOrPut(f) { loader(f) }

        val prepared = mutableListOf<Prepared>()
        val unassigned = mutableListOf<IdEntry>()
        for (entry in node.occurrences) {
            val sc = entry.schemaId?.let { schemasById[it] }
            val root = tree(entry.file)
            val objectField = if (sc != null && root != null) {
                sc.fieldAt(root, entry.path)?.takeIf { it.type == FieldType.OBJECT }
            } else null
            if (sc == null || root == null || objectField == null) {
                unassigned += entry
                continue
            }
            val decl = sc.entityFor(id)
            prepared += Prepared(entry, sc, root, decl, decl?.aspect ?: sc.id, objectField)
        }

        // 並び: 定義出現 → レジストリのアスペクト宣言順 → ファイル → 要素パス → アスペクト名（決定的。§4.33）。
        // 宣言のないスキーマ群はスキーマIDのアルファベット順でなくファイルパス順に落とす
        // （番号付きディレクトリの流れ＝概念→詳細の順に乗せ、detail が先頭に来るのを防ぐ。UX-80）
        val ordered = prepared.sortedWith(
            compareBy(
                { if (it.entry == node.defining) 0 else 1 },
                { typeDef?.aspectOrder(it.aspect) ?: Int.MAX_VALUE },
                { it.entry.file.toString() },
                { it.entry.path.joinToString("/") },
                { it.aspect },
            )
        )

        // 所有フィールドを座標つきでマージする。重複所有は隠さず duplicateOf で注記する（§4.33）
        val claimed = mutableMapOf<String, Pair<Path, YPath>>()
        val sections = ordered.map { p ->
            val ownedKeys = ownedFieldKeys(p.schema, p.root, p.entry.path, p.decl)
            val element = getAt(p.root, p.entry.path) as? YNode.Map
            val declared = p.schema.effectiveFields(p.objectField.fields, element).fields.associateBy { it.key }
            val fields = ownedKeys.map { key ->
                val fieldPath = p.entry.path + key
                val dup = claimed[key]
                if (dup == null) claimed[key] = p.entry.file to fieldPath
                AspectField(
                    key = key,
                    label = declared[key]?.let { (p.schema.resolve(it) ?: it).displayLabel } ?: key,
                    value = element?.entries?.get(key),
                    file = p.entry.file,
                    path = fieldPath,
                    duplicateOf = dup,
                )
            }
            AspectSection(
                aspect = p.aspect,
                label = typeDef?.aspectLabel(p.aspect) ?: p.aspect,
                entry = p.entry,
                schemaId = p.schema.id,
                ownsDeclared = p.decl?.owns != null,
                ownedKeys = ownedKeys,
                fields = fields,
            )
        }

        return EntityComposite(
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
    fun ownerSection(composite: EntityComposite, key: String): AspectSection? =
        composite.sections.firstOrNull { key in it.ownedKeys }
}
