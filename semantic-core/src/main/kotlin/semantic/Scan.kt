package semantic

import model.DocumentId
import model.YNode
import model.YPath
import model.scalarText
import schema.DisplayCore
import schema.EntityDecl
import schema.EntityRole
import schema.FieldDef
import schema.FieldType
import schema.RefNaming
import schema.RefScope
import schema.RefTarget
import schema.SchemaTree
import schema.entityFor

/**
 * 走査対象の1文書（3層コア移行 Phase 5・SM-001。計画書§4.2の SemanticDocument）。
 * ファイルI/Oを行わず、座標は上位層（workspace-core）が与える不透明な DocumentId とする。
 */
data class SemanticDocument(
    val id: DocumentId,
    val root: YNode,
    /** 適用スキーマの構造コア（null = スキーマなし。命名規約ヒューリスティクスで検出）。 */
    val schema: SchemaTree? = null,
    /** 適用スキーマのID（ScannedId.schemaId / ScannedRef.sourceSchemaId に写す）。 */
    val schemaId: String? = null,
    /** スキーマの entity 宣言（エンティティ型・役割の解決。空 = 宣言なし）。 */
    val entities: List<EntityDecl> = emptyList(),
    /** 文書の論理ID（`_document.id` ＞ 相対パス。上位層が解決して渡す）。 */
    val documentId: String = id.value,
)

/** 走査で見つかった ID 定義1件（座標は DocumentId ＋ YPath。ルート側 IdEntry の Path 非依存版）。 */
data class ScannedId(
    val id: String,
    val document: DocumentId,
    val path: YPath,
    val title: String?,
    val schemaId: String?,
    val documentId: String,
    /** スキーマの entity 宣言から解決したエンティティ型（R20-01。null = 宣言なし）。 */
    val entityType: String? = null,
    /** この出現の役割（定義／詳細化。R20-01。null = 宣言なし）。 */
    val role: EntityRole? = null,
)

/** 走査で見つかった参照1件（属性付き参照では path は to 値の座標。R20-03）。 */
data class ScannedRef(
    val targetId: String,
    val document: DocumentId,
    val path: YPath,
    val viaKey: String,
    val idPatterns: List<String> = emptyList(),
    val scope: RefScope = RefScope(),
    val relation: String? = null,
    val sourceSchemaId: String? = null,
    /** 属性付き参照（R20-03）のスカラー属性の写し（表示注記用）。 */
    val attributes: Map<String, String> = emptyMap(),
    /** 参照の対象種別（R21-03）: エンティティ（既定）／文書。文書参照は別名前空間で解決する。 */
    val targetKind: RefTarget = RefTarget.ENTITY,
) {
    /** 属性の1行注記（例「role: 承認者」）。表示側は括弧に閉じて添える（R20-03）。 */
    fun attributeSummary(): String? =
        attributes.entries.takeIf { it.isNotEmpty() }?.joinToString(", ") { (k, v) -> "$k: $v" }
}

/**
 * 1文書から ID 定義と参照を抽出する走査カーネル（旧 index.DocScanner の Path 非依存版。SM-001）。
 * - スキーマがある場合: type: id / type: ref のフィールドを正として扱う
 * - スキーマがない場合: 命名規約ヒューリスティクス（RefNaming）で検出する
 * - スキーマ外のサブツリー: 未知項目の境界で走査を止める
 * ルート側の index.DocScanner はこのカーネルへの委譲シェル（Path ↔ DocumentId の変換のみ）。
 */
object SemanticScanner {

