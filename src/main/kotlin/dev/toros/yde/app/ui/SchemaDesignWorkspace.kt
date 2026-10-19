package dev.toros.yde.app.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.AppState
import dev.toros.yde.app.OpenDocument
import dev.toros.yde.app.schema.SchemaDef
import dev.toros.yde.semantic.schema.SchemaKind
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.pretty

internal val LocalSchemaReadOnly = compositionLocalOf { false }

/** 既存の設定入力も、新しい構造編集と同じ閲覧専用状態・共通部品を使う。 */
@Composable
internal fun SchemaTextField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    singleLine: Boolean = false, textStyle: TextStyle = TextStyle(fontSize = 13.sp),
    placeholder: (@Composable () -> Unit)? = null,
) = CompactTextField(value, onValueChange, modifier, singleLine = singleLine,
    textStyle = textStyle, placeholder = placeholder, readOnly = LocalSchemaReadOnly.current)

@Composable
internal fun SchemaEditButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = TextButton(onClick, modifier, enabled = enabled && !LocalSchemaReadOnly.current, shape = PanelShape, content = content)

@Composable
internal fun SchemaCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit) =
    Checkbox(checked, onCheckedChange, enabled = !LocalSchemaReadOnly.current)

@Composable
internal fun SchemaDeleteButton(title: String, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    SchemaEditButton(onClick = { confirm = true }) { Text("削除", color = Palette.ErrorRed, fontSize = 12.sp) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(title) },
        text = { Text("この設定を削除します。") },
        confirmButton = { SchemaEditButton(onClick = { onDelete(); confirm = false }) { Text("削除", color = Palette.ErrorRed) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("キャンセル") } })
}

@Composable
internal fun SchemaDesignWorkspace(
    state: AppState, doc: OpenDocument, root: YNode.Map, library: Boolean,
    schema: SchemaDef?, types: TypeChoices, problems: List<String>,
    onRename: ((List<String>) -> Unit)?, onDelete: ((List<String>) -> Unit)?, onAlign: () -> Unit,
) {
    doc.version
    val ctx = FormCtx(state, doc, emptyList())
    val document = state.documentOf(doc.file)
    val section = (state.memory.schemaSection[document] ?: if (library) 1 else 0).let {
        if (library && it == 0) 1 else it
    }
    val definitions = root.entries["definitions"] as? YNode.Map ?: remember(root) { YNode.Map() }
    val choices = (definitions.entries.keys + state.schemas.filter { it.id != schema?.id }.flatMap { module ->
        module.definitions.keys.map { "${module.id}#$it" }
    }).distinct().sorted()
    // 検証からの移動では、該当する区画と定義を選んでから一覧の選択へ引き渡す。
    LaunchedEffect(state.highlightPath) {
        val target = state.highlightPath ?: return@LaunchedEffect
        when (target.firstOrNull()) {
            "root" -> if (!library) state.memory.schemaSection[document] = 0
            "definitions" -> {
                state.memory.schemaSection[document] = 1
                (target.getOrNull(1) as? String)?.let { state.memory.schemaDefinition[document] = it }
            }
            else -> state.memory.schemaSection[document] = 2
        }
    }
    val problemsPath = listOf("@schema-problems")
    val problemsOpen = ctx.expanded(problemsPath, false)
    Column(Modifier.fillMaxSize().background(Palette.EditorBg).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("項目構成", "名前付き定義", "基本設定").forEachIndexed { index, label ->
                if (index != 0 || !library) FilterChip(selected = section == index,
                    onClick = { state.memory.schemaSection[document] = index }, label = { Text(label, fontSize = 12.sp) },
                    shape = PanelShape)
            }
            Spacer(Modifier.weight(1f))
            if (problems.isNotEmpty()) TextButton(onClick = { ctx.setExpanded(problemsPath, !problemsOpen) }) {
                Text("${if (problemsOpen) "▾" else "▸"} 確認 ${problems.size} 件", color = Palette.ErrorRed, fontSize = 12.sp)
            }
        }
        if (problems.isNotEmpty() && problemsOpen) Column(
            Modifier.fillMaxWidth().heightIn(max = 130.dp).verticalScroll(rememberScrollState()),
        ) { problems.forEach { Text(it, fontSize = 11.sp, color = Palette.ErrorRed) } }
        when (section) {
            0 -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ルートの項目構成", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (schema?.kind == SchemaKind.DOCUMENT && schema.derive == null) {
                        SchemaEditButton(onAlign) { Text("文書を定義順へ整列…", fontSize = 12.sp) }
                    }
                }
                SchemaStructureEditor(ctx, listOf("root"), "ルート", types, choices, onRename, onDelete)
            }
            1 -> {
                val name = state.memory.schemaDefinition[document]?.takeIf { it in definitions.entries }
                    ?: definitions.entries.keys.firstOrNull()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 定義の切替は閲覧専用でも許可する。
                    if (name != null) CompositionLocalProvider(LocalSchemaReadOnly provides false) {
                        ChoiceButton(name, definitions.entries.keys.toList()) { state.memory.schemaDefinition[document] = it }
                    }
                    Spacer(Modifier.weight(1f))
                    AddDefinitionButton(definitions, doc) {
                        root.entries["definitions"] = definitions
                        state.memory.schemaDefinition[document] = it
                    }
                }
                if (name == null) Text("再利用する型や構造を追加できます。項目の「型」または「定義参照」で選んで使います。",
                    fontSize = 13.sp, color = Palette.SubText)
                else SchemaStructureEditor(ctx, listOf("definitions", name), name, types, choices, null, null)
            }
            else -> SchemaScroll(Modifier.weight(1f).fillMaxWidth()) {
                Text("基本設定", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                CompactScalar(root, "id", "スキーマID", doc)
                CompactScalar(root, "name", "スキーマ名", doc)
                SchemaKindEditor(root, doc, library)
                CompactMultilineScalar(root, "description", "説明", doc)
                if (!library) {
                    CompactScalar(root, "match", "適用パターン", doc)
                    SchemaSettingsGroup(ctx, "phases", "フェーズ") { PhasesEditor(root, doc) }
                    SchemaSettingsGroup(ctx, "coverage", "カバレッジ") { CoverageEditor(root, doc) }
                    SchemaSettingsGroup(ctx, "entity", "エンティティ") { EntityEditor(root, doc) }
                }
            }
        }
    }
}

