package ui

import agent.GenerationRun
import ai.AiGenerationInput
import ai.AiProvider
import ai.RefCandidate
import ai.ReferenceDoc
import core.Workspace
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.AppState
import app.OpenDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import model.YNode
import model.YPath
import model.getAt
import model.pretty
import schema.FieldType
import validate.Severity
import validate.Validator
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path

// AI接続設定（プロバイダ・APIキー・モデル）は ui.AiSettings をエージェント実行と共有する。
// 実行系も agent.GenerationRun（1ターンで提出する特殊アクティビティ）でカーネルへ統合されており、
// 生成のたびに実行記録（_runs）が通常のアクティビティ実行と同じ形式で残る（R9-01）。

private enum class AiDialogStage { INPUT, LOADING, PREVIEW, ERROR }

/** 参考文書の候補1件（同一スキーマの他文書）。title はID索引のルート要素タイトル（あれば）。 */
private class RefDocChoice(val file: Path, val rel: String, val title: String?)

/** ダイアログに並べる参考文書候補の上限と、既定で自動選択する件数（更新日時の新しい順）。 */
private const val REF_DOC_CHOICE_LIMIT = 20
private const val REF_DOC_AUTO_SELECT = 3

@Composable
fun AiGenerationHost(state: AppState) {
    val request = state.aiRequest ?: return
    val doc = state.openDoc ?: return
    val schema = doc.schema ?: return
    var instruction by remember(request) { mutableStateOf(defaultInstruction(request)) }
    var appendToArray by remember(request) {
        mutableStateOf(request.field.type == FieldType.ARRAY && !request.wholeDocument)
    }
    var stage by remember(request) { mutableStateOf(AiDialogStage.INPUT) }
    var errorMessage by remember(request) { mutableStateOf("") }
    var proposedTarget by remember(request) { mutableStateOf<YNode?>(null) }
    var proposedRoot by remember(request) { mutableStateOf<YNode?>(null) }
    var generationJob by remember(request) { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val currentTarget = getAt(doc.root ?: return, request.path)
    val currentTargetYaml = currentTarget?.let(YamlIO::dump) ?: "（未設定）"
    // 同一スキーマの他文書を参考文書候補にする。並びは更新日時の新しい順（AppState.sameSchemaFiles）で、
    // 先頭 REF_DOC_AUTO_SELECT 件を自動選択しつつ、チェックボックスで利用者が選び直せる
    val refChoices = remember(request) {
        val rootTitles = state.index.entries.values.flatten()
            .filter { it.path.isEmpty() }.associateBy({ it.file }, { it.title })
        state.sameSchemaFiles(doc.file, schema.id)
            .take(REF_DOC_CHOICE_LIMIT)
            .map { file -> RefDocChoice(file, state.relPath(file), rootTitles[file]) }
    }
    var selectedRefs by remember(request) {
        mutableStateOf(refChoices.take(REF_DOC_AUTO_SELECT).map { it.rel }.toSet())
    }

    fun generate() {
        stage = AiDialogStage.LOADING
        errorMessage = ""
        val baseRoot = cloneNode(doc.root ?: return)
        val baseTarget = getAt(baseRoot, request.path)
        // 未保存編集はUIスレッドで確定させ、ディスク読込だけをIOへ持ち込む（docCache は Compose の状態のため）
        val chosenRefs = refChoices.filter { it.rel in selectedRefs }
            .map { choice -> Triple(choice.file, choice.rel, state.docFor(choice.file)?.root?.let(YamlIO::dump)) }
        generationJob = scope.launch {
            try {
                val generated = withContext(Dispatchers.IO) {
                    val ws = Workspace.open(state.workspace ?: error("ワークスペースが開かれていません"))
                    val referenceDocs = chosenRefs.mapNotNull { (file, rel, unsavedYaml) ->
                        val yaml = unsavedYaml ?: runCatching { Files.readString(file) }.getOrNull()
                        yaml?.let { ReferenceDoc(rel, it) }
                    }
                    GenerationRun.run(
                        ws,
                        GenerationRun.Request(
                            provider = AiSettings.provider,
                            input = AiGenerationInput(
                                apiKey = AiSettings.apiKey,
                                model = AiSettings.model.trim(),
                                instruction = instruction,
                                field = request.field,
                                schema = schema,
                                targetPath = request.path.pretty(),
                                currentValueYaml = baseTarget?.let(YamlIO::dump) ?: "（未設定）",
                                currentDocumentYaml = YamlIO.dump(baseRoot),
                                idCandidates = state.index.entries.values.flatten()
                                    .map { RefCandidate(it.id, it.schemaId, it.documentId) },
                                referenceDocs = referenceDocs,
                            ),
                            fileRel = state.relPath(doc.file),
                            targetPath = request.path,
                            wholeDocument = request.wholeDocument,
                            appendToArray = appendToArray,
                        ),
                    ).generated
                }
                val target = combineProposal(baseTarget, generated, appendToArray, request.wholeDocument)
                proposedTarget = target
                proposedRoot = replaceInClone(baseRoot, request.path, target, request.wholeDocument)
                stage = AiDialogStage.PREVIEW
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = buildString {
                    append(e.message ?: e.toString())
                    // 失敗時も実行記録（入出力メッセージ入り）が残るため、デバッグの参照先を示す
                    (e as? GenerationRun.GenerationFailedException)?.runFile?.let {
                        append("\n実行記録: ${state.relPath(it)}")
                    }
                }
                stage = AiDialogStage.ERROR
            }
        }
    }

    fun cancelGeneration() {
        generationJob?.cancel()
        generationJob = null
        stage = AiDialogStage.INPUT
    }

    val validationIssues = remember(proposedRoot) {
        proposedRoot?.let { root ->
            Validator.validate(root, null, doc.file, doc.schema, state.index)
        }.orEmpty()
    }

    AlertDialog(
        onDismissRequest = { if (stage != AiDialogStage.LOADING) state.dismissAi() },
        modifier = Modifier.widthIn(min = 680.dp, max = 1040.dp),
        title = {
            Column {
                Text(if (request.wholeDocument) "文書をAIで作成" else "${request.field.displayLabel}をAIで作成")
                Text(
                    "生成結果は確認後に適用され、自動保存されません",
                    fontSize = 11.sp,
                    color = Palette.SubText,
                )
            }
        },
        text = {
            when (stage) {
                AiDialogStage.INPUT, AiDialogStage.ERROR -> AiInputContent(
                    instruction = instruction,
                    onInstructionChange = { instruction = it },
                    provider = AiSettings.provider,
                    onProviderChange = { AiSettings.provider = it },
                    apiKey = AiSettings.apiKey,
                    onApiKeyChange = { AiSettings.apiKey = it },
                    model = AiSettings.model,
                    onModelChange = { AiSettings.model = it },
                    isArray = request.field.type == FieldType.ARRAY && !request.wholeDocument,
                    appendToArray = appendToArray,
                    onAppendChange = { appendToArray = it },
                    refChoices = refChoices,
                    selectedRefs = selectedRefs,
                    onToggleRef = { rel ->
                        selectedRefs = if (rel in selectedRefs) selectedRefs - rel else selectedRefs + rel
                    },
                    errorMessage = errorMessage,
                )
                AiDialogStage.LOADING -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("AI提案を生成しています…")
                }
                AiDialogStage.PREVIEW -> AiPreviewContent(
                    before = currentTargetYaml,
                    after = proposedTarget?.let(YamlIO::dump).orEmpty(),
                    errors = validationIssues.count { it.severity == Severity.ERROR },
                    warnings = validationIssues.count { it.severity == Severity.WARNING },
                )
            }
        },
        confirmButton = {
            when (stage) {
                AiDialogStage.INPUT, AiDialogStage.ERROR -> Button(
                    enabled = instruction.isNotBlank() && AiSettings.apiKey.isNotBlank() && AiSettings.model.isNotBlank(),
                    onClick = ::generate,
                ) { Text("生成") }
                AiDialogStage.PREVIEW -> Button(onClick = {
                    doc.root = proposedRoot?.let(::cloneNode) ?: return@Button
                    doc.markEdited()
                    state.dismissAi()
                }) { Text("提案を適用") }
                AiDialogStage.LOADING -> Unit
            }
        },
        dismissButton = {
            Row {
                when (stage) {
                    AiDialogStage.PREVIEW -> {
                        TextButton(onClick = { stage = AiDialogStage.INPUT }) { Text("指示を変更") }
                        TextButton(onClick = ::generate) { Text("再生成") }
                        TextButton(onClick = state::dismissAi) { Text("キャンセル") }
                    }
                    AiDialogStage.LOADING -> TextButton(onClick = ::cancelGeneration) { Text("生成を中止") }
                    else -> TextButton(onClick = state::dismissAi) { Text("キャンセル") }
                }
            }
        },
    )
}

