package dev.toros.yde.desktop.ui.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.desktop.connections.LocalConnectionController
import dev.toros.yde.desktop.connections.ConnectionSettingsState
import dev.toros.yde.desktop.lifecycle.PresenceStatus
import dev.toros.yde.desktop.lifecycle.ResidentSettings
import dev.toros.yde.desktop.ui.common.CompactTextField
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.server.*
import dev.toros.yde.server.auth.ClientRegistration
import dev.toros.yde.server.auth.IssuedCredential
import dev.toros.yde.server.auth.WebLoginCode
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** ワークスペース未選択でも開ける常設の設定ページ。通信や保存の規則は Controller に委ねる。 */
@Composable
internal fun LocalConnectionSettingsPage(
    controller: LocalConnectionController, workspaceLabel: String?,
    presence: PresenceStatus? = null, onHide: () -> Unit = {},
    onResidentChanged: (ResidentSettings) -> Unit = {}, onBack: () -> Unit,
) {
    val model by controller.state.collectAsState()
    val status by controller.connections.status.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    var port by remember(status.desired.port) { mutableStateOf(status.desired.port.toString()) }
    var editor by remember { mutableStateOf<ClientEditor?>(null) }
    var issued by remember { mutableStateOf<IssuedCredential?>(null) }
    var webLogin by remember { mutableStateOf<WebLoginCode?>(null) }
    var browserError by remember { mutableStateOf<String?>(null) }
    var issuedTo by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<Pair<String, suspend () -> Unit>?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(controller) { while (true) { delay(1000); tick++ } }
    val usable = model.ready && !model.busy && !model.blocked
    LaunchedEffect(controller.workspaceId()) { editor = null; issued = null; webLogin = null; issuedTo = null }
    val expires = issuedTo?.let { tick; controller.access.credentialExpiry(it) }
    LaunchedEffect(expires) { if (expires == null) { issued = null; webLogin = null; issuedTo = null } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = !model.busy, onClick = {
                if (editor != null || port != status.desired.port.toString()) confirmation = "入力中の設定を破棄して戻りますか？" to { editor = null; onBack() }
                else onBack()
            }) { Text("戻る") }
            Text("設定 / 接続と常駐", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        HorizontalDivider(color = Palette.Border)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!model.ready) Text("設定を読み込んでいます…")
            if (editor == null) model.error?.let { Text(it, color = Palette.ErrorRed, fontSize = 13.sp) }
            model.message?.let { Text(it, fontSize = 13.sp) }
            if (model.blocked) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(enabled = !model.busy, onClick = { scope.launch { controller.initialize() } }) { Text("設定を再読込") }
                TextButton(enabled = !model.busy, onClick = {
                    confirmation = "保存した接続・常駐設定と登録権限を初期化しますか？全機能を OFF にします。提出済みの作業は残ります。" to { controller.reset() }
                }) { Text("接続設定を初期化", color = Palette.ErrorRed) }
            }
            ResidentSettingsPane(model.resident, presence, usable,
                onChange = { scope.launch { if (controller.setResident(it) != null) onResidentChanged(it) } }, onHide = onHide)
            HorizontalDivider(color = Palette.Border)
            Text("この PC 内の接続", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("使う機能だけを ON にしてください。すべて OFF で停止が完了すると、待受ポートを閉じます。設定は次回起動時に復元します。", fontSize = 13.sp)
            Text("API・MCP は保存版の閲覧・業務操作・変更提案・直接確定に対応しています。Web はダッシュボード・表・チャート・業務詳細の閲覧と、定義済み業務操作の入力・確認・保存に対応しています。", fontSize = 12.sp, color = Palette.SubText)
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
            OutlinedButton(enabled = usable && controller.workspaceId() != null && editor == null, onClick = {
                editor = ClientEditor(initialFeature = LocalFeature.entries.singleOrNull { status.desired.enabled(it) } ?: LocalFeature.MCP)
            }) { Text("接続者を追加") }
            model.registrations.forEach { registration ->
                val client = registration.client
                val current = client.caller.workspaceId == controller.workspaceId()
                val expiry = tick.let { controller.access.credentialExpiry(client.id) }
                val issueUnavailable = tokenIssueUnavailableReason(model, status, registration, controller.workspaceId())
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("${client.name} / ${client.feature.label()}${if (current) "" else "（別のワークスペース）"}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    val p = registration.permissions
                    Text(connectionPermissionSummary(p), fontSize = 12.sp)
                    Text(expiry?.let { "資格情報の期限: ${time(it)}" } ?: "有効な資格情報なし", fontSize = 12.sp, color = Palette.SubText)
                    issueUnavailable?.let { Text(it, fontSize = 12.sp, color = Palette.SubText) }
                    if (client.feature == LocalFeature.MCP && !status.desired.mcpEnabled && status.desired.apiEnabled) {
                        Text("この登録は MCP 用です。API を使う場合は、API を選んで接続者を追加してください。", fontSize = 12.sp, color = Palette.SubText)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = usable && current && editor == null, onClick = { editor = ClientEditor(registration) }) { Text("権限を編集") }
                        TextButton(enabled = issueUnavailable == null,
                            onClick = {
                                val issue: suspend () -> Unit = {
                                    if (client.feature == LocalFeature.WEB) controller.issueWebLogin(client.id)?.let { issuedTo = client.id; webLogin = it; browserError = null }
                                    else controller.issue(client.id)?.let { issuedTo = client.id; issued = it }
                                }
                                if (expiry == null) scope.launch { issue() }
                                else confirmation = "「${client.name}」の資格情報を再発行しますか？旧資格情報を失効させ、実行中の要求を停止します。開始した保存の完了は待ちます。" to issue
                            }) { Text((if (client.feature == LocalFeature.WEB) "接続コードを" else "トークンを") + if (expiry == null) "発行" else "再発行") }
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
                ClientPermissionEditor(draft, usable, workspaceLabel, controller::documentsForSettings, onCancel = { editor = null }, loadActions = controller::actionsForSettings) {
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
    webLogin?.let { code ->
        val expired = tick.let { !java.time.Instant.now().isBefore(code.expiresAt) }
        val url = "http://127.0.0.1:${status.desired.port}/app/"
        AlertDialog(onDismissRequest = { webLogin = null; issuedTo = null }, title = { Text("Web の接続コード") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (expired) "接続コードの期限が切れました。閉じて再発行してください。" else "期限: ${time(code.expiresAt)}。2 分以内に 1 回だけ使えます。コードをコピーし、Web 画面に貼り付けて接続してください。")
                TextButton(enabled = !expired, onClick = { clipboard.setText(AnnotatedString(code.value)) }) { Text("接続コードをコピー") }
                TextButton(enabled = !expired, onClick = {
                    browserError = runCatching { uriHandler.openUri(url) }.exceptionOrNull()?.let { "ブラウザを開けませんでした。下の接続先をコピーして開いてください。" }
                }) { Text("Web を開く") }
                TextButton(onClick = { clipboard.setText(AnnotatedString(url)) }) { Text("接続先をコピー") }
                Text(url, fontSize = 12.sp)
                browserError?.let { Text(it, color = Palette.ErrorRed) }
                Text("ログイン後の有効期間は発行から 30 分です。再発行・Web OFF・権限変更・ワークスペース切替で接続は失効します。", fontSize = 12.sp)
            } }, confirmButton = { TextButton(onClick = { webLogin = null; issuedTo = null }) { Text("閉じる") } })
    }
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

/** ボタンの可否と説明を同じ条件から求め、OFF の指定も直ちに反映する。実際の発行条件は LocalAccess が再確認する。 */
internal fun tokenIssueUnavailableReason(
    model: ConnectionSettingsState, status: LocalConnectionStatus, registration: ClientRegistration, workspaceId: String?,
): String? {
    val feature = registration.client.feature
    return when {
        !model.ready -> "接続設定の読込が完了するまで、トークンを発行できません。"
        model.blocked -> "接続設定を読み込めないため発行できません。上のエラーを確認し、「設定を再読込」を押してください。"
        model.busy -> "接続設定を処理しています。完了するまで、トークンを発行できません。"
        workspaceId == null -> "トークンを発行するには、接続者を登録したワークスペースを開いてください。"
        registration.client.caller.workspaceId != workspaceId -> "別のワークスペースの登録です。登録したワークスペースを開いてから発行してください。"
        !status.desired.enabled(feature) -> "${feature.label()} が OFF のため発行できません。上の ${feature.label()} を ON にして、稼働中になるまで待ってください。"
        else -> when (status.features.getValue(feature).phase) {
            ConnectionPhase.STARTING -> "${feature.label()} を起動しています。稼働中になるとトークンを発行できます。"
            ConnectionPhase.STOPPING -> "${feature.label()} の停止処理が終わるまで、トークンを発行できません。"
            ConnectionPhase.ERROR -> "${feature.label()} の起動・停止に失敗しています。上のエラーを確認し、「設定保存と接続を再試行」を押してください。"
            ConnectionPhase.STOPPED -> "${feature.label()} が停止しています。「設定保存と接続を再試行」を押して、稼働中になるまで待ってください。"
            ConnectionPhase.RUNNING -> null
        }
    }
}
