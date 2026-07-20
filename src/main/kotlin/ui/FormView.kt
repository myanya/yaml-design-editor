package ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import app.AppState
import app.OpenDocument
import index.DocScanner
import index.IdEntry
import index.IdIndex
import index.RefEntry
import index.isAllowedBy
import model.YNode
import model.YPath
import schema.FieldDef
import schema.FieldType
import schema.effectiveFields
import yaml.YamlIO

private val fieldTextStyle = TextStyle(fontSize = 13.sp)

/**
 * フォーム編集のコンテキスト。
 * localIds は未保存編集を含む「現在のドキュメント内の ID 集合」で、参照解決の即時判定に使う。
 */
class FormCtx(
    val state: AppState,
    val doc: OpenDocument,
    val localIds: List<IdEntry>,
) {
    fun markEdited() = doc.markEdited()

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
    val ctx = remember(doc, doc.version, state.indexVersion) { FormCtx(state, doc, localIds) }

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
            val schemaDef = doc.schema
            val rootDef = schemaDef?.resolve(schemaDef.root)
            if (rootDef?.type == FieldType.OBJECT && root is YNode.Map) {
                ObjectFields(ctx, schemaDef.resolve(schemaDef.root)?.fields.orEmpty(), root, emptyList())
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
                    ?: runCatching { YamlIO.parse(java.nio.file.Files.readString(entry.file)) }.getOrNull()
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

// ================================================================ スキーマ駆動フォーム

/** スキーマの fields をフォーム行として描画し、スキーマ外のキーも汎用行で表示する。 */
@Composable
fun ObjectFields(ctx: FormCtx, fields: List<FieldDef>, map: YNode.Map, path: YPath) {
    // enum 値に応じた case フィールドを合成した実効フィールドで描画する
    val effective = ctx.doc.schema?.effectiveFields(fields, map)
    val effectiveList = effective?.fields ?: fields
    val definedKeys = effectiveList.mapNotNull { it.key }.toSet()
    for (field in effectiveList) {
        val key = field.key ?: continue
        FieldRow(ctx, field, MapSlot(map, key), path + key)
    }
    val extraKeys = map.entries.keys.filter { it !in definedKeys && !(path.isEmpty() && it == "_document") }
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
}

@Composable
private fun FieldRow(ctx: FormCtx, field: FieldDef, slot: Slot, path: YPath) {
    val effective = ctx.doc.schema?.resolve(field) ?: field
    Highlightable(ctx.state, path) {
        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.width(148.dp).padding(top = 8.dp, end = 8.dp)) {
                Row {
                    Text(effective.displayLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    if (effective.required) Text(" *", color = Palette.ErrorRed, fontSize = 12.sp)
                    if (effective.type != FieldType.ARRAY) {
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
                effective.description?.let { Text(it, fontSize = 10.sp, color = Palette.SubText, maxLines = 2) }
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
    }
}

@Composable
private fun ScalarTextEditor(ctx: FormCtx, field: FieldDef, slot: Slot, singleLine: Boolean) {
    val node = slot.get()
    val text = (node as? YNode.Scalar)?.value?.toString() ?: ""
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

@Composable
private fun EnumEditor(ctx: FormCtx, field: FieldDef, slot: Slot) {
    val current = (slot.get() as? YNode.Scalar)?.value?.toString()
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
            Text(current ?: "（未設定）", fontSize = 13.sp)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            field.values.forEach { v ->
                DropdownMenuItem(text = { Text(v, fontSize = 13.sp) }, onClick = {
                    slot.set(YNode.Scalar(v))
                    ctx.markEdited()
                    open = false
                })
            }
            DropdownMenuItem(text = { Text("（未設定にする）", fontSize = 13.sp, color = Palette.SubText) }, onClick = {
                slot.remove()
                ctx.markEdited()
                open = false
            })
        }
    }
}

@Composable
private fun IdEditor(ctx: FormCtx, slot: Slot) {
    val text = (slot.get() as? YNode.Scalar)?.value?.toString() ?: ""
    val otherOccurrences = if (text.isBlank()) 0 else
        ctx.state.index.entries[text].orEmpty().count { it.file != ctx.doc.file }
    Column {
        CompactTextField(
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

    // 候補: ID パターンとスコープで絞り込み、同一論理 ID は1候補にまとめる。
    val candidates: List<IdEntry> = remember(ctx.state.indexVersion, field.idPatterns, field.scope) {
        ctx.state.index.entries.values.asSequence()
            .filter { occurrences -> occurrences.any { it.isAllowedBy(refConstraint) } }
            .mapNotNull { occurrences -> occurrences.firstOrNull { it.isAllowedBy(refConstraint) } }
            .sortedBy { it.id }
            .toList()
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
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
    if (node == null) {
        TextButton(onClick = {
            slot.set(YNode.Seq(mutableListOf(defaultNode(field.item, ctx.doc.schema))))
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
    var expanded by remember(path) { mutableStateOf(true) }
    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, Palette.Border, PanelShape)
            .padding(6.dp)
    ) {
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)) {
            Text(if (expanded) "▼ ${node.items.size} 件" else "▶ ${node.items.size} 件", fontSize = 11.sp, color = Palette.SubText)
        }
        if (expanded) {
        node.items.forEachIndexed { i, _ ->
            Highlightable(ctx.state, path + i) {
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                    Text(
                        "${i + 1}.",
                        fontSize = 12.sp, color = Palette.SubText,
                        modifier = Modifier.padding(top = 12.dp, end = 6.dp),
                    )
                    Box(Modifier.weight(1f)) {
                        ValueEditor(ctx, field.item ?: FieldDef(), SeqSlot(node, i), path + i)
                    }
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

@Composable
private fun NestedObjectEditor(ctx: FormCtx, field: FieldDef, slot: Slot, path: YPath) {
    val node = slot.get()
    if (node == null) {
        TextButton(onClick = {
            slot.set(defaultNode(field, ctx.doc.schema))
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
    var expanded by remember(path) { mutableStateOf(true) }
    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, Palette.Border, PanelShape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)) {
            Text(if (expanded) "▼ 詳細" else "▶ 詳細", fontSize = 11.sp, color = Palette.SubText)
        }
        if (expanded) ObjectFields(ctx, field.fields, node, path)
    }
}

@Composable
private fun MapEditor(ctx: FormCtx, field: FieldDef, slot: Slot, path: YPath) {
    val node = slot.get()
    if (node == null) {
        TextButton(onClick = { slot.set(YNode.Map()); ctx.markEdited() }) {
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
    var expanded by remember(path) { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth().border(1.dp, Palette.Border, PanelShape).padding(6.dp)) {
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)) {
            Text(if (expanded) "▼ ${node.entries.size} 件" else "▶ ${node.entries.size} 件", fontSize = 11.sp, color = Palette.SubText)
        }
        if (expanded) {
            node.entries.keys.toList().forEach { key ->
                val childPath = path + key
                Highlightable(ctx.state, childPath) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.Top) {
                        Text(key, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(150.dp).padding(top = 10.dp))
                        Box(Modifier.weight(1f)) {
                            ValueEditor(ctx, field.value ?: FieldDef(), MapSlot(node, key), childPath)
                        }
                        IconButton(onClick = { node.entries.remove(key); ctx.markEdited() },
                            modifier = Modifier.size(32.dp).padding(top = 8.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = "キーを削除", Modifier.size(16.dp), tint = Palette.SubText)
                        }
                    }
                }
            }
            AddTypedMapKeyRow(ctx, node, field.value)
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
                                modifier = Modifier.padding(top = 12.dp, end = 6.dp),
                            )
                            Box(Modifier.weight(1f)) {
                                GenericEditor(ctx, SeqSlot(node, i), path + i, refHint)
                            }
                            IconButton(
                                onClick = { node.items.removeAt(i); ctx.markEdited() },
                                modifier = Modifier.size(32.dp).padding(top = 8.dp),
                            ) { Icon(Icons.Default.Delete, contentDescription = "削除", Modifier.size(16.dp), tint = Palette.SubText) }
                        }
                    }
                }
                TextButton(onClick = {
                    node.items.add(YNode.Scalar(""))
                    ctx.markEdited()
                }) {
                    Icon(Icons.Default.Add, contentDescription = null, Modifier.size(16.dp))
                    Text("追加", fontSize = 13.sp)
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
                    AddKeyRow(ctx, node)
                }
            }
        }
    }
}

@Composable
fun GenericMapEntryRow(ctx: FormCtx, map: YNode.Map, key: String, mapPath: YPath) {
    val childPath = mapPath + key
    val childRefHint = DocScanner.isGenericRefKey(key) || DocScanner.isGenericRefListKey(key)
    Highlightable(ctx.state, childPath) {
        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.Top) {
            Text(
                key,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(150.dp).padding(top = 10.dp),
            )
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
