package ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.AppState
import app.OpenDocument
import core.Views
import index.DocScanner
import index.IdEntry
import index.IdIndex
import model.YNode
import model.getAt
import model.pretty
import schema.FieldType
import validate.Validator

/**
 * ID横ぐしビュー（R1-05）。
 * 同一論理IDの全出現・その要素からの参照・被参照・カバレッジ充足を1画面に集約する。
 * 表示は索引（保存済み状態）に基づく。
 */
@Composable
fun IdCrossView(state: AppState) {
    state.indexVersion
    var query by remember { mutableStateOf("") }
    val ids = remember(state.indexVersion, query) {
        state.index.entries.keys
            .filter { query.isBlank() || it.contains(query, ignoreCase = true) }
            .sorted()
            .take(300)
    }
    Row(Modifier.fillMaxSize().background(Palette.EditorBg)) {
        // ---------------- 左: ID一覧 ----------------
        Column(Modifier.width(260.dp).fillMaxHeight().background(Palette.PanelBg)) {
            Text("ID横ぐしビュー", fontWeight = FontWeight.Bold, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            CompactTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("IDを検索…", fontSize = 12.sp, color = Palette.SubText) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            )
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxSize()) {
                val listState = rememberLazyListState()
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(ids, key = { it }) { id ->
                        val selected = state.crossViewId == id
                        val occurrences = state.index.occurrences(id)
                        Column(
                            Modifier.fillMaxWidth()
                                .background(if (selected) Palette.Selected else Color.Transparent)
                                .clickable { state.openCrossView(id) }
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                        ) {
                            Text(id, fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold)
                            Text(
                                listOfNotNull(occurrences.firstOrNull()?.title, "${occurrences.size} 出現")
                                    .joinToString(" — "),
                                fontSize = 10.sp, color = Palette.SubText, maxLines = 1,
                            )
                        }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.Border))
        // ---------------- 右: 詳細 ----------------
        val selectedId = state.crossViewId
        if (selectedId == null || state.index.occurrences(selectedId).isEmpty()) {
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text("左の一覧からIDを選択してください", fontSize = 13.sp, color = Palette.SubText)
                Text("同一論理IDの全出現・参照・被参照・カバレッジをまとめて表示します",
                    fontSize = 11.sp, color = Palette.SubText)
            }
        } else {
            IdCrossDetail(state, selectedId)
        }
    }
}

@Composable
private fun IdCrossDetail(state: AppState, id: String) {
    val occurrences = state.index.occurrences(id)
    val backlinks = remember(id, state.indexVersion) { state.index.backlinksTo(setOf(id)) }
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp)) {
            Text(id, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            occurrences.firstOrNull()?.title?.let {
                Text(it, fontSize = 12.sp, color = Palette.SubText)
            }
            Spacer(Modifier.height(12.dp))

            // ---------------- 出現（段階的詳細化の各文書） ----------------
            CrossSection("出現（${occurrences.size}）")
            occurrences.forEach { entry -> OccurrenceCard(state, entry) }

            // ---------------- カバレッジ ----------------
            // activeFrom のフェーズ判定には「IDを定義している文書」のルートを渡す（未到達ルールは灰色で区別表示）
            val statuses = remember(id, state.indexVersion) {
                occurrences.mapNotNull { e -> state.schemas.firstOrNull { it.id == e.schemaId }?.let { it to e } }
                    .distinctBy { it.first.id }
                    .flatMap { (sc, e) -> Validator.coverageStatuses(id, sc, state.index, state.ensureDoc(e.file).root) }
            }
            if (statuses.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                val pending = statuses.count { !it.active }
                CrossSection(
                    "カバレッジ（${statuses.count { it.active && it.satisfied }}/${statuses.count { it.active }} 充足" +
                        (if (pending > 0) "、$pending 件はフェーズ未到達" else "") + "）"
                )
                statuses.forEach { status ->
                    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (status.active && status.satisfied) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null, Modifier.size(14.dp),
                            tint = when {
                                !status.active -> Palette.SubText
                                status.satisfied -> Palette.OkGreen
                                else -> Palette.WarnOrange
                            },
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                !status.active ->
                                    "フェーズ未到達（${status.rule.activeFrom} から判定。現在 ${Validator.coverageCountLabel(status.rule)} ${status.count} 件／必要 ${status.rule.minCount} 件）" +
                                        (status.rule.description?.let { " — $it" } ?: "")
                                status.satisfied ->
                                    "${Validator.coverageCountLabel(status.rule)} ${status.count} 件（必要 ${status.rule.minCount} 件）" +
                                        (status.rule.description?.let { " — $it" } ?: "")
                                else -> Validator.coverageMessage(status)
                            },
                            fontSize = 12.sp,
                            color = when {
                                !status.active -> Palette.SubText
                                status.satisfied -> Palette.Text
                                else -> Palette.WarnOrange
                            },
                        )
                    }
                }
            }

            // ---------------- 被参照 ----------------
            Spacer(Modifier.height(12.dp))
            CrossSection("被参照（${backlinks.size}）")
            if (backlinks.isEmpty()) Text("このIDへの参照はありません", fontSize = 11.sp, color = Palette.SubText)
            backlinks.forEach { bl ->
                // 参照を書いた要素の ID・タイトル（表示解決の結果。R10-01）を主表示にする
                val source = remember(bl, state.indexVersion) { state.index.sourceEntryOf(bl) }
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { state.openFile(bl.file, bl.path) }
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.ArrowForward, contentDescription = null, Modifier.size(13.dp), tint = Palette.SubText)
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(
                            (source?.let { listOfNotNull(it.id, it.title).joinToString(" — ") }
                                ?: state.relPath(bl.file)) + (bl.relation?.let { "（関係: $it）" } ?: ""),
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "${state.relPath(bl.file)} ／ ${bl.viaKey} ／ ${bl.path.pretty()}",
                            fontSize = 10.sp, color = Palette.SubText,
                        )
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/**
 * 1 出現のカード。要素をその場で編集できるフォーム（R7-04）と、その要素サブツリーからの参照を表示する。
 * 文書は `state.ensureDoc` でdocCacheへ取り込み、編集は他のフォームと同じ Slot＋markEdited() の経路を通す
 * （`_views` のようなビュー定義は不要。ビュー専用の書込経路は作らない。ARCHITECTURE §4.14 の原則をここにも適用）。
 */
