package dev.toros.yde.app.ui

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import dev.toros.yde.app.AppState
import dev.toros.yde.app.OpenDocument
import dev.toros.yde.app.core.DocBacklinkRow
import dev.toros.yde.app.core.DocBacklinksDef
import dev.toros.yde.app.core.DocSectionResolved
import dev.toros.yde.app.core.DocViewAttachment
import dev.toros.yde.app.core.DocViewDef
import dev.toros.yde.app.core.Views
import dev.toros.yde.app.index.DocScanner
import dev.toros.yde.app.index.IdEntry
import dev.toros.yde.app.index.IdIndex
import dev.toros.yde.app.index.RefEntry
import dev.toros.yde.app.index.idMatchesPattern
import dev.toros.yde.app.index.isAllowedBy
import dev.toros.yde.semantic.schema.ValueFormat
import dev.toros.yde.app.schema.IdSuggest
import dev.toros.yde.app.schema.SchemaDef
import dev.toros.yde.semantic.schema.RefTarget
import dev.toros.yde.semantic.schema.Relations
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.app.schema.Display
import dev.toros.yde.yaml.validate.Severity
import dev.toros.yde.app.validate.Validator
import dev.toros.yde.semantic.schema.AutoValue
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.app.schema.effectiveFields
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Path

private val fieldTextStyle = TextStyle(fontSize = 13.sp)

/** ネスト構造（object・array・map）を既定で展開する深さ（フォームのルートからのパス段数）。 */
private const val DEFAULT_EXPAND_DEPTH = 2

/**
 * フォーム編集のコンテキスト。
 * localIds は未保存編集を含む「現在のドキュメント内の ID 集合」で、参照解決の即時判定に使う。
 * readOnly は閲覧専用モード（R10-04）: 編集UIを表示専用に切り替え、markEdited への到達を拒否する。
 * 既定値を state.readOnlyMode から取るため、FormCtx を作る全ビュー（フォーム・グリッド・横ぐし）へ自動波及する。
 * ソースに解析できない書きかけがある間（doc.draftBroken。P43 R43-02）も閲覧専用＝フォームで触って書きかけを失わない。
 * baseDepth は埋め込みフォーム（ID横ぐし）の基準パス段数。既定展開の深さ判定を
 * 「文書ルートから」ではなく「表示しているフォームのルートから」の相対深さで行うために使う。
 */
class FormCtx(
    val state: AppState,
    val doc: OpenDocument,
    val localIds: List<IdEntry>,
    val readOnly: Boolean = state.readOnlyMode || doc.draftBroken,
    val baseDepth: Int = 0,
    /** 文書ビューの要素単位アタッチ（R14-03）: 要素パス → その位置に差し込む被参照。ObjectFields が描画する。 */
    val attachments: Map<YPath, List<Pair<DocViewAttachment, DocBacklinkRow>>> = emptyMap(),
    /** パス → 検証指摘の最悪重大度（UX-14）。FieldRow が入力枠の色づけに使う（空 = 色づけしない）。 */
    val issues: Map<YPath, Severity> = emptyMap(),
    /** AI生成へ渡す関連文書の候補（R24-02）。エンティティページが出現・参照先/元のファイルを注入する。 */
    val aiRelatedFiles: List<Path> = emptyList(),
) {
    /** このパスの検証指摘の重大度（UX-14）。エラー=赤・警告=オレンジの枠色に使う。 */
    fun issueAt(path: YPath): Severity? = issues[path]

    /**
     * ネスト構造の既定展開判定。フォームのルートから DEFAULT_EXPAND_DEPTH 階層までは開き、
     * より深い階層は畳んで畳み込みサマリ（E-11）に任せる。編集・閲覧専用モード共通。
     */
    fun defaultExpanded(path: YPath): Boolean = path.size - baseDepth <= DEFAULT_EXPAND_DEPTH

    /**
     * ハイライト対象（ジャンプ先）がこのパスの配下にあるか（UX-10）。
     * 畳まれたままだと対象の Highlightable がコンポーズされず、スクロールもハイライトも届かないため、
     * 畳みを持つエディタはこの判定で自動展開する（展開はジャンプ後も維持する）。
     */
    fun highlightInside(path: YPath): Boolean =
        state.highlightPath?.let { it.size > path.size && it.subList(0, path.size) == path } == true

    /** この文書の文書ID（画面の覚えの鍵）。 */
    private val documentId get() = state.documentOf(doc.file)

    /**
     * 畳みを開いているか。画面の覚え（`ScreenMemory`）→ 既定の深さの順で決め、ジャンプ先が中なら開いて覚える
     * （UX-10。展開はジャンプ後も維持）。覚えは画面を離れても残るので、戻ってきたとき同じ見え方になる。
     */
    fun expanded(path: YPath, default: Boolean = defaultExpanded(path)): Boolean {
        if (highlightInside(path)) {
            setExpanded(path, true)
            return true
        }
        return state.memory.fold(documentId, path) ?: default
    }

    fun setExpanded(path: YPath, expanded: Boolean) = state.memory.setFold(documentId, path, expanded)

    fun markEdited(coalesceWith: Any? = null) {
        check(!readOnly) { "閲覧専用モードでは編集できません" }
        doc.markEdited(coalesceWith)
    }

    fun resolvable(ref: RefEntry): Boolean =
        ref.targetId.isNotBlank() &&
            (localIds.any { it.id == ref.targetId && it.isAllowedBy(ref) } || state.overlayIndex().resolve(ref).isNotEmpty())

    /**
     * タイトルの索引借用（UX-71）: 要素自身にタイトルがないとき、同一論理IDの他出現（定義出現など）から
     * タイトルを借りる。id しか持たない写像行・詳細行の畳みヘッダが「何のIDか」を語れるようにする。
     */
    fun indexTitleLookup(): (String) -> String? = { id ->
        state.overlayIndex().occurrences(id).firstNotNullOfOrNull { it.title }
    }

    /** スキーマ外の汎用参照は、パターン／スコープ制約なしで解決する。 */
    fun resolvable(id: String): Boolean =
        resolvable(RefEntry(id, state.documentOf(doc.file), emptyList(), "ref"))

    /** 項目の宣言（idPatterns・scope）から参照の制約を組み立てる。参照欄とテーブルの参照セルが共用する。 */
    fun refConstraint(field: FieldDef, id: String): RefEntry =
        RefEntry(id, state.documentOf(doc.file), emptyList(), field.key ?: "ref", field.idPatterns, field.scope)

    /**
     * 参照先の名前（表示解決の結果＝`IdEntry.title`）。未保存の編集を含む自文書の ID（localIds）を優先し、
     * 次に上書き索引の出現を見る。どちらも idPatterns と scope で許される出現だけ
     * （architecture/index-references.md「索引の二層」）。解決できない・名前が無い・名前が ID と同じなら null。
     */
    fun refTargetTitle(field: FieldDef, id: String): String? {
        val ref = refConstraint(field, id)
        if (!resolvable(ref)) return null
        val title = localIds.firstOrNull { it.id == id && it.isAllowedBy(ref) }?.title
            ?: state.overlayIndex().occurrences(id).firstNotNullOfOrNull { e -> if (e.isAllowedBy(ref)) e.title else null }
        return title?.takeIf { it.isNotBlank() && it != id }
    }

    /** スキーマ型に応じてスカラー値を変換して書き込む。 */
    fun setScalar(slot: Slot, raw: String, type: FieldType) {
        val v: Any? = when (type) {
            FieldType.INT -> raw.toLongOrNull() ?: raw
            FieldType.NUMBER -> (raw.toLongOrNull() ?: raw.toDoubleOrNull()) ?: raw
            else -> raw
        }
        writeScalar(slot, v)
    }

    /** 元の値の型を保ちながらスカラー値を書き込む（汎用エディタ用）。 */
    fun setGenericScalar(slot: Slot, raw: String) {
        val prev = (slot.get() as? YNode.Scalar)?.value
        val v: Any? = when (prev) {
            is Boolean -> raw.toBooleanStrictOrNull() ?: raw
            is Int, is Long -> raw.toLongOrNull() ?: raw
            is Float, is Double -> raw.toDoubleOrNull() ?: raw
            else -> raw
        }
        writeScalar(slot, v)
    }

    private fun writeScalar(slot: Slot, v: Any?) {
        // 閲覧専用モードではツリーを変異させる前に拒否する（markEdited の防御より手前で止める）
        check(!readOnly) { "閲覧専用モードでは編集できません" }
        val node = slot.get()
        if (node is YNode.Scalar) {
            node.value = v
            // 同一スカラーへの連続入力は履歴1エントリへ合体（UX-11。キー=変異対象ノードの同一性。
            // フィールド移動・Undo後はインスタンスが変わるため自然に境界になる）
            markEdited(coalesceWith = node)
        } else {
            val created = YNode.Scalar(v)
            slot.set(created)
            markEdited(coalesceWith = created)
        }
    }
}

/** スキーマ定義に基づく新規ノードの初期値を作る。 */
/** 保存時に自動で入る値（auto）の案内文（項目の説明の下に出す）。 */
internal fun autoValueHint(auto: AutoValue): String = when (auto) {
    AutoValue.CREATED -> "作成時に自動で入ります（空のときだけ）"
    AutoValue.UPDATED -> "保存のときに自動で今の日付・時刻になります（中身が変わっていたとき）"
    AutoValue.REVISION -> "保存のたびに自動で +1 されます（中身が変わっていたとき）"
}

fun defaultNode(field: FieldDef?, schema: SchemaDef? = null, depth: Int = 0): YNode {
    if (depth > 24) return YNode.Map()
    val resolved = schema?.resolve(field) ?: field
    if (resolved?.default != null) return YamlIO.fromAny(resolved.default)
    return when (resolved?.type) {
        FieldType.BOOLEAN -> YNode.Scalar(false)
        FieldType.INT, FieldType.NUMBER -> YNode.Scalar(0)
        FieldType.ARRAY -> YNode.Seq()
        FieldType.MAP -> YNode.Map()
        FieldType.OBJECT -> {
            val entries = LinkedHashMap<String, YNode>().apply {
                resolved.fields.forEach { ch ->
                    val key = ch.key
                    if (ch.required && key != null) put(key, defaultNode(ch, schema, depth + 1))
                }
            }
            val map = YNode.Map(entries)
            // 判別 enum の初期値に応じた case フィールドのうち必須のものを補う
            schema?.effectiveFields(resolved.fields, map)?.fields?.forEach { ch ->
                val key = ch.key ?: return@forEach
                if (ch.required && key !in entries) entries[key] = defaultNode(ch, schema, depth + 1)
            }
            map
        }
        else -> YNode.Scalar("")
    }
}

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

// ================================================================ 文書ビュー（R14-01）

