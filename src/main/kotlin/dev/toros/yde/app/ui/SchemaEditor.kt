package dev.toros.yde.app.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.OpenDocument
import dev.toros.yde.app.AppState
import dev.toros.yde.semantic.RefactoringPlan
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.semantic.schema.AutoValue
import dev.toros.yde.semantic.schema.BuiltinTypes
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.SchemaTree
import dev.toros.yde.app.schema.SchemaDef
import dev.toros.yde.semantic.schema.SchemaKind
import dev.toros.yde.app.schema.SchemaLoader
import dev.toros.yde.app.schema.generateSample
import dev.toros.yde.app.schema.validateDefinition
import dev.toros.yde.workspace.SchemaIssue
import dev.toros.yde.yaml.YamlIO

/** `_schemas` 用の構造化エディタ。YAML の表現を意識せずフィールド定義を編集できる。 */
@Composable
fun SchemaEditor(state: AppState, doc: OpenDocument) {
    // 入力途中の文字・ダイアログ・解析キャッシュは文書に閉じる。
    // version を鍵にすると入力のたびにフォーカスまで失うため、文書の同一性だけで区切る。
    key(doc) {
        SchemaEditorContent(state, doc)
    }
}

@Composable
private fun SchemaEditorContent(state: AppState, doc: OpenDocument) {
    doc.version
    val root = doc.root as? YNode.Map
    if (root == null) {
        Text("スキーマのルートはオブジェクトである必要があります", color = Palette.ErrorRed,
            modifier = Modifier.padding(16.dp))
        return
    }
    val schemaResult = remember(doc.version, state.schemas) {
        runCatching { SchemaLoader.parseSchemaText(doc.canonicalText(), doc.file, state.schemas) }
    }
    val schema = schemaResult.getOrNull()
    val issues = remember(schema, doc.version) { schema?.validateDefinition().orEmpty() }
    // 型の選択肢（P33）: 基底の種類のほかに、この文書の definitions のスカラーの定義・組込みの型・他モジュールのスカラーの定義
    val typeChoices = remember(doc.version, state.schemas, schema?.id) { TypeChoices.of(root, state, schema?.id) }
    val library = scalar(root, "kind") == SchemaKind.LIBRARY.id
    // タブ（設計・プレビュー・適用対象）は画面の覚え（文書ごと。離れても残る）
    val schemaKey = state.documentOf(doc.file)
    val mode = state.memory.schemaTab[schemaKey] ?: 0
    // 一括改名・一括削除の対象（スキーマID × ドット区切りフィールドパス。R25-01 / R25-03）と整列（R25-02）
    var renameFieldTarget by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    renameFieldTarget?.let { (schemaId, path) ->
        SchemaFieldRenameDialog(state, doc, schemaId, path) { renameFieldTarget = null }
    }
    var deleteFieldTarget by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    deleteFieldTarget?.let { (schemaId, path) ->
        SchemaRefactorConfirmDialog(
            state, doc,
            title = "フィールドを一括削除",
            header = "スキーマ「$schemaId」のフィールド「${path.joinToString(".")}」を、全該当文書のデータごと削除します",
            planner = { state.planSchemaFieldDelete(schemaId, path.joinToString(".")) },
        ) { deleteFieldTarget = null }
    }
    var alignOrderTarget by remember { mutableStateOf<String?>(null) }
    alignOrderTarget?.let { schemaId ->
        SchemaRefactorConfirmDialog(
            state, doc,
            title = "文書をスキーマ順へ整列",
            header = "スキーマ「$schemaId」の全該当文書のキー順を、スキーマのフィールド順に並べ替えます（値・コメントは保持）",
            planner = { state.planSchemaAlignOrder(schemaId) },
        ) { alignOrderTarget = null }
    }
    Column(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalTypeChoices provides typeChoices,
            LocalSchemaAuthoring provides (mode == 0),
            LocalSchemaReadOnly provides (state.readOnlyMode || doc.draftBroken),
            LocalSchemaHistory provides state.historyHandler(doc)) { when (mode) {
            0 -> SchemaDesignWorkspace(
                state, doc, root, library, schema, typeChoices,
                problems = listOfNotNull(schemaResult.exceptionOrNull()?.let { "スキーマを解析できません: ${it.message}" }) +
                    issues.map { "${it.path}: ${it.message}" },
                onRename = schema?.let { s -> { path -> renameFieldTarget = s.id to path } },
                onDelete = schema?.let { s -> { path -> deleteFieldTarget = s.id to path } },
                onAlign = { schema?.let { alignOrderTarget = it.id } },
            )
            1 -> SchemaPreview(state, doc, schema, issues)
            else -> SchemaTargets(state, schema)
        } }
    }
}

/**
 * 設計 UI の型の選択肢（P33）: 基底の種類のほかに型の名前（この文書の definitions のスカラーの定義・組込みの型・
 * 他モジュールのスカラーの定義）。型の名前には基底の種類を添え、書ける決まりの入力欄はその基底の種類で出す。
 */
internal class TypeChoices(val names: List<String>, private val bases: Map<String, String>) {
    /** 型の名前 → 基底の種類（分からなければ string）。基底の種類はそのまま。 */
    fun baseKindOf(typeId: String): String = if (FieldType.isBaseKind(typeId)) typeId else bases[typeId] ?: FieldType.STRING.id

    companion object {
        val EMPTY = TypeChoices(emptyList(), emptyMap())

        fun of(root: YNode.Map, state: AppState, ownId: String?): TypeChoices {
            val bases = LinkedHashMap<String, String>()
            val builtinTree = SchemaTree(BuiltinTypes.MODULE_ID, FieldDef(type = FieldType.OBJECT), BuiltinTypes.definitions)
            BuiltinTypes.definitions.forEach { (name, def) ->
                val resolved = builtinTree.resolve(def) ?: def
                bases[name] = resolved.type.id
            }
            // 他のモジュールの定義（スカラーの型と、オブジェクト型などの入れ物の定義。P33 第三弾で入れ物も type: 名前 で使える）
            state.schemas.filter { it.id != ownId }.forEach { module ->
                module.definitions.forEach { (name, def) ->
                    val resolved = module.tree.resolve(def) ?: def
                    bases["${module.id}#$name"] = resolved.type.id
                }
            }
            // この文書の definitions は保存前の内容から（type の連鎖を辿る。$ref で書いた定義は、参照先の基底の種類）
            val definitions = root.entries["definitions"] as? YNode.Map
            fun rawBase(name: String, depth: Int): String? {
                if (depth > 16) return null
                val m = definitions?.entries?.get(name) as? YNode.Map ?: return null
                val t = scalar(m, "type") ?: scalar(m, "\$ref") ?: scalar(m, "ref") ?: FieldType.STRING.id
                return when {
                    FieldType.isBaseKind(t) -> t
                    definitions?.entries?.containsKey(t) == true -> rawBase(t, depth + 1)
                    else -> bases[t]
                }
            }
            definitions?.entries?.keys?.toList()?.forEach { name -> rawBase(name, 0)?.let { bases[name] = it } }
            return TypeChoices(bases.keys.toList(), bases)
        }
    }
}

internal val LocalTypeChoices = compositionLocalOf { TypeChoices.EMPTY }

