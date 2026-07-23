package ui

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.OpenDocument
import app.AppState
import model.YNode
import schema.FieldType
import schema.SchemaDef
import schema.SchemaIssue
import schema.SchemaKind
import schema.SchemaLoader
import schema.generateSample
import schema.validateDefinition
import yaml.YamlIO

/** `_schemas` 用の構造化エディタ。YAML の表現を意識せずフィールド定義を編集できる。 */
@Composable
fun SchemaEditor(state: AppState, doc: OpenDocument) {
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
    val library = scalar(root, "kind") == SchemaKind.LIBRARY.id
    var mode by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = mode, containerColor = Palette.Surface, contentColor = Palette.Primary) {
            Tab(mode == 0, { mode = 0 }, text = { Text("設計", fontSize = 11.sp) })
            Tab(mode == 1, { mode = 1 }, text = { Text("プレビュー", fontSize = 11.sp) })
            Tab(mode == 2, { mode = 2 }, text = { Text("適用対象", fontSize = 11.sp) })
        }
        when (mode) {
            0 -> Box(Modifier.weight(1f).fillMaxWidth()) {
                val scroll = rememberScrollState()
                Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp)) {
            Text("スキーマ設計", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("適用条件とフィールド構造を編集します。再帰構造は \$ref で名前付き定義を参照してください。",
                fontSize = 11.sp, color = Palette.SubText)
            Spacer(Modifier.height(10.dp))
            CompactScalar(root, "id", "スキーマID", doc)
            CompactScalar(root, "name", "スキーマ名", doc)
            SchemaKindEditor(root, doc, library)
            CompactMultilineScalar(root, "description", "スキーマの説明", doc)
            if (!library) CompactScalar(root, "match", "適用パターン", doc)
            if (schemaResult.isFailure) {
                SchemaProblem("スキーマを解析できません: ${schemaResult.exceptionOrNull()?.message}")
            }
            issues.forEach { SchemaProblem("${it.path}: ${it.message}") }
            Spacer(Modifier.height(12.dp))

            val definitions = ensureMap(root, "definitions", doc, mark = false)
            val externalDefinitions = state.schemas.asSequence()
                .filter { it.id != schema?.id }
                .flatMap { module -> module.definitions.keys.asSequence().map { "${module.id}#$it" } }
                .toList()
            val definitionChoices = (definitions.entries.keys + externalDefinitions).distinct().sorted()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("名前付き定義", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                AddDefinitionButton(definitions, doc)
            }
            definitions.entries.keys.toList().forEach { name ->
                DefinitionCard(name, definitions, doc, definitionChoices)
            }
            if (!library) {
                Spacer(Modifier.height(12.dp))
                Text("ルート定義", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                val rootDef = ensureMap(root, "root", doc, mark = false)
                FieldDefinitionEditor(rootDef, doc, definitionChoices, allowKey = false, depth = 0)
            } else {
                Text(
                    "library は業務文書へ適用されず、definitions だけを他スキーマへ公開します。",
                    fontSize = 11.sp,
                    color = Palette.SubText,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(48.dp))
                }
                VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
            1 -> SchemaPreview(state, doc, schema, issues)
            else -> SchemaTargets(state, schema)
        }
    }
}