/** スキーマ駆動フォームのルート描画。文書ビューがない文書の既定表示＝暗黙の既定ビュー。 */
@Composable
internal fun SchemaRootForm(
    ctx: FormCtx,
    root: YNode,
    include: List<String>? = null,
    exclude: Set<String> = emptySet(),
) {
    val doc = ctx.doc
    val schemaDef = doc.schema
    val rootDef = schemaDef?.resolve(schemaDef.root)
    if (rootDef?.type == FieldType.OBJECT && root is YNode.Map) {
        ObjectFields(ctx, schemaDef.resolve(schemaDef.root)?.fields.orEmpty(), root, emptyList(), include, exclude)
    } else if (rootDef?.type == FieldType.MAP) {
        ValueEditor(ctx, rootDef, RootSlot(doc), emptyList())
    } else {
        if (schemaDef != null) {
            Text(
                "スキーマ「${schemaDef.name}」のルート定義とドキュメント構造が一致しないため、汎用ビューで表示しています。",
                fontSize = 12.sp, color = Palette.WarnOrange,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        GenericEditor(ctx, RootSlot(doc), emptyList(), refHint = false)
    }
}

/**
 * 文書ビューのセクション描画。fields セクションは既存のフォーム経路（FormCtx / Slot）へ委譲し、
 * backlinks セクションは読み取り専用の被参照表を出す——ビュー専用の書込経路は作らない（architecture/views.md「ビュー機構」）。
 */
@Composable
internal fun DocViewSections(ctx: FormCtx, view: DocViewDef, root: YNode.Map, localIds: List<IdEntry>) {
    val state = ctx.state
    val doc = ctx.doc
    // 行（文書内ID）は未保存編集を反映（localIds）、被参照は索引＝保存済み状態基準（SidePanel と同じ）
    val resolved = remember(doc, doc.version, state.indexVersion, view) {
        state.workspace?.let { ws ->
            Views.resolveDocument(view, state.documentOf(doc.file), state.overlayIndex(), localIds) { d ->
                state.unsavedFirstTree()(d)
            }
        }.orEmpty()
    }
    resolved.forEachIndexed { i, sectionResolved ->
        val section = sectionResolved.section
        FormAnchor(section.fieldsPath ?: listOf("@view", i)) { Column(Modifier.fillMaxWidth()) {
            if (i > 0) Spacer(Modifier.height(12.dp))
            section.label?.let {
                Text(it, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Palette.Primary)
                Spacer(Modifier.height(4.dp))
            }
            val fieldsPath = section.fieldsPath
            if (fieldsPath != null) {
                val include = section.fieldsInclude.ifEmpty { null }
                val exclude = section.fieldsExclude.toSet()
                if (fieldsPath.isEmpty()) {
                    SchemaRootForm(ctx, root, include, exclude)
                } else {
                    val field = doc.schema?.let { Views.fieldAt(it, root, fieldsPath) }
                    val slot = slotAt(root, fieldsPath)
                    val node = getAt(root, fieldsPath)
                    when {
                        slot == null -> Text(
                            "パス「${fieldsPath.pretty()}」は文書内に存在しません",
                            fontSize = 11.sp, color = Palette.WarnOrange,
                        )
                        // 選別指定つきの object パスは、そのオブジェクトの子フィールドを直接（選別して）描画する
                        (include != null || exclude.isNotEmpty()) &&
                            field?.type == FieldType.OBJECT && node is YNode.Map ->
                            ObjectFields(ctx, field.fields, node, fieldsPath, include, exclude)
                        field != null -> FieldRow(ctx, field, slot, fieldsPath)
                        else -> GenericEditor(ctx, slot, fieldsPath, refHint = false)
                    }
                }
            } else {
                val flowView = section.flowView
                if (flowView != null) EmbeddedFlowPane(state, doc, flowView)
                else BacklinkSectionTable(state, sectionResolved)
            }
        } }
    }
}

/** 被参照表セクション。派生表示のため常に読み取り専用（クリックで参照元の位置へジャンプ）。 */
@Composable
private fun BacklinkSectionTable(state: AppState, resolved: DocSectionResolved) {
    val def = resolved.section.backlinks ?: return
    val rows = resolved.rows.orEmpty()
    Column(
        Modifier.fillMaxWidth().background(Palette.PanelBg, PanelShape)
            .border(1.dp, Palette.Border, PanelShape).padding(10.dp),
    ) {
        if (rows.isEmpty()) {
            Text("対象のIDがこの文書にありません", fontSize = 11.sp, color = Palette.SubText)
        }
        rows.forEachIndexed { i, row ->
            if (i > 0) Spacer(Modifier.height(6.dp))
            Text(
                listOfNotNull(row.id, row.title).joinToString(" "),
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            )
            BacklinkItems(state, def, row)
        }
    }
}

/** 被参照1行分の項目一覧（セクション表・要素単位アタッチで共用。読み取り専用＋ジャンプ）。 */
@Composable
private fun BacklinkItems(state: AppState, def: DocBacklinksDef, row: DocBacklinkRow) {
    if (row.backlinks.isEmpty()) {
        Text("（被参照なし）", fontSize = 10.sp, color = Palette.SubText)
    }
    row.backlinks.forEach { b ->
        Row(
            Modifier.fillMaxWidth()
                .clickable {
                    // 参照元の位置へジャンプ（参照値そのものの位置。SidePanel の被参照と同じ経路）
                    state.openFile(b.document, b.refPath)
                    state.editorTab = 0
                }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, Modifier.size(12.dp), tint = Palette.Primary)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    listOfNotNull(b.sourceId ?: b.document.value, b.sourceTitle).joinToString(" "),
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                )
                val details = def.columns.mapIndexed { ci, col ->
                    "${col.label}: ${Views.cellText(b.values.getOrNull(ci), 40)}"
                } + listOfNotNull(
                    // 被参照方向は inverseLabel（R20-02）＋属性付き参照の属性注記（R20-03）
                    Relations.inverseLabelOf(state.relations, b.relation)?.let { "関係: $it" },
                    b.attributes,
                    b.document.value,
                )
                Text(details.joinToString("　"), fontSize = 10.sp, color = Palette.SubText, maxLines = 2)
            }
        }
    }
}

/** 要素単位アタッチ（R14-03）の描画。ObjectFields の末尾から、その要素のパスに差し込まれた被参照表を出す。 */
@Composable
private fun AttachedBacklinks(ctx: FormCtx, path: YPath) {
    val attached = ctx.attachments[path] ?: return
    attached.forEach { (attachment, row) ->
        Spacer(Modifier.height(6.dp))
        Column(
            Modifier.fillMaxWidth().background(Palette.PanelBg, PanelShape)
                .border(1.dp, Palette.Border, PanelShape).padding(8.dp),
        ) {
            attachment.label?.let {
                Text(it, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Palette.Primary)
                Spacer(Modifier.height(2.dp))
            }
            BacklinkItems(ctx.state, attachment.backlinks, row)
        }
    }
}

// ================================================================ スキーマ駆動フォーム

/**
 * スキーマの fields をフォーム行として描画し、スキーマ外のキーも汎用行で表示する。
 * include / exclude は文書ビューの fields セクションによる表示キーの選別（R14-03）:
 * include はその順で列挙したキーだけを描画し（スキーマ外キーの表示なし）、exclude は該当キーを除いて通常描画する。
 */
@Composable
fun ObjectFields(
    ctx: FormCtx,
    fields: List<FieldDef>,
    map: YNode.Map,
    path: YPath,
    include: List<String>? = null,
    exclude: Set<String> = emptySet(),
) {
    // enum 値に応じた case フィールドを合成した実効フィールドで描画する
    val effective = ctx.doc.schema?.effectiveFields(fields, map)
    val effectiveList = effective?.fields ?: fields
    if (include != null) {
        // 選別モード: 列挙順に描画。スキーマに無いキーもデータにあれば汎用行で出す（無ければリントが指摘）
        val byKey = effectiveList.associateBy { it.key }
        for (key in include) {
            val field = byKey[key]
            if (field != null) FieldRow(ctx, field, MapSlot(map, key), path + key)
            else if (map.entries.containsKey(key)) GenericMapEntryRow(ctx, map, key, path)
        }
        AttachedBacklinks(ctx, path)
        return
    }
    val definedKeys = effectiveList.mapNotNull { it.key }.toSet()
    for (field in effectiveList) {
        val key = field.key ?: continue
        if (key in exclude) continue
        FieldRow(ctx, field, MapSlot(map, key), path + key)
    }
    val extraKeys = map.entries.keys.filter {
        it !in definedKeys && it !in exclude && !(path.isEmpty() && it == "_document")
    }
    val inactiveKeys = extraKeys.filter { effective?.inactive?.containsKey(it) == true }
    if (inactiveKeys.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("現在の選択では使用しないキー", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.WarnOrange)
        for (k in inactiveKeys.toList()) {
            val info = effective?.inactive?.get(k) ?: continue
            Text(
                "「$k」は「${info.discriminator.displayLabel}」が ${info.values.joinToString(" / ")} のとき使用できます",
                fontSize = 10.sp, color = Palette.SubText,
            )
            GenericMapEntryRow(ctx, map, k, path)
        }
    }
    val unknownKeys = extraKeys.filter { it !in inactiveKeys }
    if (unknownKeys.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("スキーマ外のキー", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.WarnOrange)
        for (k in unknownKeys.toList()) {
            GenericMapEntryRow(ctx, map, k, path)
        }
    }
    // 文書ビューの要素単位アタッチ（R14-03）: この要素のパスに差し込まれた被参照表
    AttachedBacklinks(ctx, path)
}

private val LocalFormIndent = staticCompositionLocalOf { 0.dp }

/** 深い構造でも入力幅を残し、境界は控えめな縦線で示す。 */
@Composable
internal fun FormGroup(nested: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val used = LocalFormIndent.current
    val indent = if (nested) (FormSpacing.MaxIndent - used).coerceIn(0.dp, FormSpacing.Indent) else 0.dp
    val guide = Palette.Border
    CompositionLocalProvider(LocalFormIndent provides used + indent) {
        Column(Modifier.fillMaxWidth().drawBehind {
            if (indent > 0.dp) drawLine(guide, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx())
        }.padding(start = indent, top = 4.dp, bottom = 4.dp), content = content)
    }
}

@Composable
internal fun FormFieldLayout(
    modifier: Modifier = Modifier,
    labelWidth: Dp = 148.dp,
    structured: Boolean = false,
    label: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    // 入れ子ごとの実際の幅で判断し、項目名の固定幅が入力欄を押し潰さないようにする。
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (stackFormField(structured, maxWidth.value, labelWidth.value)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (structured) 8.dp else 4.dp)) {
                Box(Modifier.fillMaxWidth()) { label() }
                Box(Modifier.fillMaxWidth()) { content() }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(Modifier.width(labelWidth)) { label() }
                Box(Modifier.weight(1f)) { content() }
            }
        }
    }
}

