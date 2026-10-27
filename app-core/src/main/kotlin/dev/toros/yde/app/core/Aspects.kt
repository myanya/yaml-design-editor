package dev.toros.yde.app.core

import dev.toros.yde.app.index.IdIndex
import dev.toros.yde.semantic.SemanticAspectField
import dev.toros.yde.semantic.SemanticAspectSection
import dev.toros.yde.semantic.SemanticEntityComposite
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.semantic.schema.EntityTypeDef
import dev.toros.yde.app.schema.SchemaDef
import dev.toros.yde.semantic.ScannedId
import dev.toros.yde.semantic.SchemaSemantics
import dev.toros.yde.semantic.SemanticAspects
import dev.toros.yde.semantic.schema.entityFor

/**
 * エンティティ合成（R23-01。architecture/graph-query.md「エンティティページ」）を、索引（IdIndex）とアプリ側のスキーマの型（SchemaDef）から呼ぶ窓口。
 * 合成アルゴリズムの本体は semantic-core の SemanticAspects（3層コア移行 Phase 6B）。結果の型も、カーネルの型の別名
 * （P27 段D の D-d・D-e。以前は、ファイルの場所を Path で持つ同じ形の型を root にも持ち、合成のたびに写していた）。
 * エンティティページ（UI）・`read_entity`（エージェント）・CLI `entity show` が同じ合成を消費する
 * （人間とAIが同じ「エンティティの1枚」を見る共有契約）。
 * 値の読み取りは loader 注入（UI: docCache 優先／エージェント: ステージング／CLI: ディスク。architecture/index-references.md「索引の二層」）。
 */

/** 合成された所有フィールド1件。場所（document, path）は原本上の値の位置（＝編集・パッチの対象）。 */
typealias AspectField = SemanticAspectField

/** 合成の1区分（アスペクト）。1出現に対応する。 */
typealias AspectSection = SemanticAspectSection

/** エンティティ全体像（R23-01）。sections の並びは決定的（architecture/graph-query.md「エンティティページ」）。 */
typealias EntityComposite = SemanticEntityComposite

object Aspects {

    /** ID の全出現からエンティティ全体像を合成する。ID が存在しなければ null。 */
    fun compose(
        id: String,
        index: IdIndex,
        schemas: List<SchemaDef>,
        entityTypes: Map<String, EntityTypeDef>,
        loader: (DocumentId) -> YNode?,
    ): EntityComposite? {
        val semantics = schemas.associate { it.id to SchemaSemantics(it.id, it.tree, it.entities) }
        return SemanticAspects.compose(id, index.graph, semantics, entityTypes, loader)
    }

    /**
     * 書き先解決: 新しい情報（フィールド key）をどの出現に書くべきか＝そのキーを所有する最初のアスペクト。
     * どのアスペクトも所有しないキーは null（呼び出し側が定義出現などへフォールバックする）。
     */
    fun ownerSection(composite: EntityComposite, key: String): AspectSection? =
        composite.sections.firstOrNull { key in it.ownedKeys }

    /**
     * 出現 1 つの側面の表示名（レジストリの aspects の label ＞ スキーマの entity.aspect ＞ スキーマ ID）。
     * 合成の見出しと同じ決まりで、関係パネルの「所属」とレンズの出現の箱が使う。
     */
    fun aspectLabelOf(entry: ScannedId, schemas: List<SchemaDef>, entityTypes: Map<String, EntityTypeDef>): String {
        val schema = schemas.firstOrNull { it.id == entry.schemaId }
        val aspect = schema?.let { sc -> entityFor(sc.entities, entry.id)?.aspect ?: sc.id } ?: (entry.schemaId ?: "スキーマなし")
        return entry.entityType?.let { entityTypes[it]?.aspectLabel(aspect) } ?: aspect
    }
}