@Composable
private fun SchemaProblem(message: String) {
    Text("⚠ $message", fontSize = 11.sp, color = Palette.ErrorRed,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
}

@Composable
internal fun SchemaKindEditor(map: YNode.Map, doc: OpenDocument, library: Boolean) {
    var confirmLibrary by remember { mutableStateOf(false) }
    SchemaSettingRow("スキーマ種別") {
        ChoiceButton(if (library) "library" else "document", listOf("document", "library")) { selected ->
            when {
                selected == "library" && !library -> confirmLibrary = true
                selected == "document" && library -> {
                    map.entries["kind"] = YNode.Scalar("document")
                    map.entries.putIfAbsent("match", YNode.Scalar("**/*.{yaml,yml}"))
                    map.entries.putIfAbsent(
                        "root",
                        YNode.Map(linkedMapOf(
                            "type" to YNode.Scalar("object"),
                            "fields" to YNode.Seq(),
                        )),
                    )
                    doc.markEdited()
                }
            }
        }
    }
    if (confirmLibrary) AlertDialog(
        onDismissRequest = { confirmLibrary = false },
        title = { Text("共通定義ライブラリへ変更") },
        text = { Text("適用パターン・ルート定義・フェーズ・カバレッジを削除し、definitions だけを公開します。") },
        confirmButton = {
            TextButton(onClick = {
                map.entries["kind"] = YNode.Scalar("library")
                listOf("match", "root", "phases", "coverage").forEach(map.entries::remove)
                doc.markEdited()
                confirmLibrary = false
            }) { Text("libraryへ変更", color = Palette.ErrorRed) }
        },
        dismissButton = { TextButton(onClick = { confirmLibrary = false }) { Text("キャンセル") } },
    )
}

@Composable
private fun SchemaPreview(state: AppState, sourceDoc: OpenDocument, schema: SchemaDef?, issues: List<SchemaIssue>) {
    if (schema?.kind == SchemaKind.LIBRARY) {
        Column(Modifier.padding(16.dp)) {
            Text("共通定義ライブラリ", fontWeight = FontWeight.Bold)
            Text("library はデータ文書へ適用されないため、サンプルプレビューはありません。", fontSize = 12.sp, color = Palette.SubText)
        }
        return
    }
    if (schema == null || issues.isNotEmpty()) {
        Column(Modifier.padding(16.dp)) {
            Text("プレビューを生成できません", fontWeight = FontWeight.Bold, color = Palette.ErrorRed)
            Text("設計タブでスキーマの問題を修正してください。", fontSize = 12.sp, color = Palette.SubText)
        }
        return
    }
    val sample = remember(schema, sourceDoc.version) { schema.generateSample() }
    val sampleText = remember(sample) { YamlIO.dump(sample) }
    val previewDoc = remember(schema, sampleText) { OpenDocument(sourceDoc.file, sample, schema, sampleText, null) }
    var source by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("サンプルデータ", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (!source) {
                val document = state.documentOf(sourceDoc.file)
                FormDisplayControls(state.memory.formDisplay, document, state.memory.navigation(document, false))
            }
            TextButton(onClick = { source = false }) { Text("フォーム", fontSize = 11.sp) }
            TextButton(onClick = { source = true }) { Text("YAML", fontSize = 11.sp) }
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(sampleText)) },
                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 2.dp)) { Text("YAMLをコピー", fontSize = 11.sp) }
        }
        if (source) {
            val scroll = rememberScrollState()
            Text(sampleText, fontFamily = AppFonts.Mono, fontSize = 12.sp, color = Palette.Text,
                modifier = Modifier.fillMaxSize().verticalScroll(scroll).background(Palette.EditorBg).padding(12.dp))
        } else Box(Modifier.weight(1f).fillMaxWidth()) { FormView(state, previewDoc) }
    }
}

@Composable
private fun SchemaTargets(state: AppState, schema: SchemaDef?) {
    if (schema == null) {
        Text("スキーマを解析できないため適用対象を確認できません", color = Palette.ErrorRed, modifier = Modifier.padding(16.dp))
        return
    }
    if (schema.kind == SchemaKind.LIBRARY) {
        Column(Modifier.padding(16.dp)) {
            Text("共通定義ライブラリ", fontWeight = FontWeight.Bold)
            Text("業務文書には直接適用されません。公開定義: ${schema.definitions.size} 件", fontSize = 12.sp, color = Palette.SubText)
        }
        return
    }
    val targets = remember(schema, state.yamlFiles) {
        state.yamlFiles.filter { !state.relPath(it).startsWith("_schemas/") && schema.matches(state.relPath(it)) }
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("適用パターン: ${schema.match}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("${targets.size} ファイルに適用されます", fontSize = 11.sp, color = Palette.SubText)
        Spacer(Modifier.height(8.dp))
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            targets.forEach { file ->
                val rel = state.relPath(file)
                val overlaps = state.schemas.filter { it.source != schema.source && it.matches(rel) }
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(rel, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    if (overlaps.isNotEmpty()) Text("競合: ${overlaps.joinToString { it.name }}",
                        fontSize = 10.sp, color = Palette.WarnOrange)
                }
            }
            if (targets.isEmpty()) Text("一致するファイルはありません", fontSize = 12.sp, color = Palette.WarnOrange)
        }
    }
}

@Composable
internal fun CompactScalar(map: YNode.Map, key: String, label: String, doc: OpenDocument) {
    doc.version  // 可変の木を読む子自身でも購読し、入力欄へ最新値を渡す。
    val value = (map.entries[key] as? YNode.Scalar)?.value?.toString().orEmpty()
    FormFieldLayout(modifier = Modifier.padding(vertical = FormSpacing.FieldGap), label = {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
    }) {
        // 連続入力は履歴1エントリへ合体（UX-11。キー=対象Map×キー名）
        SchemaTextField(value, { map.entries[key] = YNode.Scalar(it); doc.markEdited(coalesceWith = map to key) },
            singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
            modifier = Modifier.fillMaxWidth())
    }
}

/**
 * 数値制約の入力（R16-01 / UX-73）: 空欄で項目削除、数値として書き込む
 * （数値でない入力は文字列のまま書き、メタスキーマ検証が型不適合として指摘する）。
 */
@Composable
private fun CompactNumberScalar(map: YNode.Map, key: String, label: String, doc: OpenDocument, integer: Boolean) {
    doc.version
    val value = (map.entries[key] as? YNode.Scalar)?.value?.toString().orEmpty()
    FormFieldLayout(modifier = Modifier.padding(vertical = FormSpacing.FieldGap), label = {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
    }) {
        SchemaTextField(value, { raw ->
            if (raw.isBlank()) map.entries.remove(key) else {
                val typed: Any = if (integer) raw.toLongOrNull() ?: raw else raw.toDoubleOrNull() ?: raw
                map.entries[key] = YNode.Scalar(typed)
            }
            doc.markEdited(coalesceWith = map to key)
        }, singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
            placeholder = { Text("未設定") },
            modifier = Modifier.fillMaxWidth())
    }
}