@Composable
internal fun FieldRow(ctx: FormCtx, field: FieldDef, slot: Slot, path: YPath) {
    @Suppress("UNUSED_VARIABLE") val version = ctx.doc.version
    val effective = ctx.doc.schema?.resolve(field) ?: field
    // 閲覧専用モード（R10-04）はパディングを圧縮して表示密度を上げる
    val structured = isStructuredFormValue(effective, slot.get())
    val labelTop = if (structured) 0.dp else if (ctx.readOnly) 2.dp else 8.dp
    Highlightable(ctx.state, path) {
        FormFieldLayout(
            Modifier.padding(top = if (structured) FormSpacing.GroupGap else FormSpacing.FieldGap,
                bottom = FormSpacing.FieldGap),
            structured = structured,
            label = {
                Column(Modifier.padding(top = labelTop, end = 8.dp)) {
                    FieldHelp(effective.displayLabel, effective.description, showPreview = !ctx.readOnly) { labelModifier, helpButton ->
                        FlowRow {
                            Text(effective.displayLabel, labelModifier, fontSize = if (structured) 14.sp else 13.sp,
                                fontWeight = FontWeight.SemiBold)
                            if (effective.required) Text(" *", color = Palette.ErrorRed, fontSize = 12.sp)
                            // requiredFrom はフェーズ到達で必須になる項目（無条件必須の * とは区別して注記する）
                            effective.requiredFrom?.let {
                                Text(" *$it〜", color = Palette.WarnOrange, fontSize = 10.sp)
                            }
                            helpButton()
                            if (effective.type != FieldType.ARRAY && !ctx.readOnly) {
                                IconButton(
                                    onClick = { ctx.state.requestAi(ctx.doc, effective, path, relatedFiles = ctx.aiRelatedFiles) },
                                    modifier = Modifier.size(22.dp),
                                ) {
                                    Icon(
                                        Icons.Default.AutoAwesome,
                                        contentDescription = "${effective.displayLabel}をAIで作成",
                                        modifier = Modifier.size(13.dp),
                                        tint = Palette.Primary,
                                    )
                                }
                            }
                        }
                    }
                    if (!ctx.readOnly) {
                        // 保存時に自動で入る値（auto）: 手で直せるが、保存のときに機械が入れることを知らせる
                        effective.auto?.let { Text(autoValueHint(it), fontSize = 10.sp, color = Palette.SubText) }
                    }
                }
            },
        ) {
            // 検証指摘のあるフィールドは入力枠を色づけする（UX-14: エラー=赤・警告=オレンジ）
            val issueColor = when (ctx.issueAt(path)) {
                Severity.ERROR -> Palette.ErrorRed
                Severity.WARNING -> Palette.WarnOrange
                else -> null
            }
            Box(
                Modifier.fillMaxWidth().then(
                    if (issueColor != null) Modifier.border(1.dp, issueColor, PanelShape) else Modifier
                )
            ) { ValueEditor(ctx, effective, slot, path) }
        }
    }
}

/** 型に応じたエディタの振り分け。 */
@Composable
fun ValueEditor(ctx: FormCtx, field: FieldDef, slot: Slot, path: YPath) {
    val effective = ctx.doc.schema?.resolve(field) ?: field
    // 型変更後の残留値（UX-70）: スキーマの型と現在値の構造が合わないと各エディタが編集不能になる
    // （例 string→array 変更後の文字列値）。ここで検知して「消す／変換」つきの修復表示へ切り替える
    val node = slot.get()
    if (node != null && structureMismatch(effective, node)) {
        TypeMismatchRepair(ctx, effective, slot, path, node)
        return
    }
    // 複数行の文字列は、型から引き継いだヒント（display: multiline。組込みの型 text）で判定する。基底の種類の分岐は増やさない
    if (effective.multiline) {
        ScalarTextEditor(ctx, effective, slot, singleLine = false)
        return
    }
    when (effective.type) {
        FieldType.STRING -> ScalarTextEditor(ctx, effective, slot, singleLine = true)
        // 意味の基底型（日付・時刻・日時）は正準の形の文字で編集する（案内はヒント→型の既定）。右端のボタンでピッカー
        FieldType.DATE, FieldType.TIME, FieldType.DATETIME ->
            ScalarTextEditor(ctx, effective, slot, singleLine = true, trailing = { TemporalPickerButton(ctx, effective, slot) })
        FieldType.INT, FieldType.NUMBER -> ScalarTextEditor(ctx, effective, slot, singleLine = true)
        // ファイルの場所: 入力欄＋選択＋OS で開く。画像なら下に表示（ui/FileEditor.kt）
        FieldType.FILE -> FileEditor(ctx, effective, slot, path)
        FieldType.BOOLEAN -> BooleanEditor(ctx, slot)
        FieldType.ENUM -> EnumEditor(ctx, effective, slot)
        FieldType.ID -> IdEditor(ctx, effective, slot)
        FieldType.REF -> RefEditor(ctx, effective, slot, path)
        FieldType.ARRAY -> ArrayEditor(ctx, effective, slot, path)
        FieldType.MAP -> MapEditor(ctx, effective, slot, path)
        FieldType.OBJECT -> NestedObjectEditor(ctx, effective, slot, path)
        // any は型を定めないため汎用エディタで編集する（R11-02）
        FieldType.ANY -> GenericEditor(ctx, slot, path, refHint = false)
    }
}

/** スキーマ型と現在値の構造の不一致判定（UX-70）。スカラー型はスカラー以外、array は Seq 以外、object / map は Map 以外。 */
private fun structureMismatch(field: FieldDef, node: YNode): Boolean = when (field.type) {
    FieldType.ANY -> false
    FieldType.ARRAY -> node !is YNode.Seq
    FieldType.OBJECT, FieldType.MAP -> node !is YNode.Map
    // 属性付き参照（fields 宣言あり）は {to, 属性} の Map も正しい形（R20-03）
    FieldType.REF -> !(node is YNode.Scalar || (field.fields.isNotEmpty() && node is YNode.Map))
    else -> node !is YNode.Scalar
}

/**
 * 型変更後の残留値の修復UI（UX-70）: 現在値を汎用エディタで見せたまま、
 * 「値を消す」と、可能な組だけ「変換して引き継ぐ」を提示する。
 */
@Composable
private fun TypeMismatchRepair(ctx: FormCtx, field: FieldDef, slot: Slot, path: YPath, node: YNode) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "スキーマの型（${field.type.id}）と現在の値の形が一致しません（型変更後の残留値）。値を消すか、変換して引き継いでください。",
            fontSize = 11.sp, color = Palette.WarnOrange,
        )
        if (!ctx.readOnly) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { slot.remove(); ctx.markEdited() }) {
                    Text("値を消す", fontSize = 11.sp, color = Palette.ErrorRed)
                }
                if (field.type == FieldType.ARRAY && node is YNode.Scalar && node.value?.toString()?.isNotBlank() == true) {
                    TextButton(onClick = {
                        slot.set(YNode.Seq(mutableListOf<YNode>(node)))
                        ctx.markEdited()
                    }) { Text("配列に変換（現在値を1要素に）", fontSize = 11.sp) }
                }
                if (field.type !in setOf(FieldType.ARRAY, FieldType.OBJECT, FieldType.MAP) && node is YNode.Seq) {
                    val scalars = node.items.mapNotNull { (it as? YNode.Scalar)?.value?.toString() }
                    if (scalars.isNotEmpty() && scalars.size == node.items.size) {
                        TextButton(onClick = { ctx.setScalar(slot, scalars.first(), field.type) }) {
                            Text(
                                if (scalars.size == 1) "スカラーに変換（要素の値を引き継ぐ）"
                                else "スカラーに変換（先頭の値だけ引き継ぐ）",
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
        // 残留値の中身は汎用エディタで確認・部分編集できる（閲覧専用モードは表示のみ）
        GenericEditor(ctx, slot, path, refHint = false)
    }
}

/** 閲覧専用モードの値表示（R10-04）。未設定は淡色で示す。 */
@Composable
private fun ReadOnlyValueText(text: String?, modifier: Modifier = Modifier) {
    val blank = text.isNullOrBlank()
    Text(
        if (blank) "（未設定）" else text!!,
        fontSize = 13.sp,
        color = if (blank) Palette.SubText else Palette.Text,
        modifier = modifier.padding(top = 2.dp, bottom = 2.dp),
    )
}

@Composable
private fun ScalarTextEditor(
    ctx: FormCtx, field: FieldDef, slot: Slot, singleLine: Boolean,
    trailing: (@Composable () -> Unit)? = null,   // 入力欄の右端（日付のピッカーなど。閲覧専用では出ない）
) {
    val node = slot.get()
    val text = (node as? YNode.Scalar)?.value?.toString() ?: ""
    // string は自由な文字列: 欄は 1 行で始まり、改行が入れば縦に伸びる（複数行を保証するのは text＝display: multiline）
    val oneLine = singleLine && '\n' !in text
    // 複数行の欄の高さ: text の rows（行数）があればその分、無ければ 3 行ほど
    val multilineMinHeight = (field.rows?.let { it * 20 + 16 } ?: 72).dp
    // 表示の形（format。¥#,##0 や yyyy年M月d日）と桁区切り（display: grouped）: 見せ方だけの宣言。編集中は素の値で、
    // 数は打ち込んだ桁区切りを値から落とす（ValueFormat＝値と表示形式の分離）
    val numeric = field.type == FieldType.INT || field.type == FieldType.NUMBER
    val numberDisplay = field.hints.numberDisplay?.takeIf { numeric }
    val formatted: ((String) -> String)? = if (field.format != null || numberDisplay != null) { s -> ValueFormat.display(field, s) } else null
    if (ctx.readOnly) {
        ReadOnlyValueText(formatted?.invoke(text) ?: text)
        return
    }
    val typeError = when (field.type) {
        FieldType.INT -> text.isNotBlank() && text.toLongOrNull() == null
        FieldType.NUMBER -> text.isNotBlank() && text.toDoubleOrNull() == null
        else -> false
    }
    CompactTextField(
        clearFocusOnEscape = true,
        value = text,
        onValueChange = { ctx.setScalar(slot, if (numeric && formatted != null) it.replace(",", "") else it, field.type) },
        singleLine = oneLine,
        isError = typeError,
        textStyle = fieldTextStyle,
        unfocusedText = formatted,
        trailingIcon = trailing,
        placeholder = {
            // 入力欄の案内は型から引き継いだヒント（例 組込みの型 date の YYYY-MM-DD）。無ければ数の種類だけ案内する
            val hint = field.placeholder ?: when (field.type) {
                FieldType.INT -> "整数"
                FieldType.NUMBER -> "数値"
                else -> ""
            }
            if (hint.isNotEmpty()) Text(hint)
        },
        modifier = if (oneLine) Modifier.fillMaxWidth()
        else Modifier.fillMaxWidth().heightIn(min = multilineMinHeight),
    )
}

@Composable
private fun BooleanEditor(ctx: FormCtx, slot: Slot) {
    val node = slot.get()
    val value = (node as? YNode.Scalar)?.value as? Boolean
    if (ctx.readOnly) {
        ReadOnlyValueText(value?.let { if (it) "はい" else "いいえ" })
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = value == true,
            onCheckedChange = {
                slot.set(YNode.Scalar(it))
                ctx.markEdited()
            },
            modifier = Modifier.size(32.dp),
        )
        if (value == null) Text("（未設定）", fontSize = 12.sp, color = Palette.SubText)
    }
}

/**
 * enum の選択エディタ。リッチ選択肢（R10-03: {value, label, description}）に対応し、
 * フォームは label を表示、データ・検証は常に value を使う。
 */
@Composable
private fun EnumEditor(ctx: FormCtx, field: FieldDef, slot: Slot) {
    val current = (slot.get() as? YNode.Scalar)?.value?.toString()
    val currentOption = field.optionFor(current)
    // 説明はボタンの右に1行（省略）で出し、縦に表示領域を広げない（UX-76。全文は選択肢メニューで読める）
    if (ctx.readOnly) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ReadOnlyValueText(currentOption?.displayLabel ?: current)
            currentOption?.description?.let {
                Text(it, fontSize = 10.sp, color = Palette.SubText, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
            }
        }
        return
    }
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            OutlinedButton(shape = PanelShape, onClick = { open = true },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                Text(currentOption?.displayLabel ?: current ?: "（未設定）", fontSize = 13.sp)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                field.enumOptions().forEach { option ->
                    val selected = option.value == current
                    ChoiceMenuItem(selected = selected, text = {
                        Column {
                            Text(
                                option.displayLabel, fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) Palette.Primary else Palette.Text,
                            )
                            // 表示ラベルと保存される値が異なる場合は値も添える（データの正体を隠さない）
                            if (option.label != null && option.label != option.value) {
                                Text(option.value, fontSize = 10.sp, color = Palette.SubText)
                            }
                            option.description?.let { MarkdownView(it, compact = true) }
                        }
                    }, onClick = {
                        slot.set(YNode.Scalar(option.value))
                        ctx.markEdited()
                        open = false
                    })
                }
                ChoiceMenuItem(selected = current == null,
                    text = { Text("（未設定にする）", fontSize = 13.sp, color = Palette.SubText) }, onClick = {
                    slot.remove()
                    ctx.markEdited()
                    open = false
                })
            }
        }
        // 現在選択値の説明（選択肢を開かなくても意味が分かるようにする）
        currentOption?.description?.let {
            Text(it, fontSize = 10.sp, color = Palette.SubText, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false))
        }
    }
}

