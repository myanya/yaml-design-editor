package dev.toros.yde.app.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import dev.toros.yde.app.AppState
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.workspace.InitPlan
import dev.toros.yde.workspace.InitRequest
import dev.toros.yde.workspace.WorkspaceInit
import dev.toros.yde.workspace.WorkspaceTemplate
import dev.toros.yde.workspace.WorkspaceTemplates
import java.nio.file.Files
import java.nio.file.Path

/**
 * 初期化ウィザード（新しいワークスペースを作る。architecture/workspace.md「ワークスペースの初期化」）。
 * 浮いたパネルで 4 歩: ①場所 ②始め方（テンプレートから／自分の項目で／既存のフォルダに後入れ）③内容 ④確認 →「作成して開く」。
 * 計画と書き込みはカーネルの `WorkspaceInit` で、ここは答えを集めて見せるだけ。ワークスペースを開く前に書くので Undo は無く、
 * 代わりに確認で作られるファイルを全部見せる。入口（スタート画面・メニュー・パレット・未初期化フォルダの案内）はどこでも
 * `AppState.startInitWizard` を呼び、Main がスペースに関係なく載せる。
 */
@Composable
fun InitWizardHost(state: AppState) {
    val work = state.initWizard ?: return
    val wizard = remember(work) { WizardState(work, state) }
    InitWizardDialog(state, wizard)
}

private enum class InitMode(val label: String, val description: String) {
    TEMPLATE("テンプレートから", "課題管理などの一式（スキーマ・ビュー・操作）を写して始めます。サンプルデータを入れるかどうかを選べます。"),
    CUSTOM("自分の項目で", "管理するもの 1 種類の名前・ID の接頭辞・項目を答えると、スキーマ・空の一覧・一覧ビュー・ダッシュボード・登録の操作ができます。"),
    ADOPT("既存のフォルダに後入れ", "いまある YAML はそのままに、台帳にするフォルダだけを宣言します。開いたあと「既存文書から推測」でスキーマを作れます。"),
}

/** 書き込み先の様子。 */
private data class TargetInfo(val exists: Boolean, val isDirectory: Boolean, val initialized: Boolean, val yamlCount: Int, val empty: Boolean)

private class WizardState(val work: AppState.InitWizardWork, state: AppState) {
    var step by mutableStateOf(0)
    var parent by mutableStateOf<Path?>(
        work.fixedRoot ?: FilePickers.lastDirectory(FilePickers.Purpose.WORKSPACE) ?: state.recentWorkspaces.firstOrNull()?.parent,
    )
    var folderName by mutableStateOf("")
    var mode by mutableStateOf(InitMode.TEMPLATE)
    var templateId by mutableStateOf(WorkspaceTemplates.fixed.firstOrNull()?.id)
    var includeSample by mutableStateOf(true)
    /** 「自分の項目で」の答え。配列は SnapshotStateList、要素の組は SnapshotStateMap で持ち、計画のときに素の Map へ直す。既定値は最初から入れておく。 */
    val answers = mutableStateMapOf<String, Any?>().also { map ->
        WorkspaceTemplates.custom?.params?.forEach { p -> p.key?.let { key -> p.default?.let { map[key] = it } } }
    }
    val ledger = mutableStateListOf<String>()
    var showProblems by mutableStateOf(false)
    var plan by mutableStateOf<InitPlan?>(null)
    var planError by mutableStateOf<String?>(null)
    var preview by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)

    val target: Path?
        get() = work.fixedRoot ?: parent?.let { p -> folderName.trim().takeIf { it.isNotEmpty() }?.let { p.resolve(it) } ?: p }

    fun plainAnswers(): Map<String, Any?> = answers.entries.associate { (k, v) -> k to plain(v) }

    private fun plain(v: Any?): Any? = when (v) {
        is Map<*, *> -> v.entries.associate { (k, x) -> k.toString() to plain(x) }
        is List<*> -> v.map { plain(it) }
        else -> v
    }

    fun request(): InitRequest? = when (mode) {
        InitMode.TEMPLATE -> templateId?.let { WorkspaceTemplates.get(it) }?.let { InitRequest.FromTemplate(it, includeSampleData = includeSample) }
        InitMode.CUSTOM -> WorkspaceTemplates.custom?.let { InitRequest.FromTemplate(it, plainAnswers(), includeSampleData = true) }
        InitMode.ADOPT -> InitRequest.Adopt(ledger.toList())
    }
}