/** 空欄で項目自体を削除する任意スカラー入力（pattern 等、空文字を残すと意味が変わるキー用）。 */
@Composable
private fun CompactOptionalScalar(map: YNode.Map, key: String, label: String, hint: String, doc: OpenDocument) {
    doc.version
    val value = (map.entries[key] as? YNode.Scalar)?.value?.toString().orEmpty()
    FormFieldLayout(modifier = Modifier.padding(vertical = FormSpacing.FieldGap), label = {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
    }) {
        SchemaTextField(value, {
            if (it.isBlank()) map.entries.remove(key) else map.entries[key] = YNode.Scalar(it)
            doc.markEdited(coalesceWith = map to key)
        }, singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
            placeholder = { Text(hint) },
            modifier = Modifier.fillMaxWidth())
    }
}

@Composable
internal fun CompactMultilineScalar(map: YNode.Map, key: String, label: String, doc: OpenDocument) {
    doc.version
    val value = (map.entries[key] as? YNode.Scalar)?.value?.toString().orEmpty()
    FormFieldLayout(modifier = Modifier.padding(vertical = FormSpacing.FieldGap), label = {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
    }) {
        SchemaTextField(
            value = value,
            onValueChange = { map.entries[key] = YNode.Scalar(it); doc.markEdited(coalesceWith = map to key) },
            singleLine = false,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        )
    }
}

@Composable
internal fun AddDefinitionButton(definitions: YNode.Map, doc: OpenDocument, compact: Boolean = false, onAdded: (String) -> Unit = {}) {
    var open by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    // 定義の種類（P33）: 構造（object。$ref で使う）か、型（スカラーの決まりの束。項目の type に名前で使う）
    var kind by remember { mutableStateOf(DEFINITION_KIND_TYPE) }
    SchemaEditButton(onClick = { open = true }) { Text(if (compact) "＋ 追加" else "＋ 定義を追加", fontSize = 12.sp) }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("名前付き定義を追加") },
        text = {
            Column {
                SchemaTextField(name, { name = it }, singleLine = true, placeholder = { Text("定義名") },
                    clearFocusOnEscape = false, workspaceHistory = false)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("種類", fontSize = 12.sp, modifier = Modifier.width(60.dp))
                    ChoiceButton(kind, listOf(DEFINITION_KIND_TYPE, DEFINITION_KIND_STRUCTURE)) { kind = it }
                }
                Text(
                    if (kind == DEFINITION_KIND_TYPE) "値の型: string や int などの基底の種類に決まり（形式・範囲・選択肢）を足した定義。項目の型に名前で選んで使います（決まりは狭めるだけ）"
                    else "オブジェクト型: object の定義。同一性（どのエンティティ型か）を持たせられ、項目の型に名前で選んで使い、使う側で項目を足す・同じ項目を狭めることができます",
                    fontSize = 10.sp, color = Palette.SubText,
                )
            }
        },
        confirmButton = { SchemaEditButton(enabled = name.isNotBlank() && name.trim() !in definitions.entries, onClick = {
            definitions.entries[name.trim()] = newFieldMap(if (kind == DEFINITION_KIND_STRUCTURE) "object" else "string")
            onAdded(name.trim()); doc.markEdited(); open = false; name = ""
        }) { Text("追加") } },
        dismissButton = { SchemaEditButton(onClick = { open = false }) { Text("キャンセル") } },
    )
}

private const val DEFINITION_KIND_TYPE = "値の型（スカラー）"
private const val DEFINITION_KIND_STRUCTURE = "オブジェクト型（object）"

/**
 * object の同一性（entity。オブジェクト型。P33 第三弾）の編集: この object の要素がどのエンティティ型か。
 * definitionName は名前付きの定義の最上位（entity.type を省くと定義名）。
 */
@Composable
private fun ObjectEntityEditor(map: YNode.Map, doc: OpenDocument, definitionName: String?) {
    doc.version
    val entity = map.entries["entity"] as? YNode.Map
    SchemaSettingRow("同一性（entity）") {
        SchemaCheckbox(entity != null, { on ->
            if (on) map.entries["entity"] = YNode.Map() else map.entries.remove("entity")
            doc.markEdited()
        })
        Text(
            if (entity == null) "この object の要素がどのエンティティ型かを宣言する（ID の形で見分けなくてよくなる。型が同一性を持つなら不要）"
            else "要素の型で同一性が決まる（文書スキーマの entity より優先）",
            fontSize = 10.sp, color = Palette.SubText,
        )
    }
    if (entity != null) {
        Column(Modifier.padding(start = 12.dp)) {
            CompactOptionalScalar(
                entity, "type", "エンティティ型名",
                if (definitionName != null) "省略すると定義名「$definitionName」" else "必須（例 screen-item）", doc,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val defines = (entity.entries["defines"] as? YNode.Scalar)?.value == true
                SchemaCheckbox(defines, { if (it) entity.entries["defines"] = YNode.Scalar(true) else entity.entries.remove("defines"); doc.markEdited() })
                Text("この要素の出現が定義（誕生）", fontSize = 11.sp)
            }
            CompactOptionalScalar(entity, "aspect", "側面名（省略時はスキーマID）", "例 detail", doc)
            StringListEditor(entity, "owns", "所有する項目（省略時は宣言した項目全部）", "status, summary", doc)
        }
    }
}

