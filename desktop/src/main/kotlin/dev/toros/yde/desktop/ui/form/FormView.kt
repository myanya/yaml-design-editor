package dev.toros.yde.desktop.ui.form

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.core.Views
import dev.toros.yde.app.index.DocScanner
import dev.toros.yde.app.index.IdEntry
import dev.toros.yde.app.index.IdIndex
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.validate.Severity
import dev.toros.yde.app.validate.Validator
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.OpenDocument
import dev.toros.yde.desktop.ui.common.CompactTextField
import dev.toros.yde.desktop.ui.common.MarkdownView
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.desktop.ui.common.PanelShape
import dev.toros.yde.desktop.ui.common.Slot
import dev.toros.yde.desktop.ui.common.historyHandler

/*
 * フォームの入口（FormView）: 文書を開いたときの描画の振り分け・文書の情報（_document）・同じ ID の出現の帯。
 * FormView.kt から分けた（R47-22）。部品の地図は docs/architecture/ui.md「フォームと FormCtx」。
 */

// ================================================================ FormView

@Composable
fun FormView(state: AppState, doc: OpenDocument) {
    // 編集・索引更新のたびに再コンポーズさせる
    doc.version
    state.indexVersion

    val root = doc.root
    if (root == null) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("YAML のパースに失敗しました", color = Palette.ErrorRed, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(doc.parseError ?: "", fontSize = 13.sp, color = Palette.SubText)
            Spacer(Modifier.height(8.dp))
            Text("「ソース」タブでテキストを修正すると自動で反映されます。", fontSize = 13.sp)
        }
        return
    }

    val localIds = remember(doc, doc.version) {
        val documentId = IdIndex.documentIdOf(root, state.relPath(doc.file))
        DocScanner.scan(root, state.documentOf(doc.file), doc.schema, documentId).first
    }
    // 文書ビュー（R14-01）: マッチする定義があれば表示合成をビューに委ねる。ビューは台帳層の機構のため台帳の文書に限る
    val docView = remember(state.indexVersion, state.editVersion, doc.file, doc.schema) {
        val ws = state.workspace
        val rel = state.relPath(doc.file)
        if (ws == null || !state.layout.inLedger(rel)) null
        else Views.load(ws, state.schemas, state.unsavedFirstTree()).docViewFor(rel, doc.schema?.id)
    }
    // 要素単位アタッチ（R14-03）: 要素パス → 被参照 に解決して FormCtx へ載せる（描画は ObjectFields）
    val attachments = remember(doc, doc.version, state.indexVersion, docView) {
        val ws = state.workspace
        if (docView == null || docView.attachments.isEmpty() || ws == null) emptyMap()
        else Views.resolveAttachments(docView, state.documentOf(doc.file), state.overlayIndex(), localIds, state.unsavedFirstTree())
            .flatMap { resolved -> resolved.rows.map { row -> row.path to (resolved.attachment to row) } }
            .groupBy({ it.first }, { it.second })
    }
    // 検証指摘のパス索引（UX-14）: 指摘のあるフィールドの入力枠を色づけする（全指摘種別に効く）
    val fieldIssues = remember(doc, doc.version, state.indexVersion, state.editVersion) {
        Validator.validate(
            doc.root, doc.parseError, state.documentOf(doc.file), doc.schema, state.overlayIndex(),
            indexed = state.isIndexed(doc.file), allSchemas = state.schemas,
            loader = state.unsavedFirstTree(),
            relations = state.relations, enforcement = state.enforcement,
            files = state.filePolicy, fileExists = state::fileExists,
        )
            .filter { it.path.isNotEmpty() && it.severity != Severity.INFO }
            .groupBy({ it.path }) { it.severity }
            .mapValues { (_, sevs) -> if (Severity.ERROR in sevs) Severity.ERROR else Severity.WARNING }
    }
    val ctx = remember(doc, doc.version, state.indexVersion, state.readOnlyMode, doc.draftBroken, attachments, fieldIssues) {
        FormCtx(state, doc, localIds, attachments = attachments, issues = fieldIssues)
    }

    DocumentFormWorkspace(ctx, root, docView, localIds)
}

/** `_document` のうち、人とLLMが文書を把握するための表示メタデータを編集する。 */
private class DocumentMetadataSlot(private val root: YNode.Map, private val key: String) : Slot {
    private fun metadata(): YNode.Map? = root.entries["_document"] as? YNode.Map
    override fun get(): YNode? = metadata()?.entries?.get(key)
    override fun set(n: YNode) {
        val metadata = metadata() ?: YNode.Map().also { root.entries["_document"] = it }
        metadata.entries[key] = n
    }
    override fun remove() { metadata()?.entries?.remove(key) }
}