/**
 * ID入力エディタ。既存IDの補完ピッカー（UX-06。写像行・別文書での詳細化の入力向け）と
 * 次の連番の提案（UX-64）を持つ。候補・採番の形式の源は field の pattern（UX-79）＞
 * entity 宣言の idPattern（R20-01）＝検証と同じ宣言を読む。
 * ID 欄は定義位置そのものなので、ジャンプの行き先は横ぐし（エンティティページ）だけに絞り、
 * 右端の Hub ボタンと「出現／被参照」の注記から 1 クリックで直行する（閲覧専用でも残す）。
 */
@Composable
private fun IdEditor(ctx: FormCtx, field: FieldDef?, slot: Slot) {
    val text = (slot.get() as? YNode.Scalar)?.value?.toString() ?: ""
    val otherOccurrences = if (text.isBlank()) 0 else
        ctx.state.overlayIndex().entries[text].orEmpty().count { it.document != ctx.state.documentOf(ctx.doc.file) }
    // 横ぐしへ飛べるのは、上書き索引（未保存の編集を含む）に出現がある ID だけ（エンティティページの表示条件と同じ）
    val inIndex = text.isNotBlank() && ctx.state.overlayIndex().occurrences(text).isNotEmpty()
    Column {
        if (ctx.readOnly) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ReadOnlyValueText(text, modifier = Modifier.weight(1f, fill = false))
                if (inIndex) {
                    EntityPageButton(ctx, text, iconButtonSize = 28.dp, iconSize = 16.dp)
                }
            }
        } else {
            var menuOpen by remember { mutableStateOf(false) }
            val schemaDef = ctx.doc.schema
            val entityGlobs = schemaDef?.entities?.mapNotNull { it.idPattern }.orEmpty()
            val patternRegex = field?.pattern?.let { p -> runCatching { Regex(p) }.getOrNull() }
            val candidates: List<IdEntry> = remember(ctx.state.indexVersion, ctx.state.editVersion, text, field?.pattern) {
                ctx.state.overlayIndex().entries.values.asSequence()
                    .mapNotNull { it.firstOrNull() }
                    .filter { e ->
                        when {
                            patternRegex != null -> patternRegex.matches(e.id)
                            entityGlobs.isNotEmpty() -> entityGlobs.any { idMatchesPattern(e.id, it) }
                            else -> true
                        }
                    }
                    .filter { text.isBlank() || it.id.contains(text, true) || (it.title?.contains(text, true) == true) }
                    .sortedBy { it.id }
                    .take(50)
                    .toList()
            }
            // 次の連番（UX-64）: 系列は現在文書のIDを優先し、提案が既存IDと衝突する場合は出さない
            val suggestion = remember(ctx.state.indexVersion, ctx.state.editVersion, field?.pattern) {
                IdSuggest.next(
                    existing = ctx.state.overlayIndex().entries.keys,
                    pattern = field?.pattern,
                    idPatternGlobs = entityGlobs,
                    preferred = ctx.localIds.map { it.id },
                )?.takeIf { it !in ctx.state.overlayIndex().entries }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                CompactTextField(
                    clearFocusOnEscape = true,
                    value = text,
                    onValueChange = { ctx.setScalar(slot, it, FieldType.ID) },
                    onDismissPopup = if (menuOpen) ({ menuOpen = false }) else null,
                    singleLine = true,
                    textStyle = fieldTextStyle,
                    trailingIcon = {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.ArrowDropDown, contentDescription = "候補を表示", modifier = Modifier.size(18.dp))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    properties = PopupProperties(focusable = false),
                ) {
                    suggestion?.let { s ->
                        DropdownMenuItem(
                            text = { Text("採番: $s（次の連番）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                            onClick = { ctx.setScalar(slot, s, FieldType.ID); menuOpen = false },
                        )
                    }
                    if (candidates.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text("既存ID（選ぶと同一IDの出現＝詳細化・写像行になる）", fontSize = 10.sp, color = Palette.SubText) },
                            onClick = {}, enabled = false,
                        )
                        candidates.forEach { e ->
                            ChoiceMenuItem(selected = e.id == text, text = {
                                Column {
                                    Text(e.id, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        listOfNotNull(e.title, e.document.value).joinToString(" — "),
                                        fontSize = 11.sp, color = Palette.SubText,
                                    )
                                }
                            }, onClick = { ctx.setScalar(slot, e.id, FieldType.ID); menuOpen = false })
                        }
                    }
                    if (suggestion == null && candidates.isEmpty()) {
                        DropdownMenuItem(text = { Text("候補なし", fontSize = 12.sp, color = Palette.SubText) },
                            onClick = { menuOpen = false }, enabled = false)
                    }
                }
            }
            EntityPageButton(ctx, text, enabled = inIndex, iconButtonSize = 38.dp)
            }
        }
        // 注記（他文書での出現・被参照）は横ぐしへのリンクを兼ねる（出現一覧と関係パネルは同じページにある）
        val note = when {
            otherOccurrences > 0 -> "同一論理IDの別文書での出現: $otherOccurrences 件"
            text.isNotBlank() -> ctx.state.overlayIndex().backlinksTo(setOf(text)).size
                .takeIf { it > 0 }?.let { "被参照: $it 箇所" }
            else -> null
        }
        if (note != null) {
            Text(
                if (inIndex) "$note（横ぐしで見る）" else note,
                fontSize = 11.sp,
                color = if (inIndex) Palette.Primary else Palette.SubText,
                modifier = if (inIndex) Modifier.clickable { ctx.state.openCrossView(text) } else Modifier,
            )
        }
    }
}

/**
 * 横ぐし（エンティティページ）への直行ボタン。ビューグリッドの ID セルと同じ Hub アイコンで、
 * 押すと `openCrossView(id)`（移動として履歴に積まれる）。ID 欄と閲覧専用のテーブルセルで共用する。
 */
@Composable
private fun EntityPageButton(
    ctx: FormCtx,
    id: String,
    enabled: Boolean = true,
    iconButtonSize: Dp = 38.dp,
    iconSize: Dp = 20.dp,
) {
    IconButton(enabled = enabled, onClick = { ctx.state.openCrossView(id) }, modifier = Modifier.size(iconButtonSize)) {
        Icon(
            Icons.Default.Hub,
            contentDescription = "横ぐしビューで表示",
            tint = if (enabled) Palette.Primary else Palette.Border,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
fun RefEditor(ctx: FormCtx, field: FieldDef, slot: Slot, path: YPath = emptyList()) {
    // 属性付き参照（R20-03。architecture/graph-query.md「エンティティ型と関係レジストリ」）: fields 宣言のある ref は
    // 参照行（to）＋属性行（ObjectFields 再利用）で編集する。スカラー形式のままでも編集できる。
    if (field.fields.isNotEmpty()) {
        val node = slot.get()
        val mapNode = node as? YNode.Map
        Column(Modifier.fillMaxWidth()) {
            RefScalarEditor(ctx, field, if (mapNode != null) MapSlot(mapNode, "to") else slot)
            if (mapNode != null) {
                ObjectFields(ctx, field.fields, mapNode, path)
            } else if (!ctx.readOnly) {
                TextButton(onClick = {
                    val current = (node as? YNode.Scalar)?.value?.toString().orEmpty()
                    slot.set(YNode.Map(linkedMapOf<String, YNode>("to" to YNode.Scalar(current))))
                    ctx.markEdited()
                }) {
                    Icon(Icons.Default.Add, contentDescription = null, Modifier.size(14.dp))
                    Text("属性を追加（{to, 属性} 形式へ）", fontSize = 11.sp)
                }
            }
        }
        return
    }
    RefScalarEditor(ctx, field, slot)
}

/**
 * 参照値（スカラー）1つ分のエディタ本体。属性付き参照では to 値の Slot を受け取る（R20-03）。
 * titleInField=false は参照先の名前を欄の中に出さない（テーブルの参照セルが欄の下に出す）。
 */
@Composable
private fun RefScalarEditor(ctx: FormCtx, field: FieldDef, slot: Slot, titleInField: Boolean = true) {
    if (field.refTarget == RefTarget.DOCUMENT) {
        DocRefScalarEditor(ctx, slot)
        return
    }
    val current = (slot.get() as? YNode.Scalar)?.value?.toString() ?: ""
    var menuOpen by remember { mutableStateOf(false) }
    val refConstraint = ctx.refConstraint(field, current)
    val resolved = ctx.resolvable(refConstraint)
    if (ctx.readOnly) {
        // 閲覧専用モードでもジャンプは閲覧行為として残す（R10-04）
        Row(verticalAlignment = Alignment.CenterVertically) {
            val target = ctx.state.overlayIndex().occurrences(current).firstOrNull()
            ReadOnlyValueText(
                listOfNotNull(current.takeIf { it.isNotBlank() }, target?.title).joinToString(" — ").takeIf { it.isNotBlank() },
                modifier = Modifier.weight(1f, fill = false),
            )
            if (resolved) {
                RefJumpButton(ctx, current, iconButtonSize = 28.dp, iconSize = 16.dp)
            }
        }
        if (current.isNotBlank() && !resolved) {
            Text("未解決の参照です", fontSize = 11.sp, color = Palette.ErrorRed)
        }
        return
    }

    // 候補: ID パターンとスコープで絞り込み、同一論理 ID は1候補にまとめる。
    val candidates: List<IdEntry> = remember(ctx.state.indexVersion, field.idPatterns, field.scope) {
        ctx.state.overlayIndex().entries.values.asSequence()
            .filter { occurrences -> occurrences.any { it.isAllowedBy(refConstraint) } }
            .mapNotNull { occurrences -> occurrences.firstOrNull { it.isAllowedBy(refConstraint) } }
            .sortedBy { it.id }
            .toList()
    }

    // 解決先の名前（表示解決は IdEntry.title に一元化済み・R10-01。解決は FormCtx.refTargetTitle に一元化）
    val targetTitle = if (titleInField) ctx.refTargetTitle(field, current) else null
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 解決済みの参照先タイトルは入力欄の枠内（右端）に併記する（ID＋表示名を1つの領域で見せる。UX-31）
        Box(Modifier.weight(1f)) {
            CompactTextField(
                clearFocusOnEscape = true,
                value = current,
                onValueChange = { ctx.setScalar(slot, it, FieldType.REF) },
                onDismissPopup = if (menuOpen) ({ menuOpen = false }) else null,
                singleLine = true,
                isError = current.isNotBlank() && !resolved,
                textStyle = fieldTextStyle,
                placeholder = { Text("参照先 ID") },
                suffix = targetTitle?.let {
                    {
                        Text(
                            it,
                            fontSize = 12.sp,
                            color = Palette.SubText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 260.dp).padding(start = 8.dp),
                        )
                    }
                },
                trailingIcon = {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "候補を表示", modifier = Modifier.size(18.dp))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                properties = PopupProperties(focusable = false),
            ) {
                if (candidates.isEmpty()) {
                    DropdownMenuItem(text = { Text("候補なし", fontSize = 12.sp, color = Palette.SubText) },
                        onClick = { menuOpen = false }, enabled = false)
                }
                candidates.forEach { e ->
                    val selected = e.id == current
                    ChoiceMenuItem(selected = selected, text = {
                        Column {
                            Text(
                                e.id,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                color = if (selected) Palette.Primary else Palette.Text,
                            )
                            Text(
                                listOfNotNull(e.title, e.document.value).joinToString(" — "),
                                fontSize = 11.sp, color = Palette.SubText,
                            )
                        }
                    }, onClick = {
                        ctx.setScalar(slot, e.id, FieldType.REF)
                        menuOpen = false
                    })
                }
            }
        }
        RefJumpButton(ctx, current, enabled = resolved, iconButtonSize = 38.dp)
    }
    if (current.isNotBlank() && !resolved) {
        Text("未解決の参照です", fontSize = 11.sp, color = Palette.ErrorRed)
    }
}

/**
 * 参照ジャンプボタン（UX-75）: クリックで行き先（定義ファイル／エンティティページ）を選ぶメニューを出す。
 * 検証指摘・計算セルの来歴と同じ「行き先の選択」流儀（DropdownMenu。architecture/ui.md「UI シェル」 整合）。
 */
@Composable
private fun RefJumpButton(
    ctx: FormCtx,
    id: String,
    enabled: Boolean = true,
    iconButtonSize: Dp = 38.dp,
    iconSize: Dp = 24.dp,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(enabled = enabled, onClick = { open = true }, modifier = Modifier.size(iconButtonSize)) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "参照先へ移動",
                tint = if (enabled) Palette.Primary else Palette.Border,
                modifier = Modifier.size(iconSize),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("定義へ移動（ファイル）", fontSize = 12.sp) },
                onClick = { open = false; ctx.state.navigateToId(id) },
            )
            DropdownMenuItem(
                text = { Text("エンティティページで開く（全出現・関係）", fontSize = 12.sp) },
                onClick = { open = false; ctx.state.openCrossView(id) },
            )
        }
    }
}