private val FOLDER_NAME_FORBIDDEN = Regex("""[\\/:*?"<>|]""")

private fun inspect(target: Path?): TargetInfo? {
    if (target == null) return null
    val exists = Files.exists(target)
    val isDir = exists && Files.isDirectory(target)
    val empty = isDir && Files.list(target).use { it.findFirst().isEmpty }
    return TargetInfo(
        exists = exists, isDirectory = isDir,
        initialized = isDir && WorkspaceInit.isInitialized(target),
        yamlCount = if (isDir) WorkspaceInit.yamlCount(target) else 0,
        empty = empty,
    )
}

@Composable
private fun InitWizardDialog(state: AppState, w: WizardState) {
    val target = w.target
    val info = remember(target, w.step) { inspect(target) }
    val custom = WorkspaceTemplates.custom
    val folderOk = w.work.fixedRoot != null || w.folderName.isBlank() || !FOLDER_NAME_FORBIDDEN.containsMatchIn(w.folderName)
    val locationOk = target != null && folderOk && info != null && !info.initialized && (!info.exists || info.isDirectory)
    val adoptable = info != null && info.exists && info.yamlCount > 0
    val problems = remember(w.mode, w.answers.toMap(), w.step) {
        if (w.mode == InitMode.CUSTOM && custom != null) WorkspaceInit.checkAnswers(custom, WorkspaceInit.normalizeAnswers(custom, w.plainAnswers()))
        else emptyList()
    }
    val contentOk = when (w.mode) {
        InitMode.TEMPLATE -> w.templateId != null
        InitMode.CUSTOM -> custom != null && problems.isEmpty()
        InitMode.ADOPT -> true
    }
    val dirty = state.workspace != null && state.hasDirtyDocs()
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val bodyMax = if (windowHeight <= 0.dp) 600.dp else (windowHeight * 0.85f - 170.dp).coerceAtLeast(360.dp)

    fun makePlan() {
        w.plan = null; w.planError = null; w.preview = null; w.error = null
        val request = w.request() ?: run { w.planError = "テンプレートが見つかりません"; return }
        runCatching { WorkspaceInit.plan(target, request) }
            .onSuccess { w.plan = it; w.preview = it.files.firstOrNull()?.let { f -> f.path + "\n\n" + f.text } }
            .onFailure { w.planError = it.message ?: "計画を作れません" }
    }

    fun next() {
        when (w.step) {
            2 -> { if (w.mode == InitMode.CUSTOM && !contentOk) { w.showProblems = true; return }; makePlan() }
        }
        w.step++
    }

    fun create() {
        val plan = w.plan ?: return
        val root = target ?: return
        runCatching { WorkspaceInit.apply(root, plan) }
            .onSuccess { result ->
                state.status = "ワークスペースを作りました: ${result.written.size} ファイル" +
                    (if (result.skipped.isNotEmpty()) "（既にあった ${result.skipped.size} ファイルは書いていません）" else "")
                state.openInitialized(root, adopted = w.mode == InitMode.ADOPT)
            }
            .onFailure { w.error = "作成できません: ${it.message}" }
    }

    AlertDialog(
        onDismissRequest = { state.closeInitWizard() },
        properties = DialogProperties(dismissOnClickOutside = false),
        modifier = Modifier.widthIn(min = 760.dp, max = 1000.dp),
        title = {
            Column {
                Text("新しいワークスペースを作る")
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf("場所", "始め方", "内容", "確認").forEachIndexed { i, label ->
                        if (i > 0) Text("  ›  ", fontSize = 11.sp, color = Palette.SubText)
                        Text(
                            "${i + 1} $label", fontSize = 11.sp,
                            fontWeight = if (i == w.step) FontWeight.Bold else FontWeight.Normal,
                            color = if (i == w.step) Palette.Primary else Palette.SubText,
                        )
                    }
                }
            }
        },
        text = {
            val scroll = rememberScrollState()
            Box(Modifier.fillMaxWidth().heightIn(max = bodyMax)) {
                Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = 10.dp)) {
                    when (w.step) {
                        0 -> LocationStep(state, w, info, folderOk)
                        1 -> ModeStep(w, adoptable, info)
                        2 -> when (w.mode) {
                            InitMode.TEMPLATE -> TemplateStep(w)
                            InitMode.CUSTOM -> CustomStep(w, custom, problems)
                            InitMode.ADOPT -> AdoptStep(w, target)
                        }
                        else -> ConfirmStep(w, target, dirty)
                    }
                    w.error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, fontSize = 12.sp, color = Palette.ErrorRed)
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Box(Modifier.matchParentSize()) {
                    VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (w.step > 0) {
                    TextButton(onClick = { w.step-- }) { Text("戻る") }
                    Spacer(Modifier.width(6.dp))
                }
                when (w.step) {
                    0 -> Button(enabled = locationOk, onClick = ::next) { Text("次へ") }
                    1 -> Button(enabled = w.mode != InitMode.ADOPT || adoptable, onClick = ::next) { Text("次へ") }
                    2 -> Button(enabled = w.mode == InitMode.CUSTOM || contentOk, onClick = ::next) { Text("次へ") }
                    else -> Button(enabled = w.plan != null && !dirty, onClick = ::create) { Text("作成して開く") }
                }
            }
        },
        dismissButton = { TextButton(onClick = { state.closeInitWizard() }) { Text("やめる") } },
    )
}

