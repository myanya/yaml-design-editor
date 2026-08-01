package ui

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
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import app.AppState
import app.OpenDocument
import core.DocBacklinkRow
import core.DocBacklinksDef
import core.DocSectionResolved
import core.DocViewAttachment
import core.DocViewDef
import core.Views
import index.DocScanner
import index.IdEntry
import index.IdIndex
import index.RefEntry
import index.isAllowedBy
import model.YNode
import model.YPath
import model.pretty
import schema.Display
import schema.FieldDef
import schema.FieldType
import schema.effectiveFields
import yaml.YamlIO

private val fieldTextStyle = TextStyle(fontSize = 13.sp)

/** ネスト構造（object・array・map）を既定で展開する深さ（フォームのルートからのパス段数）。 */
private const val DEFAULT_EXPAND_DEPTH = 2

/**
 * フォーム編集のコンテキスト。
 * localIds は未保存編集を含む「現在のドキュメント内の ID 集合」で、参照解決の即時判定に使う。
 * readOnly は閲覧専用モード（R10-04）: 編集UIを表示専用に切り替え、markEdited への到達を拒否する。
 * 既定値を state.readOnlyMode から取るため、FormCtx を作る全ビュー（フォーム・グリッド・横ぐし）へ自動波及する。
 * baseDepth は埋め込みフォーム（ID横ぐし）の基準パス段数。既定展開の深さ判定を
 * 「文書ルートから」ではなく「表示しているフォームのルートから」の相対深さで行うために使う。
 */
class FormCtx(
    val state: AppState,
    val doc: OpenDocument,
    val localIds: List<IdEntry>,
    val readOnly: Boolean = state.readOnlyMode,
    val baseDepth: Int = 0,
    /** 文書ビューの要素単位アタッチ（R14-03）: 要素パス → その位置に差し込む被参照。ObjectFields が描画する。 */
    val attachments: Map<YPath, List<Pair<DocViewAttachment, DocBacklinkRow>>> = emptyMap(),
) {
    /**
     * ネスト構造の既定展開判定。フォームのルートから DEFAULT_EXPAND_DEPTH 階層までは開き、
     * より深い階層は畳んで畳み込みサマリ（E-11）に任せる。編集・閲覧専用モード共通。
     */
    fun defaultExpanded(path: YPath): Boolean = path.size - baseDepth <= DEFAULT_EXPAND_DEPTH

    fun markEdited() {
        check(!readOnly) { "閲覧専用モードでは編集できません" }
        doc.markEdited()
    }

    fun resolvable(ref: RefEntry): Boolean =
        ref.targetId.isNotBlank() &&
            (localIds.any { it.id == ref.targetId && it.isAllowedBy(ref) } || state.index.resolve(ref).isNotEmpty())

    /** スキーマ外の汎用参照は、パターン／スコープ制約なしで解決する。 */
    fun resolvable(id: String): Boolean =
        resolvable(RefEntry(id, doc.file, emptyList(), "ref"))

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
        if (node is YNode.Scalar) node.value = v else slot.set(YNode.Scalar(v))
        markEdited()
    }
}

