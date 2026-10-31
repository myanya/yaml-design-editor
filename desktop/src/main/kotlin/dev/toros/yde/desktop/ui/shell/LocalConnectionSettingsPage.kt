package dev.toros.yde.desktop.ui.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.service.ConnectionPermissions
import dev.toros.yde.desktop.connections.LocalConnectionController
import dev.toros.yde.desktop.ui.common.CompactTextField
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.server.*
import dev.toros.yde.server.auth.ClientRegistration
import dev.toros.yde.server.auth.IssuedCredential
import dev.toros.yde.yaml.model.DocumentId
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** ワークスペース未選択でも開ける常設の設定ページ。通信や保存の規則は Controller に委ねる。 */
@Composable
internal fun LocalConnectionSettingsPage(controller: LocalConnectionController, workspaceLabel: String?, onBack: () -> Unit) {
    val model by controller.state.collectAsState()
    val status by controller.connections.status.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var port by remember(status.desired.port) { mutableStateOf(status.desired.port.toString()) }
    var editor by remember { mutableStateOf<ClientEditor?>(null) }
    var issued by remember { mutableStateOf<IssuedCredential?>(null) }
    var issuedTo by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<Pair<String, suspend () -> Unit>?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(controller) { while (true) { delay(1000); tick++ } }
    val usable = model.ready && !model.busy && !model.blocked
    LaunchedEffect(controller.workspaceId()) { editor = null; issued = null; issuedTo = null }
    val expires = issuedTo?.let { tick; controller.access.credentialExpiry(it) }
    LaunchedEffect(expires) { if (expires == null) { issued = null; issuedTo = null } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = !model.busy, onClick = {
                if (editor != null || port != status.desired.port.toString()) confirmation = "入力中の設定を破棄して戻りますか？" to { editor = null; onBack() }
                else onBack()
            }) { Text("戻る") }
            Text("設定 / ローカル接続", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        HorizontalDivider(color = Palette.Border)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("この PC 内の接続", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("使う機能だけを ON にしてください。すべて OFF で停止が完了すると、待受ポートを閉じます。設定は次回起動時に復元します。", fontSize = 13.sp)
            Text("この開発版では接続の設定・認証まで利用できます。API・MCP の業務操作と Web 画面は順次公開予定です。", fontSize = 12.sp, color = Palette.SubText)
            if (!model.ready) Text("設定を読み込んでいます…")
            if (editor == null) model.error?.let { Text(it, color = Palette.ErrorRed, fontSize = 13.sp) }
            model.message?.let { Text(it, fontSize = 13.sp) }
            if (model.blocked) TextButton(enabled = !model.busy, onClick = {
                confirmation = "保存した接続設定と登録権限を初期化しますか？全機能を OFF にします。提出済みの作業は残ります。" to { controller.reset() }
            }) { Text("接続設定を初期化", color = Palette.ErrorRed) }
            LocalFeature.entries.forEach { feature ->
                val actual = status.features.getValue(feature)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Switch(checked = status.desired.enabled(feature), enabled = usable,
                            modifier = Modifier.semantics { contentDescription = "${feature.label()} を有効にする" },
                            onCheckedChange = { enabled -> scope.launch { controller.setEnabled(feature, enabled) } })
                        Text(feature.label(), Modifier.width(55.dp), fontWeight = FontWeight.SemiBold)
                        Text("指定: ${if (status.desired.enabled(feature)) "ON" else "OFF"} / ${actual.phase.label()}", fontSize = 13.sp)
                        val url = "http://127.0.0.1:${status.desired.port}${feature.path()}"
                        TextButton(enabled = actual.phase == ConnectionPhase.RUNNING, onClick = { clipboard.setText(AnnotatedString(url)) }) { Text("接続先をコピー") }
                    }
                    Text("http://127.0.0.1:${status.desired.port}${feature.path()}", fontSize = 12.sp, color = Palette.SubText)
                    actual.error?.let { Text(it, color = Palette.ErrorRed, fontSize = 12.sp) }
                    if (feature == LocalFeature.WEB) Text("Web を有効にすると、画面と画面用の通信をこの PC 内で待ち受けます。API が OFF でも利用できます。", fontSize = 12.sp)
                }
            }
            Text(if (status.stopped) "待受なし" else "待受: ${status.listener.label()}${status.boundPort?.let { "（127.0.0.1:$it）" }.orEmpty()}", fontSize = 13.sp)
            status.error?.let { Text(it, color = Palette.ErrorRed, fontSize = 12.sp) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ポート", fontSize = 13.sp)
                CompactTextField(port, { port = it }, Modifier.width(100.dp).semantics { contentDescription = "ポート番号" }, singleLine = true,
                    readOnly = !usable || !status.stopped || status.desired.requiresListener)
                OutlinedButton(enabled = usable && status.stopped && !status.desired.requiresListener && port != status.desired.port.toString(),
                    onClick = { scope.launch { controller.setPort(port) } }) { Text("ポートを保存") }
                TextButton(enabled = usable, onClick = { scope.launch { controller.retry() } }) { Text("設定保存と接続を再試行") }
            }
            Text("ポートは全機能の停止完了後に変更できます。占有されている場合はエラーを表示し、別のポートへ切り替えません。", fontSize = 12.sp, color = Palette.SubText)
            HorizontalDivider(color = Palette.Border)
            Text("接続者と権限", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(workspaceLabel?.let { "現在のワークスペース: $it" } ?: "接続者を登録するにはワークスペースを開いてください。", fontSize = 13.sp)
            Text("登録権限は保存します。トークンは発行時だけ表示し、再起動・機能 OFF・ワークスペース切替後には再発行が必要です。", fontSize = 12.sp, color = Palette.SubText)
            OutlinedButton(enabled = usable && controller.workspaceId() != null && editor == null, onClick = { editor = ClientEditor() }) { Text("接続者を追加") }
            model.registrations.forEach { registration ->
                val client = registration.client
                val current = client.caller.workspaceId == controller.workspaceId()
                val expiry = tick.let { controller.access.credentialExpiry(client.id) }
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("${client.name} / ${client.feature.label()}${if (current) "" else "（別のワークスペース）"}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    val p = registration.permissions
                    Text("閲覧 ${p.readable.size} 文書・提案 ${p.proposable.size} 文書・業務操作 ${p.actionable.size} 文書 / ${p.actions.size} 操作", fontSize = 12.sp)
                    Text(expiry?.let { "資格情報の期限: ${time(it)}" } ?: "有効な資格情報なし", fontSize = 12.sp, color = Palette.SubText)
                    if (client.feature == LocalFeature.WEB) Text("Web のログインとセッション発行は、Web 画面の公開後に利用できます。", fontSize = 12.sp, color = Palette.SubText)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = usable && current && editor == null, onClick = { editor = ClientEditor(registration) }) { Text("権限を編集") }
                        TextButton(enabled = usable && current && client.feature != LocalFeature.WEB && status.features.getValue(client.feature).phase == ConnectionPhase.RUNNING,
                            onClick = {
                                val issue: suspend () -> Unit = { controller.issue(client.id)?.let { issuedTo = client.id; issued = it }; Unit }
                                if (expiry == null) scope.launch { issue() }
                                else confirmation = "「${client.name}」のトークンを再発行しますか？旧トークンを失効させ、実行中の要求を停止します。開始した保存の完了は待ちます。" to issue
                            }) { Text(if (expiry == null) "トークンを発行" else "トークンを再発行") }
                        TextButton(enabled = usable && expiry != null, onClick = {
                            confirmation = "「${client.name}」の資格情報を失効させますか？実行中の要求は停止し、開始した保存の完了を待ちます。" to { controller.revoke(client.id) }
                        }) { Text("失効", color = Palette.ErrorRed) }
                        TextButton(enabled = usable, onClick = {
                            confirmation = "「${client.name}」の登録と権限を削除しますか？提出済みの作業は残りますが、この登録による承認はできなくなります。" to { controller.remove(client.id) }
                        }) { Text("登録を削除", color = Palette.ErrorRed) }
                    }
                }
            }
            editor?.let { draft ->
                model.error?.let { Text(it, color = Palette.ErrorRed, fontSize = 13.sp) }
                ClientPermissionEditor(draft, usable, onCancel = { editor = null }) {
                    scope.launch {
                        if (draft.id == null) {
                            if (controller.register(draft.feature, draft.name.trim(), draft.permissions()) != null) editor = null
                        } else if (controller.setPermissions(draft.id, draft.permissions()) != null) editor = null
                    }
                }
            }
        }
    }
    issued?.let { credential -> AlertDialog(onDismissRequest = { issued = null; issuedTo = null },
        title = { Text("接続トークン") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("期限: ${time(credential.expiresAt)}。この表示を閉じると値を再表示できません。再発行すると旧トークンは失効します。")
            TextButton(onClick = { clipboard.setText(AnnotatedString(credential.value)) }) { Text("トークンをコピー") }
            Text("コピーした値を接続先ツールに設定してください。", fontSize = 12.sp)
        } }, confirmButton = { TextButton(onClick = { issued = null; issuedTo = null }) { Text("閉じる") } }) }
    confirmation?.let { (message, action) -> AlertDialog(onDismissRequest = { confirmation = null }, title = { Text("確認") }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { confirmation = null; scope.launch { action() } }) { Text("実行", color = Palette.ErrorRed) } },
        dismissButton = { TextButton(onClick = { confirmation = null }) { Text("キャンセル") } }) }
}