@Composable
internal fun FieldDefinitionEditor(
    map: YNode.Map,
    doc: OpenDocument,
    definitions: List<String>,
    allowKey: Boolean,
    ctx: FormCtx,
    path: dev.toros.yde.yaml.model.YPath,
    /** 名前付きの定義の最上位なら定義名（object の entity.type の既定の案内に使う）。 */
    definitionName: String? = null,
) {
    doc.version  // CONVENTIONS §2 / UX-24
    val ref = scalar(map, "\$ref") ?: scalar(map, "ref")
    if (allowKey) CompactScalar(map, "key", "キー", doc)
    if (allowKey) CompactScalar(map, "label", "ラベル", doc)
    // 型の選択肢は基底の種類＋型の名前（P33）。書ける決まりの入力欄は、型の名前ならその基底の種類で出す
    val types = LocalTypeChoices.current
    val currentTypeId = scalar(map, "type") ?: FieldType.STRING.id
    var pendingType by remember(map) { mutableStateOf<String?>(null) }
    fun removedByType(type: String): List<String> {
        val base = types.baseKindOf(type)
        return listOfNotNull(
            "fields".takeIf { base != "object" }, "unknownFields".takeIf { base != "object" },
            "item".takeIf { base != "array" }, "display".takeIf { base != "array" && base != "map" },
            "value".takeIf { base != "map" }, "cases".takeIf { base != "enum" },
        ).filter { it in map.entries }
    }
    fun changeType(type: String) {
        removedByType(type).forEach(map.entries::remove)
        MapSlot(map, "type").set(YNode.Scalar(type))
        ctx.markEdited()
    }
    pendingType?.let { type ->
        AlertDialog(onDismissRequest = { pendingType = null }, title = { Text("型を $type に変更") },
            text = { Text("この型で使用しない設定（${removedByType(type).joinToString()}）を削除します。") },
            confirmButton = { SchemaEditButton(onClick = { changeType(type); pendingType = null }) {
                Text("型を変更", color = Palette.ErrorRed)
            } }, dismissButton = { TextButton(onClick = { pendingType = null }) { Text("キャンセル") } })
    }
    if (ref == null) {
        SchemaSettingRow("型") {
            ChoiceButton(currentTypeId, FieldType.entries.map { it.id } + types.names) {
                if (it != currentTypeId) {
                    if (removedByType(it).isEmpty()) changeType(it) else pendingType = it
                }
            }
            if (!FieldType.isBaseKind(currentTypeId)) {
                Spacer(Modifier.width(8.dp))
                Text("型の名前（基底の種類: ${types.baseKindOf(currentTypeId)}。ここに書く決まりは狭めるだけ）", fontSize = 10.sp, color = Palette.SubText)
            }
        }
    }
    SchemaSettingRow("定義参照") {
        ChoiceButton(ref?.let { "\$$it" } ?: "使用しない",
            listOf("使用しない") + definitions.map { "\$$it" }) { choice ->
            map.entries.remove("ref")
            if (choice == "使用しない") map.entries.remove("\$ref")
            else map.entries["\$ref"] = YNode.Scalar(choice.removePrefix("\$"))
            doc.markEdited()
        }
        if (allowKey) {
            Spacer(Modifier.width(8.dp))
            val required = (map.entries["required"] as? YNode.Scalar)?.value == true
            SchemaCheckbox(required, { map.entries["required"] = YNode.Scalar(it); doc.markEdited() })
            Text("必須", fontSize = 11.sp)
        }
    }
    SchemaPropertyGroup(ctx, path + "@presentation", if (allowKey) "説明・表示・必須条件" else "説明・表示") {
        if (!allowKey) CompactScalar(map, "label", "ラベル", doc)
        CompactMultilineScalar(map, "description", "説明", doc)
        // requiredFrom: フェーズ機構（P7）。「必須」チェックとの併用は不可（メタ検証でエラーになる）
        if (allowKey) CompactScalar(map, "requiredFrom", "必須になるフェーズ（requiredFrom）", doc)
        // role: 表示役割（R10-01）。畳み込みヘッダ・参照候補・一覧などの表示解決でこのフィールドの値が使われる
        if (allowKey) {
            SchemaSettingRow("表示役割（role）") {
                ChoiceButton(scalar(map, "role") ?: "（なし）", listOf("（なし）", "title", "subtitle")) {
                    if (it == "（なし）") map.entries.remove("role") else map.entries["role"] = YNode.Scalar(it)
                    doc.markEdited()
                }
                Spacer(Modifier.width(8.dp))
                Text("要約表示のタイトル／サブタイトルに使う", fontSize = 10.sp, color = Palette.SubText)
            }
        }
    }
    val additional = schemaFieldAdditionalFields(doc.schema?.tree, map, types.baseKindOf(currentTypeId), allowKey, ref != null)
    if (additional.isNotEmpty()) SchemaPropertyGroup(ctx, path + "@additional", "追加設定・操作・検査") {
        additional.forEach { field -> FieldRow(ctx, field, MapSlot(map, field.key!!), path + field.key!!) }
    }
    if (ref != null) return
    when (types.baseKindOf(currentTypeId)) {
        "object" -> {
            SchemaSettingRow("スキーマ外項目") {
                ChoiceButton(scalar(map, "unknownFields") ?: "warning", listOf("allow", "warning", "error")) {
                    if (it == "warning") map.entries.remove("unknownFields")
                    else map.entries["unknownFields"] = YNode.Scalar(it)
                    doc.markEdited()
                }
            }
            ObjectEntityEditor(map, doc, definitionName)
        }
        "array", "map" -> DisplayHintRow(map, doc)
        "enum" -> EnumValuesEditor(map, doc)
        "ref" -> {
            StringListEditor(map, if ("idPattern" in map.entries && "idPatterns" !in map.entries) "idPattern" else "idPatterns", "IDパターン", "ACTOR-*, ROLE-*", doc)
            CompactScalar(map, "relation", "関係種別", doc)
            // 参照対象種別（R21-03）: document = 文書ノード（_document.id ＞ 相対パス）への参照
            CompactScalar(map, "target", "参照対象（entity / document）", doc)
            val scope = map.entries["scope"] as? YNode.Map ?: remember(map) { YNode.Map() }
            StringListEditor(scope, "schemaIds", "対象スキーマID", "system-concepts, actor-detail", doc) { map.entries["scope"] = scope }
            StringListEditor(scope, "documentIds", "対象文書ID", "system-overview, actor-design", doc) { map.entries["scope"] = scope }
        }
        // IDフォーマットの強制（UX-79）: pattern 制約（R16-01）を id 型でも宣言できる（検証・採番の共通パターン源）
        "id" -> CompactOptionalScalar(map, "pattern", "形式（正規表現）", "例 TASK-\\d{3}（IDの形式を強制）", doc)
        // 意味の基底型（日付・時刻・日時）: 正準の形に加えて狭める形式と、画面で見せる形
        "date", "time", "datetime" -> {
            CompactOptionalScalar(map, "pattern", "形式（正規表現）", "正準の形に加えて狭める形式（例 30 分刻み ^\\d{2}:(00|30)$）", doc)
            CompactOptionalScalar(map, "format", "表示の形", "画面で見せる形（例 yyyy年M月d日・H:mm）。ファイルは正準の形のまま", doc)
            // 保存時に自動で入る値（auto）: 作成日時 created・更新日時 updated
            AutoValueChooser(map, listOf(AutoValue.CREATED, AutoValue.UPDATED), "作成のとき／中身が変わって保存するときに今の日付・時刻が入る", doc)
        }
        // 制約語彙（R16-01）の入力（UX-73: 設計UIに未露出だった分）
        "string" -> {
            CompactOptionalScalar(map, "pattern", "形式（正規表現）", "値の全体が一致する正規表現（R16-01）", doc)
            CompactNumberScalar(map, "minLength", "最小文字数", doc, integer = true)
            CompactNumberScalar(map, "maxLength", "最大文字数", doc, integer = true)
            // 複数行の編集（display: multiline）と行数（rows）。組込みの型 text は既にこれを持つ（型から引き継ぐ）
            val multiline = scalar(map, "display") == "multiline"
            SchemaSettingRow("複数行で編集") {
                SchemaCheckbox(multiline, {
                    if (it) map.entries["display"] = YNode.Scalar("multiline") else { map.entries.remove("display"); map.entries.remove("rows") }
                    doc.markEdited()
                })
                Text(
                    if (currentTypeId == BuiltinTypes.TEXT) "型 text は複数行の欄（ここで付け直す必要はありません）"
                    else "display: multiline（複数行の欄を保証。1 行の保証は型 line・1 語は型 word）",
                    fontSize = 10.sp, color = Palette.SubText,
                )
            }
            if (multiline) CompactNumberScalar(map, "rows", "行数（rows）", doc, integer = true)
        }
        "int" -> {
            CompactNumberScalar(map, "min", "最小値", doc, integer = true)
            CompactNumberScalar(map, "max", "最大値", doc, integer = true)
            CompactOptionalScalar(map, "format", "表示の形", "画面で見せる形（例 ¥#,##0・#,##0 円・版なら \"'rev-'000\"）。値は素の数のまま", doc)
            // 保存時に自動で入る値（auto）: 版 revision（中身が変わって保存するたびに保存済みの値 +1。新しい要素は 1）
            AutoValueChooser(map, listOf(AutoValue.REVISION), "中身が変わって保存するたびに +1（新しい要素は 1）", doc)
        }
        "number" -> {
            CompactNumberScalar(map, "min", "最小値", doc, integer = false)
            CompactNumberScalar(map, "max", "最大値", doc, integer = false)
            CompactOptionalScalar(map, "format", "表示の形", "画面で見せる形（例 #,##0.00・0.0%）。値は素の数のまま", doc)
        }
    }
    val currentType = types.baseKindOf(currentTypeId)
    // 入力欄の案内とサンプル値（P33 のヒント。型から引き継ぎ、ここに書けば上書き）: 値を文字で打つ基底の種類だけ
    if (currentType in HINT_KINDS) {
        CompactOptionalScalar(map, "placeholder", "入力欄の案内", "空欄のときに薄く出す文（例 YYYY-MM-DD）。型の案内を上書き", doc)
        if (map.entries["sample"] !is YNode.Map && map.entries["sample"] !is YNode.Seq)
            CompactOptionalScalar(map, "sample", "サンプル値", "例として示す値（サンプル YAML・AI 生成の例）。型のサンプルを上書き", doc)
    }
    // enum の初期値は選択肢の直下（EnumValuesEditor 内）で選択式にしている（UX-84）
    if (currentType !in setOf("object", "array", "map", "enum") && map.entries["default"] !is YNode.Map && map.entries["default"] !is YNode.Seq) DefaultValueEditor(map, currentType, doc)
}

