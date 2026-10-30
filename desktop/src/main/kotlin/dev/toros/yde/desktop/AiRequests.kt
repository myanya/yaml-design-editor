package dev.toros.yde.desktop

import dev.toros.yde.app.schema.SchemaDef
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import java.nio.file.Path

// AI 生成の要求を組み立てる（画面を持たない）。要求は AppState.requestAi に置き、ダイアログは ui/run/AiGenerationDialog.kt の
// AiGenerationHost が出す。ファイル一覧・ビューグリッド・エンティティページはここを呼び、ui.run を import しない
// （architecture/ui.md「画面のパッケージ」）。

/**
 * マージ生成（出現単位・文書たたき台）の生成対象フィールド（R24-02 / R24-03）:
 * object 定義を、生成してよいキーへ選別した複製として返す。ownedKeys 指定時（アスペクトカード）は
 * 所有キーに限定し、`type: id` のフィールドは常に除外する（IDの改変は参照書き換えを伴うため、
 * エンティティ操作 R21-02 の領分——生成では触らない）。label はダイアログのタイトル・既定指示・
 * プロンプトの「生成対象」にそのまま出る利用者向けの呼び名（例「REQ-001 の要件定義」）。
 * 生成できるフィールドが残らなければ null（ボタンを出さない）。
 */
internal fun mergeGenField(
    schema: SchemaDef,
    objectField: FieldDef,
    ownedKeys: List<String>?,
    label: String,
): FieldDef? {
    val resolved = schema.resolve(objectField) ?: objectField
    if (resolved.type != FieldType.OBJECT) return null
    val fields = resolved.fields.filter { f ->
        val key = f.key ?: return@filter false
        (ownedKeys == null || key in ownedKeys) && (schema.resolve(f) ?: f).type != FieldType.ID
    }
    if (fields.isEmpty()) return null
    return resolved.copy(fields = fields, hints = resolved.hints.copy(label = label))
}

/**
 * 新規作成した文書のたたき台生成を要求する（R24-03）: ルート object を id 型フィールド抜きの
 * キー単位マージで生成する（スキャフォールドが設定したID・`_document` メタデータを生成が壊さない）。
 * entityId があれば関連文書（そのIDの出現・参照先・参照元）も内容の文脈として付く。
 * スキーマが適用されない等で生成できない場合は false を返す（呼び出し側がステータスで案内する）。
 */
internal fun requestDocumentDraft(state: AppState, file: Path, entityId: String? = null): Boolean {
    val doc = state.ensureDoc(file)
    val docSchema = doc.schema ?: return false
    val label = entityId?.let { "$it の詳細文書" } ?: "この文書"
    val genField = mergeGenField(docSchema, docSchema.root, ownedKeys = null, label = label) ?: return false
    val related = entityId?.let { state.entityRelatedFiles(it, file) }.orEmpty()
    state.requestAi(doc, genField, emptyList(), relatedFiles = related, mergeObjectKeys = true)
    return true
}
