package dev.toros.yde.desktop.ui.form

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.core.DocViewDef
import dev.toros.yde.app.index.IdEntry
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.desktop.ui.common.FormSpacing
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.desktop.ui.common.PanelShape
import dev.toros.yde.desktop.ui.common.slotAt
import dev.toros.yde.desktop.ui.shell.UiTooltip

internal val LocalActionTargetSelection = compositionLocalOf<((YPath) -> Unit)?> { null }

/** 参照セルではなく、その項目を保有する最も近い要素を対象とする。 */
internal fun actionTargetAt(ids: List<IdEntry>, path: YPath): String? =
    ids.filter { path.take(it.path.size) == it.path }.maxByOrNull { it.path.size }?.id

internal fun activeActionTarget(ids: List<IdEntry>, chosen: String?): String? =
    chosen?.takeIf { id -> ids.any { it.id == id } } ?: ids.firstOrNull { it.path.isEmpty() }?.id

/** 文書ビューの表示順・選別を反映し、隠された原本項目へスクロールさせない。 */
internal fun documentViewOutline(items: List<FormOutlineItem>, view: DocViewDef?): List<FormOutlineItem> {
    if (view == null) return items
    val visible = linkedMapOf<YPath, FormOutlineItem>()
    items.firstOrNull()?.let { visible[it.path] = it }
    items.filter { it.path.firstOrNull() == "_document" }.forEach { visible[it.path] = it }
    view.sections.forEachIndexed { i, section ->
        val path = section.fieldsPath
        if (path == null) {
            val key = listOf("@view", i)
            visible[key] = FormOutlineItem(key, emptyList(), section.label ?: "関連情報 ${i + 1}", depth = 1)
        } else {
            val sectionItems = items.filter { item ->
                item.path.take(path.size) == path && (item.path == path ||
                    (section.fieldsInclude.isEmpty() || item.path.getOrNull(path.size) in section.fieldsInclude) &&
                    item.path.getOrNull(path.size) !in section.fieldsExclude)
            }
            val ordered = if (section.fieldsInclude.isEmpty()) sectionItems else
                sectionItems.filter { it.path == path } + section.fieldsInclude.flatMap { key ->
                    sectionItems.filter { it.path.getOrNull(path.size) == key }
                }
            ordered.forEach { visible.putIfAbsent(it.path, it) }
        }
    }
    val preceding = mutableListOf<YPath>()
    return visible.values.map { item ->
        // 後続セクションに親が現れても、先行セクションの枝をその親の下へ隠さない。
        val projected = if (item.path.isEmpty()) item else item.copy(parent = nearestFormPath(preceding, item.parent))
        preceding.add(item.path)
        projected
    }
}

@Composable
internal fun DocumentFormWorkspace(ctx: FormCtx, root: YNode, view: DocViewDef?, localIds: List<IdEntry>) {
    val doc = ctx.doc
    doc.version
    val memory = ctx.state.memory.navigation(ctx.state.documentOf(doc.file), false)
    val items = remember(doc, doc.version, doc.schema, view) {
        val outline = documentOutline(root, doc.schema).filter { item ->
            item.path.firstOrNull() != "_document" || item.path.size <= 1 || item.path.getOrNull(1) in listOf("name", "description")
        }
        documentViewOutline(outline, view)
    }
    var previous by remember(doc) { mutableStateOf<List<FormOutlineItem>>(emptyList()) }
    val selectedPath = retainedFormSelection(items, memory.selected, previous)
    val selected = items.firstOrNull { it.path == selectedPath }
    SideEffect { memory.selected = selectedPath; previous = items }
    fun select(path: YPath) { previous = emptyList(); memory.selected = path }
    fun activate(path: YPath) { memory.actionTarget = actionTargetAt(localIds, path) }
    fun reveal(path: YPath) {
        // 表の行詳細も同じ畳みを使うので、セルの選択は行詳細を開いて到達する。
        for (size in 0..path.size) ctx.setExpanded(path.take(size), true)
    }
    val whole: @Composable () -> Unit = {
        FormAnchor(emptyList()) {
            Column(Modifier.fillMaxWidth()) {
                if (!ctx.state.relPath(doc.file).startsWith("_schemas/") && root is YNode.Map) {
                    FormAnchor(listOf("_document")) { DocumentMetadataEditor(ctx, root) }
                    SameIdOccurrencesStrip(ctx, localIds)
                    Spacer(Modifier.height(FormSpacing.GroupGap))
                }
                if (view != null && root is YNode.Map) DocViewSections(ctx, view, root, localIds)
                else SchemaRootForm(ctx, root)
            }
        }
    }
    CompositionLocalProvider(LocalActionTargetSelection provides ::activate) {
    StructuredForm(ctx, false, items, selectedPath, ::select, ::reveal, onActivate = ::activate,
        selectedContent = { modifier ->
            val scroll = remember(doc, selectedPath) { ScrollState(0) }
            Box(modifier.background(Palette.Surface, PanelShape).border(1.dp, Palette.Border, PanelShape)) {
                Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp)) {
                    if (selectedPath.isNullOrEmpty()) whole()
                    else if (selected != null) key(selectedPath) {
                        LaunchedEffect(doc, selectedPath) { ctx.setExpanded(selectedPath, true) }
                        UiTooltip(selectedPath.pretty()) { Text(selected.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                        Spacer(Modifier.height(8.dp))
                        val viewIndex = if (selectedPath.firstOrNull() == "@view") selectedPath.getOrNull(1) as? Int else null
                        if (viewIndex != null && view != null && root is YNode.Map) {
                            DocViewSections(ctx, view.copy(sections = listOf(view.sections[viewIndex])), root, localIds)
                        } else if (selectedPath == listOf("_document") && root is YNode.Map) {
                            DocumentMetadataEditor(ctx, root)
                        } else if (view != null && root is YNode.Map && view.sections.any { it.fieldsPath == selectedPath }) {
                            DocViewSections(ctx, view.copy(sections = view.sections.filter { it.fieldsPath == selectedPath }), root, localIds)
                        } else {
                            val slot = slotAt(root, selectedPath)
                            if (slot != null) {
                                val field = selected.field
                                if (field != null) FieldRow(ctx, field, slot, selectedPath)
                                else GenericEditor(ctx, slot, selectedPath, refHint = false)
                            }
                        }
                    }
                    Spacer(Modifier.height(40.dp))
                }
                VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }, wholeContent = whole)
    }
}
