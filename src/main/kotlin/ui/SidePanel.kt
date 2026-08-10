package ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.OpenDocument
import index.DocScanner
import index.isAllowedBy
import model.YNode
import model.pretty
import schema.currentPhaseValue
import validate.Issue
import validate.Severity
import validate.Validator

/**
 * 右サイドパネル＝IDインスペクタ（R12-03）。「この文書・このIDについて知る」を1箇所に集める。
 * - 検証: スキーマ違反・未解決参照・ID 重複（クリックで該当箇所へジャンプ）・フェーズ進捗
 * - 関係: この文書からの参照と、この文書の ID への被参照（クリックでジャンプ）
 * - 出現: 文書ルートIDの同一論理IDの全出現とカバレッジ充足。全出現のその場編集は ID横ぐしビューへ展開する
 *   ※ 関係・出現は保存済みの索引に基づくため、未保存の編集は反映されない
 */
@Composable
fun SidePanel(state: AppState, width: androidx.compose.ui.unit.Dp = 320.dp) {
    Column(Modifier.width(width).fillMaxHeight().background(Palette.PanelBg)) {
        val doc = state.openDoc
        if (doc == null) {
            Text(
                "ファイルを開くと検証結果・参照・同一IDの出現が表示されます",
                Modifier.padding(16.dp),
                fontSize = 12.sp, color = Palette.SubText,
            )
            return@Column
        }
        // 再コンポーズトリガ
        doc.version
        state.indexVersion

        val issues = remember(doc, doc.version, state.indexVersion, state.editVersion) {
            Validator.validate(
                doc.root, doc.parseError, doc.file, doc.schema, state.overlayIndex(),
                indexed = state.isIndexed(doc.file), workspace = state.workspace, allSchemas = state.schemas,
                loader = state.unsavedFirstLoader(),
            )
        }
        // ビュー定義文書はリント（ビュー→スキーマの整合検査）もここに出す。編集中ツリーで判定するため
        // 保存前でもその場で指摘される（グリッドのバナーと同じ内容。UX-43）
        val viewDefErrors = remember(doc, doc.version, state.schemas) {
            if (state.layout.zoneOf(state.relPath(doc.file)) == schema.WorkspaceLayout.Zone.VIEWS) {
                core.Views.lintDocument(doc.root, doc.file, state.schemas)
            } else emptyList()
        }
        val scanned = remember(doc, doc.version) {
            val root = doc.root
            if (root == null) emptyList<index.IdEntry>() to emptyList<index.RefEntry>()
            else DocScanner.scan(root, doc.file, doc.schema, state.overlayIndex().documentIdFor(doc.file, root))
        }
        val (localIds, localRefs) = scanned
        val backlinks = remember(doc, doc.version, state.indexVersion, state.editVersion) {
            state.overlayIndex().backlinksTo(localIds.map { it.id }.toSet())
                .filter { it.file != doc.file }
        }
        // 出現タブの対象ID: 文書ルートのID（段階的詳細化の単位。E-09 と同じ基準）
        val rootId = remember(doc, doc.version) {
            localIds.firstOrNull { it.path.isEmpty() }?.id ?: localIds.firstOrNull()?.id
        }
        val occurrences = remember(rootId, state.indexVersion, state.editVersion) {
            rootId?.let { state.overlayIndex().occurrences(it) }.orEmpty()
        }
        // カバレッジ（V-05）: activeFrom のフェーズ判定には「IDを定義している文書」のルートを渡す
        val coverage = remember(rootId, state.indexVersion, state.editVersion) {
            val id = rootId
            if (id == null) emptyList()
            else occurrences
                .mapNotNull { e -> state.schemas.firstOrNull { it.id == e.schemaId }?.let { it to e } }
                .distinctBy { it.first.id }
                .flatMap { (sc, e) -> Validator.coverageStatuses(id, sc, state.overlayIndex(), state.ensureDoc(e.file).root) }
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
            Tab(selectedTab == 0, { selectedTab = 0 }, text = { Text("検証 ${issues.size + state.schemaErrors.size + viewDefErrors.size}", fontSize = 11.sp) })
            Tab(selectedTab == 1, { selectedTab = 1 }, text = { Text("関係 ${localRefs.size + backlinks.size}", fontSize = 11.sp) })
            Tab(selectedTab == 2, { selectedTab = 2 }, text = { Text("出現 ${occurrences.size}", fontSize = 11.sp) })
        }

        Box(Modifier.fillMaxSize()) {
            val listState = rememberLazyListState()
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 8.dp)) {

                // ---------------- 検証 ----------------
                if (selectedTab == 0) {
                item { SectionHeader("検証（${issues.size + state.schemaErrors.size + viewDefErrors.size}）") }
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
                // ビュー定義のリント指摘（UX-43。未保存の編集にもその場で追随する）
                items(viewDefErrors.size) { i ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Warning, null, Modifier.size(14.dp), tint = Palette.ErrorRed)
                        Spacer(Modifier.width(6.dp))
                        Text("ビュー定義: ${viewDefErrors[i]}", fontSize = 12.sp, color = Palette.ErrorRed)
                    }
                }
                if (issues.isEmpty() && state.schemaErrors.isEmpty() && viewDefErrors.isEmpty()) {
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
                    IssueRow(state, doc, issues[i])
                }
                }

                // ---------------- 関係（参照・被参照） ----------------
                if (selectedTab == 1) {
                item { SectionHeader("参照（${localRefs.size}）") }
                if (localRefs.isEmpty()) {
                    item { EmptyNote("このドキュメントからの参照はありません") }
                }
                items(localRefs.size) { i ->
                    val ref = localRefs[i]
                    val resolvable = state.overlayIndex().resolve(ref).isNotEmpty() ||
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
                                // 関係レジストリの表示ラベル（R20-02）・属性付き参照の属性注記（R20-03）
                                ref.viaKey +
                                    (schema.Relations.labelOf(state.relations, ref.relation)?.let { "（関係: $it）" } ?: "") +
                                    (ref.attributeSummary()?.let { "（$it）" } ?: "") +
                                    " ／ ${ref.path.pretty()}",
                                fontSize = 10.sp, color = Palette.SubText,
                            )
                        }
                    }
                }
                item { SectionHeader("被参照（${backlinks.size}）") }
                if (backlinks.isEmpty()) {
                    item { EmptyNote("他ファイルからの参照はありません（保存時に更新）") }
                }
                items(backlinks.size) { i ->
                    val bl = backlinks[i]
                    // 参照を書いた要素の ID・タイトル（表示解決の結果。R10-01）を主表示にする
                    val source = remember(bl, state.indexVersion, state.editVersion) { state.overlayIndex().sourceEntryOf(bl) }
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
                                "${bl.targetId} ← " + (source?.let { listOfNotNull(it.id, it.title).joinToString(" — ") }
                                    ?: state.relPath(bl.file)),
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                // 被参照方向は inverseLabel（R20-02）＋属性注記（R20-03）
                                "${state.relPath(bl.file)} ／ " + bl.viaKey +
                                    (schema.Relations.inverseLabelOf(state.relations, bl.relation)?.let { "（関係: $it）" } ?: "") +
                                    (bl.attributeSummary()?.let { "（$it）" } ?: "") +
                                    " ／ ${bl.path.pretty()}",
                                fontSize = 10.sp, color = Palette.SubText,
                            )
                        }
                    }
                }
                }

                // ---------------- 出現（同一論理ID。R12-03） ----------------
                if (selectedTab == 2) {
                if (rootId == null) {
                    item { EmptyNote("この文書にはルートIDがありません（id を持つ文書で使えます）") }
                } else {
                    item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                            Text(rootId, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            occurrences.firstOrNull()?.title?.let {
                                Text(it, fontSize = 11.sp, color = Palette.SubText)
                            }
                            TextButton(onClick = { state.openCrossView(rootId) }) {
                                Text("ID横ぐしビューで全出現を編集", fontSize = 11.sp)
                            }
                        }
                    }
                    item { SectionHeader("出現（${occurrences.size}）") }
                    if (occurrences.isEmpty()) {
                        item { EmptyNote("索引に出現がありません（保存で索引が更新されます）") }
                    }
                    items(occurrences.size) { i ->
                        val entry = occurrences[i]
                        val isHere = entry.file == doc.file
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = !isHere) { state.navigateToOccurrence(entry) }
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.ArrowForward, contentDescription = null,
                                Modifier.size(13.dp),
                                tint = if (isHere) Palette.SubText else Palette.Primary,
                            )
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text(
                                    (entry.title ?: entry.id) + if (isHere) "（この文書）" else "",
                                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    listOfNotNull(state.relPath(entry.file), entry.schemaId).joinToString(" ／ "),
                                    fontSize = 10.sp, color = Palette.SubText,
                                )
                            }
                        }
                    }
                    val statuses = coverage
                    if (statuses.isNotEmpty()) {
                        val pendingCount = statuses.count { !it.active }
                        item {
                            SectionHeader(
                                "カバレッジ（${statuses.count { it.active && it.satisfied }}/${statuses.count { it.active }} 充足" +
                                    (if (pendingCount > 0) "、$pendingCount 件はフェーズ未到達" else "") + "）"
                            )
                        }
                        items(statuses.size) { i ->
                            val status = statuses[i]
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (status.active && status.satisfied) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null, Modifier.size(13.dp),
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
                                            "フェーズ未到達（${status.rule.activeFrom} から判定）" +
                                                (status.rule.description?.let { " — $it" } ?: "")
                                        status.satisfied ->
                                            "${Validator.coverageCountLabel(status.rule)} ${status.count} 件（必要 ${status.rule.minCount} 件）" +
                                                (status.rule.description?.let { " — $it" } ?: "")
                                        else -> Validator.coverageMessage(status)
                                    },
                                    fontSize = 11.sp,
                                    color = when {
                                        !status.active -> Palette.SubText
                                        status.satisfied -> Palette.Text
                                        else -> Palette.WarnOrange
                                    },
                                )
                            }
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

