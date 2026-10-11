package dev.toros.yde.app.ui

import dev.toros.yde.app.agent.GenerationRun
import dev.toros.yde.app.ai.AiGenerationInput
import dev.toros.yde.app.ai.AiProvider
import dev.toros.yde.app.ai.RefCandidate
import dev.toros.yde.app.ai.ReferenceDoc
import dev.toros.yde.app.schema.SchemaDef
import dev.toros.yde.semantic.schema.RefTarget
import dev.toros.yde.app.core.Workspace
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
import dev.toros.yde.app.AppState
import dev.toros.yde.app.OpenDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.yaml.validate.Severity
import dev.toros.yde.app.validate.Validator
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path

// AI接続設定（プロバイダ・APIキー・モデル）は ui.AiSettings をエージェント実行と共有する。
// 実行系も agent.GenerationRun（1ターンで提出する特殊アクティビティ）でカーネルへ統合されており、
// 生成のたびに実行記録（_runs）が通常のアクティビティ実行と同じ形式で残る（R9-01）。

private enum class AiDialogStage { INPUT, LOADING, PREVIEW, ERROR }

/**
 * 参考・関連文書の候補1件。related=true は関連文書（呼び出し元が注入した内容の文脈。R24-02）、
 * false は参考文書（同一スキーマの他文書＝書き方の手本）。title はID索引のルート要素タイトル（あれば）。
 */
private class RefDocChoice(val file: Path, val rel: String, val title: String?, val related: Boolean)

/** ダイアログに並べる参考・関連文書候補の合計上限と、既定で自動選択する件数。 */
private const val REF_DOC_CHOICE_LIMIT = 20
private const val REF_DOC_AUTO_SELECT = 3

/** 生成時に選択済みだった候補1件（unsavedYaml は未保存編集中のツリーを確定した全文）。 */
private class ChosenDoc(val related: Boolean, val file: Path, val rel: String, val unsavedYaml: String?)

/**
 * マージ生成（出現単位・文書たたき台）の生成対象フィールド（R24-02 / R24-03）:
 * object 定義を、生成してよいキーへ選別した複製として返す。ownedKeys 指定時（アスペクトカード）は
 * 所有キーに限定し、`type: id` のフィールドは常に除外する（IDの改変は参照書き換えを伴うため、
 * エンティティ操作 R21-02 の領分——生成では触らない）。label はダイアログのタイトル・既定指示・
 * プロンプトの「生成対象」にそのまま出る利用者向けの呼び名（例「REQ-001 の要件定義」）。
 * 生成できるフィールドが残らなければ null（ボタンを出さない）。
 */
internal fun mergeGenField(
    schema: SchemaDef,
    objectField: FieldDef,
    ownedKeys: List<String>?,
    label: String,
): FieldDef? {
    val resolved = schema.resolve(objectField) ?: objectField
    if (resolved.type != FieldType.OBJECT) return null
    val fields = resolved.fields.filter { f ->
        val key = f.key ?: return@filter false
        (ownedKeys == null || key in ownedKeys) && (schema.resolve(f) ?: f).type != FieldType.ID
    }
    if (fields.isEmpty()) return null
    return resolved.copy(fields = fields, hints = resolved.hints.copy(label = label))
}

/**
 * 新規作成した文書のたたき台生成を要求する（R24-03）: ルート object を id 型フィールド抜きの
 * キー単位マージで生成する（スキャフォールドが設定したID・`_document` メタデータを生成が壊さない）。
 * entityId があれば関連文書（そのIDの出現・参照先・参照元）も内容の文脈として付く。
 * スキーマが適用されない等で生成できない場合は false を返す（呼び出し側がステータスで案内する）。
 */
internal fun requestDocumentDraft(state: AppState, file: Path, entityId: String? = null): Boolean {
    val doc = state.ensureDoc(file)
    val docSchema = doc.schema ?: return false
    val label = entityId?.let { "$it の詳細文書" } ?: "この文書"
    val genField = mergeGenField(docSchema, docSchema.root, ownedKeys = null, label = label) ?: return false
    val related = entityId?.let { state.entityRelatedFiles(it, file) }.orEmpty()
    state.requestAi(doc, genField, emptyList(), relatedFiles = related, mergeObjectKeys = true)
    return true
}

