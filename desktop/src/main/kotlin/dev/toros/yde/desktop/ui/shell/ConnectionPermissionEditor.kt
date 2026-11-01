package dev.toros.yde.desktop.ui.shell

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.service.ConnectionPermissions
import dev.toros.yde.app.service.ConnectionReadScope
import dev.toros.yde.app.service.ConnectionWriteScope
import dev.toros.yde.desktop.ui.common.CompactTextField
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.server.LocalFeature
import dev.toros.yde.server.auth.ClientRegistration
import dev.toros.yde.yaml.model.DocumentId

private fun permissionLines(text: String) = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toSet()
private fun permissionDocuments(text: String) = permissionLines(text).map(::DocumentId).toSet()
private fun ConnectionPermissions.isSimple() = readScope == ConnectionReadScope.WORKSPACE && readable.isEmpty() &&
    proposable.isEmpty() && committable.isEmpty() && actionable.isEmpty()

internal class ClientEditor(registration: ClientRegistration? = null, initialFeature: LocalFeature = LocalFeature.MCP) {
    val id = registration?.client?.id
    var name by mutableStateOf(registration?.client?.name.orEmpty())
    var feature by mutableStateOf(registration?.client?.feature ?: initialFeature)
    var simple by mutableStateOf(registration == null || registration.permissions.isSimple())
    var readScope by mutableStateOf(registration?.permissions?.readScope ?: ConnectionReadScope.WORKSPACE)
    var readable by mutableStateOf(registration?.permissions?.readable?.joinToString("\n") { it.value }.orEmpty())
    var proposable by mutableStateOf(registration?.permissions?.proposable?.joinToString("\n") { it.value }.orEmpty())
    var actionable by mutableStateOf(registration?.permissions?.actionable?.joinToString("\n") { it.value }.orEmpty())
    var actions by mutableStateOf(registration?.permissions?.actions?.joinToString("\n").orEmpty())
    var committable by mutableStateOf(registration?.permissions?.committable?.joinToString("\n") { it.value }.orEmpty())
    var proposalScope by mutableStateOf(registration?.permissions?.proposalScope ?: ConnectionWriteScope.SELECTED)
    var commitScope by mutableStateOf(registration?.permissions?.commitScope ?: ConnectionWriteScope.SELECTED)
    var actionScope by mutableStateOf(registration?.permissions?.actionScope ?: ConnectionWriteScope.SELECTED)
    fun permissions() = ConnectionPermissions(
        if (readScope == ConnectionReadScope.SELECTED) permissionDocuments(readable) else emptySet(),
        if (proposalScope == ConnectionWriteScope.SELECTED) permissionDocuments(proposable) else emptySet(),
        if (actionScope == ConnectionWriteScope.SELECTED) permissionDocuments(actionable) else emptySet(), permissionLines(actions), readScope,
        if (commitScope == ConnectionWriteScope.SELECTED) permissionDocuments(committable) else emptySet(), proposalScope, commitScope, actionScope,
    )
    fun useSimple() {
        readScope = ConnectionReadScope.WORKSPACE
        readable = ""; proposable = ""; committable = ""; actionable = ""; actions = ""; simple = true
        proposalScope = ConnectionWriteScope.SELECTED; commitScope = ConnectionWriteScope.SELECTED; actionScope = ConnectionWriteScope.SELECTED
    }
}

internal fun connectionPermissionSummary(p: ConnectionPermissions): String {
    val read = if (p.readScope == ConnectionReadScope.WORKSPACE) "ワークスペース全体（今後の追加を含む）" else "指定した ${p.readable.size} 文書"
    fun scope(s: ConnectionWriteScope, n: Int) = if (s == ConnectionWriteScope.LEDGER) "業務データ全体" else "$n 文書"
    return "閲覧: $read / 提案: ${scope(p.proposalScope, p.proposable.size)} / 直接確定: ${scope(p.commitScope, p.committable.size)} / 業務操作: ${scope(p.actionScope, p.actionable.size)}・${p.actions.size} 操作"
}