/** 保存時に自動で入る値（auto）の選択（型に合う印だけを選べる。「（なし）」で外す）。 */
@Composable
private fun AutoValueChooser(map: YNode.Map, choices: List<AutoValue>, hint: String, doc: OpenDocument) {
    doc.version
    val none = "（なし）"
    val current = scalar(map, "auto")?.let { raw -> AutoValue.parse(raw)?.let { "${it.id}（${it.label}）" } ?: raw } ?: none
    SchemaSettingRow("自動で入る値（auto）") {
        ChoiceButton(current, listOf(none) + choices.map { "${it.id}（${it.label}）" }) { picked ->
            val chosen = choices.firstOrNull { picked.startsWith(it.id) }
            if (chosen == null) map.entries.remove("auto") else map.entries["auto"] = YNode.Scalar(chosen.id)
            doc.markEdited()
        }
        Spacer(Modifier.width(8.dp))
        Text(hint, fontSize = 10.sp, color = Palette.SubText)
    }
}

/** 入力欄の案内・サンプル値を書ける基底の種類（文字で値を打つもの）。 */
private val HINT_KINDS = setOf(
    FieldType.STRING.id, FieldType.INT.id, FieldType.NUMBER.id, FieldType.DATE.id, FieldType.TIME.id, FieldType.DATETIME.id,
    FieldType.FILE.id, FieldType.ID.id, FieldType.REF.id,
)

/** 一覧表示形式のヒント（R12-06）。array / map で共有。省略時はフォーム側が列構成から自動判定する。 */
@Composable
private fun DisplayHintRow(map: YNode.Map, doc: OpenDocument) {
    doc.version
    SchemaSettingRow("一覧の表示形式") {
        ChoiceButton(scalar(map, "display") ?: "（自動）", listOf("（自動）", "table", "list")) {
            if (it == "（自動）") map.entries.remove("display") else map.entries["display"] = YNode.Scalar(it)
            doc.markEdited()
        }
        Spacer(Modifier.width(8.dp))
        Text("フォームの表形式（table）／縦並び（list）を固定する", fontSize = 10.sp, color = Palette.SubText)
    }
}

// ================================================================ フェーズ・カバレッジ（R9-03）

/** 文書ルートの `phases` 宣言（リスト省略形も読み、編集時は key / order の object 形へ正規化する）。 */
@Composable
internal fun PhasesEditor(root: YNode.Map, doc: OpenDocument) {
    doc.version  // CONVENTIONS §2 / UX-24
    val node = root.entries["phases"]
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("フェーズ（工程）", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (node == null) {
            SchemaEditButton(onClick = {
                root.entries["phases"] = YNode.Map(linkedMapOf(
                    "key" to YNode.Scalar("phase"),
                    "order" to YNode.Seq(),
                ))
                doc.markEdited()
            }) { Text("＋ フェーズを宣言", fontSize = 12.sp) }
        } else {
            SchemaDeleteButton("フェーズを削除") { root.entries.remove("phases"); doc.markEdited() }
        }
    }
    if (node == null) {
        Text(
            "宣言すると、文書がルート項目で現在フェーズを申告し、requiredFrom（フェーズ累積必須）と activeFrom（カバレッジ判定の保留）が使えます。",
            fontSize = 11.sp, color = Palette.SubText,
        )
        return
    }
    // リスト省略形（phases: [a, b]）は表示だけ対応し、編集した時点で object 形へ正規化する
    val currentKey = (node as? YNode.Map)?.let { scalar(it, "key") } ?: "phase"
    val currentOrder = when (node) {
        is YNode.Seq -> node.items.mapNotNull { (it as? YNode.Scalar)?.value?.toString() }
        is YNode.Map -> (node.entries["order"] as? YNode.Seq)?.items?.mapNotNull { (it as? YNode.Scalar)?.value?.toString() }.orEmpty()
        else -> emptyList()
    }
    fun normalized(): YNode.Map = (root.entries["phases"] as? YNode.Map)
        ?: YNode.Map(linkedMapOf(
            "key" to YNode.Scalar(currentKey),
            "order" to YNode.Seq(currentOrder.map { YNode.Scalar(it) }.toMutableList()),
        )).also { root.entries["phases"] = it }
    SchemaSettingRow("申告キー") {
        SchemaTextField(currentKey, { raw ->
            normalized().entries["key"] = YNode.Scalar(raw)
            doc.markEdited()
        }, singleLine = true, modifier = Modifier.weight(1f))
    }
    SchemaSettingRow("フェーズ順序") {
        SchemaTextField(currentOrder.joinToString(", "), { raw ->
            normalized().entries["order"] = YNode.Seq(
                raw.split(',').map { YNode.Scalar(it.trim()) }.filter { it.value.toString().isNotBlank() }.toMutableList()
            )
            doc.markEdited()
        }, singleLine = true, placeholder = { Text("requirements, logical-design, physical-design") }, modifier = Modifier.weight(1f))
    }
    Text("先頭が最初の工程。文書はルート直下の「$currentKey」で現在フェーズを申告します。", fontSize = 11.sp, color = Palette.SubText)
}