@Composable
fun AiGenerationHost(state: AppState) {
    val request = state.aiRequest ?: return
    // 対象文書は要求が持つ（R24-01）: openDoc に依存しないため、エンティティページ等の
    // 編集スペース以外からの生成でも、ボタンを押したフォームの文書が対象になる
    val doc = request.doc
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
    // 候補は2グループ（R24-02）: 関連文書（呼び出し元の注入。エンティティの出現・参照先/元）を先頭に、
    // 同一スキーマの他文書（更新日時の新しい順）を続ける。自動選択は関連文書があれば関連文書から
    // 最大 REF_DOC_AUTO_SELECT 件（なければ従来どおり同一スキーマの先頭 REF_DOC_AUTO_SELECT 件）
    val refChoices = remember(request) {
        val rootTitles = state.overlayIndex().entries.values.flatten()
            .filter { it.path.isEmpty() }.associateBy({ it.document }, { it.title })
        val related = request.relatedFiles.filter { it != doc.file }.distinct()
        val relatedSet = related.toSet()
        val sameSchema = state.sameSchemaFiles(doc.file, schema.id).filter { it !in relatedSet }
        (related.map { it to true } + sameSchema.map { it to false })
            .take(REF_DOC_CHOICE_LIMIT)
            .map { (file, rel) -> RefDocChoice(file, state.relPath(file), rootTitles[state.documentOf(file)], related = rel) }
    }
    var selectedRefs by remember(request) {
        mutableStateOf(
            refChoices.filter { it.related }.take(REF_DOC_AUTO_SELECT)
                .ifEmpty { refChoices.take(REF_DOC_AUTO_SELECT) }
                .map { it.rel }.toSet()
        )
    }

    fun generate() {
        stage = AiDialogStage.LOADING
        errorMessage = ""
        val baseRoot = cloneNode(doc.root ?: return)
        val baseTarget = getAt(baseRoot, request.path)
        // 未保存編集はUIスレッドで確定させ、ディスク読込だけをIOへ持ち込む（docCache は Compose の状態のため）
        val chosenRefs = refChoices.filter { it.rel in selectedRefs }
            .map { choice -> ChosenDoc(choice.related, choice.file, choice.rel, state.docFor(choice.file)?.root?.let(YamlIO::dump)) }
        generationJob = scope.launch {
            try {
                val generated = withContext(Dispatchers.IO) {
                    val ws = Workspace.open(state.workspace ?: error("ワークスペースが開かれていません"))
                    fun load(c: ChosenDoc): ReferenceDoc? {
                        val yaml = c.unsavedYaml ?: runCatching { Files.readString(c.file) }.getOrNull()
                        return yaml?.let { ReferenceDoc(c.rel, it) }
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
                                idCandidates = state.overlayIndex().entries.values.flatten()
                                    .map { RefCandidate(it.id, it.schemaId, it.documentId) } +
                                    // 文書参照（target: document。R21-03）の候補は文書ノード表から
                                    state.overlayIndex().graph.documents.keys.map {
                                        RefCandidate(it, null, it, kind = RefTarget.DOCUMENT)
                                    },
                                referenceDocs = chosenRefs.filter { !it.related }.mapNotNull(::load),
                                // 関連文書（R24-02）: 内容の文脈として参考文書とは別系統で渡す
                                contextDocs = chosenRefs.filter { it.related }.mapNotNull(::load),
                            ),
                            fileRel = state.relPath(doc.file),
                            targetPath = request.path,
                            wholeDocument = request.wholeDocument,
                            appendToArray = appendToArray,
                            mergeObjectKeys = request.mergeObjectKeys,
                        ),
                    ).generated
                }
                val target = combineProposal(baseTarget, generated, appendToArray, request.wholeDocument, request.mergeObjectKeys)
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
            // 提案の内容を重ねた索引で検証する（提案で新しく書かれた ID にも、定義体の検査——詳細文書の不足・
            // エンティティの整合・利用者の constraints——を効かせる。BACKLOG UX-109）
            Validator.validate(
                root, null, state.documentOf(doc.file), doc.schema, state.overlayIndexWith(doc.file, root),
                indexed = state.isIndexed(doc.file),
                allSchemas = state.schemas, loader = state.unsavedFirstTree(),
                relations = state.relations, enforcement = state.enforcement,
                files = state.filePolicy, fileExists = state::fileExists,
            )
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
        // 候補は2グループ（R24-02）: 関連文書＝内容の文脈、参考文書＝書き方の手本
        val relatedChoices = refChoices.filter { it.related }
        val schemaChoices = refChoices.filter { !it.related }
        if (relatedChoices.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("関連文書（この要素の出現・参照先・参照元）", fontSize = 12.sp, color = Palette.SubText)
            Text(
                "チェックした文書の全文を内容の文脈としてAIに渡します。既定では先頭から最大 $REF_DOC_AUTO_SELECT 件を自動選択しています。",
                fontSize = 10.sp,
                color = Palette.SubText,
            )
            relatedChoices.forEach { choice -> RefChoiceRow(choice, choice.rel in selectedRefs, onToggleRef) }
        }
        if (schemaChoices.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("参考文書（同じスキーマの他文書）", fontSize = 12.sp, color = Palette.SubText)
            Text(
                "チェックした文書の全文を書き方の手本としてAIに渡します。" +
                    if (relatedChoices.isEmpty()) "既定では更新が新しい順に最大 $REF_DOC_AUTO_SELECT 件を自動選択しています。" else "",
                fontSize = 10.sp,
                color = Palette.SubText,
            )
            schemaChoices.forEach { choice -> RefChoiceRow(choice, choice.rel in selectedRefs, onToggleRef) }
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
            "現在の文書、対象フィールドのスキーマ、選択した関連文書・参考文書、ワークスペース内の既存ID（最大500件）",
            fontSize = 12.sp,
        )
        if (errorMessage.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(errorMessage, color = Palette.ErrorRed, fontSize = 12.sp)
        }
    }
}

