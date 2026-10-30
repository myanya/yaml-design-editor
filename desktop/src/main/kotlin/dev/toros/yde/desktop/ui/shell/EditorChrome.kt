package dev.toros.yde.desktop.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDropDown
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
import dev.toros.yde.app.core.DocViewDef
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.EditorTab
import dev.toros.yde.desktop.FormSurface
import dev.toros.yde.desktop.OpenDocument
import dev.toros.yde.desktop.ui.common.ActionMenuItem
import dev.toros.yde.desktop.ui.common.ChoiceMenu
import dev.toros.yde.desktop.ui.common.ChoiceOption
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.desktop.ui.common.PanelShape
import dev.toros.yde.desktop.ui.common.UiTooltip
import dev.toros.yde.desktop.ui.form.FormDisplayControls

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

/**
 * 編集方式とスキーマの補助画面を一段で切り替える。保存済みのタブの値は変更しない。
 * 業務の文書は「フォーム・ビュー・ソース・差分」。docViews が null ならビューのタブを出さない（スキーマの文書・台帳の外の文書）。
 * 空なら押せない形で出し、文書ビューの書き方を添える。複数当たるときはビューのタブの ▾ で選び、選んだものを文書ごとに覚える。
 */
@Composable
internal fun EditorModeBar(
    state: AppState, doc: OpenDocument, schemaDocument: Boolean, tab: EditorTab,
    docViews: List<DocViewDef>? = null, chosenView: DocViewDef? = null,
) {
    val document = state.documentOf(doc.file)
    val schemaMode = state.memory.schemaTab[document] ?: 0
    var menu by remember(doc) { mutableStateOf(false) }
    var viewMenu by remember(doc) { mutableStateOf(false) }
    fun openSchema(mode: Int) { state.memory.schemaTab[document] = mode; state.editorTab = EditorTab.FORM }
    Row(Modifier.fillMaxWidth().background(Palette.Surface).padding(horizontal = 8.dp).height(40.dp),
        verticalAlignment = Alignment.CenterVertically) {
        EditorModeButton(if (schemaDocument) "設計" else "フォーム", tab == EditorTab.FORM && (!schemaDocument || schemaMode == 0)) {
            if (schemaDocument) openSchema(0) else state.editorTab = EditorTab.FORM
        }
        if (docViews != null) {
            if (docViews.isEmpty()) {
                UiTooltip("この文書に当たる文書ビューはありません。ビューの定義（.yde/views）に kind: document と、" +
                    "適用先の match（ファイルの glob）か schemaId を書くと、ここで表示を合成できます") {
                    EditorModeButton("ビュー", selected = false, enabled = false) {}
                }
            } else {
                EditorModeButton("ビュー: ${chosenView?.name ?: docViews.first().name}", tab == EditorTab.VIEW) {
                    state.editorTab = EditorTab.VIEW
                }
                if (docViews.size > 1) Box {
                    UiTooltip("当たる文書ビューを選ぶ（${docViews.size} 件）") {
                        IconButton(onClick = { viewMenu = true }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.ArrowDropDown, "ビューを選ぶ", Modifier.size(18.dp))
                        }
                    }
                    ChoiceMenu(viewMenu, { viewMenu = false }, docViews.map { v ->
                        ChoiceOption(v.id, v.name, v.id, selected = v.id == chosenView?.id)
                    }, onSelect = { id -> state.memory.docViewChoice[document] = id; state.editorTab = EditorTab.VIEW })
                }
            }
        }
        EditorModeButton("ソース", tab == EditorTab.SOURCE) { state.editorTab = EditorTab.SOURCE }
        EditorModeButton("差分", tab == EditorTab.DIFF) { state.editorTab = EditorTab.DIFF }
        Spacer(Modifier.weight(1f))
        if (tab.formFamily && (!schemaDocument || schemaMode == 0) || tab == EditorTab.META_FORM) {
            val surface = when {
                tab == EditorTab.VIEW -> FormSurface.VIEW
                schemaDocument && tab == EditorTab.FORM -> FormSurface.SCHEMA
                else -> FormSurface.FORM
            }
            FormDisplayControls(state.memory.formDisplay, document, state.memory.navigation(document, surface))
        }
        if (schemaDocument) {
            val auxiliary = when {
                tab == EditorTab.META_FORM -> "汎用フォーム"
                tab == EditorTab.FORM && schemaMode == 2 -> "適用対象"
                else -> "プレビュー"
            }
            EditorModeButton(auxiliary, tab == EditorTab.META_FORM || tab == EditorTab.FORM && schemaMode != 0) {
                when (auxiliary) {
                    "汎用フォーム" -> state.editorTab = EditorTab.META_FORM
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
                    ChoiceOption(1, "プレビュー", selected = tab == EditorTab.FORM && schemaMode == 1),
                    ChoiceOption(2, "適用対象", selected = tab == EditorTab.FORM && schemaMode == 2),
                    ChoiceOption(3, "汎用フォーム", selected = tab == EditorTab.META_FORM),
                ), onSelect = { if (it == 3) state.editorTab = EditorTab.META_FORM else openSchema(it) })
            }
        }
    }
}

@Composable
private fun EditorModeButton(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.width(IntrinsicSize.Max).widthIn(min = 56.dp, max = 320.dp).fillMaxHeight()
        .selectable(selected, enabled = enabled, role = Role.Tab, onClick = onClick)) {
        Box(Modifier.weight(1f).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 12.sp,
                color = when { !enabled -> Palette.Border; selected -> Palette.Primary; else -> Palette.SubText },
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) Palette.Primary else Palette.Surface))
    }
}