/** スキーマ定義に基づく新規ノードの初期値を作る。 */
fun defaultNode(field: FieldDef?, schema: schema.SchemaDef? = null, depth: Int = 0): YNode {
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
                    if (ch.required && ch.key != null) put(ch.key, defaultNode(ch, schema, depth + 1))
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
            Text("「ソース」タブでテキストを修正して「反映」してください。", fontSize = 13.sp)
        }
        return
    }

    val localIds = remember(doc, doc.version) {
        val documentId = IdIndex.documentIdOf(root, state.relPath(doc.file))
        DocScanner.scan(root, doc.file, doc.schema, documentId).first
    }
    // 文書ビュー（R14-01）: マッチする定義があれば表示合成をビューに委ねる。ビューは台帳層の機構のため台帳の文書に限る
    val docView = remember(state.indexVersion, doc.file, doc.schema) {
        val ws = state.workspace
        val rel = state.relPath(doc.file)
        if (ws == null || !state.layout.inLedger(rel)) null
        else Views.load(ws, state.schemas).docViewFor(rel, doc.schema?.id)
    }
    // 要素単位アタッチ（R14-03）: 要素パス → 被参照 に解決して FormCtx へ載せる（描画は ObjectFields）
    val attachments = remember(doc, doc.version, state.indexVersion, docView) {
        val ws = state.workspace
        if (docView == null || docView.attachments.isEmpty() || ws == null) emptyMap()
        else Views.resolveAttachments(docView, ws, doc.file, state.index, localIds) { f ->
            state.docFor(f)?.root
                ?: runCatching { YamlIO.parseFirst(java.nio.file.Files.readString(f)) }.getOrNull()
        }.flatMap { resolved -> resolved.rows.map { row -> row.path to (resolved.attachment to row) } }
            .groupBy({ it.first }, { it.second })
    }
    val ctx = remember(doc, doc.version, state.indexVersion, state.readOnlyMode, attachments) {
        FormCtx(state, doc, localIds, attachments = attachments)
    }

    Box(Modifier.fillMaxSize()) {
        val scroll = rememberScrollState()
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            if (!state.relPath(doc.file).startsWith("_schemas/") && root is YNode.Map) {
                DocumentMetadataEditor(ctx, root)
                Spacer(Modifier.height(8.dp))
                SameIdOccurrencesStrip(ctx, localIds)
                Spacer(Modifier.height(8.dp))
            }
            if (docView != null && root is YNode.Map) {
                DocViewSections(ctx, docView, root, localIds)
            } else {
                SchemaRootForm(ctx, root)
            }
            Spacer(Modifier.height(80.dp))
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scroll),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
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
private fun DocumentMetadataEditor(ctx: FormCtx, root: YNode.Map) {
    val nameSlot = DocumentMetadataSlot(root, "name")
    val descriptionSlot = DocumentMetadataSlot(root, "description")
    if (ctx.readOnly) {
        // 閲覧専用モード（R10-04）: 入力欄を出さず、記載済みのメタデータだけを高密度で表示する
        val name = (nameSlot.get() as? YNode.Scalar)?.value?.toString().orEmpty()
        val description = (descriptionSlot.get() as? YNode.Scalar)?.value?.toString().orEmpty()
        if (name.isBlank() && description.isBlank()) return
        Column(
            Modifier.fillMaxWidth().background(Palette.PanelBg, PanelShape)
                .border(1.dp, Palette.Border, PanelShape).padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (name.isNotBlank()) Text(name, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (description.isNotBlank()) Text(description, fontSize = 11.sp, color = Palette.SubText)
        }
        return
    }
    Column(
        Modifier.fillMaxWidth().background(Palette.PanelBg, PanelShape)
            .border(1.dp, Palette.Border, PanelShape).padding(12.dp),
    ) {
        Text("文書メタデータ", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.Primary)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text("文書名", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(136.dp).padding(top = 9.dp))
            CompactTextField(
                value = (nameSlot.get() as? YNode.Scalar)?.value?.toString().orEmpty(),
                onValueChange = { ctx.setScalar(nameSlot, it, FieldType.STRING) },
                singleLine = true,
                placeholder = { Text("この文書を表す名前", fontSize = 13.sp, color = Palette.SubText) },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.width(136.dp).padding(top = 9.dp, end = 8.dp)) {
                Text("概要", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("文書を開かずに内容を判断できる要約", fontSize = 10.sp, color = Palette.SubText)
            }
            CompactTextField(
                value = (descriptionSlot.get() as? YNode.Scalar)?.value?.toString().orEmpty(),
                onValueChange = { ctx.setScalar(descriptionSlot, it, FieldType.TEXT) },
                placeholder = { Text("この文書が何を示し、何を含むかを簡潔に記述", fontSize = 13.sp, color = Palette.SubText) },
                modifier = Modifier.weight(1f).heightIn(min = 84.dp),
            )
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
private fun SameIdOccurrencesStrip(ctx: FormCtx, localIds: List<IdEntry>) {
    val state = ctx.state
    state.indexVersion
    val rootId = localIds.firstOrNull { it.path.isEmpty() }?.id ?: return
    val others = remember(rootId, state.indexVersion) {
        state.index.occurrences(rootId).filter { it.file != ctx.doc.file }
    }
    if (others.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().background(Palette.PanelBg, PanelShape)
            .border(1.dp, Palette.Border, PanelShape).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "他文書での定義（同一ID: $rootId）", fontSize = 12.sp,
                fontWeight = FontWeight.Bold, color = Palette.Primary, modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { state.openCrossView(rootId) },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            ) { Text("横ぐしビュー", fontSize = 11.sp) }
        }
        others.forEach { entry ->
            val summary = remember(entry, state.indexVersion) {
                val root = state.docFor(entry.file)?.root
                    ?: runCatching { YamlIO.parseFirst(java.nio.file.Files.readString(entry.file)) }.getOrNull()
                val element = root?.let { model.getAt(it, entry.path) } as? YNode.Map
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
                Icon(Icons.Default.ArrowForward, contentDescription = null, Modifier.size(13.dp), tint = Palette.Primary)
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        listOfNotNull(entry.title, state.relPath(entry.file)).joinToString(" — "),
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
private fun SchemaRootForm(
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
 * backlinks セクションは読み取り専用の被参照表を出す——ビュー専用の書込経路は作らない（ARCHITECTURE §4.14）。
 */
@Composable
private fun DocViewSections(ctx: FormCtx, view: DocViewDef, root: YNode.Map, localIds: List<IdEntry>) {
    val state = ctx.state
    val doc = ctx.doc
    // 行（文書内ID）は未保存編集を反映（localIds）、被参照は索引＝保存済み状態基準（SidePanel と同じ）
    val resolved = remember(doc, doc.version, state.indexVersion, view) {
        state.workspace?.let { ws ->
            Views.resolveDocument(view, ws, doc.file, state.index, localIds) { f ->
                state.docFor(f)?.root
                    ?: runCatching { YamlIO.parseFirst(java.nio.file.Files.readString(f)) }.getOrNull()
            }
        }.orEmpty()
    }
    resolved.forEachIndexed { i, sectionResolved ->
        val section = sectionResolved.section
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
                val node = model.getAt(root, fieldsPath)
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
            BacklinkSectionTable(state, sectionResolved)
        }
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
                    state.openFile(b.file, b.refPath)
                    state.editorTab = 0
                }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.ArrowForward, contentDescription = null, Modifier.size(12.dp), tint = Palette.Primary)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    listOfNotNull(b.sourceId ?: b.rel, b.sourceTitle).joinToString(" "),
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                )
                val details = def.columns.mapIndexed { ci, col ->
                    "${col.label}: ${Views.cellText(b.values.getOrNull(ci), 40)}"
                } + listOfNotNull(b.relation?.let { "関係: $it" }, b.rel)
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

@Composable
private fun FieldRow(ctx: FormCtx, field: FieldDef, slot: Slot, path: YPath) {
    val effective = ctx.doc.schema?.resolve(field) ?: field
    // 閲覧専用モード（R10-04）はパディングを圧縮して表示密度を上げる
    val labelTop = if (ctx.readOnly) 2.dp else 8.dp
    Highlightable(ctx.state, path) {
        Row(Modifier.fillMaxWidth().padding(vertical = if (ctx.readOnly) 0.dp else 1.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.width(148.dp).padding(top = labelTop, end = 8.dp)) {
                Row {
                    Text(effective.displayLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    if (effective.required) Text(" *", color = Palette.ErrorRed, fontSize = 12.sp)
                    // requiredFrom はフェーズ到達で必須になる項目（無条件必須の * とは区別して注記する）
                    effective.requiredFrom?.let {
                        Text(" *$it〜", color = Palette.WarnOrange, fontSize = 10.sp)
                    }
                    if (effective.type != FieldType.ARRAY && !ctx.readOnly) {
                        IconButton(
                            onClick = { ctx.state.requestAi(effective, path) },
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
                if (!ctx.readOnly) {
                    effective.description?.let { Text(it, fontSize = 10.sp, color = Palette.SubText, maxLines = 2) }
                }
            }
            Box(Modifier.weight(1f)) { ValueEditor(ctx, effective, slot, path) }
        }
    }
}

/** 型に応じたエディタの振り分け。 */
@Composable
fun ValueEditor(ctx: FormCtx, field: FieldDef, slot: Slot, path: YPath) {
    val effective = ctx.doc.schema?.resolve(field) ?: field
    when (effective.type) {
        FieldType.STRING, FieldType.DATE -> ScalarTextEditor(ctx, effective, slot, singleLine = true)
        FieldType.TEXT -> ScalarTextEditor(ctx, effective, slot, singleLine = false)
        FieldType.INT, FieldType.NUMBER -> ScalarTextEditor(ctx, effective, slot, singleLine = true)
        FieldType.BOOLEAN -> BooleanEditor(ctx, slot)
        FieldType.ENUM -> EnumEditor(ctx, effective, slot)
        FieldType.ID -> IdEditor(ctx, slot)
        FieldType.REF -> RefEditor(ctx, effective, slot)
        FieldType.ARRAY -> ArrayEditor(ctx, effective, slot, path)
        FieldType.MAP -> MapEditor(ctx, effective, slot, path)
        FieldType.OBJECT -> NestedObjectEditor(ctx, effective, slot, path)
        // any は型を定めないため汎用エディタで編集する（R11-02）
        FieldType.ANY -> GenericEditor(ctx, slot, path, refHint = false)
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
private fun ScalarTextEditor(ctx: FormCtx, field: FieldDef, slot: Slot, singleLine: Boolean) {
    val node = slot.get()
    val text = (node as? YNode.Scalar)?.value?.toString() ?: ""
    if (ctx.readOnly) {
        ReadOnlyValueText(text)
        return
    }
    val typeError = when (field.type) {
        FieldType.INT -> text.isNotBlank() && text.toLongOrNull() == null
        FieldType.NUMBER -> text.isNotBlank() && text.toDoubleOrNull() == null
        else -> false
    }
    CompactTextField(
        value = text,
        onValueChange = { ctx.setScalar(slot, it, field.type) },
        singleLine = singleLine,
        isError = typeError,
        textStyle = fieldTextStyle,
        placeholder = {
            val hint = when (field.type) {
                FieldType.DATE -> "YYYY-MM-DD"
                FieldType.INT -> "整数"
                FieldType.NUMBER -> "数値"
                else -> ""
            }
            if (hint.isNotEmpty()) Text(hint, fontSize = 13.sp, color = Palette.SubText)
        },
        modifier = if (singleLine) Modifier.fillMaxWidth()
        else Modifier.fillMaxWidth().heightIn(min = 72.dp),
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
    if (ctx.readOnly) {
        Column {
            ReadOnlyValueText(currentOption?.displayLabel ?: current)
            currentOption?.description?.let { Text(it, fontSize = 10.sp, color = Palette.SubText) }
        }
        return
    }
    var open by remember { mutableStateOf(false) }
    Column {
        Box {
            OutlinedButton(onClick = { open = true },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                Text(currentOption?.displayLabel ?: current ?: "（未設定）", fontSize = 13.sp)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                field.enumOptions().forEach { option ->
                    val selected = option.value == current
                    DropdownMenuItem(text = {
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
                            option.description?.let { Text(it, fontSize = 11.sp, color = Palette.SubText) }
                        }
                    }, trailingIcon = {
                        if (selected) {
                            Icon(Icons.Default.Check, contentDescription = "選択中",
                                tint = Palette.Primary, modifier = Modifier.size(18.dp))
                        }
                    }, onClick = {
                        slot.set(YNode.Scalar(option.value))
                        ctx.markEdited()
                        open = false
                    }, modifier = Modifier.background(if (selected) Palette.Selected else Color.Transparent))
                }
                DropdownMenuItem(text = { Text("（未設定にする）", fontSize = 13.sp, color = Palette.SubText) }, onClick = {
                    slot.remove()
                    ctx.markEdited()
                    open = false
                })
            }
        }
        // 現在選択値の説明（選択肢を開かなくても意味が分かるようにする）
        currentOption?.description?.let { Text(it, fontSize = 10.sp, color = Palette.SubText) }
    }
}

@Composable
private fun IdEditor(ctx: FormCtx, slot: Slot) {
    val text = (slot.get() as? YNode.Scalar)?.value?.toString() ?: ""
    val otherOccurrences = if (text.isBlank()) 0 else
        ctx.state.index.entries[text].orEmpty().count { it.file != ctx.doc.file }
    Column {
        if (ctx.readOnly) {
            ReadOnlyValueText(text)
        } else CompactTextField(
            value = text,
            onValueChange = { ctx.setScalar(slot, it, FieldType.ID) },
            singleLine = true,
            textStyle = fieldTextStyle,
            modifier = Modifier.fillMaxWidth(),
        )
        if (otherOccurrences > 0) {
            Text("同一論理IDの別文書での出現: $otherOccurrences 件", fontSize = 11.sp, color = Palette.SubText)
        } else if (text.isNotBlank()) {
            val backlinks = ctx.state.index.backlinksTo(setOf(text)).size
            if (backlinks > 0) {
                Text("被参照: $backlinks 箇所", fontSize = 11.sp, color = Palette.SubText)
            }
        }
    }
}

@Composable
fun RefEditor(ctx: FormCtx, field: FieldDef, slot: Slot) {
    val current = (slot.get() as? YNode.Scalar)?.value?.toString() ?: ""
    var menuOpen by remember { mutableStateOf(false) }
    val refConstraint = RefEntry(current, ctx.doc.file, emptyList(), field.key ?: "ref", field.idPatterns, field.scope)
    val resolved = ctx.resolvable(refConstraint)
    if (ctx.readOnly) {
        // 閲覧専用モードでもジャンプは閲覧行為として残す（R10-04）
        Row(verticalAlignment = Alignment.CenterVertically) {
            val target = ctx.state.index.occurrences(current).firstOrNull()
            ReadOnlyValueText(
                listOfNotNull(current.takeIf { it.isNotBlank() }, target?.title).joinToString(" — ").takeIf { it.isNotBlank() },
                modifier = Modifier.weight(1f, fill = false),
            )
            if (resolved) {
                IconButton(onClick = { ctx.state.navigateToId(current) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.ArrowForward, contentDescription = "定義へ移動",
                        tint = Palette.Primary, modifier = Modifier.size(16.dp))
                }
            }
        }
        if (current.isNotBlank() && !resolved) {
            Text("未解決の参照です", fontSize = 11.sp, color = Palette.ErrorRed)
        }
        return
    }

    // 候補: ID パターンとスコープで絞り込み、同一論理 ID は1候補にまとめる。
    val candidates: List<IdEntry> = remember(ctx.state.indexVersion, field.idPatterns, field.scope) {
        ctx.state.index.entries.values.asSequence()
            .filter { occurrences -> occurrences.any { it.isAllowedBy(refConstraint) } }
            .mapNotNull { occurrences -> occurrences.firstOrNull { it.isAllowedBy(refConstraint) } }
            .sortedBy { it.id }
            .toList()
    }

    // 解決先タイトル（表示解決は IdEntry.title に一元化済み・R10-01）。未保存編集を含むローカルIDを優先する（§4.5）。
    val targetTitle = if (!resolved) null else
        (ctx.localIds.firstOrNull { it.id == current && it.isAllowedBy(refConstraint) }
            ?: ctx.state.index.occurrences(current).firstOrNull { it.isAllowedBy(refConstraint) })
            ?.title?.takeIf { it.isNotBlank() && it != current }
    Row(verticalAlignment = Alignment.CenterVertically) {
        // タイトルを添えるときはフィールドを詰め、同じ行の右側に参照先の意味を見せる（縦幅を増やさない）
        Box(Modifier.weight(if (targetTitle != null) 0.45f else 1f)) {
            CompactTextField(
                value = current,
                onValueChange = { ctx.setScalar(slot, it, FieldType.REF) },
                singleLine = true,
                isError = current.isNotBlank() && !resolved,
                textStyle = fieldTextStyle,
                placeholder = { Text("参照先 ID", fontSize = 13.sp, color = Palette.SubText) },
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
                    DropdownMenuItem(text = {
                        Column {
                            Text(
                                e.id,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                color = if (selected) Palette.Primary else Palette.Text,
                            )
                            Text(
                                listOfNotNull(e.title, ctx.state.relPath(e.file)).joinToString(" — "),
                                fontSize = 11.sp, color = Palette.SubText,
                            )
                        }
                    }, trailingIcon = {
                        if (selected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "選択中",
                                tint = Palette.Primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }, onClick = {
                        ctx.setScalar(slot, e.id, FieldType.REF)
                        menuOpen = false
                    }, modifier = Modifier.background(if (selected) Palette.Selected else Color.Transparent))
                }
            }
        }
        if (targetTitle != null) {
            Text(
                targetTitle,
                fontSize = 12.sp,
                color = Palette.SubText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(0.55f).padding(start = 8.dp),
            )
        }
        IconButton(
            enabled = resolved,
            onClick = { ctx.state.navigateToId(current) },
            modifier = Modifier.size(38.dp),
        ) {
            Icon(
                Icons.Default.ArrowForward,
                contentDescription = "定義へ移動",
                tint = if (resolved) Palette.Primary else Palette.Border,
            )
        }
    }
    if (current.isNotBlank() && !resolved) {
        Text("未解決の参照です", fontSize = 11.sp, color = Palette.ErrorRed)
    }
}

@Composable
private fun ArrayEditor(ctx: FormCtx, field: FieldDef, slot: Slot, path: YPath) {
    val node = slot.get()
    // 既定は浅い階層のみ展開し、深い階層は畳んで要約を見せる（編集・閲覧共通）。
    // キーに readOnly を含めないことで、モード切替時も展開状態＝表示の深さを保つ。
    var expanded by remember(path) { mutableStateOf(ctx.defaultExpanded(path)) }
    if (node == null) {
        if (ctx.readOnly) {
            ReadOnlyValueText(null)
            return
        }
        TextButton(onClick = {
            slot.set(YNode.Seq(mutableListOf(defaultNode(field.item, ctx.doc.schema))))
            expanded = true
            ctx.markEdited()
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
    // 畳み込みサマリ（R10-02）: 表示解決による「n件: 先頭要素の要約, …」
    val header = Display.seqHeaderText(node, ctx.doc.schema, field.item)
    // テーブル表示（R12-06）: 列はスキーマの適否判定（自動＋display ヒント）で決まり、
    // 実データが全て map のときだけ表にする（型がずれた要素は縦リストの汎用表示で扱う）
    val columns = Display.tableColumns(ctx.doc.schema, field)
    val tabular = columns != null && node.items.isNotEmpty() && node.items.all { it is YNode.Map }
    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, Palette.Border, PanelShape)
            .padding(6.dp)
    ) {
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)) {
            Text((if (expanded) "▼ " else "▶ ") + header, fontSize = 11.sp, color = Palette.SubText, maxLines = 1)
        }
        if (expanded) {
        if (tabular) {
            ArrayTable(ctx, field, columns.orEmpty(), node, path)
        } else {
        node.items.forEachIndexed { i, _ ->
            Highlightable(ctx.state, path + i) {
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
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
                }
            }
        }
        }
        if (!ctx.readOnly) {
        TextButton(onClick = {
            node.items.add(defaultNode(field.item, ctx.doc.schema))
            ctx.markEdited()
        }) {
            Icon(Icons.Default.Add, contentDescription = null, Modifier.size(16.dp))
            Text("追加", fontSize = 13.sp)
        }
        TextButton(onClick = { ctx.state.requestAi(field, path) }) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text("AIで候補追加", fontSize = 12.sp)
        }
        }
        }
    }
}

// ================================================================ テーブル表示（R12-06: 配列・map）

/** 列の型に応じたセルの最小幅（余白があれば tableColumnWidths が広げ、収まらなければ横スクロール）。 */
private fun tableCellWidth(type: FieldType): Dp = when (type) {
    FieldType.BOOLEAN -> 90.dp
    FieldType.INT, FieldType.NUMBER -> 90.dp
    FieldType.DATE -> 110.dp
    FieldType.ENUM -> 140.dp
    FieldType.ID -> 150.dp
    FieldType.REF -> 200.dp
    FieldType.TEXT -> 240.dp
    else -> 170.dp
}

private val TABLE_EXPAND_WIDTH = 28.dp
private val TABLE_OPS_WIDTH = 76.dp        // 配列の行操作（上へ・下へ・削除）
private val TABLE_KEY_OPS_WIDTH = 28.dp    // map の行操作（キー削除のみ）
private val TABLE_KEY_WIDTH = 150.dp       // map のキー列

/** 余白の配分先になる列（文字系）。数値・真偽・日付は固定幅のままにする。 */
private val FLEX_CELL_TYPES = setOf(
    FieldType.STRING, FieldType.TEXT, FieldType.ENUM, FieldType.ID, FieldType.REF,
)

/**
 * 列幅の割付（R12-06）: 型別の最小幅を保証しつつ、利用可能幅に余りがあれば文字系の列で
 * 等分して表を右端まで使う。文字系の列がなければ伸ばさない（数値だけの表を間延びさせない）。
 * 利用可能幅に収まらない場合は最小幅のまま＝横スクロールに委ねる。
 */
internal fun tableColumnWidths(types: List<FieldType>, available: Dp, fixed: Dp): List<Dp> {
    val base = types.map { tableCellWidth(it) }
    val natural = fixed + base.fold(0.dp) { acc, w -> acc + w }
    val flexCount = types.count { it in FLEX_CELL_TYPES }
    if (available <= natural || flexCount == 0) return base
    val extra = (available - natural) / flexCount
    return types.mapIndexed { i, t -> base[i] + if (t in FLEX_CELL_TYPES) extra else 0.dp }
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
) {
    val hScroll = rememberScrollState()
    // 利用可能幅を測り、余白は文字系の列へ配って表を右端まで使う
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val keyWidth = if (keyHeader != null) TABLE_KEY_WIDTH else 0.dp
    val trailing = if (ctx.readOnly || ops == null) 0.dp else opsWidth
    val fixed = TABLE_EXPAND_WIDTH + keyWidth + trailing
    val widths = tableColumnWidths(columns.map { it.type }, maxWidth, fixed)
    val rowWidth = fixed + widths.fold(0.dp) { acc, w -> acc + w }
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().horizontalScroll(hScroll)) {
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
            Divider(color = Palette.Border, modifier = Modifier.width(rowWidth))
            rows.forEachIndexed { i, (segment, element) ->
                var rowExpanded by remember(path, segment) { mutableStateOf(false) }
                val rowPath = path + segment
                Highlightable(ctx.state, rowPath) {
                    Column(Modifier.width(rowWidth)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Box(Modifier.width(TABLE_EXPAND_WIDTH).padding(top = if (ctx.readOnly) 0.dp else 7.dp)) {
                                IconButton(onClick = { rowExpanded = !rowExpanded }, modifier = Modifier.size(24.dp)) {
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
                                Row(Modifier.width(opsWidth).padding(top = 7.dp)) { ops(i) }
                            }
                        }
                        if (rowExpanded) {
                            Column(Modifier.padding(start = TABLE_EXPAND_WIDTH, top = 2.dp, bottom = 6.dp)) {
                                ObjectFields(ctx, elementDef?.fields.orEmpty(), element, rowPath)
                            }
                        }
                    }
                }
                Divider(color = Palette.Border.copy(alpha = .4f), modifier = Modifier.width(rowWidth))
            }
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
    ObjectTable(ctx, itemDef, columns, rows, path, opsWidth = TABLE_OPS_WIDTH, ops = { i ->
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
    })
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
    // スキーマとずれた複合値はセルで編集せず要約表示に留める（編集は行の展開フォーム）
    if (value != null && value !is YNode.Scalar) {
        TableCellText(Display.inline(value, ctx.doc.schema, col))
        return
    }
    if (ctx.readOnly || col.type == FieldType.TEXT) {
        TableCellText(readOnlyCellText(col, value as? YNode.Scalar))
        return
    }
    ValueEditor(ctx, col, slot, itemPath + key)
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
    // キーに readOnly を含めないことで、モード切替時も展開状態＝表示の深さを保つ。
    var expanded by remember(path) { mutableStateOf(ctx.defaultExpanded(path)) }
    if (node == null) {
        if (ctx.readOnly) {
            ReadOnlyValueText(null)
            return
        }
        TextButton(onClick = {
            slot.set(defaultNode(field, ctx.doc.schema))
            expanded = true
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
    // 畳み込みサマリ（R10-02）: 表示解決による「ID タイトル（n項目）」
    val header = Display.headerText(node, ctx.doc.schema, field)
    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, Palette.Border, PanelShape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)) {
            Text((if (expanded) "▼ " else "▶ ") + header, fontSize = 11.sp, color = Palette.SubText, maxLines = 1)
        }
        if (expanded) ObjectFields(ctx, field.fields, node, path)
    }
}

@Composable
private fun MapEditor(ctx: FormCtx, field: FieldDef, slot: Slot, path: YPath) {
    val node = slot.get()
    // 既定は浅い階層のみ展開（編集・閲覧共通）。モード切替時も展開状態＝表示の深さを保つ
    var expanded by remember(path) { mutableStateOf(ctx.defaultExpanded(path)) }
    if (node == null) {
        if (ctx.readOnly) {
            ReadOnlyValueText(null)
            return
        }
        TextButton(onClick = { slot.set(YNode.Map()); expanded = true; ctx.markEdited() }) {
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
    Column(Modifier.fillMaxWidth().border(1.dp, Palette.Border, PanelShape).padding(6.dp)) {
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)) {
            Text(if (expanded) "▼ ${node.entries.size} 件" else "▶ ${node.entries.size} 件", fontSize = 11.sp, color = Palette.SubText)
        }
        if (expanded) {
            if (tabular) {
                MapTable(ctx, field, columns.orEmpty(), node, path)
            } else {
            node.entries.keys.toList().forEach { key ->
                val childPath = path + key
                Highlightable(ctx.state, childPath) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.Top) {
                        Text(key, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(150.dp).padding(top = if (ctx.readOnly) 2.dp else 10.dp))
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
            if (!ctx.readOnly) AddTypedMapKeyRow(ctx, node, field.value)
        }
    }
}

@Composable
private fun AddTypedMapKeyRow(ctx: FormCtx, map: YNode.Map, valueDef: FieldDef?) {
    var newKey by remember { mutableStateOf("") }
    val trimmed = newKey.trim()
    Row(verticalAlignment = Alignment.CenterVertically) {
        CompactTextField(value = newKey, onValueChange = { newKey = it }, singleLine = true,
            textStyle = fieldTextStyle, placeholder = { Text("新しいキー", fontSize = 13.sp, color = Palette.SubText) },
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
                value = "",
                onValueChange = { ctx.setGenericScalar(slot, it) },
                singleLine = true,
                textStyle = fieldTextStyle,
                placeholder = { Text("（null）", fontSize = 13.sp, color = Palette.SubText) },
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
                            Icon(Icons.Default.ArrowForward, contentDescription = "定義へ移動",
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
                                Icons.Default.ArrowForward,
                                contentDescription = "定義へ移動",
                                tint = Palette.Primary,
                            )
                        }
                    }
                }
            }
        }
        is YNode.Seq -> {
            Column(
                Modifier.fillMaxWidth()
                    .border(1.dp, Palette.Border, PanelShape)
                    .padding(6.dp)
            ) {
                node.items.forEachIndexed { i, _ ->
                    Highlightable(ctx.state, path + i) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
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
                Column(
                    Modifier.fillMaxWidth()
                        .border(1.dp, Palette.Border, PanelShape)
                        .padding(6.dp)
                ) {
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
    val childPath = mapPath + key
    val childRefHint = DocScanner.isGenericRefKey(key) || DocScanner.isGenericRefListKey(key)
    var renaming by remember(key) { mutableStateOf(false) }
    var renameText by remember(key) { mutableStateOf(key) }
    if (ctx.readOnly) {
        // 閲覧専用モード（R10-04）: キー名変更・削除を出さない高密度の表示行
        Highlightable(ctx.state, childPath) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(key, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(150.dp).padding(top = 2.dp))
                Box(Modifier.weight(1f)) {
                    GenericEditor(ctx, MapSlot(map, key), childPath, refHint = childRefHint)
                }
            }
        }
        return
    }
    Highlightable(ctx.state, childPath) {
        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.Top) {
            if (renaming) {
                // キー名の変更（R8-05）。順序・値・コメントは保たれる
                val valid = renameText.trim().let { it.isNotEmpty() && (it == key || it !in map.entries) }
                CompactTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    isError = !valid,
                    textStyle = fieldTextStyle,
                    modifier = Modifier.width(150.dp),
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
                    modifier = Modifier.width(126.dp).padding(top = 10.dp),
                )
                IconButton(
                    onClick = { renameText = key; renaming = true },
                    modifier = Modifier.size(24.dp).padding(top = 8.dp),
                ) { Icon(Icons.Default.Edit, contentDescription = "キー名を変更", Modifier.size(13.dp), tint = Palette.SubText) }
            }
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

@Composable
private fun AddKeyRow(ctx: FormCtx, map: YNode.Map) {
    var newKey by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CompactTextField(
            value = newKey,
            onValueChange = { newKey = it },
            singleLine = true,
            textStyle = fieldTextStyle,
            placeholder = { Text("新しいキー", fontSize = 13.sp, color = Palette.SubText) },
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