@Composable
private fun RefChoiceRow(choice: RefDocChoice, checked: Boolean, onToggle: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 30.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle(choice.rel) },
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
    // マージ生成（出現単位・文書たたき台）: displayLabel は「REQ-001 の要件定義」形式の呼び名
    request.mergeObjectKeys ->
        (if (request.relatedFiles.isEmpty()) "現在の文書を踏まえ、" else "現在の文書と関連文書を踏まえ、") +
            "${request.field.displayLabel}の各フィールドを記入してください。"
    request.field.type == FieldType.ARRAY -> "既存の内容を踏まえ、不足している${request.field.displayLabel}の候補を作成してください。"
    else -> "現在の文書を踏まえ、${request.field.displayLabel}を分かりやすく具体的に作成してください。"
}

private fun cloneNode(node: YNode): YNode = YamlIO.parse(YamlIO.dump(node))

internal fun combineProposal(
    current: YNode?,
    generated: YNode,
    append: Boolean,
    wholeDocument: Boolean,
    mergeObjectKeys: Boolean = false,
): YNode {
    if (wholeDocument) return generated
    if (append && current is YNode.Seq && generated is YNode.Seq) {
        return YNode.Seq((current.items.map(::cloneNode) + generated.items.map(::cloneNode)).toMutableList())
    }
    // 出現単位の生成（R24-02）: キー単位マージ＝非生成キーとキー順を保持し、生成キーだけ上書き・新キーは末尾。
    // 丸ごと置換にしないのは、所有外フィールド・スキーマ外キー・IDを生成が黙って消す事故を防ぐため
    if (mergeObjectKeys && current is YNode.Map && generated is YNode.Map) {
        val merged = LinkedHashMap<String, YNode>()
        current.entries.forEach { (key, value) -> merged[key] = cloneNode(generated.entries[key] ?: value) }
        generated.entries.forEach { (key, value) -> if (key !in merged) merged[key] = cloneNode(value) }
        return YNode.Map(merged)
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
