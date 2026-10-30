package dev.toros.yde.desktop.ui.form

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.core.DocBacklinkRow
import dev.toros.yde.app.core.DocBacklinksDef
import dev.toros.yde.app.core.DocSectionResolved
import dev.toros.yde.app.core.DocViewDef
import dev.toros.yde.app.core.Views
import dev.toros.yde.app.index.DocScanner
import dev.toros.yde.app.index.IdEntry
import dev.toros.yde.app.index.IdIndex
import dev.toros.yde.semantic.schema.Relations
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.desktop.ui.common.PanelShape
import dev.toros.yde.desktop.ui.common.RootSlot
import dev.toros.yde.desktop.ui.common.slotAt

/*
 * 文書ビュー（R14-01）: スキーマ駆動フォームの既定の描画（SchemaRootForm）と、文書ビューの節・被参照の表。
 * FormView.kt から分けた（R47-22）。部品の地図は docs/architecture/ui.md「フォームと FormCtx」。
 */

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
    val ws = state.workspace
    // 表の区画（grid）は表の定義を目録から引く（索引の更新ごとに読み直す）
    val catalog = remember(ws, state.indexVersion) { ws?.let { Views.load(it, state.schemas, state.unsavedFirstTree()) } }
    // 行（文書内ID）は未保存編集を反映（localIds）、被参照は索引＝保存済み状態基準（SidePanel と同じ）。
    // 表の区画は未保存の編集にも追随する（editVersion）
    val resolved = remember(doc, doc.version, state.indexVersion, state.editVersion, view, catalog) {
        if (ws == null) emptyList() else Views.resolveDocument(
            view, state.documentOf(doc.file), state.overlayIndex(), localIds,
            loader = state.unsavedFirstTree(), schemaFor = state::schemaFor, catalog = catalog,
        )
    }
    resolved.forEachIndexed { i, sectionResolved ->
        val section = sectionResolved.section
        FormAnchor(if (section.isLocalFields) section.fieldsPath!! else listOf("@view", i)) { Column(Modifier.fillMaxWidth()) {
            if (i > 0) Spacer(Modifier.height(12.dp))
            section.label?.let {
                Text(it, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Palette.Primary)
                Spacer(Modifier.height(4.dp))
            }
            val fieldsPath = section.fieldsPath
            if (section.fieldsIn != null) {
                OccurrenceSection(state, sectionResolved)
            } else if (section.grid != null) {
                GridSection(state, sectionResolved)
            } else if (fieldsPath != null) {
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
                val embed = section.embed
                when {
                    embed != null -> LocalFormSlots.current.embeddedView(state, doc, embed)
                    else -> BacklinkSectionTable(state, sectionResolved)
                }
            }
        } }
    }
}

/**
 * 同じ ID の別の出現（側面）の区画（`fields: {in: …}`。複合文書ビュー 段階 1）。出現の文書を `state.ensureDoc` で docCache へ取り込み、
 * その要素の所有フィールドをエンティティページの側面カードと同じ経路（FormCtx ＋ Slot ＋ markEdited）で編集する。
 * 出どころが原本 1 か所の部分木なので、そのままフォームとして編集できる（ビュー専用の書込経路は作らない）。
 */
@Composable
private fun OccurrenceSection(state: AppState, resolved: DocSectionResolved) {
    val section = resolved.section
    val occ = resolved.occurrence
    if (occ == null) {
        Text(resolved.reason ?: "同じ ID の出現がありません", fontSize = 11.sp, color = Palette.WarnOrange)
        return
    }
    val doc = state.ensureDoc(occ.document)
    doc.version
    state.indexVersion
    val root = doc.root
    val map = root?.let { getAt(it, occ.path) as? YNode.Map }
    val objectField = remember(doc, doc.version) {
        root?.let { r -> doc.schema?.let { Views.fieldAt(it, r, occ.path) } }
    }
    Column(
        Modifier.fillMaxWidth().background(Palette.Surface, PanelShape)
            .border(1.dp, Palette.Border, PanelShape).padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(
                    occ.document.value,
                    occ.schemaId?.let { "スキーマ: $it" },
                    occ.path.pretty().takeIf { it != "(root)" },
                ).joinToString(" ／ "),
                fontSize = 10.sp, color = Palette.SubText, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { state.navigateToOccurrence(occ) }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "開く", Modifier.size(16.dp), tint = Palette.Primary)
            }
        }
        if (root == null) {
            Text(
                "YAML を解析できないため編集できません: ${doc.parseError ?: "不明なエラー"}",
                fontSize = 11.sp, color = Palette.ErrorRed,
            )
            return@Column
        }
        val localIds = remember(doc, doc.version) {
            val documentId = IdIndex.documentIdOf(root, state.relPath(doc.file))
            DocScanner.scan(root, state.documentOf(doc.file), doc.schema, documentId).first
        }
        val ctx = remember(doc, doc.version, state.indexVersion, state.readOnlyMode, doc.draftBroken) {
            FormCtx(state, doc, localIds, baseDepth = occ.path.size)
        }
        val include = section.fieldsInclude.ifEmpty { null }
        val exclude = section.fieldsExclude.toSet()
        when {
            map == null -> Text(
                "この要素が見つかりません（構造が変わった可能性があります）",
                fontSize = 11.sp, color = Palette.WarnOrange,
            )
            objectField?.type == FieldType.OBJECT -> ObjectFields(ctx, objectField.fields, map, occ.path, include, exclude)
            else -> {
                val slot = if (occ.path.isEmpty()) RootSlot(doc) else slotAt(root, occ.path)
                if (slot != null) GenericEditor(ctx, slot, occ.path, refHint = true)
                else Text("この要素を編集できません", fontSize = 11.sp, color = Palette.WarnOrange)
            }
        }
    }
}

/**
 * 表の区画（`grid: {view, params}`。複合文書ビュー 段階 1）。受け取る値は開いた文書の値で、行は台帳のビュータブと同じ表で描く
 * （セルの編集は表の経路＝原本の木の直接編集）。表は遅延描画でフォームの縦スクロールの中にあるので、行数に応じた高さを与える。
 */
@Composable
private fun GridSection(state: AppState, resolved: DocSectionResolved) {
    val grid = resolved.section.grid ?: return
    val rows = resolved.gridRows
    val view = resolved.gridView
    if (rows == null || view == null) {
        Text("表「${grid.view}」を解決できません: ${resolved.reason ?: "不明"}", fontSize = 11.sp, color = Palette.WarnOrange)
        return
    }
    val params = resolved.gridParams.orEmpty().entries.joinToString(", ") { (k, v) -> "$k = $v" }
    Text(
        "表: ${view.name}" + (if (params.isNotEmpty()) "（$params）" else "") + "  ${rows.size} 行",
        fontSize = 10.sp, color = Palette.SubText,
    )
    Spacer(Modifier.height(4.dp))
    val height = (40 + rows.size.coerceIn(1, 12) * 46).dp
    Box(Modifier.fillMaxWidth().height(height).border(1.dp, Palette.Border, PanelShape)) {
        LocalFormSlots.current.embeddedGrid(state, view, rows)
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
internal fun AttachedBacklinks(ctx: FormCtx, path: YPath) {
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
