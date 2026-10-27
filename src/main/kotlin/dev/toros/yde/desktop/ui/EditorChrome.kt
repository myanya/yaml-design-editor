package dev.toros.yde.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.HistoryTooltip
import dev.toros.yde.desktop.OpenDocument

/** 文書の状態と操作を一段に収める。補助情報は文書名から開く。 */
@Composable
internal fun EditorDocumentHeader(state: AppState, doc: OpenDocument, title: String) {
    doc.version
    val clipboard = LocalClipboardManager.current
    var information by remember(doc) { mutableStateOf(false) }
    var actionsOpen by remember(doc) { mutableStateOf(false) }
    val actions = remember(doc, state.indexVersion) { state.actionsForDocument(doc.file) }
    Row(Modifier.fillMaxWidth().background(Palette.Surface).padding(horizontal = 12.dp).heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { information = true }, shape = PanelShape,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)) {
                Text(title, color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(" ▾", color = Palette.SubText)
            }
            DropdownMenu(information, { information = false }) {
                Column(Modifier.widthIn(max = 440.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("文書情報", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(state.relPath(doc.file), fontSize = 12.sp, color = Palette.SubText)
                    Text(doc.schema?.let { "スキーマ: ${it.name}" } ?: "スキーマなし（汎用ビュー）", fontSize = 12.sp)
                }
                DropdownMenuItem(text = { Text("フルパスをコピー") }, onClick = {
                    clipboard.setText(AnnotatedString(doc.file.toAbsolutePath().normalize().toString()))
                    information = false
                })
            }
        }
        if (doc.dirty || doc.draftDirty) Text("● 未保存", fontSize = 11.sp, color = Palette.WarnOrange)
        if (state.readOnlyMode || doc.draftBroken) Text("閲覧専用", fontSize = 11.sp, color = Palette.WarnOrange,
            modifier = Modifier.background(Palette.ConflictBg, PanelShape).padding(horizontal = 6.dp, vertical = 3.dp))
        HistoryTooltip("元に戻す (Ctrl+Z)", state.undoPreview()) {
            IconButton(state::undoFromUi, enabled = state.canUndo(), modifier = Modifier.size(32.dp)) {
                Icon(Icons.AutoMirrored.Filled.Undo, "元に戻す (Ctrl+Z)", Modifier.size(17.dp))
            }
        }
        HistoryTooltip("やり直す (Ctrl+Y)", state.redoPreview()) {
            IconButton(state::redoFromUi, enabled = state.canRedo(), modifier = Modifier.size(32.dp)) {
                Icon(Icons.AutoMirrored.Filled.Redo, "やり直す (Ctrl+Y)", Modifier.size(17.dp))
            }
        }
        OutlinedButton(onClick = state::saveCurrent, enabled = doc.dirty || doc.draftDirty, shape = PanelShape,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) {
            Icon(Icons.Default.Save, null, Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text("保存", fontSize = 12.sp)
        }
        Box {
            UiTooltip("文書操作") {
                IconButton(onClick = { actionsOpen = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.MoreVert, "文書操作", Modifier.size(18.dp))
                }
            }
            DropdownMenu(actionsOpen, { actionsOpen = false }) {
                DropdownMenuItem(text = { Text("文書情報") }, onClick = { actionsOpen = false; information = true })
                if (doc.schema != null && doc.root != null) DropdownMenuItem(text = { Text("AIで作成…") },
                    enabled = !state.readOnlyMode && !doc.draftBroken, onClick = {
                        actionsOpen = false
                        state.requestAi(doc, doc.schema!!.root, emptyList(), wholeDocument = true)
                    })
                if (actions.isNotEmpty()) HorizontalDivider(color = Palette.Border)
                // 型の操作は閲覧専用でも実行できる。
                actions.forEach { entry ->
                    ActionMenuItem(state, entry, state.relPath(doc.file), onClick = {
                        actionsOpen = false
                        state.startAction(entry, state.relPath(doc.file))
                    })
                }
            }
        }
    }
}

/** 編集方式とスキーマの補助画面を一段で切り替える。保存済みのタブの値は変更しない。 */
@Composable
internal fun EditorModeBar(state: AppState, doc: OpenDocument, schemaDocument: Boolean, tab: Int) {
    val document = state.documentOf(doc.file)
    val schemaMode = state.memory.schemaTab[document] ?: 0
    var menu by remember(doc) { mutableStateOf(false) }
    fun openSchema(mode: Int) { state.memory.schemaTab[document] = mode; state.editorTab = 0 }
    Row(Modifier.fillMaxWidth().background(Palette.Surface).padding(horizontal = 8.dp).height(40.dp),
        verticalAlignment = Alignment.CenterVertically) {
        EditorModeButton(if (schemaDocument) "設計" else "フォーム", tab == 0 && (!schemaDocument || schemaMode == 0)) {
            if (schemaDocument) openSchema(0) else state.editorTab = 0
        }
        EditorModeButton("ソース", tab == 1) { state.editorTab = 1 }
        EditorModeButton("差分", tab == 2) { state.editorTab = 2 }
        Spacer(Modifier.weight(1f))
        if (tab == 0 && (!schemaDocument || schemaMode == 0) || tab == 3) {
            FormDisplayControls(state.memory.formDisplay, document, state.memory.navigation(document, schemaDocument && tab == 0))
        }
        if (schemaDocument) {
            val auxiliary = when {
                tab == 3 -> "汎用フォーム"
                tab == 0 && schemaMode == 2 -> "適用対象"
                else -> "プレビュー"
            }
            EditorModeButton(auxiliary, tab == 3 || tab == 0 && schemaMode != 0) {
                when (auxiliary) {
                    "汎用フォーム" -> state.editorTab = 3
                    "適用対象" -> openSchema(2)
                    else -> openSchema(1)
                }
            }
            Box {
                UiTooltip("表示する画面") {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.MoreVert, "表示する画面", Modifier.size(18.dp))
                    }
                }
                ChoiceMenu(menu, { menu = false }, listOf(
                    ChoiceOption(1, "プレビュー", selected = tab == 0 && schemaMode == 1),
                    ChoiceOption(2, "適用対象", selected = tab == 0 && schemaMode == 2),
                    ChoiceOption(3, "汎用フォーム", selected = tab == 3),
                ), onSelect = { if (it == 3) state.editorTab = 3 else openSchema(it) })
            }
        }
    }
}

@Composable
private fun EditorModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(IntrinsicSize.Max).widthIn(min = 56.dp).fillMaxHeight().selectable(selected, role = Role.Tab, onClick = onClick)) {
        Box(Modifier.weight(1f).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 12.sp, color = if (selected) Palette.Primary else Palette.SubText,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) Palette.Primary else Palette.Surface))
    }
}