@Composable
private fun SchemaProblem(message: String) {
    Text("⚠ $message", fontSize = 11.sp, color = Palette.ErrorRed,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
}

@Composable
private fun SchemaKindEditor(map: YNode.Map, doc: OpenDocument, library: Boolean) {
    var confirmLibrary by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("スキーマ種別", fontSize = 12.sp, modifier = Modifier.width(140.dp))
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
            TextButton(onClick = { source = false }) { Text("フォーム", fontSize = 11.sp) }
            TextButton(onClick = { source = true }) { Text("YAML", fontSize = 11.sp) }
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(sampleText)) },
                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 2.dp)) { Text("YAMLをコピー", fontSize = 11.sp) }
        }
        if (source) {
            val scroll = rememberScrollState()
            Text(sampleText, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Palette.Text,
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
private fun CompactScalar(map: YNode.Map, key: String, label: String, doc: OpenDocument) {
    val value = (map.entries[key] as? YNode.Scalar)?.value?.toString().orEmpty()
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, modifier = Modifier.width(140.dp))
        CompactTextField(value, { map.entries[key] = YNode.Scalar(it); doc.markEdited() },
            singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
            modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CompactMultilineScalar(map: YNode.Map, key: String, label: String, doc: OpenDocument) {
    val value = (map.entries[key] as? YNode.Scalar)?.value?.toString().orEmpty()
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, fontSize = 12.sp, modifier = Modifier.width(140.dp).padding(top = 8.dp))
        CompactTextField(
            value = value,
            onValueChange = { map.entries[key] = YNode.Scalar(it); doc.markEdited() },
            singleLine = false,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
            modifier = Modifier.weight(1f).heightIn(min = 72.dp),
        )
    }
}

@Composable
private fun AddDefinitionButton(definitions: YNode.Map, doc: OpenDocument) {
    var open by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    TextButton(onClick = { open = true }) { Text("＋ 定義を追加", fontSize = 12.sp) }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("名前付き定義を追加") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("定義名") }) },
        confirmButton = { TextButton(enabled = name.isNotBlank() && name !in definitions.entries, onClick = {
            definitions.entries[name.trim()] = newFieldMap("object"); doc.markEdited(); open = false; name = ""
        }) { Text("追加") } },
        dismissButton = { TextButton(onClick = { open = false }) { Text("キャンセル") } },
    )
}

@Composable
private fun DefinitionCard(name: String, definitions: YNode.Map, doc: OpenDocument, names: List<String>) {
    val map = definitions.entries[name] as? YNode.Map ?: return
    var expanded by remember(name) { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).border(1.dp, Palette.Border, PanelShape).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "▼ $name" else "▶ $name", fontSize = 12.sp) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { definitions.entries.remove(name); doc.markEdited() }) {
                Text("削除", fontSize = 11.sp, color = Palette.ErrorRed)
            }
        }
        if (expanded) FieldDefinitionEditor(map, doc, names, allowKey = false, depth = 0)
    }
}