/** スキーマの `entity`（エンティティ型宣言。R20-01）の構造化編集。builtin-schema の entity と対更新（architecture/graph-query.md「エンティティ型と関係レジストリ」）。 */
@Composable
internal fun EntityEditor(root: YNode.Map, doc: OpenDocument) {
    doc.version  // CONVENTIONS §2 / UX-24
    fun newDecl() = YNode.Map(linkedMapOf<String, YNode>("type" to YNode.Scalar(""), "defines" to YNode.Scalar(true)))
    val node = root.entries["entity"]
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("エンティティ型", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        SchemaEditButton(onClick = {
            when (val current = root.entries["entity"]) {
                null -> root.entries["entity"] = newDecl()
                is YNode.Seq -> current.items.add(newDecl())
                else -> root.entries["entity"] = YNode.Seq(mutableListOf(current, newDecl()))
            }
            doc.markEdited()
        }) { Text("＋ 型を追加", fontSize = 12.sp) }
    }
    if (node == null) {
        Text(
            "宣言すると出現にエンティティ型と役割（定義／詳細化）が付き、定義出現の欠落・競合・IDパターン逸脱が検証で指摘されます。",
            fontSize = 11.sp, color = Palette.SubText,
        )
        return
    }
    val decls: List<YNode.Map> = when (node) {
        is YNode.Seq -> node.items.filterIsInstance<YNode.Map>()
        is YNode.Map -> listOf(node)
        else -> emptyList()
    }
    decls.forEachIndexed { index, decl ->
        Column(Modifier.fillMaxWidth().padding(top = 6.dp).border(1.dp, Palette.Border, PanelShape).padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("型 ${index + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                SchemaDeleteButton("エンティティ宣言を削除") {
                    when (val current = root.entries["entity"]) {
                        is YNode.Seq -> {
                            current.items.remove(decl)
                            if (current.items.isEmpty()) root.entries.remove("entity")
                        }
                        else -> root.entries.remove("entity")
                    }
                    doc.markEdited()
                }
            }
            CompactScalar(decl, "type", "型名", doc)
            CompactScalar(decl, "idPattern", "IDパターン（例 SCR-*）", doc)
            // ID の型（P33）: definitions の id を狭めた型の名前で ID を判別する（idPattern と併用すれば両方に合う ID）
            val types = LocalTypeChoices.current
            val idTypes = types.names.filter { types.baseKindOf(it) == FieldType.ID.id }
            SchemaSettingRow("IDの型（idType）") {
                val current = scalar(decl, "idType") ?: "（なし）"
                ChoiceButton(current, listOf("（なし）") + (idTypes + listOfNotNull(scalar(decl, "idType"))).distinct()) {
                    if (it == "（なし）") decl.entries.remove("idType") else decl.entries["idType"] = YNode.Scalar(it)
                    doc.markEdited()
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (idTypes.isEmpty()) "definitions に id を狭めた型（例 画面ID: {type: id, pattern: ...}）を定義すると選べます"
                    else "型の形式（pattern）で ID を判別します",
                    fontSize = 10.sp, color = Palette.SubText,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val defines = (decl.entries["defines"] as? YNode.Scalar)?.value == true
                SchemaCheckbox(defines, { decl.entries["defines"] = YNode.Scalar(it); doc.markEdited() })
                Text("この文書群の出現が定義（誕生）", fontSize = 11.sp)
            }
            // アスペクト宣言（R23-01。architecture/graph-query.md「エンティティページ」）: この出現が所有する側面名と所有フィールドの限定
            CompactScalar(decl, "aspect", "アスペクト名（省略時はスキーマID）", doc)
            StringListEditor(decl, "owns", "所有フィールド（省略時は宣言フィールド全部）", "status, summary", doc)
        }
    }
    Text(
        "複数型は idPattern か idType（ID の型）で判別します。defines を付けた文書群の出現が「定義」になります。" +
            "aspect / owns はエンティティページ・read_entity の合成（R23-01）で「この出現がどの側面を所有するか」に使われます。",
        fontSize = 11.sp, color = Palette.SubText,
    )
}

/** スキーマの `coverage`（カバレッジルール）の構造化編集。 */
@Composable
internal fun CoverageEditor(root: YNode.Map, doc: OpenDocument) {
    doc.version  // CONVENTIONS §2 / UX-24
    val coverage = root.entries["coverage"] as? YNode.Seq
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("カバレッジルール", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        SchemaEditButton(onClick = {
            val seq = ensureSeq(root, "coverage", doc, mark = false)
            seq.items.add(YNode.Map(linkedMapOf(
                "idPattern" to YNode.Scalar("*"),
                "detailIn" to YNode.Map(),
            )))
            doc.markEdited()
        }) { Text("＋ ルールを追加", fontSize = 12.sp) }
    }
    if (coverage == null || coverage.items.isEmpty()) {
        Text(
            "「このスキーマが定義するIDは、指定スキーマの詳細文書に出現すること」を機械判定します" +
                "（被参照数の要求は関係レジストリの cardinality か constraints で宣言します）。",
            fontSize = 11.sp, color = Palette.SubText,
        )
    }
    val phaseNames = ((root.entries["phases"] as? YNode.Map)?.entries?.get("order") as? YNode.Seq)
        ?.items?.mapNotNull { (it as? YNode.Scalar)?.value?.toString() }
        ?: (root.entries["phases"] as? YNode.Seq)?.items?.mapNotNull { (it as? YNode.Scalar)?.value?.toString() }
        ?: emptyList()
    coverage?.items?.forEachIndexed { index, node ->
        val rule = node as? YNode.Map ?: return@forEachIndexed
        Column(Modifier.fillMaxWidth().padding(top = 6.dp).border(1.dp, Palette.Border, PanelShape).padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ルール ${index + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                ArrayDeleteButton(doc, coverage, index, LocalSchemaReadOnly.current,
                    label = "カバレッジルール", textButton = true,
                    afterDelete = { if (coverage.items.isEmpty()) root.entries.remove("coverage") })
            }
            CompactScalar(rule, "id", "ルールID（任意）", doc)
            CompactScalar(rule, "description", "説明", doc)
            CompactScalar(rule, "idPattern", "対象IDパターン", doc)
            val detail = rule.entries["detailIn"] as? YNode.Map ?: remember(rule) { YNode.Map() }
            StringListEditor(detail, "schemaIds", "出現先スキーマID", "screen-detail", doc) { rule.entries["detailIn"] = detail }
            CoverageMinCount(detail, doc) { rule.entries["detailIn"] = detail }
            SchemaSettingRow("重大度") {
                ChoiceButton(scalar(rule, "level") ?: "warning", listOf("warning", "error")) {
                    if (it == "warning") rule.entries.remove("level") else rule.entries["level"] = YNode.Scalar(it)
                    doc.markEdited()
                }
                if (phaseNames.isNotEmpty()) {
                    Spacer(Modifier.width(12.dp))
                    Text("判定開始フェーズ", fontSize = 12.sp)
                    Spacer(Modifier.width(6.dp))
                    ChoiceButton(scalar(rule, "activeFrom") ?: "（常時）", listOf("（常時）") + phaseNames) {
                        if (it == "（常時）") rule.entries.remove("activeFrom") else rule.entries["activeFrom"] = YNode.Scalar(it)
                        doc.markEdited()
                    }
                }
            }
        }
    }
}

/** minCount は数値型で書き込む（文字列だとパース時に既定値へ落ちるため）。 */
@Composable
private fun CoverageMinCount(map: YNode.Map, doc: OpenDocument, attach: () -> Unit) {
    doc.version
    val value = (map.entries["minCount"] as? YNode.Scalar)?.value?.toString().orEmpty()
    SchemaSettingRow("必要数（minCount）") {
        SchemaTextField(value, { raw ->
            attach()
            val n = raw.trim().toLongOrNull()
            if (raw.isBlank()) map.entries.remove("minCount")
            else if (n != null) map.entries["minCount"] = YNode.Scalar(n)
            doc.markEdited()
        }, singleLine = true, placeholder = { Text("1") }, modifier = Modifier.width(120.dp))
    }
}

@Composable
internal fun ChoiceButton(value: String, choices: List<String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(enabled = !LocalSchemaReadOnly.current, shape = PanelShape, onClick = { open = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text(value, fontSize = 12.sp); Text(" ▾", color = Palette.SubText) }
        ChoiceMenu(open, { open = false }, choices.map { ChoiceOption(it, it, selected = it == value) }, onSelect)
    }
}