/**
 * 文書参照（target: document。R21-03。architecture/graph-query.md「エンティティ操作」）のエディタ。
 * 候補・解決は文書ノード表（documentId ＝ `_document.id` ＞ 相対パス）に対して行い、
 * エンティティの名前空間には混ぜない。ジャンプは参照先文書を開く。
 */
@Composable
private fun DocRefScalarEditor(ctx: FormCtx, slot: Slot) {
    val current = (slot.get() as? YNode.Scalar)?.value?.toString() ?: ""
    var menuOpen by remember { mutableStateOf(false) }
    val documents = remember(ctx.state.indexVersion, ctx.state.editVersion) {
        ctx.state.overlayIndex().graph.documents
    }
    val selfDocId = remember(ctx.doc, ctx.state.indexVersion) {
        ctx.doc.root?.let { ctx.state.overlayIndex().documentIdFor(ctx.state.documentOf(ctx.doc.file), it) }
    }
    val resolved = current.isNotBlank() && (documents.containsKey(current) || current == selfDocId)
    // 文書名（_document.name）は表示専用の注記
    val loader = ctx.state.unsavedFirstTree()
    val targetName = documents[current]?.let { node -> loader(node.document)?.let { IdIndex.documentNameOf(it) } }
    fun open(documentId: String) {
        documents[documentId]?.let { ctx.state.openFile(it.document) }
    }
    if (ctx.readOnly) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ReadOnlyValueText(
                listOfNotNull(current.takeIf { it.isNotBlank() }, targetName).joinToString(" — ").takeIf { it.isNotBlank() },
                modifier = Modifier.weight(1f, fill = false),
            )
            if (resolved && documents.containsKey(current)) {
                IconButton(onClick = { open(current) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "文書を開く",
                        tint = Palette.Primary, modifier = Modifier.size(16.dp))
                }
            }
        }
        if (current.isNotBlank() && !resolved) {
            Text("未解決の文書参照です", fontSize = 11.sp, color = Palette.ErrorRed)
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            CompactTextField(
                clearFocusOnEscape = true,
                value = current,
                onValueChange = { ctx.setScalar(slot, it, FieldType.REF) },
                onDismissPopup = if (menuOpen) ({ menuOpen = false }) else null,
                singleLine = true,
                isError = current.isNotBlank() && !resolved,
                textStyle = fieldTextStyle,
                placeholder = { Text("参照先の文書（_document.id または相対パス）") },
                suffix = targetName?.let {
                    {
                        Text(
                            it, fontSize = 12.sp, color = Palette.SubText, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 260.dp).padding(start = 8.dp),
                        )
                    }
                },
                trailingIcon = {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "候補を表示", modifier = Modifier.size(18.dp))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                properties = PopupProperties(focusable = false),
            ) {
                if (documents.isEmpty()) {
                    DropdownMenuItem(text = { Text("候補なし", fontSize = 12.sp, color = Palette.SubText) },
                        onClick = { menuOpen = false }, enabled = false)
                }
                documents.values.sortedBy { it.documentId }.forEach { node ->
                    val selected = node.documentId == current
                    ChoiceMenuItem(selected = selected, text = {
                        Column {
                            Text(
                                node.documentId,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                color = if (selected) Palette.Primary else Palette.Text,
                            )
                            val name = loader(node.document)?.let { IdIndex.documentNameOf(it) }
                            Text(
                                listOfNotNull(name, node.document.value).joinToString(" — "),
                                fontSize = 11.sp, color = Palette.SubText,
                            )
                        }
                    }, onClick = {
                        ctx.setScalar(slot, node.documentId, FieldType.REF)
                        menuOpen = false
                    })
                }
            }
        }
        IconButton(
            enabled = resolved && documents.containsKey(current),
            onClick = { open(current) },
            modifier = Modifier.size(38.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "文書を開く",
                tint = if (resolved) Palette.Primary else Palette.Border,
            )
        }
    }
    if (current.isNotBlank() && !resolved) {
        Text("未解決の文書参照です", fontSize = 11.sp, color = Palette.ErrorRed)
    }
}

@Composable
private fun ArrayEditor(ctx: FormCtx, field: FieldDef, slot: Slot, path: YPath) {
    val node = slot.get()
    // 既定は浅い階層のみ展開し、深い階層は畳んで要約を見せる（編集・閲覧共通）。
    // 開閉は画面の覚え（ScreenMemory）が文書とパスで持ち、モード切替や画面の行き来でも保つ。ジャンプ先が中なら開く（UX-10）
    val expanded = ctx.expanded(path)
    if (node == null) {
        if (ctx.readOnly) {
            ReadOnlyValueText(null)
            return
        }
        TextButton(onClick = {
            // 要素の作り方は型の create と配列の insert の決まり（architecture/actions.md）。受け取る値があればカードが開く
            ctx.state.addArrayElement(ctx.doc, path, field)
                .onSuccess { ctx.setExpanded(path, true) }
                .onFailure { ctx.state.status = "追加できません: ${it.message}" }
        }) {
            Icon(Icons.Default.Add, contentDescription = null, Modifier.size(16.dp))
            Text("配列を作成して要素を追加", fontSize = 13.sp)
        }
        return
    }
    if (node !is YNode.Seq) {
        Text("（配列ではない値が設定されています。汎用表示）", fontSize = 11.sp, color = Palette.WarnOrange)
        GenericEditor(ctx, slot, path, refHint = false)
        return
    }
    // 畳み込みサマリ（R10-02）: 表示解決による「n件: 先頭要素の要約, …」。タイトルは索引から借用（UX-71）
    val header = Display.seqHeaderText(node, ctx.doc.schema, field.item, titleLookup = ctx.indexTitleLookup())
    // テーブル表示（R12-06）: 列はスキーマの適否判定（自動＋display ヒント）で決まり、
    // 実データが全て map のときだけ表にする（型がずれた要素は縦リストの汎用表示で扱う）
    val columns = Display.tableColumns(ctx.doc.schema, field)
    val tabular = columns != null && node.items.isNotEmpty() && node.items.all { it is YNode.Map }
    FormGroup(nested = path.isNotEmpty()) {
        FormFoldHeader(header, expanded) { ctx.setExpanded(path, !expanded) }
        if (expanded) {
        if (tabular) {
            ArrayTable(ctx, field, columns.orEmpty(), node, path)
        } else {
        // 取っ手（⋮⋮）からのドラッグで並べ替え。ドロップで 1 回だけ木を動かす（Undo 1 回分）。上下ボタンも残す
        ReorderableList(items = node.items, onMove = { from, to -> moveSeqItem(node, from, to); ctx.markEdited() }) { i, _, dragging ->
            Highlightable(ctx.state, path + i) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        .background(if (dragging) Palette.Selected else Color.Transparent, PanelShape),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (!ctx.readOnly) Handle(Modifier.padding(top = 12.dp, end = 2.dp))
                    Text(
                        "${i + 1}.",
                        fontSize = 12.sp, color = Palette.SubText,
                        modifier = Modifier.padding(top = if (ctx.readOnly) 2.dp else 12.dp, end = 6.dp),
                    )
                    Box(Modifier.weight(1f)) {
                        ValueEditor(ctx, field.item ?: FieldDef(), SeqSlot(node, i), path + i)
                    }
                    if (!ctx.readOnly) {
                    Column {
                        IconButton(
                            onClick = {
                                if (i > 0) {
                                    val tmp = node.items[i - 1]; node.items[i - 1] = node.items[i]; node.items[i] = tmp
                                    ctx.markEdited()
                                }
                            },
                            enabled = i > 0,
                            modifier = Modifier.size(24.dp),
                        ) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上へ", Modifier.size(16.dp)) }
                        IconButton(
                            onClick = {
                                if (i < node.items.size - 1) {
                                    val tmp = node.items[i + 1]; node.items[i + 1] = node.items[i]; node.items[i] = tmp
                                    ctx.markEdited()
                                }
                            },
                            enabled = i < node.items.size - 1,
                            modifier = Modifier.size(24.dp),
                        ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下へ", Modifier.size(16.dp)) }
                    }
                    IconButton(
                        onClick = { node.items.removeAt(i); ctx.markEdited() },
                        modifier = Modifier.size(32.dp).padding(top = 8.dp),
                    ) { Icon(Icons.Default.Delete, contentDescription = "削除", Modifier.size(16.dp), tint = Palette.SubText) }
                    }
                    // 操作メニューは閲覧専用でも出す（操作は編集ではない。architecture/actions.md）
                    ElementOpsButton(ctx, path + i, Modifier.padding(top = if (ctx.readOnly) 0.dp else 8.dp))
                }
            }
        }
        }
        if (!ctx.readOnly) {
        TextButton(onClick = {
            // 要素の作り方は型の create と配列の insert の決まり（採番つき。architecture/actions.md）。受け取る値があればカードが開く
            ctx.state.addArrayElement(ctx.doc, path, field).onFailure { ctx.state.status = "追加できません: ${it.message}" }
        }) {
            Icon(Icons.Default.Add, contentDescription = null, Modifier.size(16.dp))
            Text("追加", fontSize = 13.sp)
        }
        TextButton(onClick = { ctx.state.requestAi(ctx.doc, field, path, relatedFiles = ctx.aiRelatedFiles) }) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text("AIで候補追加", fontSize = 12.sp)
        }
        }
        }
    }
}

