package core

import index.IdEntry
import index.IdIndex
import index.toIdEntry
import index.toKernelGraph
import model.YNode
import model.YPath
import schema.EntityTypeDef
import schema.SchemaDef
import semantic.SchemaSemantics
import semantic.SemanticAspectField
import semantic.SemanticAspects
import java.nio.file.Path

/**
 * エンティティ合成（R23-01。ARCHITECTURE §4.33）のディスク索引向けシェル。
 * 合成アルゴリズムの本体は semantic-core の SemanticAspects（3層コア移行 Phase 6B）で、
 * ここは IdIndex / SchemaDef をカーネル入力（DocumentId 座標・SchemaSemantics）へ写して委譲し、
 * 結果を従来の Path 座標型へ写し戻す。
 * エンティティページ（UI）・`read_entity`（エージェント）・CLI `entity show` が同じ合成を消費する
 * （人間とAIが同じ「エンティティの1枚」を見る共有契約）。
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
        val graph = index.toKernelGraph()
        val semantics = schemas.associate { it.id to SchemaSemantics(it.id, it.tree, it.entities) }
        val composite = SemanticAspects.compose(id, graph, semantics, entityTypes) { d -> loader(Path.of(d.value)) }
            ?: return null

        fun field(f: SemanticAspectField) = AspectField(
            key = f.key,
            label = f.label,
            value = f.value,
            file = Path.of(f.document.value),
            path = f.path,
            duplicateOf = f.duplicateOf?.let { (d, p) -> Path.of(d.value) to p },
        )

        return EntityComposite(
            id = composite.id,
            entityType = composite.entityType,
            typeLabel = composite.typeLabel,
            title = composite.title,
            defining = composite.defining?.toIdEntry(),
            sections = composite.sections.map { s ->
                AspectSection(s.aspect, s.label, s.entry.toIdEntry(), s.schemaId, s.ownsDeclared, s.ownedKeys, s.fields.map(::field))
            },
            unassigned = composite.unassigned.map { it.toIdEntry() },
        )
    }

    /**
     * 書き先解決: 新しい情報（フィールド key）をどの出現に書くべきか＝そのキーを所有する最初のアスペクト。
     * どのアスペクトも所有しないキーは null（呼び出し側が定義出現などへフォールバックする）。
     */
    fun ownerSection(composite: EntityComposite, key: String): AspectSection? =
        composite.sections.firstOrNull { key in it.ownedKeys }
}