@Composable
private fun FieldDefinitionEditor(map: YNode.Map, doc: OpenDocument, definitions: List<String>, allowKey: Boolean, depth: Int) {
    if (depth > 12) { Text("表示可能な深さを超えました", color = Palette.WarnOrange); return }
    val ref = scalar(map, "\$ref")
    if (allowKey) CompactScalar(map, "key", "キー", doc)
    CompactScalar(map, "label", "ラベル", doc)
    CompactScalar(map, "description", "説明", doc)
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("定義参照", fontSize = 12.sp, modifier = Modifier.width(140.dp))
        ChoiceButton(ref?.let { "\$$it" } ?: "使用しない",
            listOf("使用しない") + definitions.map { "\$$it" }) { choice ->
            if (choice == "使用しない") map.entries.remove("\$ref")
            else map.entries["\$ref"] = YNode.Scalar(choice.removePrefix("\$"))
            doc.markEdited()
        }
        if (allowKey) {
            Spacer(Modifier.width(8.dp))
            val required = (map.entries["required"] as? YNode.Scalar)?.value == true
            Checkbox(required, { map.entries["required"] = YNode.Scalar(it); doc.markEdited() })
            Text("必須", fontSize = 11.sp)
        }
    }
    // requiredFrom: フェーズ機構（P7）。「必須」チェックとの併用は不可（メタ検証でエラーになる）
    if (allowKey) CompactScalar(map, "requiredFrom", "必須になるフェーズ（requiredFrom）", doc)
    if (ref == null) {
        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("型", fontSize = 12.sp, modifier = Modifier.width(140.dp))
            ChoiceButton(scalar(map, "type") ?: "string", FieldType.entries.map { it.id }) {
                map.entries["type"] = YNode.Scalar(it)
                if (it != "object") map.entries.remove("fields")
                if (it != "object") map.entries.remove("unknownFields")
                if (it != "array") map.entries.remove("item")
                if (it != "map") map.entries.remove("value")
                if (it != "enum") map.entries.remove("cases")
                doc.markEdited()
            }
        }
    }
    if (ref != null) return
    when (scalar(map, "type") ?: "string") {
        "object" -> {
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("スキーマ外項目", fontSize = 12.sp, modifier = Modifier.width(140.dp))
                ChoiceButton(scalar(map, "unknownFields") ?: "warning", listOf("allow", "warning", "error")) {
                    if (it == "warning") map.entries.remove("unknownFields")
                    else map.entries["unknownFields"] = YNode.Scalar(it)
                    doc.markEdited()
                }
            }
            val fields = ensureSeq(map, "fields", doc, mark = false)
            fields.items.forEachIndexed { index, node ->
                val child = node as? YNode.Map ?: return@forEachIndexed
                Column(Modifier.padding(start = 12.dp, top = 5.dp).border(1.dp, Palette.Border, PanelShape).padding(6.dp)) {
                    Row { Text("フィールド ${index + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f))
                        TextButton(onClick = { fields.items.removeAt(index); doc.markEdited() }) { Text("削除", color = Palette.ErrorRed, fontSize = 10.sp) } }
                    FieldDefinitionEditor(child, doc, definitions, allowKey = true, depth = depth + 1)
                }
            }
            TextButton(onClick = { fields.items.add(newFieldMap("string")); doc.markEdited() }) { Text("＋ フィールドを追加", fontSize = 11.sp) }
        }
        "array" -> {
            Text("要素定義", fontSize = 11.sp, color = Palette.SubText)
            FieldDefinitionEditor(ensureMap(map, "item", doc, mark = false), doc, definitions, allowKey = false, depth = depth + 1)
        }
        "map" -> {
            Text("値定義", fontSize = 11.sp, color = Palette.SubText)
            FieldDefinitionEditor(ensureMap(map, "value", doc, mark = false), doc, definitions, allowKey = false, depth = depth + 1)
        }
        "enum" -> EnumValuesEditor(map, doc, definitions, depth)
        "ref" -> {
            StringListEditor(map, "idPatterns", "IDパターン", "ACTOR-*, ROLE-*", doc)
            CompactScalar(map, "relation", "関係種別", doc)
            val scope = ensureMap(map, "scope", doc, mark = false)
            StringListEditor(scope, "schemaIds", "対象スキーマID", "system-concepts, actor-detail", doc)
            StringListEditor(scope, "documentIds", "対象文書ID", "system-overview, actor-design", doc)
        }
    }
    val currentType = scalar(map, "type") ?: "string"
    if (currentType !in setOf("object", "array", "map")) DefaultValueEditor(map, currentType, doc)
}

@Composable
private fun ChoiceButton(value: String, choices: List<String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text(value, fontSize = 11.sp) }
        DropdownMenu(open, { open = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(text = { Text(choice) }, onClick = { onSelect(choice); open = false })
            }
        }
    }
}