// ================================================================ 1 場所

@Composable
private fun LocationStep(state: AppState, w: WizardState, info: TargetInfo?, folderOk: Boolean) {
    Text("どこに作りますか", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    if (w.work.fixedRoot != null) {
        Text("開いているフォルダに作ります。", fontSize = 12.sp, color = Palette.SubText)
        Spacer(Modifier.height(4.dp))
        Text(w.work.fixedRoot.toString(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    } else {
        val choose = rememberDirectoryChooser(
            FilePickers.Purpose.WORKSPACE, "ワークスペースを作る場所（親フォルダ）を選択",
            fallback = w.parent ?: state.recentWorkspaces.firstOrNull()?.parent,
            onError = { w.error = it },
        ) { w.parent = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = choose, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) { Text("場所を選ぶ…", fontSize = 12.sp) }
            Spacer(Modifier.width(10.dp))
            Text(w.parent?.toString() ?: "まだ選んでいません", fontSize = 12.sp, color = if (w.parent == null) Palette.SubText else Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        Text("新しいフォルダの名前", fontSize = 12.sp, color = Palette.SubText)
        CompactTextField(
            w.folderName, { w.folderName = it }, singleLine = true, isError = !folderOk,
            placeholder = { Text("例: customers（空なら、選んだフォルダをそのまま使います）") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!folderOk) Text("フォルダ名に使えない文字があります（\\ / : * ? \" < > |）", fontSize = 11.sp, color = Palette.ErrorRed)
    }
    Spacer(Modifier.height(10.dp))
    val target = w.target
    val (text, color) = when {
        target == null || info == null -> "場所を選んでください" to Palette.SubText
        info.initialized -> "初期化済みです（.yde/ か _schemas/ があります）。開くには「ワークスペースを開く…」を使ってください。" to Palette.ErrorRed
        info.exists && !info.isDirectory -> "フォルダではなくファイルです。" to Palette.ErrorRed
        !info.exists -> "新しく作ります: $target" to Palette.OkGreen
        info.empty -> "空のフォルダに作ります: $target" to Palette.OkGreen
        info.yamlCount > 0 -> "YAML が ${info.yamlCount} 件あるフォルダです。テンプレートを展開しても既存のファイルは上書きしません。「既存のフォルダに後入れ」も選べます。" to Palette.WarnOrange
        else -> "ファイルのあるフォルダです（YAML はありません）。既存のファイルは上書きしません。" to Palette.WarnOrange
    }
    Text(text, fontSize = 12.sp, color = color)
}

// ================================================================ 2 始め方

@Composable
private fun ModeStep(w: WizardState, adoptable: Boolean, info: TargetInfo?) {
    Text("どう始めますか", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    InitMode.entries.forEach { mode ->
        val enabled = mode != InitMode.ADOPT || adoptable
        Row(
            Modifier.fillMaxWidth()
                .background(if (w.mode == mode) Palette.Selected else Palette.PanelBg, PanelShape)
                .border(1.dp, Palette.Border, PanelShape)
                .clickable(enabled = enabled) { w.mode = mode }
                .padding(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RadioButton(selected = w.mode == mode, onClick = { w.mode = mode }, enabled = enabled, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(mode.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) Palette.Text else Palette.SubText)
                Text(mode.description, fontSize = 11.sp, color = Palette.SubText)
                if (mode == InitMode.ADOPT && !adoptable) {
                    Text(
                        if (info?.exists == true) "このフォルダに YAML が無いので後入れはできません" else "まだ無いフォルダには後入れできません",
                        fontSize = 11.sp, color = Palette.WarnOrange,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

// ================================================================ 3 内容: テンプレートから

@Composable
private fun TemplateStep(w: WizardState) {
    Text("テンプレートを選びます", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    val templates = WorkspaceTemplates.fixed
    if (templates.isEmpty()) Text("同梱のテンプレートがありません", fontSize = 12.sp, color = Palette.ErrorRed)
    templates.forEach { t ->
        Row(
            Modifier.fillMaxWidth()
                .background(if (w.templateId == t.id) Palette.Selected else Palette.PanelBg, PanelShape)
                .border(1.dp, Palette.Border, PanelShape)
                .clickable { w.templateId = t.id }
                .padding(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RadioButton(selected = w.templateId == t.id, onClick = { w.templateId = t.id }, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(t.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                t.description?.let { Text(it, fontSize = 11.sp, color = Palette.SubText) }
                Text("${t.files.size} ファイル", fontSize = 10.sp, color = Palette.SubText)
            }
        }
        Spacer(Modifier.height(6.dp))
    }
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(w.includeSample, { w.includeSample = it }, Modifier.size(28.dp))
        Column {
            Text("サンプルデータを含める", fontSize = 12.sp)
            Text("試すときは入れたまま、本番で使うなら外します。外すと一覧のファイルは空で作られます。", fontSize = 11.sp, color = Palette.SubText)
        }
    }
}

// ================================================================ 3 内容: 自分の項目で

@Composable
private fun CustomStep(w: WizardState, custom: WorkspaceTemplate?, problems: List<String>) {
    Text("管理するものを決めます", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(2.dp))
    Text("ここで決めるのは最初の形です。項目はあとから設計スペースで足したり直したりできます。", fontSize = 11.sp, color = Palette.SubText)
    Spacer(Modifier.height(8.dp))
    if (custom == null) {
        Text("「自分の項目で」のテンプレート（${WorkspaceTemplates.CUSTOM}）が同梱されていません", fontSize = 12.sp, color = Palette.ErrorRed)
        return
    }
    custom.params.forEach { p ->
        val key = p.key ?: return@forEach
        ParamEditor(custom, p, w.answers[key], onChange = { v -> if (v == null) w.answers.remove(key) else w.answers[key] = v })
        Spacer(Modifier.height(10.dp))
    }
    if (w.showProblems && problems.isNotEmpty()) {
        HorizontalDivider(color = Palette.Border)
        Spacer(Modifier.height(6.dp))
        problems.forEach { Text("・$it", fontSize = 12.sp, color = Palette.ErrorRed) }
    }
}

/** 問い 1 つの入力欄。型（文字・選択肢・はい／いいえ・数・一覧）に合わせた素朴な部品で、ワークスペースが無くても動く。 */
@Composable
private fun ParamEditor(template: WorkspaceTemplate, field: FieldDef, value: Any?, onChange: (Any?) -> Unit) {
    val f = template.resolvedParam(field)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.label ?: f.key.orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (f.required) Text(" *", fontSize = 12.sp, color = Palette.ErrorRed)
        }
        f.description?.let { Text(it, fontSize = 11.sp, color = Palette.SubText) }
        Spacer(Modifier.height(3.dp))
        when {
            f.type == FieldType.ARRAY -> {
                val item = f.item?.let { template.resolvedParam(it) }
                if (item != null && item.type == FieldType.OBJECT) ObjectRowsEditor(template, item, value, onChange)
                else ScalarListEditor(value, onChange, placeholder = item?.placeholder)
            }
            else -> ScalarEditor(f, value, onChange, Modifier.widthIn(max = 420.dp))
        }
    }
}

@Composable
private fun ScalarEditor(f: FieldDef, value: Any?, onChange: (Any?) -> Unit, modifier: Modifier = Modifier) {
    when (f.type) {
        FieldType.ENUM -> EnumPicker(f, value?.toString(), onChange, modifier)
        FieldType.BOOLEAN -> Checkbox(value == true || value?.toString() == "true", { onChange(it) }, Modifier.size(28.dp))
        else -> {
            val text = value?.toString().orEmpty()
            CompactTextField(
                text, { onChange(it.takeIf { s -> s.isNotEmpty() }) },
                singleLine = !f.multiline,
                placeholder = f.placeholder?.let { hint -> { Text(hint, fontSize = 12.sp) } },
                modifier = modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun EnumPicker(f: FieldDef, value: String?, onChange: (Any?) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val options = f.enumOptions()
    val current = options.firstOrNull { it.value == value }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, shape = PanelShape, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp), modifier = Modifier.height(30.dp)) {
            Text(current?.label ?: current?.value ?: value ?: "選ぶ…", fontSize = 12.sp, color = if (current == null) Palette.SubText else Palette.Text)
            Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp))
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(o.label ?: o.value, fontSize = 12.sp) }, onClick = { onChange(o.value); open = false })
            }
        }
    }
}

/** スカラーの一覧（選択肢の値など）: 読点か空白で区切って書く。 */
@Composable
private fun ScalarListEditor(value: Any?, onChange: (Any?) -> Unit, placeholder: String?) {
    val text = (value as? List<*>)?.joinToString(", ") { it.toString() }.orEmpty()
    var draft by remember(value == null) { mutableStateOf(text) }
    CompactTextField(
        draft,
        { s ->
            draft = s
            val items = s.split(',', '、', ' ', '　').map { it.trim() }.filter { it.isNotEmpty() }
            onChange(items.takeIf { it.isNotEmpty() })
        },
        singleLine = true,
        placeholder = { Text(placeholder ?: "読点で区切って書きます（例: A, B, C）", fontSize = 12.sp) },
        modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
    )
}

/** 項目の組の一覧（状態の選択肢・そのほかの項目）: 1 行 1 要素の表。 */
@Composable
private fun ObjectRowsEditor(template: WorkspaceTemplate, item: FieldDef, value: Any?, onChange: (Any?) -> Unit) {
    @Suppress("UNCHECKED_CAST")
    val rows = value as? SnapshotStateList<SnapshotStateMap<String, Any?>>
        ?: mutableStateListOf<SnapshotStateMap<String, Any?>>().also { onChange(it) }
    val columns = item.fields.filter { it.key != null }
    fun newRow(): SnapshotStateMap<String, Any?> = mutableStateMapOf<String, Any?>().also { row ->
        columns.forEach { c -> template.resolvedParam(c).default?.let { row[c.key!!] = it } }
    }
    val hScroll = rememberScrollState()
    Column(Modifier.fillMaxWidth().background(Palette.PanelBg, PanelShape).border(1.dp, Palette.Border, PanelShape).padding(6.dp)) {
        Column(Modifier.horizontalScroll(hScroll)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                columns.forEach { c ->
                    val rc = template.resolvedParam(c)
                    Text(
                        (rc.label ?: c.key.orEmpty()) + if (rc.required) " *" else "",
                        fontSize = 11.sp, color = Palette.SubText, modifier = Modifier.width(columnWidth(rc)).padding(horizontal = 4.dp),
                    )
                }
                Spacer(Modifier.width(32.dp))
            }
            rows.forEachIndexed { index, row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    columns.forEach { c ->
                        val rc = template.resolvedParam(c)
                        val key = c.key!!
                        Box(Modifier.width(columnWidth(rc)).padding(horizontal = 4.dp, vertical = 2.dp)) {
                            if (rc.type == FieldType.ARRAY) ScalarListEditor(row[key], { v -> if (v == null) row.remove(key) else row[key] = v }, rc.item?.placeholder)
                            else ScalarEditor(rc, row[key], { v -> if (v == null) row.remove(key) else row[key] = v })
                        }
                    }
                    UiTooltip("この行を消す") {
                        IconButton(onClick = { rows.removeAt(index) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, "この行を消す", Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
        if (rows.isEmpty()) Text("まだありません", fontSize = 11.sp, color = Palette.SubText, modifier = Modifier.padding(4.dp))
        TextButton(onClick = { rows += newRow() }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("＋ 行を足す", fontSize = 12.sp) }
    }
}

private fun columnWidth(f: FieldDef) = when (f.type) {
    FieldType.BOOLEAN -> 56.dp
    FieldType.ENUM -> 170.dp
    FieldType.ARRAY -> 200.dp
    else -> 160.dp
}

// ================================================================ 3 内容: 既存のフォルダに後入れ

@Composable
private fun AdoptStep(w: WizardState, target: Path?) {
    Text("台帳にするフォルダを選びます", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(2.dp))
    Text(
        "台帳は ID の索引・検証の集計・エージェントの対象になる範囲です。何も選ばなければフォルダ全体が台帳です。" +
            "書くのは .yde/workspace.yaml だけで、いまある YAML には触れません。",
        fontSize = 11.sp, color = Palette.SubText,
    )
    Spacer(Modifier.height(8.dp))
    val candidates = remember(target) { target?.let { WorkspaceInit.ledgerCandidates(it) }.orEmpty() }
    if (candidates.isEmpty()) Text("直下にフォルダがありません（全体が台帳になります）", fontSize = 12.sp, color = Palette.SubText)
    candidates.forEach { (name, count) ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { if (name in w.ledger) w.ledger.remove(name) else w.ledger.add(name) }) {
            Checkbox(name in w.ledger, { on -> if (on) { if (name !in w.ledger) w.ledger.add(name) } else w.ledger.remove(name) }, Modifier.size(28.dp))
            Text(name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text("YAML $count 件", fontSize = 11.sp, color = Palette.SubText)
        }
    }
}

// ================================================================ 4 確認

@Composable
private fun ConfirmStep(w: WizardState, target: Path?, dirty: Boolean) {
    Text("この内容で作ります", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text("場所: $target", fontSize = 12.sp)
    Text("始め方: ${w.mode.label}" + when (w.mode) {
        InitMode.TEMPLATE -> "（${w.templateId?.let { WorkspaceTemplates.get(it)?.name } ?: ""}${if (w.includeSample) "・サンプルデータあり" else "・サンプルデータなし"}）"
        InitMode.CUSTOM -> "（${w.answers["label"] ?: ""}）"
        InitMode.ADOPT -> if (w.ledger.isEmpty()) "（全体が台帳）" else "（台帳: ${w.ledger.joinToString("・")}）"
    }, fontSize = 12.sp)
    w.planError?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, fontSize = 12.sp, color = Palette.ErrorRed)
        return
    }
    val plan = w.plan ?: return
    plan.notes.forEach { Text(it, fontSize = 11.sp, color = Palette.SubText) }
    if (plan.directories.isNotEmpty()) Text("作るフォルダ: ${plan.directories.joinToString("・")}", fontSize = 11.sp, color = Palette.SubText)
    if (dirty) {
        Spacer(Modifier.height(6.dp))
        Text("未保存の変更があります。先に保存するか捨ててから作ってください（作成すると新しいワークスペースを開きます）。", fontSize = 12.sp, color = Palette.WarnOrange)
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(300.dp)) {
            Text("作られるファイル（${plan.toWrite.size}）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            plan.files.forEach { f ->
                val selected = w.preview?.startsWith(f.path + "\n") == true
                Column(
                    Modifier.fillMaxWidth().background(if (selected) Palette.Selected else Palette.PanelBg)
                        .clickable { w.preview = f.path + "\n\n" + f.text }.padding(horizontal = 6.dp, vertical = 3.dp),
                ) {
                    Text(f.path, fontSize = 12.sp, fontFamily = AppFonts.Mono, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (f.exists) Palette.SubText else Palette.Text)
                    if (f.exists) Text("既にあるので書きません", fontSize = 10.sp, color = Palette.WarnOrange)
                    else if (f.sample) Text(if (w.mode == InitMode.TEMPLATE) "サンプルデータ" else "空の一覧", fontSize = 10.sp, color = Palette.SubText)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        val previewScroll = rememberScrollState()
        Box(Modifier.weight(1f).height(320.dp).background(Palette.EditorBg, PanelShape).border(1.dp, Palette.Border, PanelShape)) {
            Text(
                w.preview ?: "ファイルを選ぶと中身が見えます", fontSize = 11.sp, fontFamily = AppFonts.Mono,
                modifier = Modifier.fillMaxSize().verticalScroll(previewScroll).padding(8.dp),
                color = if (w.preview == null) Palette.SubText else Palette.Text,
            )
            VerticalScrollbar(rememberScrollbarAdapter(previewScroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}