private fun LocalFeature.label() = if (this == LocalFeature.WEB) "Web" else name
private fun LocalFeature.path() = when (this) { LocalFeature.API -> "/api/v1"; LocalFeature.MCP -> "/mcp"; LocalFeature.WEB -> "/app" }
private fun ConnectionPhase.label() = when (this) {
    ConnectionPhase.STOPPED -> "停止"; ConnectionPhase.STARTING -> "起動中"; ConnectionPhase.RUNNING -> "稼働中"
    ConnectionPhase.STOPPING -> "停止中"; ConnectionPhase.ERROR -> "エラー"
}
private fun time(value: java.time.Instant) = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(value)

private class ClientEditor(registration: ClientRegistration? = null) {
    val id = registration?.client?.id
    var name by mutableStateOf(registration?.client?.name.orEmpty())
    var feature by mutableStateOf(registration?.client?.feature ?: LocalFeature.MCP)
    var readable by mutableStateOf(registration?.permissions?.readable?.joinToString("\n") { it.value }.orEmpty())
    var proposable by mutableStateOf(registration?.permissions?.proposable?.joinToString("\n") { it.value }.orEmpty())
    var actionable by mutableStateOf(registration?.permissions?.actionable?.joinToString("\n") { it.value }.orEmpty())
    var actions by mutableStateOf(registration?.permissions?.actions?.joinToString("\n").orEmpty())
    fun permissions(): ConnectionPermissions {
        fun lines(text: String) = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toSet()
        fun documents(text: String) = lines(text).map(::DocumentId).toSet()
        return ConnectionPermissions(documents(readable), documents(proposable), documents(actionable), lines(actions))
    }
}