// ================================================================ テーブル表示（R12-06: 配列・map）

/** 列の型に応じたセルの最小幅（余白があれば tableColumnWidths が広げ、収まらなければ横スクロール）。列は `$ref` 解決済み。 */
private fun tableCellWidth(column: FieldDef): Dp = when {
    column.multiline -> 240.dp                 // 複数行の文字列（ヒント display: multiline。組込みの型 text）
    else -> when (column.type) {
        FieldType.BOOLEAN -> 90.dp
        FieldType.INT, FieldType.NUMBER -> 90.dp
        FieldType.DATE -> 140.dp                 // ピッカーのボタン分を含む
        FieldType.TIME -> 120.dp
        FieldType.DATETIME -> 190.dp
        FieldType.FILE -> 240.dp                 // 選択と OS で開くの 2 つのボタン分を含む
        FieldType.ENUM -> 140.dp
        FieldType.ID -> 180.dp                 // 候補の▼と横ぐしの Hub の 2 つのボタン分を含む
        FieldType.REF -> 240.dp                // 候補の▼・→・横ぐしの Hub の 3 つのボタン分を含む（名前は欄の下）
        else -> 170.dp
    }
}

private val TABLE_EXPAND_WIDTH = 28.dp
private val TABLE_OPS_WIDTH = 120.dp       // 配列の行操作（取っ手・上へ・下へ・削除・操作メニュー）
private val TABLE_MENU_WIDTH = 28.dp       // 閲覧専用のときの行の操作メニュー（⋯）だけの列

/** 配列の要素を from から to へ動かす（ドラッグの並べ替え。木の変異だけで、markEdited は呼び出し側）。 */
internal fun moveSeqItem(node: YNode.Seq, from: Int, to: Int) {
    if (from == to || from !in node.items.indices || to !in node.items.indices) return
    val item = node.items.removeAt(from)
    node.items.add(to, item)
}
private val TABLE_KEY_OPS_WIDTH = 28.dp    // map の行操作（キー削除のみ）
private val TABLE_KEY_WIDTH = 150.dp       // map のキー列

/** 余白の配分先になる列（文字系）。数値・真偽・日付は固定幅のままにする。 */
private val FLEX_CELL_TYPES = setOf(
    FieldType.STRING, FieldType.ENUM, FieldType.ID, FieldType.REF,
)

/**
 * 列幅の割付（R12-06）: 型別の最小幅を保証しつつ、利用可能幅に余りがあれば文字系の列で
 * 等分して表を右端まで使う。文字系の列がなければ伸ばさない（数値だけの表を間延びさせない）。
 * 利用可能幅に収まらない場合は最小幅のまま＝横スクロールに委ねる。列は `$ref` 解決済みの項目。
 */
internal fun tableColumnWidths(columns: List<FieldDef>, available: Dp, fixed: Dp): List<Dp> {
    val base = columns.map { tableCellWidth(it) }
    val natural = fixed + base.fold(0.dp) { acc, w -> acc + w }
    val flexCount = columns.count { it.type in FLEX_CELL_TYPES }
    if (available <= natural || flexCount == 0) return base
    val extra = (available - natural) / flexCount
    return columns.mapIndexed { i, c -> base[i] + if (c.type in FLEX_CELL_TYPES) extra else 0.dp }
}

/**
 * 適格な配列・map（Display.tableColumns が列を返したもの）の表形式表示の本体。
 * 行は「YPath セグメント（配列=添字、map=キー）× object 要素」。セルはスカラー列のみ
 * （編集は通常の編集経路＝Slot を再利用）。列にならないフィールド（ネスト・enum case の追加分・
 * スキーマ外キー）と長文の編集は、行頭の ▶ で開く展開フォームで扱う。
 */