@Composable
private fun OccurrenceCard(state: AppState, entry: IdEntry) {
    val doc = state.ensureDoc(entry.file)
    doc.version
    state.indexVersion
    val root = doc.root

    val refsOut = remember(entry, state.indexVersion) {
        state.index.refs.filter {
            it.file == entry.file && it.path.size >= entry.path.size &&
                it.path.subList(0, entry.path.size) == entry.path
        }
    }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .border(1.dp, Palette.Border, PanelShape)
            .background(Palette.Surface, PanelShape)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    listOfNotNull(entry.title, state.relPath(entry.file)).joinToString(" — "),
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    listOfNotNull(
                        "文書: ${entry.documentId}",
                        entry.schemaId?.let { "スキーマ: $it" },
                        entry.path.pretty().takeIf { it != "(root)" },
                    ).joinToString(" ／ "),
                    fontSize = 10.sp, color = Palette.SubText,
                )
            }
            IconButton(onClick = { state.navigateToOccurrence(entry) }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.ArrowForward, contentDescription = "開く", Modifier.size(16.dp), tint = Palette.Primary)
            }
        }
        Spacer(Modifier.height(6.dp))
        if (root == null) {
            Text(
                "YAML を解析できないため編集できません: ${doc.parseError ?: "不明なエラー"}",
                fontSize = 11.sp, color = Palette.ErrorRed,
            )
        } else {
            OccurrenceForm(state, doc, root, entry)
        }
        if (refsOut.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("この要素からの参照", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Palette.SubText)
            refsOut.forEach { ref ->
                Row(
                    Modifier.fillMaxWidth().clickable { state.navigateToId(ref.targetId) }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.ArrowForward, contentDescription = null, Modifier.size(12.dp), tint = Palette.Primary)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        ref.targetId + (ref.relation?.let { "（関係: $it）" } ?: "") + " ／ ${ref.viaKey}",
                        fontSize = 11.sp, color = Palette.Primary,
                    )
                }
            }
        }
    }
}

/**
 * 出現1件をその場で編集するフォーム本体。
 * スキーマがあれば `Views.fieldAt` で entry.path のフィールド定義（object を期待）を解決し、
 * `ObjectFields` にそのまま委譲する（ビュー機構のパス解決・フォームの行UIを複製しない）。
 * スキーマなし、またはフィールド解決が object にならない場合は汎用エディタへフォールバックする。
 */
@Composable
private fun OccurrenceForm(state: AppState, doc: OpenDocument, root: YNode, entry: IdEntry) {
    val localIds = remember(doc, doc.version) {
        val documentId = IdIndex.documentIdOf(root, state.relPath(doc.file))
        DocScanner.scan(root, doc.file, doc.schema, documentId).first
    }
    // 既定展開の深さは文書ルートではなく表示対象要素（entry.path）を基準に判定する
    val ctx = remember(doc, doc.version, state.indexVersion, state.readOnlyMode) {
        FormCtx(state, doc, localIds, baseDepth = entry.path.size)
    }
    val schemaDef = doc.schema
    val map = getAt(root, entry.path) as? YNode.Map
    val objectField = remember(schemaDef, doc.version) {
        schemaDef?.let { Views.fieldAt(it, root, entry.path) }
    }

    when {
        map == null -> Text(
            "この要素が見つかりません（構造が変わった可能性があります）",
            fontSize = 11.sp, color = Palette.WarnOrange,
        )
        schemaDef != null && objectField?.type == FieldType.OBJECT ->
            ObjectFields(ctx, objectField.fields, map, entry.path)
        else -> {
            val slot = if (entry.path.isEmpty()) RootSlot(doc) else slotAt(root, entry.path)
            if (slot != null) GenericEditor(ctx, slot, entry.path, refHint = true)
            else Text("この要素を編集できません", fontSize = 11.sp, color = Palette.WarnOrange)
        }
    }
}

@Composable
private fun CrossSection(title: String) {
    Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.SubText,
        modifier = Modifier.padding(vertical = 4.dp))
}