@Composable
internal fun ClientPermissionEditor(
    draft: ClientEditor, enabled: Boolean, workspaceLabel: String?,
    loadDocuments: suspend () -> List<DocumentId>, onCancel: () -> Unit,
    loadActions: suspend () -> List<String> = { emptyList() }, onSave: () -> Unit,
) {
    var confirmSimple by remember(draft) { mutableStateOf(false) }
    var selecting by remember(draft) { mutableStateOf<PermissionSelection?>(null) }
    var selectingActions by remember(draft) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (draft.id == null) "接続者の追加" else "${draft.name} の権限", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (draft.id == null) {
            CompactTextField(draft.name, { draft.name = it }, Modifier.fillMaxWidth().semantics { contentDescription = "接続名" },
                singleLine = true, placeholder = { Text("接続名") }, readOnly = !enabled)
            Text("利用する機能（API の呼び出しには API を選択）", fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) { LocalFeature.entries.forEach { feature ->
                val label = if (feature == LocalFeature.WEB) "Web" else feature.name
                RadioButton(selected = draft.feature == feature, enabled = enabled, onClick = { draft.feature = feature },
                    modifier = Modifier.semantics { contentDescription = "登録する機能 $label" })
                Text(label, fontSize = 13.sp)
            } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(draft.simple, enabled = enabled, onClick = {
                if (draft.permissions().isSimple()) draft.simple = true else confirmSimple = true
            }, modifier = Modifier.semantics { contentDescription = "簡単設定" })
            Text("簡単設定", fontSize = 13.sp)
            RadioButton(!draft.simple, enabled = enabled, onClick = { draft.simple = false },
                modifier = Modifier.semantics { contentDescription = "詳細設定" })
            Text("詳細設定", fontSize = 13.sp)
        }
        if (draft.simple) {
            Text("全体の閲覧と、追加の変更権限", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("このワークスペースの保存済み YAML 文書を閲覧できます。定義・構成情報と、今後追加する文書を含みます。", fontSize = 13.sp)
            WriteScopeOption("変更提案を許可（デスクトップで承認）", draft.proposalScope, enabled) { draft.proposalScope = it; draft.proposable = "" }
            WriteScopeOption("直接確定を許可（外部ツールで保存）", draft.commitScope, enabled) { draft.commitScope = it; draft.committable = "" }
            WriteScopeOption("選択した業務操作を許可", draft.actionScope, enabled) { draft.actionScope = it; draft.actionable = "" }
            Text("変更範囲は今後の追加を含む業務データ全体です。定義・構成は変更できません。直接確定と業務操作では、デスクトップの承認を待たずに保存できます。", fontSize = 12.sp, color = Palette.SubText)
        } else {
            Text("閲覧範囲", fontSize = 13.sp)
            ConnectionReadScope.entries.forEach { value ->
                val label = if (value == ConnectionReadScope.SELECTED) "指定した文書のみ" else "ワークスペース全体（今後の追加を含む）"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(draft.readScope == value, enabled = enabled, onClick = { draft.readScope = value },
                        modifier = Modifier.semantics { contentDescription = "閲覧範囲 $label" })
                    Text(label, fontSize = 13.sp)
                }
            }
            if (draft.readScope == ConnectionReadScope.SELECTED) {
                DocumentPermissionField("閲覧する文書", draft.readable, enabled, { draft.readable = it }) {
                    selecting = PermissionSelection("閲覧する文書", permissionDocuments(draft.readable)) { draft.readable = it }
                }
                Text("選択した文書だけを許可します。後から追加した文書は含みません。ビュー・検証には全入力文書の閲覧許可が必要です。", fontSize = 12.sp, color = Palette.SubText)
            } else Text("定義・構成情報を含む、保存済み YAML 文書全体の閲覧を許可します。", fontSize = 12.sp)
            Text("変更は業務データが対象です。文書指定の空欄は許可なしです。変更の計算には全入力文書の閲覧が必要です。", fontSize = 12.sp)
            fun selection(label: String, text: String, change: (String) -> Unit) = PermissionSelection(label, permissionDocuments(text)) { value ->
                change(value)
                if (draft.readScope == ConnectionReadScope.SELECTED) {
                    draft.readable = (permissionLines(draft.readable) + permissionLines(value)).sorted().joinToString("\n")
                }
            }
            WriteScopeOption("提案の対象を業務データ全体にする", draft.proposalScope, enabled) { draft.proposalScope = it; draft.proposable = "" }
            if (draft.proposalScope == ConnectionWriteScope.SELECTED) DocumentPermissionField("変更提案する文書", draft.proposable, enabled, { draft.proposable = it }) {
                selecting = selection("変更提案する文書", draft.proposable) { draft.proposable = it }
            }
            WriteScopeOption("直接確定の対象を業務データ全体にする", draft.commitScope, enabled) { draft.commitScope = it; draft.committable = "" }
            if (draft.commitScope == ConnectionWriteScope.SELECTED) DocumentPermissionField("直接確定する文書", draft.committable, enabled, { draft.committable = it }) {
                selecting = selection("直接確定する文書", draft.committable) { draft.committable = it }
            }
            WriteScopeOption("業務操作の対象を業務データ全体にする", draft.actionScope, enabled) { draft.actionScope = it; draft.actionable = "" }
            if (draft.actionScope == ConnectionWriteScope.SELECTED) DocumentPermissionField("業務操作の対象文書", draft.actionable, enabled, { draft.actionable = it }) {
                selecting = selection("業務操作の対象文書", draft.actionable) { draft.actionable = it }
            }
            Text("許可する業務操作名（1 行に 1 操作）", fontSize = 13.sp)
            PermissionTextField("許可する業務操作名（1 行に 1 操作）", draft.actions, enabled, "1 行に 1 操作名") { draft.actions = it }
        }
        if (!draft.simple || draft.actionScope == ConnectionWriteScope.LEDGER) {
            TextButton(enabled = enabled, onClick = { selectingActions = true }) { Text("業務操作を選択（${permissionLines(draft.actions).size} 件）") }
            Text("選択した名前の操作だけを許可します。後から追加した操作は含みません。", fontSize = 12.sp, color = Palette.SubText)
        }
        HorizontalDivider(color = Palette.Border)
        Text("保存する権限 / 対象: ${workspaceLabel.orEmpty()}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(connectionPermissionSummary(draft.permissions()), fontSize = 12.sp)
        Text("権限を変更すると、発行済みの資格情報は失効します。", fontSize = 12.sp, color = Palette.SubText)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = enabled && draft.name.isNotBlank(), onClick = onSave) { Text("登録権限を保存") }
            TextButton(enabled = enabled, onClick = onCancel) { Text("入力を取消") }
        }
    }
    if (confirmSimple) AlertDialog(onDismissRequest = { confirmSimple = false }, title = { Text("簡単設定への切替") },
        text = { Text("閲覧をワークスペース全体（今後の追加を含む）に変更し、変更提案・直接確定・業務操作の許可を外します。登録への反映は「登録権限を保存」を押したときです。") },
        confirmButton = { TextButton(enabled = enabled, onClick = { draft.useSimple(); confirmSimple = false }) { Text("簡単設定に切り替える") } },
        dismissButton = { TextButton(onClick = { confirmSimple = false }) { Text("キャンセル") } })
    selecting?.let { selection ->
        PermissionDocumentPicker(selection.label, selection.documents, loadDocuments, onDismiss = { selecting = null }) { documents ->
            selection.onChange(documents.map { it.value }.sorted().joinToString("\n")); selecting = null
        }
    }
    if (selectingActions) PermissionActionPicker(permissionLines(draft.actions), loadActions, { selectingActions = false }) {
        draft.actions = it.sorted().joinToString("\n"); selectingActions = false
    }
}