/**
 * 検証タブの指摘1件（R16-03c / UX-13）。整合検証の指摘（来歴つき）はクリックで
 * 「エラー箇所へ／導出規則へ／入力値へ／導出値を適用」の選択メニューを出す（計算セルの来歴と同じ流儀）。
 * 来歴のない指摘は従来どおり該当箇所へ直接ジャンプする。
 */
@Composable
private fun IssueRow(state: AppState, doc: OpenDocument, issue: Issue) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        val hasMenu = issue.derivation != null || issue.relatedIds.isNotEmpty()
        Row(
            Modifier.fillMaxWidth()
                .clickable { if (hasMenu) menuOpen = true else state.navigateToPath(issue.path) }
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                Modifier.size(14.dp).padding(top = 1.dp),
                tint = when (issue.severity) {
                    Severity.ERROR -> Palette.ErrorRed
                    Severity.WARNING -> Palette.WarnOrange
                    Severity.INFO -> Palette.SubText
                },
            )
            Spacer(Modifier.width(6.dp))
            Column {
                Text(issue.message, fontSize = 12.sp)
                Text(issue.path.pretty(), fontSize = 10.sp, color = Palette.SubText)
            }
        }
        if (hasMenu) {
            DropdownMenu(menuOpen, { menuOpen = false }) {
                val d = issue.derivation
                if (d != null) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("エラー箇所へ", fontSize = 12.sp)
                            Text(issue.path.pretty(), fontSize = 10.sp, color = Palette.SubText)
                        }
                    },
                    onClick = { menuOpen = false; state.navigateToPath(issue.path) },
                )
                val sourceFile = d.sourceFile
                if (sourceFile != null) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("導出規則へ" + (d.raw?.let { ": $it" } ?: ""), fontSize = 12.sp)
                                Text(
                                    "${state.relPath(sourceFile)} :: ${d.sourcePath?.pretty()}",
                                    fontSize = 10.sp, color = Palette.SubText,
                                )
                            }
                        },
                        onClick = { menuOpen = false; state.openFile(sourceFile, d.sourcePath) },
                    )
                }
                d.inputs.forEach { input ->
                    val inputFile = input.file
                    val inputPath = input.path
                    if (inputFile != null && inputPath != null) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("入力値へ: {${input.placeholder}} = ${input.value}", fontSize = 12.sp)
                                    Text(
                                        "${state.relPath(inputFile)} :: ${inputPath.pretty()}",
                                        fontSize = 10.sp, color = Palette.SubText,
                                    )
                                }
                            },
                            onClick = { menuOpen = false; state.openFile(inputFile, inputPath) },
                        )
                    }
                }
                if (!state.readOnlyMode) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("導出値を適用: ${d.expected}", fontSize = 12.sp, color = Palette.Primary)
                                Text("未保存の編集として書き込みます（Undo可）", fontSize = 10.sp, color = Palette.SubText)
                            }
                        },
                        onClick = { menuOpen = false; applyDerivedValue(state, doc, issue, d.expected) },
                    )
                }
                }
                // 関連ID（UX-15 の不足案内など）: 各IDの定義へジャンプできる
                issue.relatedIds.take(10).forEach { id ->
                    DropdownMenuItem(
                        text = { Text("定義へ: $id", fontSize = 12.sp) },
                        onClick = { menuOpen = false; state.navigateToId(id) },
                    )
                }
                if (issue.relatedIds.size > 10) {
                    Text(
                        "…他 ${issue.relatedIds.size - 10} 件",
                        Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        fontSize = 10.sp, color = Palette.SubText,
                    )
                }
            }
        }
    }
}

/**
 * 「導出値を適用」（R16-03c）: 指摘の座標へ導出値を通常の編集経路（Slot＋markEdited）で書き込む。
 * 未保存編集として積まれるため Undo・差分・保存の既存機構がそのまま効く（ビュー専用の書込経路は作らない）。
 */
private fun applyDerivedValue(state: AppState, doc: OpenDocument, issue: Issue, expected: String) {
    val root = doc.root ?: return
    val slot = slotAt(root, issue.path) ?: run {
        state.status = "導出値を適用できませんでした（座標に書き込めません: ${issue.path.pretty()}）"
        return
    }
    slot.set(YNode.Scalar(expected))
    doc.markEdited()
    state.status = "導出値を適用しました: $expected（未保存）"
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