@Composable
internal fun DocumentMetadataEditor(ctx: FormCtx, root: YNode.Map) {
    val nameSlot = DocumentMetadataSlot(root, "name")
    val descriptionSlot = DocumentMetadataSlot(root, "description")
    if (ctx.readOnly) {
        // 閲覧専用モード（R10-04）: 入力欄を出さず、記載済みのメタデータだけを高密度で表示する
        val name = (nameSlot.get() as? YNode.Scalar)?.value?.toString().orEmpty()
        val description = (descriptionSlot.get() as? YNode.Scalar)?.value?.toString().orEmpty()
        if (name.isBlank() && description.isBlank()) return
        Column(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
        ) {
            if (name.isNotBlank()) FormAnchor(listOf("_document", "name")) { Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            if (description.isNotBlank()) FormAnchor(listOf("_document", "description")) { MarkdownView(description, compact = true) }
        }
        return
    }
    val path = listOf("_document")
    val expanded = ctx.expanded(path, default = false)
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        FormFoldHeader("文書情報 · 名前と概要", expanded) { ctx.setExpanded(path, !expanded) }
        if (!expanded) return@Column
        Spacer(Modifier.height(8.dp))
        FormAnchor(path + "name") {
            FormFieldLayout(label = {
                Text("文書名", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 9.dp))
            }) {
                CompactTextField(
                    clearFocusOnEscape = true,
                    onHistory = ctx.historyHandler,
                    value = (nameSlot.get() as? YNode.Scalar)?.value?.toString().orEmpty(),
                    onValueChange = { ctx.setScalar(nameSlot, it, FieldType.STRING) },
                    singleLine = true,
                    placeholder = { Text("この文書を表す名前") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        FormAnchor(path + "description") {
            FormFieldLayout(label = {
                Column(Modifier.padding(top = 9.dp, end = 8.dp)) {
                    Text("概要", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("文書を開かずに内容を判断できる要約", fontSize = 11.sp, color = Palette.SubText)
                }
            }) {
                CompactTextField(
                    clearFocusOnEscape = true,
                    onHistory = ctx.historyHandler,
                    value = (descriptionSlot.get() as? YNode.Scalar)?.value?.toString().orEmpty(),
                    onValueChange = { ctx.setScalar(descriptionSlot, it, FieldType.STRING) },
                    placeholder = { Text("この文書が何を示し、何を含むかを簡潔に記述") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp),
                )
            }
        }
    }
}

/**
 * 同一IDの他文書出現ストリップ（P6）。
 * この文書のルート要素が持つIDが他の文書にも出現する場合（＝段階的詳細化の一部である場合）、
 * 相手側のスカラー情報を読み取り専用で見せる。詳細文書に一覧側の情報（名前・種別など）を
 * 重複記載しなくても、編集中に文脈を参照できるようにする。
 */
@Composable
internal fun SameIdOccurrencesStrip(ctx: FormCtx, localIds: List<IdEntry>) {
    val state = ctx.state
    state.indexVersion
    val rootId = localIds.firstOrNull { it.path.isEmpty() }?.id ?: return
    val others = remember(rootId, state.indexVersion, state.editVersion) {
        state.overlayIndex().occurrences(rootId).filter { it.document != state.documentOf(ctx.doc.file) }
    }
    if (others.isEmpty()) return
    val document = state.documentOf(ctx.doc.file)
    val expanded = state.memory.documentOccurrencesOpen[document] ?: false
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(shape = PanelShape, onClick = { state.memory.documentOccurrencesOpen[document] = !expanded },
                modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) {
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("他文書での定義 · $rootId · ${others.size} 件", fontSize = 12.sp,
                    color = Palette.SubText, modifier = Modifier.weight(1f))
            }
            TextButton(onClick = { state.openCrossView(rootId) },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                Text("横ぐしビュー", fontSize = 11.sp)
            }
        }
        if (!expanded) return@Column
        others.forEach { entry ->
            val summary = remember(entry, state.indexVersion, state.editVersion) {
                val root = state.unsavedFirstTree()(entry.document)
                val element = root?.let { getAt(it, entry.path) } as? YNode.Map
                element?.entries?.asSequence()
                    ?.filter { (_, v) -> v is YNode.Scalar }
                    ?.take(6)
                    ?.joinToString("　") { (k, v) ->
                        val text = (v as YNode.Scalar).value?.toString().orEmpty().replace("\n", " ")
                        "$k: ${if (text.length > 40) text.take(40) + "…" else text}"
                    }.orEmpty()
            }
            Row(
                Modifier.fillMaxWidth()
                    .clickable { state.navigateToOccurrence(entry) }
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, Modifier.size(13.dp), tint = Palette.Primary)
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        listOfNotNull(entry.title, entry.document.value).joinToString(" — "),
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    )
                    if (summary.isNotBlank()) {
                        Text(summary, fontSize = 10.sp, color = Palette.SubText, maxLines = 2)
                    }
                }
            }
        }
    }
}
