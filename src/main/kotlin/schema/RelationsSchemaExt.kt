package schema

import model.YNode
import model.YPath

/*
 * Relations.kt（semantic-core へ移動。3層コア移行 SM-000）の SchemaDef 向けアダプタ。
 * 宣言モデル（EntityDecl / RelationDef 等）と純粋関数は semantic-core にあり、
 * SchemaDef（ルート側の統合型）から使うための委譲だけをここに置く。
 */

/** ID に対応するエンティティ宣言を解決する（idPattern 一致が優先、なければパターンなしの宣言）。 */
fun SchemaDef.entityFor(id: String): EntityDecl? = entityFor(entities, id)

/** 出現1件の所有フィールドキー（R23-01）。実装は semantic-core（SchemaTree 版）へ委譲。 */
fun ownedFieldKeys(schemaDef: SchemaDef, root: YNode, path: YPath, decl: EntityDecl?): List<String> =
    ownedFieldKeys(schemaDef.tree, root, path, decl)