    fun scan(doc: SemanticDocument): Pair<List<ScannedId>, List<ScannedRef>> {
        val ids = mutableListOf<ScannedId>()
        val refs = mutableListOf<ScannedRef>()
        val schema = doc.schema

        fun walk(node: YNode, path: YPath, def: FieldDef?, keyLabel: String?) {
            val effective = schema?.resolve(def) ?: def
            // スキーマ上 ref と定義された位置のスカラー値は参照として記録
            if (effective?.type == FieldType.REF && node is YNode.Scalar) {
                scalarText(node)?.let {
                    refs += ScannedRef(
                        it, doc.id, path, keyLabel ?: "ref", effective.idPatterns, effective.scope,
                        relation = effective.relation, sourceSchemaId = doc.schemaId,
                        targetKind = effective.refTarget,  // 文書参照（R21-03）は別名前空間として解決される
                    )
                }
                return
            }
            // 属性付き参照（R20-03）: fields 宣言のある ref のオブジェクト形式 {to, 属性...}。
            // path は to 値の座標（ジャンプ・パッチ・バックリンクの既存セマンティクスを保つ）。属性は走査境界。
            if (effective?.type == FieldType.REF && effective.fields.isNotEmpty() && node is YNode.Map) {
                scalarText(node.entries["to"])?.let { target ->
                    val attributes = LinkedHashMap<String, String>()
                    node.entries.forEach { (k, v) ->
                        if (k != "to") scalarText(v)?.let { attributes[k] = it }
                    }
                    refs += ScannedRef(
                        target, doc.id, path + "to", keyLabel ?: "ref", effective.idPatterns, effective.scope,
                        relation = effective.relation, sourceSchemaId = doc.schemaId, attributes = attributes,
                        targetKind = effective.refTarget,
                    )
                }
                return
            }
            when (node) {
                is YNode.Scalar -> Unit
                is YNode.Seq -> {
                    val itemDef = if (effective?.type == FieldType.ARRAY) effective.item else null
                    node.items.forEachIndexed { i, child ->
                        walk(child, path + i, itemDef, keyLabel)
                    }
                }
                is YNode.Map -> {
                    // object の実効フィールド（enum 値に応じた case フィールドを含む）
                    val objectFields = if (schema != null && effective?.type == FieldType.OBJECT) {
                        schema.effectiveFields(effective.fields, node).fields
                    } else null
                    // --- ID の検出 ---
                    val idKeys = LinkedHashSet<String>()
                    objectFields?.filter { (schema?.resolve(it) ?: it).type == FieldType.ID }?.forEach { it.key?.let(idKeys::add) }
                    // スキーマ適用中は、明示的に type: id とされた項目だけを索引化する。
                    if (schema == null) idKeys.add("id")
                    for (k in idKeys) {
                        val idText = scalarText(node.entries[k]) ?: continue
                        // タイトルは表示解決（R10-01）に一元化する: スキーマの role 指定 ＞ 予約語（title/name/label）
                        val title = DisplayCore.titleOf(node, schema, effective)
                        // エンティティ型と役割（R20-01）: スキーマの entity 宣言と idPattern で走査時に解決する（§4.30）
                        val decl = entityFor(doc.entities, idText)
                        ids += ScannedId(
                            idText, doc.id, path, title, doc.schemaId, doc.documentId,
                            entityType = decl?.type,
                            role = decl?.let { if (it.defines) EntityRole.DEFINING else EntityRole.DETAIL },
                        )
                        break
                    }
                    // --- 子要素の走査と参照検出 ---
                    for ((k, child) in node.entries) {
                        // ルートの予約メタデータは業務要素の ID／参照として索引化しない。
                        if (path.isEmpty() && k == "_document") continue
                        val childDef = when {
                            objectFields != null -> objectFields.firstOrNull { it.key == k }
                            effective?.type == FieldType.MAP -> effective.value
                            else -> null
                        }
                        val childPath = path + k
                        // スキーマ外サブツリーは未知項目の境界で止め、ID・参照を索引化しない。
                        if (schema != null && childDef == null) continue
                        if (childDef == null) {
                            // スキーマなし文書ではヒューリスティクスで参照を検出する。
                            if (RefNaming.isRefKey(k) && child is YNode.Scalar) {
                                scalarText(child)?.let { refs += ScannedRef(it, doc.id, childPath, k) }
                            } else if (RefNaming.isRefListKey(k) && child is YNode.Seq) {
                                child.items.forEachIndexed { i, c ->
                                    if (c is YNode.Scalar) {
                                        scalarText(c)?.let { refs += ScannedRef(it, doc.id, childPath + i, k) }
                                    }
                                }
                            }
                        }
                        walk(child, childPath, childDef, k)
                    }
                }
            }
        }

        walk(doc.root, emptyList(), schema?.root, null)
        return ids to refs
    }
}
