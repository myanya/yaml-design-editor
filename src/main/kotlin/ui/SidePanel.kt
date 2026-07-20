package ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.Icon
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.AppState
import index.DocScanner
import index.isAllowedBy
import model.pretty
import schema.currentPhaseValue
import validate.Severity
import validate.Validator

/**
 * 右サイドパネル。
 * - 検証: スキーマ違反・未解決参照・ID 重複（クリックで該当箇所へジャンプ）
 * - 参照: このドキュメントからの参照一覧（クリックで定義へジャンプ）
 * - 被参照: このドキュメントの ID を参照している箇所（クリックで参照元へジャンプ）
 *   ※ 被参照は保存済みの索引に基づくため、未保存の編集は反映されない
 */
@Composable
fun SidePanel(state: AppState, width: androidx.compose.ui.unit.Dp = 320.dp) {
    Column(Modifier.width(width).fillMaxHeight().background(Palette.PanelBg)) {
        val doc = state.openDoc
        if (doc == null) {
            Text(
                "ファイルを開くと検証結果と参照情報が表示されます",
                Modifier.padding(16.dp),
                fontSize = 12.sp, color = Palette.SubText,
            )
            return@Column
        }
        // 再コンポーズトリガ
        doc.version
        state.indexVersion

        val issues = remember(doc, doc.version, state.indexVersion) {
            Validator.validate(doc.root, doc.parseError, doc.file, doc.schema, state.index)
        }
        val scanned = remember(doc, doc.version) {
            val root = doc.root
            if (root == null) emptyList<index.IdEntry>() to emptyList<index.RefEntry>()
            else DocScanner.scan(root, doc.file, doc.schema, state.index.documentIdFor(doc.file, root))
        }
        val (localIds, localRefs) = scanned
        val backlinks = remember(doc, doc.version, state.indexVersion) {
            state.index.backlinksTo(localIds.map { it.id }.toSet())
                .filter { it.file != doc.file }
        }
        // フェーズ進捗（phases 宣言のあるスキーマのみ。未来フェーズの未記入はエラーではなく進捗で見せる）
        val phases = doc.schema?.phases
        val phaseProgress = remember(doc, doc.version) {
            phases?.let { doc.schema?.let { schema -> Validator.phaseProgress(doc.root, schema) } }
        }
        val currentPhase = remember(doc, doc.version) {
            phases?.let { p -> doc.schema?.currentPhaseValue(doc.root)?.takeIf { it in p.order } ?: p.order.firstOrNull() }
        }
        var selectedTab by remember { mutableStateOf(0) }
        TabRow(selectedTabIndex = selectedTab, containerColor = Palette.PanelBg, contentColor = Palette.Primary) {
            Tab(selectedTab == 0, { selectedTab = 0 }, text = { Text("検証 ${issues.size + state.schemaErrors.size}", fontSize = 11.sp) })
            Tab(selectedTab == 1, { selectedTab = 1 }, text = { Text("参照 ${localRefs.size}", fontSize = 11.sp) })
            Tab(selectedTab == 2, { selectedTab = 2 }, text = { Text("被参照 ${backlinks.size}", fontSize = 11.sp) })
        }

        Box(Modifier.fillMaxSize()) {
            val listState = rememberLazyListState()
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 8.dp)) {

                // ---------------- 検証 ----------------
                if (selectedTab == 0) {
                item { SectionHeader("検証（${issues.size + state.schemaErrors.size}）") }
                // フェーズ進捗（phases 宣言のあるスキーマのみ。未来フェーズの未記入はエラーではなく進捗で見せる）
                if (phases != null && phaseProgress != null) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                            Text(
                                "フェーズ: ${currentPhase ?: "-"}（${phases.order.joinToString(" → ")}）",
                                fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                            )
                            phaseProgress.filter { it.total > 0 }.forEach { p ->
                                val reached = phases.order.indexOf(p.phase) <= phases.order.indexOf(currentPhase)
                                Text(
                                    "・${p.phase}: ${p.filled}/${p.total} 記入" + if (!reached) "（未到達）" else "",
                                    fontSize = 11.sp,
                                    color = if (p.filled >= p.total) Palette.OkGreen else Palette.SubText,
                                )
                            }
                        }
                    }
                }
                items(state.schemaErrors.size) { i ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Warning, null, Modifier.size(14.dp), tint = Palette.ErrorRed)
                        Spacer(Modifier.width(6.dp))
                        Text(state.schemaErrors[i], fontSize = 12.sp, color = Palette.ErrorRed)
                    }
                }
                if (issues.isEmpty() && state.schemaErrors.isEmpty()) {
                    item {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.CheckCircle, contentDescription = null,
                                Modifier.size(14.dp), tint = Palette.OkGreen,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("問題は見つかりませんでした", fontSize = 12.sp, color = Palette.OkGreen)
                        }
                    }
                }
                items(issues.size) { i ->
                    val issue = issues[i]
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                state.editorTab = 0
                                state.highlightPath = issue.path
                            }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            Modifier.size(14.dp).padding(top = 1.dp),
                            tint = if (issue.severity == Severity.ERROR) Palette.ErrorRed else Palette.WarnOrange,
                        )
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(issue.message, fontSize = 12.sp)
                            Text(issue.path.pretty(), fontSize = 10.sp, color = Palette.SubText)
                        }
                    }
                }
                }

                // ---------------- 参照 ----------------
                if (selectedTab == 1) {
                item { SectionHeader("参照（${localRefs.size}）") }
                if (localRefs.isEmpty()) {
                    item { EmptyNote("このドキュメントからの参照はありません") }
                }
                items(localRefs.size) { i ->
                    val ref = localRefs[i]
                    val resolvable = state.index.resolve(ref).isNotEmpty() ||
                            localIds.any { it.id == ref.targetId && it.isAllowedBy(ref) }
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = resolvable) { state.navigateToId(ref.targetId) }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.ArrowForward, contentDescription = null,
                            Modifier.size(13.dp),
                            tint = if (resolvable) Palette.Primary else Palette.ErrorRed,
                        )
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(
                                ref.targetId,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (resolvable) Palette.Primary else Palette.ErrorRed,
                            )
                            Text(
                                "${ref.viaKey}${ref.relation?.let { "（関係: $it）" } ?: ""} ／ ${ref.path.pretty()}",
                                fontSize = 10.sp, color = Palette.SubText,
                            )
                        }
                    }
                }
                }

                // ---------------- 被参照 ----------------
                if (selectedTab == 2) {
                item { SectionHeader("被参照（${backlinks.size}）") }
                if (backlinks.isEmpty()) {
                    item { EmptyNote("他ファイルからの参照はありません（保存時に更新）") }
                }
                items(backlinks.size) { i ->
                    val bl = backlinks[i]
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                // 参照元の位置へジャンプ（参照値そのものの位置を指す）
                                state.openFile(bl.file, bl.path)
                                state.editorTab = 0
                            }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.ArrowForward, contentDescription = null,
                            Modifier.size(13.dp), tint = Palette.SubText,
                        )
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(
                                "${bl.targetId} ← ${state.relPath(bl.file)}",
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${bl.viaKey}${bl.relation?.let { "（関係: $it）" } ?: ""} ／ ${bl.path.pretty()}",
                                fontSize = 10.sp, color = Palette.SubText,
                            )
                        }
                    }
                }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Palette.SubText,
    )
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text,
        Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        fontSize = 11.sp, color = Palette.SubText,
    )
}
