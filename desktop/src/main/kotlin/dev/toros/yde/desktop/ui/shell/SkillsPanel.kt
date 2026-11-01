package dev.toros.yde.desktop.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.core.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.toros.yde.app.skill.Importer
import dev.toros.yde.app.skill.Runner
import dev.toros.yde.app.skill.SkillDef
import dev.toros.yde.app.skill.SkillKind
import dev.toros.yde.app.skill.Skills
import dev.toros.yde.app.skill.Exporter
import java.nio.file.Path
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.ui.common.CompactTextField
import dev.toros.yde.desktop.ui.common.FilePickers
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.desktop.ui.common.PanelShape
import dev.toros.yde.desktop.ui.common.rememberDirectoryChooser
import dev.toros.yde.desktop.ui.common.rememberFileChooser

/**
 * 連携（skill）セクション（R5のUI）。設計スペースに常設表示する（R12-01。モーダルにしない）。
 * `_skills` の入出力変換を一覧し、エクスポートは即実行（「出力先を選ぶ…」なら選んだフォルダへ。ワークスペースの外も可）、
 * インポートは「ファイル選択→プレビュー→適用」の二段階で行う。コード実行skill（R9-06）は承認ダイアログを必ず経由する。
 */
@Composable
fun SkillsSection(state: AppState) {
    val wsDir = state.workspace ?: return
    var loaded by remember { mutableStateOf<Skills.LoadResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var importTarget by remember { mutableStateOf<SkillDef?>(null) }
    var importSource by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Importer.Preview?>(null) }
    // 取り込むファイルの選択（OS ネイティブ。最後に選んだ場所から開き、無ければワークスペースから）
    val chooseImportFile = rememberFileChooser(
        FilePickers.Purpose.IMPORT, "取り込むファイルを選択",
        fallback = wsDir, onError = { state.status = it },
    ) { importSource = it.toString() }
    var runTarget by remember { mutableStateOf<SkillDef?>(null) }  // コード実行skillの承認待ち（R9-06）
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch { withContext(Dispatchers.IO) { loaded = Skills.load(wsDir) } }
    }
    LaunchedEffect(wsDir, state.indexVersion) { reload() }

    /** まとめて書き出す定義の、上書きの確認待ち（一括で上書きするか取りやめるか）。 */
    var bundleConfirm by remember { mutableStateOf<Pair<SkillDef, List<Exporter.Plan>>?>(null) }

    /** まとめて書き出した結果の一文。 */
    fun bundleMessage(results: List<Exporter.Result>): String {
        val written = results.count { it.changed }
        return if (written == 0) "変更はありません（${results.size} ファイル。どれもそのまま）"
        else "まとめて出力しました: ${results.size} ファイルのうち $written ファイルを書きました"
    }

    /** 確認の後に書く（確認している間に書き出し先が変わっていれば、何も書かずに知らせる）。 */
    fun writeBundle(plans: List<Exporter.Plan>) {
        busy = true
        scope.launch {
            try {
                message = bundleMessage(withContext(Dispatchers.IO) { Exporter.write(plans) })
            } catch (e: Exception) {
                message = "エクスポート失敗: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    /**
     * outDir は実行のときに選んだ書き出し先のフォルダ（null＝定義の output.file）。中身が同じならファイルに触れない。
     * まとめて書き出す定義は、全部を計画してから、既存のファイルを上書きするときだけ一括の確認を出す。
     */
    fun runExport(def: SkillDef, outDir: Path? = null) {
        busy = true
        scope.launch {
            try {
                if (def.export?.isBundle == true) {
                    val plans = withContext(Dispatchers.IO) { Exporter.plans(Workspace.open(wsDir), def, outDir) }
                    if (plans.any { it.previous != null && it.changed }) bundleConfirm = def to plans
                    else message = bundleMessage(withContext(Dispatchers.IO) { Exporter.write(plans) })
                    return@launch
                }
                val result = withContext(Dispatchers.IO) { Exporter.run(Workspace.open(wsDir), def, outDir) }
                message = if (result.changed) "出力しました: ${result.rel}（${result.rows} 件）"
                else "変更はありません: ${result.rel}（${result.rows} 件。ファイルはそのまま）"
            } catch (e: Exception) {
                message = "エクスポート失敗: ${e.message}"
            } finally {
                busy = false
            }
        }
    }
    // 書き出し先のフォルダを選んで出力する（ワークスペースの外も可。定義に絶対パスを書かないため、場所は実行のときに選ぶ）
    var exportTarget by remember { mutableStateOf<SkillDef?>(null) }
    val chooseExportDir = rememberDirectoryChooser(
        FilePickers.Purpose.EXPORT, "書き出し先のフォルダを選択",
        fallback = wsDir, onError = { state.status = it },
    ) { dir -> exportTarget?.let { runExport(it, dir) } }

    fun runPreview(def: SkillDef, source: String) {
        busy = true
        preview = null
        scope.launch {
            try {
                preview = withContext(Dispatchers.IO) {
                    Importer.build(Workspace.open(wsDir), def, Path.of(source))
                }
            } catch (e: Exception) {
                message = "プレビュー失敗: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    /** コード実行skill（R9-06）。人間が承認ダイアログで内容を確認した後にのみ呼ばれる。 */
    fun runApproved(def: SkillDef) {
        val runDef = def.run ?: return
        busy = true
        scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    Runner.run(Workspace.open(wsDir), def, Runner.Approval.approve(runDef))
                }
                if (outcome.changedFiles.isNotEmpty()) state.reload()
                message = buildString {
                    append(if (outcome.ok) "実行しました" else "実行に失敗しました")
                    append("（終了コード ${outcome.exitCode}")
                    if (outcome.timedOut) append("・タイムアウト")
                    append("）")
                    if (outcome.changedFiles.isNotEmpty()) append(" 変更 ${outcome.changedFiles.size} ファイル")
                    if (outcome.outOfScope.isNotEmpty()) {
                        append(" ⚠ 宣言外の変更: ${outcome.outOfScope.take(5).joinToString(", ")}")
                    }
                    if (outcome.reservedTouched.isNotEmpty()) {
                        append(" ⚠ 予約ディレクトリの変更: ${outcome.reservedTouched.take(5).joinToString(", ")}")
                    }
                    if (!outcome.ok && outcome.stderr.isNotBlank()) {
                        append(" — ${outcome.stderr.lineSequence().firstOrNull().orEmpty().take(160)}")
                    }
                }
            } catch (e: Exception) {
                message = "実行失敗: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun applyImport(p: Importer.Preview) {
        busy = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    Workspace.open(wsDir).apply(p.changeSet, requireValid = true)
                }
                message = if (result.ok) {
                    state.reload()
                    "取り込みました（作成 ${p.created.size}・更新 ${p.updated.size}）"
                } else {
                    "適用に失敗しました: " + result.results.flatMap { r ->
                        r.issues.map { "${r.file} ${it.message}" } + listOfNotNull(r.message)
                    }.firstOrNull().orEmpty()
                }
                preview = null
                importTarget = null
            } catch (e: Exception) {
                message = "適用失敗: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Text("Excel・CSV等の取り込みと、YAML原本からの帳票再生成。定義は _skills/*.yaml に置きます",
            fontSize = 11.sp, color = Palette.SubText)
        Spacer(Modifier.height(8.dp))
        val skills = loaded
        skills?.errors?.forEach { Text("⚠ $it", fontSize = 11.sp, color = Palette.ErrorRed) }
        if (skills == null || skills.skills.isEmpty()) {
            Text("skill定義がありません。_skills/*.yaml に import / export を定義します（README §14）。",
                fontSize = 11.sp, color = Palette.SubText)
        }
        skills?.skills?.forEach { def ->
            Column(
                Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .background(Palette.PanelBg, PanelShape)
                    .border(1.dp, Palette.Border, PanelShape)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${def.name}（${def.id}）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            when (def.kind) {
                                SkillKind.IMPORT -> "取り込み → ${def.import?.targetFile}"
                                SkillKind.EXPORT -> {
                                    val export = def.export
                                    when {
                                        export == null -> ""
                                        export.isBundle -> "まとめて出力 → ${export.include.joinToString("・")}"
                                        export.viewId != null -> "出力（ビュー ${export.viewId}） → ${export.outputFile}"
                                        else -> "出力 → ${export.outputFile}"
                                    }
                                }
                                SkillKind.RUN -> "コード実行 → ${def.run?.command?.joinToString(" ")?.take(80)}"
                            },
                            fontSize = 11.sp, color = Palette.SubText,
                        )
                    }
                    when (def.kind) {
                        SkillKind.EXPORT -> Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(
                                enabled = !busy,
                                onClick = { exportTarget = def; chooseExportDir() },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            ) { Text("出力先を選ぶ…", fontSize = 12.sp) }
                            Spacer(Modifier.width(6.dp))
                            Button(
                                enabled = !busy,
                                onClick = { runExport(def) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                            ) { Text("出力", fontSize = 12.sp) }
                        }
                        SkillKind.IMPORT -> Button(
                            enabled = !busy,
                            onClick = {
                                importTarget = def
                                preview = null
                                chooseImportFile()
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                        ) { Text("取り込み…", fontSize = 12.sp) }
                        SkillKind.RUN -> Button(
                            enabled = !busy,
                            onClick = { runTarget = def },  // 実行は承認ダイアログを必ず経由する（R9-06）
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                        ) { Text("実行…", fontSize = 12.sp) }
                    }
                }

                // ---------------- インポートのプレビューと適用 ----------------
                if (importTarget?.id == def.id) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CompactTextField(
                            importSource, { importSource = it }, singleLine = true,
                            placeholder = { Text("取り込むファイル（xlsx / csv / json / md / docx）") },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(
                            enabled = !busy && importSource.isNotBlank(),
                            onClick = { runPreview(def, importSource) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        ) { Text("プレビュー", fontSize = 12.sp) }
                    }
                    preview?.let { p ->
                        Spacer(Modifier.height(4.dp))
                        if (!p.ok) {
                            p.errors.take(10).forEach { Text("✗ $it", fontSize = 11.sp, color = Palette.ErrorRed) }
                        } else {
                            Text(
                                "作成 ${p.created.size} ／ 更新 ${p.updated.size} ／ 変更なし ${p.unchanged.size}",
                                fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                            )
                            (p.created.take(8).map { "＋ $it" } + p.updated.take(8).map { "± $it" }).forEach {
                                Text(it + (p.details[it.substring(2)]?.let { d -> "（$d）" } ?: ""), fontSize = 10.sp, color = Palette.SubText)
                            }
                            if (p.skippedRows > 0) {
                                Text("条件に合わず飛ばした行: ${p.skippedRows}", fontSize = 10.sp, color = Palette.SubText)
                            }
                            if (p.skippedNulls > 0) {
                                Text("空き（null）として飛ばした要素: ${p.skippedNulls}", fontSize = 10.sp, color = Palette.SubText)
                            }
                            Row {
                                Button(
                                    enabled = !busy && p.changeSet.changes.isNotEmpty(),
                                    onClick = { applyImport(p) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                ) { Text("適用（検証つき）", fontSize = 12.sp) }
                                Spacer(Modifier.width(6.dp))
                                TextButton(onClick = { preview = null; importTarget = null }) {
                                    Text("キャンセル", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (message.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(message, fontSize = 11.sp,
                color = if ("失敗" in message) Palette.ErrorRed else Palette.OkGreen)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "インポートは既存文書のマップした項目だけを更新します（YAML側の追記は保持）。エクスポートは原本から再生成できる派生成果物です。",
            fontSize = 10.sp, color = Palette.SubText,
        )
    }

    // まとめて書き出すときの上書きの確認（ファイルごとではなく一括で上書きするか取りやめるか）
    bundleConfirm?.let { (def, plans) ->
        val overwritten = plans.filter { it.previous != null && it.changed }
        val folder = plans.firstOrNull()?.file?.toAbsolutePath()?.parent?.toString().orEmpty()
        AlertDialog(
            onDismissRequest = { bundleConfirm = null },
            title = { Text("まとめて上書きします: ${def.name}") },
            text = {
                Column(Modifier.width(640.dp)) {
                    Text("書き出し先: $folder", fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${plans.size} ファイルのうち、${overwritten.size} ファイルを上書きします" +
                            "（新規 ${plans.count { it.previous == null }}・変更なし ${plans.count { !it.changed }}）。",
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "上書き: " + overwritten.joinToString("・") { it.file.fileName.toString() },
                        fontSize = 11.sp, color = Palette.SubText,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "上書きした元のファイルは残りません。書き出し先を他のアプリで開いているときは、閉じてから上書きしてください。",
                        fontSize = 11.sp, color = Palette.SubText,
                    )
                }
            },
            confirmButton = {
                Button(enabled = !busy, onClick = {
                    bundleConfirm = null
                    writeBundle(plans)
                }) { Text("上書きする") }
            },
            dismissButton = {
                TextButton(onClick = { bundleConfirm = null; message = "まとめての出力を取りやめました（何も書いていません）" }) { Text("キャンセル") }
            },
        )
    }

    // R9-06: コード実行skillの承認ダイアログ。コマンド内容と権限を確認したうえで人間が明示的に承認する
    runTarget?.let { def ->
        val runDef = def.run
        AlertDialog(
            onDismissRequest = { runTarget = null },
            title = { Text("コード実行の承認: ${def.name}") },
            text = {
                Column(Modifier.width(640.dp)) {
                    Text(
                        "このskillはワークスペースの外にも作用しうる任意のコマンドを実行します。内容を確認してください。",
                        fontSize = 12.sp, color = Palette.ErrorRed,
                    )
                    Spacer(Modifier.height(8.dp))
                    def.description?.let { Text(it, fontSize = 12.sp); Spacer(Modifier.height(6.dp)) }
                    Text("コマンド: ${runDef?.command?.joinToString(" ")}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("作業ディレクトリ: ${runDef?.workdir}", fontSize = 11.sp, color = Palette.SubText)
                    Text("タイムアウト: ${runDef?.timeoutSeconds} 秒", fontSize = 11.sp, color = Palette.SubText)
                    Text(
                        "書込宣言: " + (runDef?.writes?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "（なし。ワークスペース内の変更はすべて警告されます）"),
                        fontSize = 11.sp, color = Palette.SubText,
                    )
                }
            },
            confirmButton = {
                Button(enabled = !busy, onClick = {
                    val target = runTarget
                    runTarget = null
                    target?.let(::runApproved)
                }) { Text("承認して実行", color = Palette.ErrorRed) }
            },
            dismissButton = { TextButton(onClick = { runTarget = null }) { Text("キャンセル") } },
        )
    }
}
