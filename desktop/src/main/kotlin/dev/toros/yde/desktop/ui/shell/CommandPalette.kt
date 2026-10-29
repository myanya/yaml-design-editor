package dev.toros.yde.desktop.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.key.*
import dev.toros.yde.app.index.IdEntry
import dev.toros.yde.yaml.model.parseYPath
import java.nio.file.Path
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.Help
import dev.toros.yde.desktop.UiSpace
import dev.toros.yde.desktop.ui.common.CompactTextField
import dev.toros.yde.desktop.ui.common.Palette

@Composable
internal fun CommandPalette(
    state: AppState,
    switchWorkspace: (Path) -> Unit,
    openWorkspaceDialog: () -> Unit,
    onShowRuns: () -> Unit,
    onRenameEntity: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val files = remember(query, state.yamlFiles, state.markdownFiles) {
        (state.yamlFiles + state.markdownFiles).filter { state.relPath(it).contains(query, true) }.take(10)
    }
    // 内蔵ヘルプ: 入力が無ければ目次だけ、あれば題名で絞る
    val helpTopics = remember(query) {
        val all = Help.topics()
        if (query.isBlank()) all.take(1) else all.filter { it.title.contains(query, true) || it.file.contains(query, true) }
    }
    // ID検索（R12-04）: 旧ツールバーのID検索ボックスを統合。ID・タイトル（表示解決 S-13）の部分一致。
    // 未保存で追加したIDも候補に出すためオーバーレイ索引で引く（UX-18。「見たまま判定」）
    val idMatches: List<IdEntry> = remember(query, state.indexVersion, state.editVersion) {
        if (query.isBlank()) emptyList()
        else state.overlayIndex().entries.values.asSequence()
            .mapNotNull { it.firstOrNull() }
            .filter { it.id.contains(query, true) || (it.title?.contains(query, true) == true) }
            .sortedBy { it.id }
            .take(10)
            .toList()
    }
    // 本文検索（R17-04）: 構造検索API（C-05）を未保存編集込みの「見たまま」で。2文字以上で発動
    val contentHits = remember(query, state.indexVersion, state.editVersion) {
        if (query.trim().length < 2) emptyList() else state.searchContent(query.trim(), limit = 10)
    }
    // ワークスペース切替候補（R10-05）: 現在以外の履歴。文字入力があれば絞り込む
    val workspaces = remember(query, state.recentWorkspaces, state.workspace) {
        state.recentWorkspaces.filter { it != state.workspace }
            .filter { query.isBlank() || it.toString().contains(query, true) }
            .take(5)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("検索・コマンド") },
        text = {
            Column(Modifier.width(520.dp).heightIn(max = 460.dp)) {
                CompactTextField(query, { query = it }, singleLine = true,
                    placeholder = { Text("ID・タイトル・ファイル・コマンドを検索…") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                idMatches.forEach { e ->
                    CommandItem("IDへ移動: ${e.id}" + (e.title?.let { " — $it" } ?: "")) { state.navigateToId(e.id); onDismiss() }
                }
                files.forEach { file -> CommandItem("開く: ${state.relPath(file)}") { state.openFile(file); onDismiss() } }
                contentHits.forEach { hit ->
                    CommandItem("本文: ${hit.file} · ${hit.path} — ${hit.excerpt}") {
                        state.workspace?.resolve(hit.file)?.let { abs ->
                            // キー名に . [ ] を含むパスは pretty 形式から復元できないため、ファイル先頭へのジャンプに落とす
                            val focus = runCatching { parseYPath(hit.path) }.getOrNull()?.takeIf { it.isNotEmpty() }
                            state.openFile(abs, focus)
                        }
                        onDismiss()
                    }
                }
                CommandItem("台帳: 一覧を表示") { state.showWorkspaceView(1); onDismiss() }
                CommandItem("ダッシュボードを表示") { state.openDashboard(); onDismiss() }
                CommandItem("台帳: ビューグリッドを表示") { state.openViewGrid(); onDismiss() }
                CommandItem("台帳: グラフレンズを表示") { state.openGraphLens(); onDismiss() }
                CommandItem("台帳: エンティティページを表示") { state.openCrossView(); onDismiss() }
                CommandItem("台帳: 準備完了レポートを表示") { state.showWorkspaceView(6); onDismiss() }
                // 操作（architecture/actions.md）: 受け手の無いアクションと、開いている文書を受け手にするアクション（閲覧専用でも出す）
                state.actionsForWorkspace().forEach { e ->
                    val available = remember(e, state.indexVersion, state.editVersion) { state.actionAvailability(e, null) }
                    CommandItem("操作: ${e.label ?: e.name}", available.enabled, available.reason) { state.startAction(e, null); onDismiss() }
                }
                state.openDoc?.let { d ->
                    state.actionsForDocument(d.file).forEach { e ->
                        val target = state.relPath(d.file)
                        val available = remember(e, target, state.indexVersion, state.editVersion) { state.actionAvailability(e, target) }
                        CommandItem("操作: ${e.label ?: e.name}（$target）", available.enabled, available.reason) { state.startAction(e, target); onDismiss() }
                    }
                }
                CommandItem("実行スペースを表示（受信箱）") { onShowRuns(); onDismiss() }
                CommandItem("設計スペースを表示") { state.showSpace(UiSpace.DESIGN); onDismiss() }
                helpTopics.forEach { topic -> CommandItem("ヘルプ: ${topic.title}") { state.openHelp(topic.file); onDismiss() } }
                CommandItem("エンティティを改名…") { onDismiss(); onRenameEntity() }
                CommandItem("すべて保存") { state.saveAll(); onDismiss() }
                CommandItem("ワークスペースを開く…") { onDismiss(); openWorkspaceDialog() }
                CommandItem("新しいワークスペースを作る…") { onDismiss(); state.startInitWizard() }
                workspaces.forEach { ws ->
                    CommandItem("ワークスペースを切替: $ws") { onDismiss(); switchWorkspace(ws) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

@Composable
private fun CommandItem(text: String, enabled: Boolean = true, reason: String? = null, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 7.dp)) {
        Text(text, fontSize = 12.sp, color = if (enabled) Palette.Text else Palette.SubText)
        reason?.let { Text(it, fontSize = 11.sp, color = Palette.SubText) }
    }
}