@Composable
private fun AiInputContent(
    instruction: String,
    onInstructionChange: (String) -> Unit,
    provider: AiProvider,
    onProviderChange: (AiProvider) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    model: String,
    onModelChange: (String) -> Unit,
    isArray: Boolean,
    appendToArray: Boolean,
    onAppendChange: (Boolean) -> Unit,
    refChoices: List<RefDocChoice>,
    selectedRefs: Set<String>,
    onToggleRef: (String) -> Unit,
    errorMessage: String,
) {
    Column(Modifier.fillMaxWidth().heightIn(max = 580.dp).verticalScroll(rememberScrollState())) {
        Text("指示", fontSize = 12.sp, color = Palette.SubText)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = instruction,
            onValueChange = onInstructionChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            placeholder = { Text("作成したい内容や重視する点を入力") },
        )
        if (isArray) {
            Spacer(Modifier.height(10.dp))
            Text("生成結果の扱い", fontSize = 12.sp, color = Palette.SubText)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = appendToArray, onClick = { onAppendChange(true) })
                Text("既存項目を残して候補を追加", fontSize = 13.sp)
                Spacer(Modifier.width(16.dp))
                RadioButton(selected = !appendToArray, onClick = { onAppendChange(false) })
                Text("配列全体を置き換え", fontSize = 13.sp)
            }
        }
        if (refChoices.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("参考文書（同じスキーマの他文書）", fontSize = 12.sp, color = Palette.SubText)
            Text(
                "チェックした文書の全文を書き方の手本としてAIに渡します。既定では更新が新しい順に最大 $REF_DOC_AUTO_SELECT 件を自動選択しています。",
                fontSize = 10.sp,
                color = Palette.SubText,
            )
            refChoices.forEach { choice ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 30.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = choice.rel in selectedRefs,
                        onCheckedChange = { onToggleRef(choice.rel) },
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(choice.rel, fontSize = 13.sp)
                    choice.title?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(it, fontSize = 11.sp, color = Palette.SubText)
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("AI接続", fontSize = 12.sp, color = Palette.SubText)
        Row(verticalAlignment = Alignment.CenterVertically) {
            AiProvider.entries.forEach { p ->
                RadioButton(selected = provider == p, onClick = { onProviderChange(p) })
                Text(p.label, fontSize = 13.sp)
                Spacer(Modifier.width(12.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text("APIキー（保存しません）") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = model,
                onValueChange = onModelChange,
                label = { Text(modelFieldLabel(provider)) },
                singleLine = true,
                modifier = Modifier.width(210.dp),
            )
        }
        Text(
            "${provider.envVar} 環境変数も利用できます。プロバイダとモデル名だけは端末の設定に保存されます。",
            fontSize = 10.sp,
            color = Palette.SubText,
        )
        AiConnectionSettingsSection(provider)
        Spacer(Modifier.height(14.dp))
        Text("送信するコンテキスト", fontSize = 12.sp, color = Palette.SubText)
        Text(
            "現在の文書、対象フィールドのスキーマ、選択した参考文書、ワークスペース内の既存ID（最大500件）",
            fontSize = 12.sp,
        )
        if (errorMessage.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(errorMessage, color = Palette.ErrorRed, fontSize = 12.sp)
        }
    }
}

@Composable
private fun AiPreviewContent(before: String, after: String, errors: Int, warnings: Int) {
    Column(Modifier.fillMaxWidth()) {
        val color = if (errors > 0) Palette.ErrorRed else if (warnings > 0) Palette.WarnOrange else Palette.OkGreen
        Text("適用後の検証: エラー $errors 件・警告 $warnings 件", fontSize = 12.sp, color = color)
        Spacer(Modifier.height(8.dp))
        val lines = remember(before, after) { calculateDiff(before, after) }
        Row(Modifier.fillMaxWidth().background(Palette.PanelBg).padding(vertical = 5.dp)) {
            Text("現在", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 42.dp))
            Text("AI提案", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 42.dp))
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 500.dp).background(Palette.EditorBg, PanelShape).padding(10.dp)) {
            val listState = rememberLazyListState()
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 8.dp)) {
                items(lines) { line -> DiffLineRow(line) }
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

private fun defaultInstruction(request: AppState.AiRequest): String = when {
    request.wholeDocument -> "現在の文書とスキーマを踏まえ、文書全体のたたき台を作成してください。"
    request.field.type == FieldType.ARRAY -> "既存の内容を踏まえ、不足している${request.field.displayLabel}の候補を作成してください。"
    else -> "現在の文書を踏まえ、${request.field.displayLabel}を分かりやすく具体的に作成してください。"
}

private fun cloneNode(node: YNode): YNode = YamlIO.parse(YamlIO.dump(node))

internal fun combineProposal(current: YNode?, generated: YNode, append: Boolean, wholeDocument: Boolean): YNode {
    if (wholeDocument) return generated
    if (append && current is YNode.Seq && generated is YNode.Seq) {
        return YNode.Seq((current.items.map(::cloneNode) + generated.items.map(::cloneNode)).toMutableList())
    }
    return generated
}

internal fun replaceInClone(root: YNode, path: YPath, value: YNode, preserveDocumentMetadata: Boolean): YNode {
    var replacement = cloneNode(value)
    if (path.isEmpty()) {
        if (preserveDocumentMetadata && root is YNode.Map && replacement is YNode.Map) {
            val metadata = root.entries["_document"]?.let(::cloneNode)
            if (metadata != null) {
                replacement = YNode.Map(LinkedHashMap<String, YNode>().apply {
                    put("_document", metadata)
                    replacement.entries.filterKeys { it != "_document" }.forEach(::put)
                })
            }
        }
        return replacement
    }
    val copy = cloneNode(root)
    val parent = getAt(copy, path.dropLast(1))
    when (val segment = path.last()) {
        is String -> (parent as? YNode.Map)?.entries?.set(segment, replacement)
            ?: error("AI提案の適用先が見つかりません")
        is Int -> (parent as? YNode.Seq)?.items?.let { items ->
            if (segment !in items.indices) error("AI提案の適用先が見つかりません")
            items[segment] = replacement
        } ?: error("AI提案の適用先が見つかりません")
        else -> error("未対応のパスです")
    }
    return copy
}