@Composable
private fun ClientPermissionEditor(draft: ClientEditor, enabled: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (draft.id == null) "接続者の追加" else "${draft.name} の権限", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (draft.id == null) {
            CompactTextField(draft.name, { draft.name = it }, Modifier.fillMaxWidth().semantics { contentDescription = "接続名" }, singleLine = true, placeholder = { Text("接続名") }, readOnly = !enabled)
            Row(verticalAlignment = Alignment.CenterVertically) { LocalFeature.entries.forEach { feature ->
                RadioButton(selected = draft.feature == feature, enabled = enabled, onClick = { draft.feature = feature }, modifier = Modifier.semantics { contentDescription = "登録する機能 ${feature.label()}" })
                Text(feature.label(), fontSize = 13.sp)
            } }
        }
        Text("対象は 1 行に 1 文書を、ワークスペースからの相対パスで指定します。提案・業務操作の対象には閲覧権限も必要です。空欄は許可なしです。", fontSize = 12.sp)
        @Composable fun field(label: String, text: String, onChange: (String) -> Unit) {
            Text(label, fontSize = 13.sp)
            CompactTextField(text, onChange, Modifier.fillMaxWidth().heightIn(min = 64.dp, max = 140.dp).semantics { contentDescription = label }, readOnly = !enabled)
        }
        field("閲覧する文書", draft.readable) { draft.readable = it }
        field("変更提案する文書", draft.proposable) { draft.proposable = it }
        field("業務操作の対象文書", draft.actionable) { draft.actionable = it }
        field("許可する業務操作名（1 行に 1 操作）", draft.actions) { draft.actions = it }
        Text("権限を変更すると、発行済みの資格情報は失効します。", fontSize = 12.sp, color = Palette.SubText)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = enabled && draft.name.isNotBlank(), onClick = onSave) { Text("登録権限を保存") }
            TextButton(enabled = enabled, onClick = onCancel) { Text("入力を取消") }
        }
    }
}
