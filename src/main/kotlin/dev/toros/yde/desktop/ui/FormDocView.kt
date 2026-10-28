package dev.toros.yde.desktop.ui

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
import dev.toros.yde.desktop.AppState
import dev.toros.yde.app.core.DocBacklinkRow
import dev.toros.yde.app.core.DocBacklinksDef
import dev.toros.yde.app.core.DocSectionResolved
import dev.toros.yde.app.core.DocViewDef
import dev.toros.yde.app.core.Views
import dev.toros.yde.app.index.IdEntry
import dev.toros.yde.semantic.schema.Relations
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.semantic.schema.FieldType

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
                val embed = section.embed
                when {
                    flowView != null -> EmbeddedFlowPane(state, doc, flowView)
                    embed != null -> EmbeddedExtensionPane(state, embed)
                    else -> BacklinkSectionTable(state, sectionResolved)
                }
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

/** 文書ビューの区画に埋め込んだ拡張の種類のビュー（`chart: {view}` など。ADR-0014）。フロー図の埋め込みと同じ位置づけ。 */
@Composable
private fun EmbeddedExtensionPane(state: AppState, embed: dev.toros.yde.semantic.VirtualDocEmbed) {
    val ws = state.workspace ?: return
    val loaded = remember(ws, state.indexVersion) { Views.load(ws, state.schemas) }
    val def = loaded.extensionView(embed.view)?.takeIf { it.viewKind == embed.kind }
    if (def == null) Text("${embed.kind}「${embed.view}」が見つかりません", fontSize = 11.sp, color = Palette.WarnOrange)
    else ExtensionViewPane(state, loaded, def, ViewPlace.SECTION)
}