/** values 配下の選択肢の値一覧（文字列形とリッチ形 {value, label, description} の両対応。R10-03）。 */
private fun enumValueStrings(map: YNode.Map): List<String> =
    (map.entries["values"] as? YNode.Seq)?.items?.mapNotNull { item ->
        when (item) {
            is YNode.Scalar -> item.value?.toString()
            is YNode.Map -> (item.entries["value"] as? YNode.Scalar)?.value?.toString()
            else -> null
        }
    }.orEmpty()

/**
 * enum 選択肢の編集（R10-03）。値だけの選択肢は文字列のまま、label / description を付けると
 * {value, label, description} のリッチ形へ切り替える（両方消すと文字列形へ戻す＝YAMLを簡潔に保つ）。
 */
@Composable
private fun EnumOptionListEditor(map: YNode.Map, doc: OpenDocument) {
    doc.version  // 選択肢の追加・削除の即時反映（CONVENTIONS §2 / UX-24）
    SchemaSettingRow("選択肢") {
        SchemaEditButton(onClick = {
            ensureSeq(map, "values", doc, mark = false).items.add(YNode.Scalar(""))
            doc.markEdited()
        }) { Text("＋ 選択肢を追加", fontSize = 11.sp) }
        Text("値が保存されるデータ。ラベル・説明はフォーム表示専用", fontSize = 10.sp, color = Palette.SubText)
    }
    val seq = map.entries["values"] as? YNode.Seq ?: return
    seq.items.forEachIndexed { index, node ->
        val value = when (node) {
            is YNode.Scalar -> node.value?.toString().orEmpty()
            is YNode.Map -> (node.entries["value"] as? YNode.Scalar)?.value?.toString().orEmpty()
            else -> ""
        }
        val label = ((node as? YNode.Map)?.entries?.get("label") as? YNode.Scalar)?.value?.toString().orEmpty()
        val description = ((node as? YNode.Map)?.entries?.get("description") as? YNode.Scalar)?.value?.toString().orEmpty()
        fun update(newValue: String = value, newLabel: String = label, newDescription: String = description) {
            seq.items[index] = if (newLabel.isBlank() && newDescription.isBlank()) YNode.Scalar(newValue)
            else YNode.Map(linkedMapOf<String, YNode>().apply {
                put("value", YNode.Scalar(newValue))
                if (newLabel.isNotBlank()) put("label", YNode.Scalar(newLabel))
                if (newDescription.isNotBlank()) put("description", YNode.Scalar(newDescription))
            })
            doc.markEdited(coalesceWith = seq to index)
        }
        Column(Modifier.fillMaxWidth().padding(top = 8.dp).border(1.dp, Palette.Border, PanelShape).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(FormSpacing.FieldGap)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("選択肢 ${index + 1}", fontSize = 12.sp, modifier = Modifier.weight(1f))
                ArrayDeleteButton(doc, seq, index, LocalSchemaReadOnly.current, label = "選択肢", textButton = true)
            }
            FormFieldLayout(label = { Text("保存する値", fontSize = 13.sp) }) {
                SchemaTextField(value, { update(newValue = it) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            FormFieldLayout(label = { Text("表示ラベル", fontSize = 13.sp) }) {
                SchemaTextField(label, { update(newLabel = it) }, singleLine = true,
                    placeholder = { Text("任意") }, modifier = Modifier.fillMaxWidth())
            }
            FormFieldLayout(label = { Text("説明", fontSize = 13.sp) }) {
                SchemaTextField(description, { update(newDescription = it) }, singleLine = true,
                    placeholder = { Text("任意") }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun EnumValuesEditor(map: YNode.Map, doc: OpenDocument) {
    doc.version  // CONVENTIONS §2 / UX-24
    EnumOptionListEditor(map, doc)
    val values = enumValueStrings(map)
    // 初期値は選択肢のすぐ下に置き、宣言済みの値から選ぶ（UX-84。cases の下だと遠く、自由入力だと選択肢とずれる）
    SchemaSettingRow("初期値") {
        ChoiceButton(scalar(map, "default") ?: "（未設定）", listOf("（未設定）") + values) {
            if (it == "（未設定）") map.entries.remove("default") else map.entries["default"] = YNode.Scalar(it)
            doc.markEdited()
        }
    }
    // 選択肢から消えた値の cases は残骸なので警告つきで削除できるようにする
    (map.entries["cases"] as? YNode.Map)?.entries?.keys?.filter { it !in values }?.toList()?.forEach { stale ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("cases「$stale」は選択肢にありません", fontSize = 11.sp, color = Palette.WarnOrange)
            SchemaDeleteButton("条件付き項目を削除") {
                val cases = map.entries["cases"] as? YNode.Map ?: return@SchemaDeleteButton
                cases.entries.remove(stale)
                if (cases.entries.isEmpty()) map.entries.remove("cases")
                doc.markEdited()
            }
        }
    }
    values.distinct().forEach { value ->
        val caseSeq = (map.entries["cases"] as? YNode.Map)?.entries?.get(value) as? YNode.Seq
        if (caseSeq == null) {
            SchemaEditButton(onClick = {
                ensureMap(map, "cases", doc, mark = false).entries[value] = YNode.Seq()
                doc.markEdited()
            }) { Text("＋ 「$value」専用のフィールドを定義", fontSize = 11.sp) }
        } else {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, top = 5.dp).border(1.dp, Palette.Border, PanelShape).padding(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("「$value」のときのフィールド", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    SchemaDeleteButton("条件付き項目を削除") {
                        val cases = map.entries["cases"] as? YNode.Map ?: return@SchemaDeleteButton
                        cases.entries.remove(value)
                        if (cases.entries.isEmpty()) map.entries.remove("cases")
                        doc.markEdited()
                    }
                }
                Text("${caseSeq.items.size} 項目 · 構成一覧で条件を選ぶと項目を追加・編集できます", fontSize = 11.sp, color = Palette.SubText)
            }
        }
    }
}

@Composable
private fun StringListEditor(map: YNode.Map, key: String, label: String, hint: String, doc: OpenDocument, attach: () -> Unit = {}) {
    doc.version
    val seq = map.entries[key] as? YNode.Seq
    val value = seq?.items?.mapNotNull { (it as? YNode.Scalar)?.value?.toString() }?.joinToString(", ") ?: scalar(map, key).orEmpty()
    FormFieldLayout(modifier = Modifier.padding(vertical = FormSpacing.FieldGap), label = {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
    }) {
        SchemaTextField(value, { raw ->
            attach()
            map.entries[key] = YNode.Seq(raw.split(',').map { YNode.Scalar(it.trim()) }.filter { it.value.toString().isNotBlank() }.toMutableList())
            doc.markEdited(coalesceWith = map to key)
        }, singleLine = true, placeholder = { Text(hint) }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun DefaultValueEditor(map: YNode.Map, type: String, doc: OpenDocument) {
    doc.version
    val value = (map.entries["default"] as? YNode.Scalar)?.value?.toString().orEmpty()
    SchemaSettingRow("初期値") {
        SchemaTextField(value, { raw ->
            if (raw.isEmpty()) map.entries.remove("default") else {
                val typed: Any = when (type) {
                    "boolean" -> raw.toBooleanStrictOrNull() ?: raw
                    "int" -> raw.toLongOrNull() ?: raw
                    "number" -> raw.toDoubleOrNull() ?: raw
                    else -> raw
                }
                map.entries["default"] = YNode.Scalar(typed)
            }
            doc.markEdited(coalesceWith = map to "default")
        }, singleLine = true, placeholder = { Text("未設定") }, modifier = Modifier.weight(1f))
    }
}

/**
 * フィールド一括改名の確認ダイアログ（R25-01。architecture/schema.md「リファクタリング」）。
 * スキーマ定義・全該当文書のデータキー（順序・コメント保持）・ビュー通常列の計画をプレビューし、
 * 適用はセッションの1トランザクション（AppState.applyEntityPlan。Undo 1回で戻る。B-1）。
 * 未保存の変更があるときは先に保存を求める（適用→保存が未保存編集まで書かないように。EntityRenameDialog と同じ流儀）。
 */
@Composable
private fun SchemaFieldRenameDialog(
    state: AppState,
    doc: OpenDocument,
    schemaId: String,
    fieldPath: List<String>,
    onDismiss: () -> Unit,
) {
    val pathText = fieldPath.joinToString(".")
    var newName by remember { mutableStateOf("") }
    var applyError by remember { mutableStateOf<String?>(null) }
    state.indexVersion
    doc.version
    val plan = remember(newName, state.indexVersion, doc.dirty) {
        if (doc.dirty || newName.isBlank()) null
        else state.planSchemaFieldRename(schemaId, pathText, newName.trim())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("フィールドを一括改名") },
        text = {
            Column(Modifier.width(560.dp).heightIn(max = 480.dp)) {
                Text("スキーマ「$schemaId」のフィールド「$pathText」", fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                CompactTextField(
                    newName, { newName = it }, singleLine = true,
                    placeholder = { Text("改名先のフィールド名") }, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                when {
                    doc.dirty -> Text("スキーマに未保存の変更があります。保存してから一括改名してください（生成は保存済み状態が基準です）",
                        fontSize = 12.sp, color = Palette.WarnOrange)
                    plan == null -> Text("改名先を入力すると、スキーマ・文書・ビューの変更プレビューを表示します",
                        fontSize = 12.sp, color = Palette.SubText)
                    plan.isFailure -> Text(plan.exceptionOrNull()?.message ?: "プレビューを生成できません",
                        fontSize = 12.sp, color = Palette.ErrorRed)
                    else -> {
                        val p = plan.getOrThrow()
                        p.summary.forEach { Text(it, fontSize = 12.sp) }
                        p.warnings.forEach { Text("注意: $it", fontSize = 12.sp, color = Palette.WarnOrange) }
                        Spacer(Modifier.height(6.dp))
                        Text("変更されるファイル:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        val scroll = rememberScrollState()
                        Column(Modifier.weight(1f, fill = false).verticalScroll(scroll)) {
                            p.changes.forEach { c ->
                                Text("・${c.document.value}（${c.operations.size} 操作）", fontSize = 12.sp, color = Palette.SubText)
                            }
                        }
                    }
                }
                applyError?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 12.sp, color = Palette.ErrorRed)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = plan?.isSuccess == true,
                onClick = {
                    plan?.getOrNull()?.let { p ->
                        state.applyEntityPlan(p)
                            .onSuccess { onDismiss() }
                            .onFailure { applyError = it.message ?: "適用に失敗しました" }
                    }
                },
            ) { Text("適用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

/**
 * スキーマ・リファクタリング操作の共通確認ダイアログ（R25-02 / R25-03。入力欄なしのプレビュー→適用）。
 * 未保存の変更があるときは先に保存を求める（適用→保存が未保存編集まで書かないように）。
 */
@Composable
private fun SchemaRefactorConfirmDialog(
    state: AppState,
    doc: OpenDocument,
    title: String,
    header: String,
    planner: () -> Result<RefactoringPlan>,
    onDismiss: () -> Unit,
) {
    var applyError by remember { mutableStateOf<String?>(null) }
    state.indexVersion
    doc.version
    val plan = remember(state.indexVersion, doc.dirty) { if (doc.dirty) null else planner() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.width(560.dp).heightIn(max = 480.dp)) {
                Text(header, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                when {
                    doc.dirty -> Text("スキーマに未保存の変更があります。保存してから実行してください（生成は保存済み状態が基準です）",
                        fontSize = 12.sp, color = Palette.WarnOrange)
                    plan == null -> Text("プレビューを生成しています…", fontSize = 12.sp, color = Palette.SubText)
                    plan.isFailure -> Text(plan.exceptionOrNull()?.message ?: "プレビューを生成できません",
                        fontSize = 12.sp, color = Palette.ErrorRed)
                    else -> {
                        val p = plan.getOrThrow()
                        p.summary.forEach { Text(it, fontSize = 12.sp) }
                        p.warnings.forEach { Text("注意: $it", fontSize = 12.sp, color = Palette.WarnOrange) }
                        Spacer(Modifier.height(6.dp))
                        if (p.changes.isEmpty()) {
                            Text("変更されるファイルはありません", fontSize = 12.sp, color = Palette.SubText)
                        } else {
                            Text("変更されるファイル:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            val scroll = rememberScrollState()
                            Column(Modifier.weight(1f, fill = false).verticalScroll(scroll)) {
                                p.changes.forEach { c ->
                                    Text("・${c.document.value}（${c.operations.size} 操作）", fontSize = 12.sp, color = Palette.SubText)
                                }
                            }
                        }
                    }
                }
                applyError?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 12.sp, color = Palette.ErrorRed)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = plan?.isSuccess == true && plan.getOrNull()?.changes?.isNotEmpty() == true,
                onClick = {
                    plan?.getOrNull()?.let { p ->
                        state.applyEntityPlan(p)
                            .onSuccess { onDismiss() }
                            .onFailure { applyError = it.message ?: "適用に失敗しました" }
                    }
                },
            ) { Text("適用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

internal fun scalar(map: YNode.Map, key: String): String? = (map.entries[key] as? YNode.Scalar)?.value?.toString()?.takeIf { it.isNotBlank() }
private fun ensureMap(parent: YNode.Map, key: String, doc: OpenDocument, mark: Boolean): YNode.Map =
    (parent.entries[key] as? YNode.Map) ?: YNode.Map().also { parent.entries[key] = it; if (mark) doc.markEdited() }
private fun ensureSeq(parent: YNode.Map, key: String, doc: OpenDocument, mark: Boolean): YNode.Seq =
    (parent.entries[key] as? YNode.Seq) ?: YNode.Seq().also { parent.entries[key] = it; if (mark) doc.markEdited() }
internal fun newFieldMap(type: String) = YNode.Map(linkedMapOf<String, YNode>("key" to YNode.Scalar(""), "type" to YNode.Scalar(type)))

/** 選択欄・チェックボックスも文字入力と同じラベル列と縦配置の境界を使う。 */
@Composable
private fun SchemaSettingRow(label: String, content: @Composable FlowRowScope.() -> Unit) {
    FormFieldLayout(Modifier.padding(vertical = FormSpacing.FieldGap), label = {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
    }) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically,
            content = content)
    }
}
