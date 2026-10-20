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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AccountTree
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
    val definitions = root.entries["definitions"] as? YNode.Map
    val choices = (definitions?.entries?.keys.orEmpty() + state.schemas.filter { it.id != schema?.id }.flatMap { module ->
        module.definitions.keys.map { "${module.id}#$it" }
    }).distinct().sorted()
    SchemaStructureEditor(ctx, root, library, schema, types, choices, problems, onRename, onDelete, onAlign)
}

@Composable
private fun SchemaBasicSettings(ctx: FormCtx, root: YNode.Map, library: Boolean) {
    val doc = ctx.doc
    doc.version
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
    ctx: FormCtx, root: YNode.Map, library: Boolean, schema: SchemaDef?, types: TypeChoices,
    definitions: List<String>, problems: List<String>,
    onRename: ((List<String>) -> Unit)?, onDelete: ((List<String>) -> Unit)?, onAlign: () -> Unit,
) {
    val doc = ctx.doc
    doc.version
    val memory = ctx.state.memory
    val document = ctx.state.documentOf(doc.file)
    val named = root.entries["definitions"] as? YNode.Map ?: remember(root) { YNode.Map() }
    val section = (memory.schemaSection[document] ?: if (library) 1 else 0).let { if (library && it == 0) 1 else it }
    val name = memory.schemaDefinition[document]?.takeIf { it in named.entries } ?: named.entries.keys.firstOrNull()
    val base: YPath = when (section) {
        2 -> listOf("@settings")
        1 -> listOf("definitions", name.orEmpty())
        else -> listOf("root")
    }
    val groups = linkedMapOf<YPath, List<SchemaOutlineEntry>>()
    if (!library) groups[listOf("root")] = schemaOutline(root.entries["root"], listOf("root"), "ルート", types::baseKindOf, emptyList())
        .mapIndexed { i, entry -> if (i == 0) entry.copy(title = "ルート") else entry }
    named.entries.forEach { (definition, node) ->
        val path = listOf("definitions", definition)
        groups[path] = schemaOutline(node, path, definition, types::baseKindOf)
            .mapIndexed { i, entry -> if (i == 0) entry.copy(title = definition) else entry }
    }
    val entries = groups[base].orEmpty()
    val memoryKey = document to base
    var previous by remember(base) { mutableStateOf<Pair<YPath, String?>?>(null) }
    val stored = memory.schemaSelection[memoryKey]
    val selected = entries.takeIf { it.isNotEmpty() }?.let {
        schemaSelectionAfterEdit(it, stored, previous?.takeIf { it.first == stored }?.second)
    }
    SideEffect {
        if (selected != null) {
            memory.schemaSelection[memoryKey] = selected.path
            previous = selected.path to (selected.node as? YNode.Map)?.let { scalar(it, "key") }
        }
    }
    fun select(path: YPath) {
        previous = null
        val target = schemaNavigationTarget(path)
        memory.schemaSection[document] = target.section
        target.definition?.let { memory.schemaDefinition[document] = it }
        target.base?.let { selectedBase ->
            memory.schemaSelection[document to selectedBase] = path
            groups[selectedBase].orEmpty().filter { it.path.size < path.size && path.take(it.path.size) == it.path }
                .forEach { ctx.setExpanded(it.path, true) }
        }
        memory.schemaNavigationOpen[document] = false
    }
    LaunchedEffect(ctx.state.highlightPath) {
        ctx.state.highlightPath?.let(::select)
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
    val outline: @Composable (Modifier, Boolean) -> Unit = { modifier, narrow ->
        Column(modifier.background(Palette.PanelBg, PanelShape).border(1.dp, Palette.Border, PanelShape)) {
            Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("構成", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                SchemaProblems(problems)
                if (narrow) SchemaIconAction("構成を閉じる", Icons.Default.Close, true) {
                    memory.schemaNavigationOpen[document] = false
                }
            }
            SchemaScroll(Modifier.weight(1f).fillMaxWidth(), padding = false) {
                Text("基本設定", fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().background(if (section == 2) Palette.Selected else Palette.PanelBg)
                        .selectable(section == 2, role = Role.RadioButton, onClick = { select(listOf("@settings")) })
                        .padding(horizontal = 12.dp, vertical = 10.dp))
                groups.forEach { (group, rows) ->
                    if (group.firstOrNull() == "definitions" && group == groups.keys.firstOrNull { it.firstOrNull() == "definitions" }) {
                        SchemaDefinitionsHeader(named, doc) { definition ->
                            root.entries["definitions"] = named
                            select(listOf("definitions", definition))
                        }
                    }
                    val visible = mutableSetOf<YPath>()
                    rows.forEach { entry ->
                        val parent = rows.firstOrNull { it.path == entry.parent }
                        if (parent == null || parent.path in visible && ctx.expanded(parent.path, parent.path.firstOrNull() == "root" && parent.depth < 2 || parent.path == base)) {
                            visible.add(entry.path)
                            val children = rows.any { it.parent == entry.path }
                            val requester = remember(entry.path) { BringIntoViewRequester() }
                            LaunchedEffect(selected?.path, entry.path) {
                                if (selected?.path == entry.path) requester.bringIntoView()
                            }
                            Row(Modifier.fillMaxWidth().bringIntoViewRequester(requester).background(if (selected?.path == entry.path) Palette.Selected else Palette.PanelBg)
                                .selectable(selected?.path == entry.path, role = Role.RadioButton, onClick = { select(entry.path) })
                                .padding(start = (entry.depth * 12).coerceAtMost(48).dp, end = 8.dp).heightIn(min = 48.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (children) IconButton(onClick = { ctx.setExpanded(entry.path, !ctx.expanded(entry.path, entry.path.firstOrNull() == "root" && entry.depth < 2 || entry.path == base)) },
                                    modifier = Modifier.size(32.dp)) {
                                    Icon(if (ctx.expanded(entry.path, entry.path.firstOrNull() == "root" && entry.depth < 2 || entry.path == base)) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        "${entry.title}の子項目を開閉", modifier = Modifier.size(18.dp))
                                } else Spacer(Modifier.width(32.dp))
                                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                    val key = (entry.node as? YNode.Map)?.let { scalar(it, "key") }
                                    Text(entry.title, fontSize = 13.sp, fontWeight = if (selected?.path == entry.path) FontWeight.SemiBold else FontWeight.Normal,
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
                if (named.entries.isEmpty()) {
                    SchemaDefinitionsHeader(named, doc) { definition ->
                        root.entries["definitions"] = named
                        select(listOf("definitions", definition))
                    }
                    Text("再利用する型や構造を追加できます", fontSize = 11.sp, color = Palette.SubText,
                        modifier = Modifier.padding(12.dp))
                }
                if (schema?.kind == SchemaKind.DOCUMENT && schema.derive == null) {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { menu = true }) { Text("スキーマ操作 ▾", fontSize = 12.sp) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("文書を定義順へ整列…") }, enabled = !ctx.readOnly,
                                onClick = { menu = false; onAlign() })
                        }
                    }
                }
            }
        }
    }
    val detail: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.background(Palette.Surface, PanelShape).border(1.dp, Palette.Border, PanelShape)) {
            if (selected == null) {
                SchemaScroll(Modifier.fillMaxSize()) {
                    if (section == 2) SchemaBasicSettings(ctx, root, library)
                    else Text("構成から名前付き定義を追加してください。", fontSize = 13.sp, color = Palette.SubText)
                }
            } else {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        UiTooltip(selected.path.pretty()) {
                            Text(selected.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    val map = selected.node as? YNode.Map
                    val canAdd = selected.caseGroup && selected.node is YNode.Seq ||
                        map != null && scalar(map, "\$ref") == null && types.baseKindOf(scalar(map, "type") ?: "string") == "object"
                    val fieldsValid = map?.entries?.get("fields").let { it == null || it is YNode.Seq }
                    if (canAdd && fieldsValid) SchemaEditButton(onClick = { addField(selected) }) {
                        Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Text("追加", fontSize = 12.sp)
                    }
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
                            selected.caseGroup -> Text("条件に合うときだけ使う項目です。上の「追加」から作成し、構成一覧で選んで編集します。", fontSize = 13.sp)
                            selected.node is YNode.Map -> {
                                FieldDefinitionEditor(selected.node, doc, definitions, selected.allowKey, ctx, selected.path,
                                    definitionName = if (selected.path == base && base.firstOrNull() == "definitions") base.last() as? String else null)
                                if (entries.any { it.parent == selected.path }) Text("子項目の設定は構成一覧で選択してください。",
                                    fontSize = 11.sp, color = Palette.SubText, modifier = Modifier.padding(top = FormSpacing.GroupGap))
                            }
                            else -> Text("定義の形式を確認してください。「汎用フォーム」または「ソース」で修正できます。", color = Palette.ErrorRed, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp)) {
        val outlineWidth = (maxWidth * .34f).coerceAtMost(320.dp)
        if (maxWidth >= 680.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            outline(Modifier.width(outlineWidth).fillMaxHeight(), false)
            detail(Modifier.weight(1f).fillMaxHeight())
        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val navigationOpen = memory.schemaNavigationOpen[document] ?: false
            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { memory.schemaNavigationOpen[document] = !navigationOpen }, shape = PanelShape) {
                    Icon(Icons.Default.AccountTree, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (navigationOpen) "編集に戻る" else "構成を開く", fontSize = 12.sp)
                }
                Text(if (section == 2) "基本設定" else selected?.title ?: "名前付き定義", fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (!navigationOpen) SchemaProblems(problems)
            }
            if (navigationOpen) outline(Modifier.fillMaxWidth().weight(1f), true)
            else detail(Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun SchemaDefinitionsHeader(named: YNode.Map, doc: OpenDocument, onAdded: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("名前付き定義", fontSize = 11.sp, color = Palette.SubText, modifier = Modifier.weight(1f))
        AddDefinitionButton(named, doc, compact = true, onAdded = onAdded)
    }
}

@Composable
private fun SchemaProblems(problems: List<String>) {
    if (problems.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("確認 ${problems.size} 件", fontSize = 11.sp, color = Palette.ErrorRed) }
        DropdownMenu(open, { open = false }) {
            Column(Modifier.widthIn(max = 440.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                problems.forEach { Text(it, fontSize = 12.sp, color = Palette.ErrorRed) }
            }
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
