package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.AppState
import core.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import skill.Importer
import skill.SkillDef
import skill.SkillKind
import skill.Skills
import skill.Exporter
import java.nio.file.Path
import javax.swing.JFileChooser

/**
 * skillパネル（R5のUI）。`_skills` の入出力変換を一覧し、
 * エクスポートは即実行、インポートは「ファイル選択→プレビュー→適用」の二段階で行う。
 */
@Composable
fun SkillsPanel(state: AppState, onClose: () -> Unit) {
    val wsDir = state.workspace ?: return
    var loaded by remember { mutableStateOf<Skills.LoadResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var importTarget by remember { mutableStateOf<SkillDef?>(null) }
    var importSource by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Importer.Preview?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch { withContext(Dispatchers.IO) { loaded = Skills.load(wsDir) } }
    }
    LaunchedEffect(wsDir) { reload() }

    fun runExport(def: SkillDef) {
        busy = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { Exporter.run(Workspace.open(wsDir), def) }
                message = "出力しました: ${result.rel}（${result.rows} 行）"
            } catch (e: Exception) {
                message = "エクスポート失敗: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

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

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        modifier = Modifier.widthIn(min = 720.dp, max = 980.dp),
        title = {
            Column {
                Text("skill（入出力変換）")
                Text("Excel・CSV等の取り込みと、YAML原本からの帳票再生成。定義は _skills/*.yaml に置きます",
                    fontSize = 11.sp, color = Palette.SubText)
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState())) {
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
                                        SkillKind.EXPORT -> "出力 → ${def.export?.outputFile}"
                                    },
                                    fontSize = 11.sp, color = Palette.SubText,
                                )
                            }
                            when (def.kind) {
                                SkillKind.EXPORT -> Button(
                                    enabled = !busy,
                                    onClick = { runExport(def) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                ) { Text("出力", fontSize = 12.sp) }
                                SkillKind.IMPORT -> Button(
                                    enabled = !busy,
                                    onClick = {
                                        importTarget = def
                                        preview = null
                                        chooseImportFile()?.let { importSource = it.toString() }
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                ) { Text("取り込み…", fontSize = 12.sp) }
                            }
                        }

                        // ---------------- インポートのプレビューと適用 ----------------
                        if (importTarget?.id == def.id) {
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CompactTextField(
                                    importSource, { importSource = it }, singleLine = true,
                                    placeholder = { Text("取り込むファイル（xlsx / csv / json / md）", color = Palette.SubText) },
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
                                        Text(it, fontSize = 10.sp, color = Palette.SubText)
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
        },
        confirmButton = {},
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("閉じる") } },
    )
}

/** Swing のファイル選択ダイアログ。 */
private fun chooseImportFile(): Path? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.FILES_ONLY
        dialogTitle = "取り込むファイルを選択"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
}