@Composable
private fun SchemaSettingsGroup(ctx: FormCtx, key: String, title: String, content: @Composable () -> Unit) =
    SchemaPropertyGroup(ctx, listOf(key), title, content)

@Composable
internal fun SchemaPropertyGroup(ctx: FormCtx, path: YPath, title: String, content: @Composable () -> Unit) {
    val expanded = ctx.expanded(path, false)
    Column(Modifier.fillMaxWidth().padding(top = FormSpacing.GroupGap).border(1.dp, Palette.Border, PanelShape).padding(8.dp)) {
        TextButton(onClick = { ctx.setExpanded(path, !expanded) }) {
            Text("${if (expanded) "▾" else "▸"} $title", fontSize = 13.sp)
        }
        if (expanded) content()
    }
}

@Composable
private fun SchemaStructureEditor(
    ctx: FormCtx, base: YPath, title: String, types: TypeChoices, definitions: List<String>,
    onRename: ((List<String>) -> Unit)?, onDelete: ((List<String>) -> Unit)?,
) {
    val doc = ctx.doc
    doc.version
    val memory = ctx.state.memory
    val memoryKey = ctx.state.documentOf(doc.file) to base
    val entries = schemaOutline(doc.root?.let { getAt(it, base) }, base, title, types::baseKindOf,
        if (base == listOf("root")) emptyList() else null)
    var previous by remember(base) { mutableStateOf<Pair<YPath, String?>?>(null) }
    val stored = memory.schemaSelection[memoryKey]
    val selected = schemaSelectionAfterEdit(entries, stored, previous?.takeIf { it.first == stored }?.second)
    SideEffect {
        memory.schemaSelection[memoryKey] = selected.path
        previous = selected.path to (selected.node as? YNode.Map)?.let { scalar(it, "key") }
    }
    fun select(path: YPath) {
        previous = null
        memory.schemaSelection[memoryKey] = path
        entries.filter { it.path.size < path.size && path.take(it.path.size) == it.path }
            .forEach { ctx.setExpanded(it.path, true) }
    }
    LaunchedEffect(ctx.state.highlightPath, base) {
        val target = ctx.state.highlightPath
        if (target != null && target.take(base.size) == base) select(schemaOutlineSelection(entries, target).path)
    }
    var deleteTarget by remember(base) { mutableStateOf<SchemaOutlineEntry?>(null) }
    deleteTarget?.let { target ->
        AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("「${target.title}」を削除") },
            text = { Text("この定義と配下の項目を削除します。適用先の文書データは変更しません。") },
            confirmButton = { SchemaEditButton(onClick = {
                schemaSlot(doc.root, target.path)?.remove()
                select(target.parent ?: base)
                ctx.markEdited(); deleteTarget = null
            }) { Text("削除", color = Palette.ErrorRed) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("キャンセル") } })
    }
    fun addField(entry: SchemaOutlineEntry) {
        val map = entry.node as? YNode.Map
        val seq = if (entry.caseGroup) entry.node as? YNode.Seq
            else map?.entries?.get("fields") as? YNode.Seq ?: YNode.Seq().also { map?.entries?.set("fields", it) }
        if (seq == null) return
        val path = if (entry.caseGroup) entry.path else entry.path + "fields"
        seq.items.add(newFieldMap("string"))
        ctx.setExpanded(entry.path, true)
        select(path + seq.items.lastIndex)
        ctx.markEdited()
    }
    val outline: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.background(Palette.PanelBg, PanelShape).border(1.dp, Palette.Border, PanelShape)) {
            Text("構成 · ${entries.count { it.allowKey }} 項目", fontSize = 12.sp, color = Palette.SubText,
                modifier = Modifier.padding(12.dp))
            SchemaScroll(Modifier.weight(1f).fillMaxWidth(), padding = false) {
                val visible = mutableSetOf<YPath>()
                entries.forEach { entry ->
                    val parent = entries.firstOrNull { it.path == entry.parent }
                    if (parent == null || parent.path in visible && ctx.expanded(parent.path, parent.depth < 2)) {
                        visible.add(entry.path)
                        val children = entries.any { it.parent == entry.path }
                        val requester = remember(entry.path) { BringIntoViewRequester() }
                        LaunchedEffect(selected.path, entry.path) {
                            if (selected.path == entry.path) requester.bringIntoView()
                        }
                        Row(Modifier.fillMaxWidth().bringIntoViewRequester(requester).background(if (selected.path == entry.path) Palette.Selected else Palette.PanelBg)
                            .selectable(selected.path == entry.path, role = Role.RadioButton, onClick = { select(entry.path) })
                            .padding(start = (entry.depth * 12).coerceAtMost(48).dp, end = 8.dp).heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (children) IconButton(onClick = { ctx.setExpanded(entry.path, !ctx.expanded(entry.path, entry.depth < 2)) },
                                modifier = Modifier.size(32.dp)) {
                                Icon(if (ctx.expanded(entry.path, entry.depth < 2)) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    "${entry.title}の子項目を開閉", modifier = Modifier.size(18.dp))
                            } else Spacer(Modifier.width(32.dp))
                            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                val key = (entry.node as? YNode.Map)?.let { scalar(it, "key") }
                                Text(entry.title, fontSize = 13.sp, fontWeight = if (selected.path == entry.path) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOfNotNull(key?.takeIf { it != entry.title }, entry.type).joinToString(" · "),
                                    fontSize = 11.sp, color = Palette.SubText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            val map = entry.node as? YNode.Map
                            if ((map?.entries?.get("required") as? YNode.Scalar)?.value == true) {
                                Text("必須", fontSize = 11.sp, color = Palette.Primary)
                            } else if (map?.let { scalar(it, "requiredFrom") } != null) {
                                Text("条件付", fontSize = 11.sp, color = Palette.SubText)
                            }
                        }
                    }
                }
            }
        }
    }
    val detail: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.background(Palette.Surface, PanelShape).border(1.dp, Palette.Border, PanelShape)) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(selected.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(selected.path.pretty(), fontSize = 11.sp, color = Palette.SubText)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val map = selected.node as? YNode.Map
                    val canAdd = selected.caseGroup && selected.node is YNode.Seq ||
                        map != null && scalar(map, "\$ref") == null && types.baseKindOf(scalar(map, "type") ?: "string") == "object"
                    val fieldsValid = map?.entries?.get("fields").let { it == null || it is YNode.Seq }
                    if (canAdd && fieldsValid) SchemaEditButton(onClick = { addField(selected) }) {
                        Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Text("項目を追加", fontSize = 12.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    val seqPath = selected.path.dropLast(1)
                    val seq = doc.root?.let { getAt(it, seqPath) } as? YNode.Seq
                    val index = selected.path.lastOrNull() as? Int
                    if (seq != null && index != null) {
                        fun move(to: Int) {
                            seq.items.add(to, seq.items.removeAt(index))
                            select(movedSchemaSelection(selected.path, seqPath, index, to))
                            ctx.markEdited()
                        }
                        SchemaIconAction("上へ移動", Icons.Default.KeyboardArrowUp, !ctx.readOnly && index > 0) { move(index - 1) }
                        SchemaIconAction("下へ移動", Icons.Default.KeyboardArrowDown, !ctx.readOnly && index < seq.items.lastIndex) { move(index + 1) }
                    }
                    if (selected.node != null && (selected.parent != null || base.firstOrNull() == "definitions")) {
                        SchemaIconAction("定義を削除", Icons.Default.Delete, !ctx.readOnly) { deleteTarget = selected }
                    }
                    if (selected.allowKey && selected.fieldPath != null && (onRename != null || onDelete != null)) {
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            SchemaIconAction("文書も一括変更", Icons.Default.MoreVert, !ctx.readOnly) { menu = true }
                            DropdownMenu(menu, { menu = false }) {
                                if (onRename != null) DropdownMenuItem(text = { Text("文書も一括改名…") },
                                    onClick = { onRename(selected.fieldPath); menu = false })
                                if (onDelete != null) DropdownMenuItem(text = { Text("文書も一括削除…", color = Palette.ErrorRed) },
                                    onClick = { onDelete(selected.fieldPath); menu = false })
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = Palette.Border)
            key(selected.path) {
                SchemaScroll(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        selected.node == null -> {
                            Text("定義を追加すると、型と設定を編集できます。", fontSize = 13.sp, color = Palette.SubText)
                            SchemaEditButton(onClick = {
                                schemaSlot(doc.root, selected.path)?.set(YNode.Map(linkedMapOf("type" to YNode.Scalar(if (base == selected.path) "object" else "string"))))
                                ctx.markEdited()
                            }) { Text("定義を作成") }
                        }
                        selected.caseGroup -> Text("条件に合うときだけ使う項目です。上の「項目を追加」から作成し、構成一覧で選んで編集します。", fontSize = 13.sp)
                        selected.node is YNode.Map -> {
                            FieldDefinitionEditor(selected.node, doc, definitions, selected.allowKey, ctx, selected.path,
                                definitionName = if (selected.path == base && base.firstOrNull() == "definitions") base.last() as? String else null)
                            if (entries.any { it.parent == selected.path }) Text("子項目の設定は構成一覧で選択してください。",
                                fontSize = 11.sp, color = Palette.SubText, modifier = Modifier.padding(top = FormSpacing.GroupGap))
                        }
                        else -> Text("定義の形式を確認してください。「フォーム」または「ソース」で修正できます。", color = Palette.ErrorRed, fontSize = 13.sp)
                    }
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val outlineWidth = (maxWidth * .34f).coerceAtMost(340.dp)
        val outlineHeight = (maxHeight * .36f).coerceAtMost(240.dp)
        if (maxWidth >= 680.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            outline(Modifier.width(outlineWidth).fillMaxHeight())
            detail(Modifier.weight(1f).fillMaxHeight())
        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            outline(Modifier.fillMaxWidth().height(outlineHeight))
            detail(Modifier.fillMaxWidth().weight(1f))
        }
    }
}

private fun schemaSlot(root: YNode?, path: YPath): Slot? = when (val parent = root?.let { getAt(it, path.dropLast(1)) }) {
    is YNode.Map -> (path.lastOrNull() as? String)?.let { MapSlot(parent, it) }
    is YNode.Seq -> (path.lastOrNull() as? Int)?.let { SeqSlot(parent, it) }
    else -> null
}

@Composable
private fun SchemaIconAction(label: String, icon: ImageVector, enabled: Boolean, action: () -> Unit) {
    UiTooltip(label) {
        IconButton(action, enabled = enabled, modifier = Modifier.size(36.dp)) { Icon(icon, label, Modifier.size(18.dp)) }
    }
}

@Composable
private fun SchemaScroll(modifier: Modifier, padding: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Box(modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(if (padding) 12.dp else 0.dp).padding(end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(if (padding) FormSpacing.FieldGap else 0.dp), content = content)
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}