@Composable
private fun WriteScopeOption(label: String, scope: ConnectionWriteScope, enabled: Boolean, change: (ConnectionWriteScope) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(scope == ConnectionWriteScope.LEDGER, enabled = enabled,
            onCheckedChange = { change(if (it) ConnectionWriteScope.LEDGER else ConnectionWriteScope.SELECTED) },
            modifier = Modifier.semantics { contentDescription = label })
        Text(label, fontSize = 13.sp)
    }
}

private class PermissionSelection(val label: String, val documents: Set<DocumentId>, val onChange: (String) -> Unit)

@Composable
private fun DocumentPermissionField(label: String, text: String, enabled: Boolean, change: (String) -> Unit, select: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 13.sp)
        TextButton(enabled = enabled, onClick = select, modifier = Modifier.semantics { contentDescription = "$label を選択" }) { Text("文書を選択") }
    }
    PermissionTextField(label, text, enabled, change = change)
}

@Composable
private fun PermissionTextField(label: String, text: String, enabled: Boolean,
                                hint: String = "1 行に 1 文書。ワークスペースからの相対パス", change: (String) -> Unit) {
    CompactTextField(text, change, Modifier.fillMaxWidth().heightIn(min = 64.dp, max = 140.dp).semantics { contentDescription = label },
        readOnly = !enabled, placeholder = { Text(hint) })
}