@Composable
private fun ObjectTable(
    ctx: FormCtx,
    elementDef: FieldDef?,
    columns: List<FieldDef>,
    rows: List<Pair<Any, YNode.Map>>,
    path: YPath,
    keyHeader: String? = null,           // 非 null なら先頭にキー列を出す（map）
    opsWidth: Dp = 0.dp,                 // 編集モードの行操作列の幅（ops とセットで指定）
    ops: (@Composable (Int) -> Unit)? = null,
    onMove: ((from: Int, to: Int) -> Unit)? = null,   // 非 null なら行をドラッグで並べ替えられる（配列）
    rowMenu: (@Composable (Int) -> Unit)? = null,     // 行の操作メニュー（⋯）。閲覧専用でも出す（操作は編集ではない）
) {
    val hScroll = rememberScrollState()
    // 利用可能幅を測り、余白は文字系の列へ配って表を右端まで使う
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val keyWidth = if (keyHeader != null) TABLE_KEY_WIDTH else 0.dp
        val trailing = if (!ctx.readOnly && ops != null) opsWidth else if (rowMenu != null) TABLE_MENU_WIDTH else 0.dp
        val fixed = TABLE_EXPAND_WIDTH + keyWidth + trailing
        val widths = tableColumnWidths(columns, maxWidth, fixed)
        val rowWidth = fixed + widths.fold(0.dp) { acc, w -> acc + w }
        Column(Modifier.fillMaxWidth()) {
            // 列だけを横スクロールし、展開フォームには表示領域の幅を渡す。
            // 見出しと各行は同じ列幅・ScrollState を使い、横位置を揃える。
            Box(Modifier.fillMaxWidth().horizontalScroll(hScroll)) {
                // ヘッダ行（ラベルはスキーマの表示ラベル。必須マーカーはフォームと同じ流儀）
                Row(Modifier.width(rowWidth).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(TABLE_EXPAND_WIDTH))
                    if (keyHeader != null) {
                        Text(
                            keyHeader, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                            color = Palette.SubText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(keyWidth).padding(horizontal = 4.dp),
                        )
                    }
                    columns.forEachIndexed { c, col ->
                        Row(Modifier.width(widths[c]).padding(horizontal = 4.dp)) {
                            Text(
                                col.displayLabel, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                color = Palette.SubText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            if (col.required) Text(" *", color = Palette.ErrorRed, fontSize = 11.sp)
                        }
                    }
                }
            }
            HorizontalDivider(color = Palette.Border)
            // 配列の行は取っ手からのドラッグで並べ替えられる（onMove がある表だけ取っ手を出す。map の表は出さない）
            ReorderableList(items = rows, onMove = { from, to -> onMove?.invoke(from, to) },
                modifier = Modifier.fillMaxWidth(), key = { it.second }) { i, (segment, element), dragging ->
                val rowPath = path + segment
                // 行の展開は画面の覚えが持つ（既定は畳む）。ジャンプ先が行の中（列にならない項目＝展開フォーム側）なら開く（UX-10）
                val rowExpanded = ctx.expanded(rowPath, default = false)
                Highlightable(ctx.state, rowPath) {
                    Column(Modifier.fillMaxWidth().background(if (dragging) Palette.Selected else Color.Transparent)) {
                        Box(Modifier.fillMaxWidth().horizontalScroll(hScroll)) {
                            Row(Modifier.width(rowWidth), verticalAlignment = Alignment.Top) {
                                Box(Modifier.width(TABLE_EXPAND_WIDTH).padding(top = if (ctx.readOnly) 0.dp else 7.dp)) {
                                    IconButton(onClick = { ctx.setExpanded(rowPath, !rowExpanded) }, modifier = Modifier.size(24.dp)) {
                                        Text(if (rowExpanded) "▼" else "▶", fontSize = 10.sp, color = Palette.SubText)
                                    }
                                }
                                if (keyHeader != null) {
                                    // map のキー列は表示のみ（キーの改名・追加は従来の縦リスト表示や汎用ビューの経路で行う）
                                    Box(Modifier.width(keyWidth).padding(horizontal = 4.dp, vertical = if (ctx.readOnly) 2.dp else 8.dp)) {
                                        Text(
                                            segment.toString(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                columns.forEachIndexed { c, col ->
                                    Box(
                                        Modifier.width(widths[c])
                                            .padding(horizontal = 4.dp, vertical = if (ctx.readOnly) 2.dp else 1.dp),
                                    ) { ObjectTableCell(ctx, col, element, rowPath) }
                                }
                                if (!ctx.readOnly && ops != null) {
                                    Row(Modifier.width(opsWidth).padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (onMove != null) Handle(Modifier.padding(end = 2.dp))
                                        ops(i)
                                        rowMenu?.invoke(i)
                                    }
                                } else if (rowMenu != null) {
                                    Box(Modifier.width(TABLE_MENU_WIDTH)) { rowMenu(i) }
                                }
                            }
                        }
                        if (rowExpanded) {
                            Column(Modifier.fillMaxWidth().padding(start = TABLE_EXPAND_WIDTH, top = 2.dp, bottom = 6.dp)) {
                                ObjectFields(ctx, elementDef?.fields.orEmpty(), element, rowPath)
                            }
                        }
                    }
                }
                HorizontalDivider(color = Palette.Border.copy(alpha = .4f))
            }
            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(hScroll),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 配列のテーブル表示。行操作は並べ替え＋削除。 */
@Composable
private fun ArrayTable(ctx: FormCtx, field: FieldDef, columns: List<FieldDef>, node: YNode.Seq, path: YPath) {
    val itemDef = ctx.doc.schema?.resolve(field.item) ?: field.item
    val rows = node.items.mapIndexedNotNull { i, item -> (item as? YNode.Map)?.let { i to it } }
    ObjectTable(ctx, itemDef, columns, rows, path, opsWidth = TABLE_OPS_WIDTH,
        onMove = { from, to -> moveSeqItem(node, from, to); ctx.markEdited() },
        ops = { i ->
        IconButton(
            onClick = {
                if (i > 0) {
                    val tmp = node.items[i - 1]; node.items[i - 1] = node.items[i]; node.items[i] = tmp
                    ctx.markEdited()
                }
            },
            enabled = i > 0,
            modifier = Modifier.size(24.dp),
        ) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上へ", Modifier.size(16.dp)) }
        IconButton(
            onClick = {
                if (i < node.items.size - 1) {
                    val tmp = node.items[i + 1]; node.items[i + 1] = node.items[i]; node.items[i] = tmp
                    ctx.markEdited()
                }
            },
            enabled = i < node.items.size - 1,
            modifier = Modifier.size(24.dp),
        ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下へ", Modifier.size(16.dp)) }
        IconButton(
            onClick = { node.items.removeAt(i); ctx.markEdited() },
            modifier = Modifier.size(24.dp),
        ) { Icon(Icons.Default.Delete, contentDescription = "削除", Modifier.size(14.dp), tint = Palette.SubText) }
    }, rowMenu = { i -> ElementOpsButton(ctx, path + i) })
}

/**
 * 配列の行の操作メニュー（⋯）: 行の要素に使える操作（型の操作と、その型を受け手にするアクション。architecture/actions.md）。
 * 選ぶとその要素を対象にしたパネルが開く。ID の無い要素・操作の無い型には出ない。型は走査した ID の型で見るので、未保存の行にも出る。
 */
@Composable
private fun ElementOpsButton(ctx: FormCtx, rowPath: YPath, modifier: Modifier = Modifier) {
    val entry = ctx.localIds.firstOrNull { it.path == rowPath } ?: return
    val actions = remember(entry.id, entry.entityType, ctx.state.indexVersion) {
        entry.entityType?.let { ctx.state.actionsForEntityType(it) } ?: ctx.state.actionsForEntity(entry.id)
    }
    if (actions.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Default.MoreVert, contentDescription = "操作", Modifier.size(16.dp), tint = Palette.SubText)
        }
        DropdownMenu(open, { open = false }) {
            Text("${entry.id} の操作", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 10.sp, color = Palette.SubText)
            actions.forEach { e ->
                DropdownMenuItem(
                    text = { Text(e.label ?: e.name, fontSize = 12.sp) },
                    onClick = { open = false; ctx.state.startAction(e, entry.id) },
                )
            }
        }
    }
}

/** map（キー→object）のテーブル表示。先頭にキー列を出し、行操作はキー削除のみ（キー追加は表の下の共通行）。 */
@Composable
private fun MapTable(ctx: FormCtx, field: FieldDef, columns: List<FieldDef>, node: YNode.Map, path: YPath) {
    val valueDef = ctx.doc.schema?.resolve(field.value) ?: field.value
    val rows = node.entries.entries.mapNotNull { (k, v) -> (v as? YNode.Map)?.let { k as Any to it } }
    ObjectTable(ctx, valueDef, columns, rows, path, keyHeader = "キー", opsWidth = TABLE_KEY_OPS_WIDTH, ops = { i ->
        IconButton(
            onClick = { node.entries.remove(rows[i].first.toString()); ctx.markEdited() },
            modifier = Modifier.size(24.dp),
        ) { Icon(Icons.Default.Delete, contentDescription = "キーを削除", Modifier.size(14.dp), tint = Palette.SubText) }
    })
}

/**
 * テーブルの1セル。スカラー編集は通常の ValueEditor（Slot 経由＝Undo・差分・readOnly 拒否が同じに効く）。
 * 長文（text）はセル内編集で行高が崩れるため表示のみとし、編集は行の展開フォームで行う。
 */
@Composable
private fun ObjectTableCell(ctx: FormCtx, col: FieldDef, map: YNode.Map, itemPath: YPath) {
    val key = col.key ?: return
    val slot = MapSlot(map, key)
    val value = slot.get()
    // 検証指摘のあるセルはテーブル表示でも枠を色づけする（UX-14。展開フォーム側の FieldRow と同じ規則）
    val issueColor = when (ctx.issueAt(itemPath + key)) {
        Severity.ERROR -> Palette.ErrorRed
        Severity.WARNING -> Palette.WarnOrange
        else -> null
    }
    Box(
        Modifier.fillMaxWidth().then(
            if (issueColor != null) Modifier.border(1.dp, issueColor, PanelShape) else Modifier
        )
    ) {
        when {
            // エンティティ参照のセルは ID・参照先の名前・横ぐしへの Hub（属性付き参照の {to, 属性} も to を見せる）
            col.type == FieldType.REF && col.refTarget != RefTarget.DOCUMENT &&
                (value == null || !structureMismatch(col, value)) -> RefCell(ctx, col, slot)
            // スキーマとずれた複合値はセルで編集せず要約表示に留める（編集は行の展開フォーム）
            value != null && value !is YNode.Scalar -> TableCellText(Display.inline(value, ctx.doc.schema, col))
            // 閲覧専用の ID セルも横ぐしへ直行できる（編集時は IdEditor の Hub ボタンが同じ役を担う）
            ctx.readOnly && col.type == FieldType.ID -> ReadOnlyIdCell(ctx, value as? YNode.Scalar)
            ctx.readOnly || col.multiline -> TableCellText(readOnlyCellText(col, value as? YNode.Scalar))
            // ファイルの場所はセルでは画像を出さない（編集は行の展開フォームで画像つき）
            col.type == FieldType.FILE -> FileEditor(ctx, col, slot, itemPath + key, preview = false)
            else -> ValueEditor(ctx, col, slot, itemPath + key)
        }
    }
}

/** 閲覧セルの表示文字列（null = 未設定）。enum は表示ラベル、boolean は はい／いいえ。 */
private fun readOnlyCellText(col: FieldDef, node: YNode.Scalar?): String? {
    val raw = node?.value ?: return null
    return when (col.type) {
        FieldType.BOOLEAN -> if (raw == true) "はい" else if (raw == false) "いいえ" else raw.toString()
        FieldType.ENUM -> col.optionFor(raw.toString())?.displayLabel ?: raw.toString()
        else -> raw.toString()
    }.replace("\n", " ").ifBlank { null }
}

/** 閲覧専用のテーブルの ID セル。値の右に横ぐしへの Hub ボタンを出す（上書き索引に出現がある ID だけ）。 */
@Composable
private fun ReadOnlyIdCell(ctx: FormCtx, node: YNode.Scalar?) {
    val id = node?.value?.toString().orEmpty()
    val inIndex = id.isNotBlank() && ctx.state.overlayIndex().occurrences(id).isNotEmpty()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f, fill = false)) { TableCellText(id.ifBlank { null }) }
        if (inIndex) {
            EntityPageButton(ctx, id, iconButtonSize = 24.dp, iconSize = 14.dp)
        }
    }
}

/**
 * テーブルの参照セル（エンティティ参照）。上段に ID、下段に参照先の名前（表示解決の結果）、右端に横ぐしへの Hub ボタン
 * （ID セルと同じ印。上書き索引に出現がある ID だけ）。編集モードも通常の参照欄（候補の▼・→ の 2 択メニュー）のまま、
 * 名前だけ欄の中でなく欄の下に出す（欄の中の右端はセル幅で ID を押しのける。ビューグリッドと同じ流儀）。
 * 属性付き参照（{to, 属性}）は to を表示だけにし、編集は行の展開フォームで行う。
 */
@Composable
private fun RefCell(ctx: FormCtx, col: FieldDef, slot: Slot) {
    val node = slot.get()
    val attributed = node is YNode.Map
    val idSlot = if (node is YNode.Map) MapSlot(node, "to") else slot
    val id = (idSlot.get() as? YNode.Scalar)?.value?.toString().orEmpty()
    val title = ctx.refTargetTitle(col, id)
    val inIndex = id.isNotBlank() && ctx.state.overlayIndex().occurrences(id).isNotEmpty()
    val displayOnly = ctx.readOnly || attributed
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (displayOnly) {
                Box(Modifier.weight(1f, fill = false)) { TableCellText(id.ifBlank { null }) }
                if (inIndex) EntityPageButton(ctx, id, iconButtonSize = 24.dp, iconSize = 14.dp)
            } else {
                Box(Modifier.weight(1f)) { RefScalarEditor(ctx, col, idSlot, titleInField = false) }
                EntityPageButton(ctx, id, enabled = inIndex)
            }
        }
        if (title != null) {
            Text(title, fontSize = 10.sp, color = Palette.SubText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else if (displayOnly && id.isNotBlank() && !ctx.resolvable(ctx.refConstraint(col, id))) {
            // 編集モードでは参照欄が同じ注記を出す
            Text("未解決の参照です", fontSize = 11.sp, color = Palette.ErrorRed)
        }
    }
}

@Composable
private fun TableCellText(text: String?) {
    Text(
        text ?: "（未設定）",
        fontSize = 13.sp,
        color = if (text == null) Palette.SubText else Palette.Text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

@Composable
private fun NestedObjectEditor(ctx: FormCtx, field: FieldDef, slot: Slot, path: YPath) {
    val node = slot.get()
    // 既定は浅い階層のみ展開し、深い階層は畳んで要約を見せる（編集・閲覧共通）。
    // 開閉は画面の覚え（ScreenMemory）が文書とパスで持ち、モード切替や画面の行き来でも保つ。ジャンプ先が中なら開く（UX-10）
    val expanded = ctx.expanded(path)
    if (node == null) {
        if (ctx.readOnly) {
            ReadOnlyValueText(null)
            return
        }
        TextButton(onClick = {
            slot.set(defaultNode(field, ctx.doc.schema))
            ctx.setExpanded(path, true)
            ctx.markEdited()
        }) {
            Icon(Icons.Default.Add, contentDescription = null, Modifier.size(16.dp))
            Text("作成", fontSize = 13.sp)
        }
        return
    }
    if (node !is YNode.Map) {
        Text("（オブジェクトではない値が設定されています。汎用表示）", fontSize = 11.sp, color = Palette.WarnOrange)
        GenericEditor(ctx, slot, path, refHint = false)
        return
    }
    // 畳み込みサマリ（R10-02 / UX-71）: 「ID タイトル — サブタイトル（n項目）」。タイトルは索引から借用
    val header = Display.headerText(node, ctx.doc.schema, field, titleLookup = ctx.indexTitleLookup())
    FormGroup(nested = path.isNotEmpty()) {
        FormFoldHeader(header, expanded) { ctx.setExpanded(path, !expanded) }
        if (expanded) ObjectFields(ctx, field.fields, node, path)
    }
}

@Composable
private fun MapEditor(ctx: FormCtx, field: FieldDef, slot: Slot, path: YPath) {
    @Suppress("UNUSED_VARIABLE") val version = ctx.doc.version
    val node = slot.get()
    // 既定は浅い階層のみ展開（編集・閲覧共通）。開閉は画面の覚えが持ち、ジャンプ先が中なら開く（UX-10）
    val expanded = ctx.expanded(path)
    if (node == null) {
        if (ctx.readOnly) {
            ReadOnlyValueText(null)
            return
        }
        TextButton(onClick = { slot.set(YNode.Map()); ctx.setExpanded(path, true); ctx.markEdited() }) {
            Icon(Icons.Default.Add, contentDescription = null, Modifier.size(16.dp))
            Text("Mapを作成", fontSize = 13.sp)
        }
        return
    }
    if (node !is YNode.Map) {
        Text("（Mapではない値が設定されています。汎用表示）", fontSize = 11.sp, color = Palette.WarnOrange)
        GenericEditor(ctx, slot, path, refHint = false)
        return
    }
    // テーブル表示（R12-06）: 適否判定は配列と共有し、値が全て map のときだけキー列つきの表にする
    val columns = Display.tableColumns(ctx.doc.schema, field)
    val tabular = columns != null && node.entries.isNotEmpty() && node.entries.values.all { it is YNode.Map }
    FormGroup(nested = path.isNotEmpty()) {
        FormFoldHeader("${node.entries.size} 件", expanded) { ctx.setExpanded(path, !expanded) }
        if (expanded) {
            if (tabular) {
                MapTable(ctx, field, columns.orEmpty(), node, path)
            } else {
            node.entries.keys.toList().forEach { key ->
                val childPath = path + key
                Highlightable(ctx.state, childPath) {
                    FormFieldLayout(
                        Modifier.padding(vertical = FormSpacing.FieldGap), labelWidth = 150.dp,
                        structured = isStructuredFormValue(
                            field.value?.let { ctx.doc.schema?.resolve(it) ?: it }, node.entries[key],
                        ),
                        label = {
                            Text(key, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = if (ctx.readOnly) 2.dp else 10.dp))
                        },
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Box(Modifier.weight(1f)) {
                                ValueEditor(ctx, field.value ?: FieldDef(), MapSlot(node, key), childPath)
                            }
                            if (!ctx.readOnly) {
                                IconButton(onClick = { node.entries.remove(key); ctx.markEdited() },
                                    modifier = Modifier.size(32.dp).padding(top = 8.dp)) {
                                    Icon(Icons.Default.Delete, contentDescription = "キーを削除", Modifier.size(16.dp), tint = Palette.SubText)
                                }
                            }
                        }
                    }
                }
            }
            }
            if (!ctx.readOnly) AddTypedMapKeyRow(ctx, node, field.value)
        }
    }
}

@Composable
private fun AddTypedMapKeyRow(ctx: FormCtx, map: YNode.Map, valueDef: FieldDef?) {
    var newKey by remember { mutableStateOf("") }
    val trimmed = newKey.trim()
    Row(verticalAlignment = Alignment.CenterVertically) {
        CompactTextField(clearFocusOnEscape = true, value = newKey, onValueChange = { newKey = it }, singleLine = true,
            textStyle = fieldTextStyle, placeholder = { Text("新しいキー") },
            modifier = Modifier.width(200.dp))
        TextButton(enabled = trimmed.isNotEmpty() && trimmed !in map.entries, onClick = {
            map.entries[trimmed] = defaultNode(valueDef, ctx.doc.schema)
            ctx.markEdited()
            newKey = ""
        }) {
            Icon(Icons.Default.Add, contentDescription = null, Modifier.size(16.dp))
            Text("キーを追加", fontSize = 13.sp)
        }
    }
}

// ================================================================ 汎用エディタ（スキーマなし）

/**
 * 任意の YAML 構造を再帰的に編集する汎用エディタ。
 * refHint = true のとき、値が索引上の ID に一致すればリンク（→）を表示する。
 */
@Composable
fun GenericEditor(ctx: FormCtx, slot: Slot, path: YPath, refHint: Boolean) {
    when (val node = slot.get()) {
        null -> {
            if (ctx.readOnly) {
                ReadOnlyValueText(null)
                return
            }
            CompactTextField(
                clearFocusOnEscape = true,
                value = "",
                onValueChange = { ctx.setGenericScalar(slot, it) },
                singleLine = true,
                textStyle = fieldTextStyle,
                placeholder = { Text("（null）") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is YNode.Scalar -> {
            val v = node.value
            if (ctx.readOnly) {
                val text = v?.toString() ?: ""
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ReadOnlyValueText(
                        if (v is Boolean) (if (v) "はい" else "いいえ") else text,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // ジャンプは閲覧行為として残す（R10-04）
                    if (refHint && ctx.resolvable(text)) {
                        IconButton(onClick = { ctx.state.navigateToId(text) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "定義へ移動",
                                tint = Palette.Primary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                return
            }
            if (v is Boolean) {
                Checkbox(
                    checked = v,
                    onCheckedChange = { slot.set(YNode.Scalar(it)); ctx.markEdited() },
                )
            } else {
                val text = v?.toString() ?: ""
                val multiline = text.contains('\n')
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CompactTextField(
                        clearFocusOnEscape = true,
                        value = text,
                        onValueChange = { ctx.setGenericScalar(slot, it) },
                        singleLine = !multiline,
                        textStyle = fieldTextStyle,
                        isError = refHint && text.isNotBlank() && !ctx.resolvable(text),
                        modifier = Modifier.weight(1f),
                    )
                    if (refHint && ctx.resolvable(text)) {
                        IconButton(onClick = { ctx.state.navigateToId(text) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "定義へ移動",
                                tint = Palette.Primary,
                            )
                        }
                    }
                }
            }
        }
        is YNode.Seq -> {
            FormGroup(nested = path.isNotEmpty()) {
                ReorderableList(items = node.items, onMove = { from, to -> moveSeqItem(node, from, to); ctx.markEdited() }) { i, _, dragging ->
                    Highlightable(ctx.state, path + i) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp)
                                .background(if (dragging) Palette.Selected else Color.Transparent, PanelShape),
                            verticalAlignment = Alignment.Top,
                        ) {
                            if (!ctx.readOnly) Handle(Modifier.padding(top = 12.dp, end = 2.dp))
                            Text(
                                "${i + 1}.",
                                fontSize = 12.sp, color = Palette.SubText,
                                modifier = Modifier.padding(top = if (ctx.readOnly) 2.dp else 12.dp, end = 6.dp),
                            )
                            Box(Modifier.weight(1f)) {
                                GenericEditor(ctx, SeqSlot(node, i), path + i, refHint)
                            }
                            if (!ctx.readOnly) {
                                IconButton(
                                    onClick = { node.items.removeAt(i); ctx.markEdited() },
                                    modifier = Modifier.size(32.dp).padding(top = 8.dp),
                                ) { Icon(Icons.Default.Delete, contentDescription = "削除", Modifier.size(16.dp), tint = Palette.SubText) }
                            }
                        }
                    }
                }
                if (!ctx.readOnly) {
                    TextButton(onClick = {
                        node.items.add(YNode.Scalar(""))
                        ctx.markEdited()
                    }) {
                        Icon(Icons.Default.Add, contentDescription = null, Modifier.size(16.dp))
                        Text("追加", fontSize = 13.sp)
                    }
                }
            }
        }
        is YNode.Map -> {
            Highlightable(ctx.state, path) {
                FormGroup(nested = path.isNotEmpty()) {
                    for (k in node.entries.keys.toList()) {
                        GenericMapEntryRow(ctx, node, k, path)
                    }
                    if (!ctx.readOnly) AddKeyRow(ctx, node)
                }
            }
        }
    }
}

/** 汎用ビューのキー名変更（R8-05: L-05）。順序と値を保って改名する。改名できないときは false。 */
internal fun renameMapKey(map: YNode.Map, oldKey: String, newKey: String): Boolean {
    val trimmed = newKey.trim()
    if (trimmed.isEmpty() || trimmed == oldKey) return false
    if (oldKey !in map.entries || trimmed in map.entries) return false
    val entries = map.entries.toList()
    map.entries.clear()
    for ((k, v) in entries) map.entries[if (k == oldKey) trimmed else k] = v
    return true
}

@Composable
fun GenericMapEntryRow(ctx: FormCtx, map: YNode.Map, key: String, mapPath: YPath) {
    @Suppress("UNUSED_VARIABLE") val version = ctx.doc.version
    val childPath = mapPath + key
    val childRefHint = DocScanner.isGenericRefKey(key) || DocScanner.isGenericRefListKey(key)
    var renaming by remember(key) { mutableStateOf(false) }
    var renameText by remember(key) { mutableStateOf(key) }
    if (ctx.readOnly) {
        // 閲覧専用モード（R10-04）: キー名変更・削除を出さない高密度の表示行
        Highlightable(ctx.state, childPath) {
            FormFieldLayout(labelWidth = 150.dp, structured = isStructuredFormValue(null, map.entries[key]), label = {
                Text(key, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 2.dp))
            }) {
                GenericEditor(ctx, MapSlot(map, key), childPath, refHint = childRefHint)
            }
        }
        return
    }
    Highlightable(ctx.state, childPath) {
        FormFieldLayout(
            Modifier.padding(vertical = FormSpacing.FieldGap), labelWidth = if (renaming) 214.dp else 150.dp,
            structured = isStructuredFormValue(null, map.entries[key]), label = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                if (renaming) {
                    // キー名の変更（R8-05）。順序・値・コメントは保たれる
                    val valid = renameText.trim().let { it.isNotEmpty() && (it == key || it !in map.entries) }
                    CompactTextField(
                        clearFocusOnEscape = true,
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        isError = !valid,
                        textStyle = fieldTextStyle,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        enabled = valid,
                        onClick = {
                            if (renameMapKey(map, key, renameText)) ctx.markEdited()
                            renaming = false
                        },
                        modifier = Modifier.size(32.dp).padding(top = 8.dp),
                    ) { Icon(Icons.Default.Check, contentDescription = "キー名を確定", Modifier.size(16.dp), tint = Palette.Primary) }
                    IconButton(
                        onClick = { renaming = false; renameText = key },
                        modifier = Modifier.size(32.dp).padding(top = 8.dp),
                    ) { Icon(Icons.Default.Close, contentDescription = "キー名変更を取消", Modifier.size(16.dp), tint = Palette.SubText) }
                } else {
                    Text(
                        key,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).padding(top = 10.dp),
                    )
                    IconButton(
                        onClick = { renameText = key; renaming = true },
                        modifier = Modifier.size(24.dp).padding(top = 8.dp),
                    ) { Icon(Icons.Default.Edit, contentDescription = "キー名を変更", Modifier.size(13.dp), tint = Palette.SubText) }
                }
            }
        }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f)) {
                    GenericEditor(ctx, MapSlot(map, key), childPath, refHint = childRefHint)
                }
                IconButton(
                    onClick = { map.entries.remove(key); ctx.markEdited() },
                    modifier = Modifier.size(32.dp).padding(top = 8.dp),
                ) { Icon(Icons.Default.Delete, contentDescription = "キーを削除", Modifier.size(16.dp), tint = Palette.SubText) }
            }
        }
    }
}

@Composable
private fun AddKeyRow(ctx: FormCtx, map: YNode.Map) {
    var newKey by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CompactTextField(
            clearFocusOnEscape = true,
            value = newKey,
            onValueChange = { newKey = it },
            singleLine = true,
            textStyle = fieldTextStyle,
            placeholder = { Text("新しいキー") },
            modifier = Modifier.width(200.dp),
        )
        TextButton(
            enabled = newKey.isNotBlank() && newKey !in map.entries,
            onClick = {
                map.entries[newKey.trim()] = YNode.Scalar("")
                ctx.markEdited()
                newKey = ""
            },
        ) {
            Icon(Icons.Default.Add, contentDescription = null, Modifier.size(16.dp))
            Text("キーを追加", fontSize = 13.sp)
        }
    }
}