@Composable
private fun EnumValuesEditor(map: YNode.Map, doc: OpenDocument, definitions: List<String>, depth: Int) {
    StringListEditor(map, "values", "選択肢", "draft, review, approved", doc)
    val values = (map.entries["values"] as? YNode.Seq)?.items?.mapNotNull { (it as? YNode.Scalar)?.value?.toString() }.orEmpty()
    // 選択肢から消えた値の cases は残骸なので警告つきで削除できるようにする
    (map.entries["cases"] as? YNode.Map)?.entries?.keys?.filter { it !in values }?.toList()?.forEach { stale ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("cases「$stale」は選択肢にありません", fontSize = 11.sp, color = Palette.WarnOrange)
            TextButton(onClick = {
                val cases = map.entries["cases"] as? YNode.Map ?: return@TextButton
                cases.entries.remove(stale)
                if (cases.entries.isEmpty()) map.entries.remove("cases")
                doc.markEdited()
            }) { Text("削除", fontSize = 10.sp, color = Palette.ErrorRed) }
        }
    }
    values.distinct().forEach { value ->
        val caseSeq = (map.entries["cases"] as? YNode.Map)?.entries?.get(value) as? YNode.Seq
        if (caseSeq == null) {
            TextButton(onClick = {
                ensureMap(map, "cases", doc, mark = false).entries[value] = YNode.Seq()
                doc.markEdited()
            }) { Text("＋ 「$value」専用のフィールドを定義", fontSize = 11.sp) }
        } else {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, top = 5.dp).border(1.dp, Palette.Border, PanelShape).padding(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("「$value」のときのフィールド", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        val cases = map.entries["cases"] as? YNode.Map ?: return@TextButton
                        cases.entries.remove(value)
                        if (cases.entries.isEmpty()) map.entries.remove("cases")
                        doc.markEdited()
                    }) { Text("削除", fontSize = 10.sp, color = Palette.ErrorRed) }
                }
                caseSeq.items.forEachIndexed { index, node ->
                    val child = node as? YNode.Map ?: return@forEachIndexed
                    Column(Modifier.padding(start = 12.dp, top = 5.dp).border(1.dp, Palette.Border, PanelShape).padding(6.dp)) {
                        Row {
                            Text("フィールド ${index + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { caseSeq.items.removeAt(index); doc.markEdited() }) { Text("削除", color = Palette.ErrorRed, fontSize = 10.sp) }
                        }
                        FieldDefinitionEditor(child, doc, definitions, allowKey = true, depth = depth + 1)
                    }
                }
                TextButton(onClick = { caseSeq.items.add(newFieldMap("string")); doc.markEdited() }) { Text("＋ フィールドを追加", fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun StringListEditor(map: YNode.Map, key: String, label: String, hint: String, doc: OpenDocument) {
    val seq = map.entries[key] as? YNode.Seq
    val value = seq?.items?.mapNotNull { (it as? YNode.Scalar)?.value?.toString() }?.joinToString(", ").orEmpty()
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, modifier = Modifier.width(140.dp))
        CompactTextField(value, { raw ->
            map.entries[key] = YNode.Seq(raw.split(',').map { YNode.Scalar(it.trim()) }.filter { it.value.toString().isNotBlank() }.toMutableList())
            doc.markEdited()
        }, singleLine = true, placeholder = { Text(hint) }, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DefaultValueEditor(map: YNode.Map, type: String, doc: OpenDocument) {
    val value = (map.entries["default"] as? YNode.Scalar)?.value?.toString().orEmpty()
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("初期値", fontSize = 12.sp, modifier = Modifier.width(140.dp))
        CompactTextField(value, { raw ->
            if (raw.isEmpty()) map.entries.remove("default") else {
                val typed: Any = when (type) {
                    "boolean" -> raw.toBooleanStrictOrNull() ?: raw
                    "int" -> raw.toLongOrNull() ?: raw
                    "number" -> raw.toDoubleOrNull() ?: raw
                    else -> raw
                }
                map.entries["default"] = YNode.Scalar(typed)
            }
            doc.markEdited()
        }, singleLine = true, placeholder = { Text("未設定", color = Palette.SubText) }, modifier = Modifier.weight(1f))
    }
}

private fun scalar(map: YNode.Map, key: String): String? = (map.entries[key] as? YNode.Scalar)?.value?.toString()?.takeIf { it.isNotBlank() }
private fun ensureMap(parent: YNode.Map, key: String, doc: OpenDocument, mark: Boolean): YNode.Map =
    (parent.entries[key] as? YNode.Map) ?: YNode.Map().also { parent.entries[key] = it; if (mark) doc.markEdited() }
private fun ensureSeq(parent: YNode.Map, key: String, doc: OpenDocument, mark: Boolean): YNode.Seq =
    (parent.entries[key] as? YNode.Seq) ?: YNode.Seq().also { parent.entries[key] = it; if (mark) doc.markEdited() }
private fun newFieldMap(type: String) = YNode.Map(linkedMapOf<String, YNode>("key" to YNode.Scalar(""), "type" to YNode.Scalar(type)))
