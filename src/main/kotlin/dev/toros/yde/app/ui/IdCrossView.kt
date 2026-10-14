package dev.toros.yde.app.ui

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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.AppState
import dev.toros.yde.app.OpenDocument
import dev.toros.yde.app.core.AspectSection
import dev.toros.yde.app.core.Aspects
import dev.toros.yde.app.core.Views
import dev.toros.yde.app.index.DocScanner
import dev.toros.yde.app.index.IdEntry
import dev.toros.yde.app.index.IdIndex
import dev.toros.yde.app.schema.Display
import dev.toros.yde.semantic.schema.EntityRole
import dev.toros.yde.semantic.schema.Relations
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.RefTarget
import dev.toros.yde.app.validate.Validator
import java.nio.file.Path

/**
 * エンティティページ（R23-02。architecture/graph-query.md「エンティティページ」）。ID横ぐしビュー（R1-05 / R7-04）の改築:
 * 同一論理IDの全出現を「アスペクト合成（エンティティ全体像）」として1画面に集約し、
 * ヘッダ（型・操作メニュー）・関係パネル（両方向）・カバレッジを併置する。
 * 合成は core.Aspects（read_entity / CLI と同一実装）、値の編集は各所有フィールドの原本座標への
 * Slot ルーティング（文書キャッシュのツリー直接編集＝architecture/graph-query.md「エンティティページ」 の既存経路のまま）。
 */
@Composable
fun IdCrossView(state: AppState) {
    state.indexVersion
    val query = state.memory.entityQuery  // 検索語は画面の覚え（タブを離れても残る）
    val ids = remember(state.indexVersion, state.editVersion, query) {
        state.overlayIndex().entries.keys
            .filter { query.isBlank() || it.contains(query, ignoreCase = true) }
            .sorted()
            .take(300)
    }
    Row(Modifier.fillMaxSize().background(Palette.EditorBg)) {
        // ---------------- 左: ID一覧 ----------------
        Column(Modifier.width(260.dp).fillMaxHeight().background(Palette.PanelBg)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("エンティティ", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                // 横ぐし編集は複数文書に及ぶため、このビューから直接保存できるようにする（UX-34）
                val dirty = state.dirtyDocCount()
                TextButton(
                    enabled = dirty > 0,
                    onClick = { state.saveAll() },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) { Text(if (dirty > 0) "すべて保存（$dirty）" else "すべて保存", fontSize = 11.sp) }
            }
            CompactTextField(
                value = query,
                onValueChange = { state.memory.entityQuery = it },
                singleLine = true,
                placeholder = { Text("IDを検索…") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            )
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxSize()) {
                val listState = rememberLazyListState()
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(ids, key = { it }) { id ->
                        val selected = state.crossViewId == id
                        val node = state.overlayIndex().graph.entities[id]
                        Column(
                            Modifier.fillMaxWidth()
                                .background(if (selected) Palette.Selected else Color.Transparent)
                                .clickable { state.openCrossView(id) }
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                        ) {
                            Text(id, fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold)
                            Text(
                                listOfNotNull(
                                    node?.entityType?.let { t -> state.entityTypes[t]?.displayLabel ?: t },
                                    node?.title,
                                    "${node?.occurrences?.size ?: 0} 出現",
                                ).joinToString(" — "),
                                fontSize = 10.sp, color = Palette.SubText, maxLines = 1,
                            )
                        }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.Border))
        // ---------------- 右: エンティティページ ----------------
        val selectedId = state.crossViewId
        if (selectedId == null || state.overlayIndex().occurrences(selectedId).isEmpty()) {
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text("左の一覧からIDを選択してください", fontSize = 13.sp, color = Palette.SubText)
                Text("エンティティの全体像（アスペクト合成）・関係・カバレッジをまとめて表示します",
                    fontSize = 11.sp, color = Palette.SubText)
            }
        } else {
            EntityPage(state, selectedId)
        }
    }
}

// ================================================================ ページ本体

@Composable
private fun EntityPage(state: AppState, id: String) {
    // 合成は core.Aspects（read_entity / CLI と同一実装）。loader は文書キャッシュのツリー
    // ＝未保存編集に追随し、編集はそのツリーへの Slot ルーティングで dirty・Undo・保存が効く（architecture/graph-query.md「エンティティページ」）
    val composite = remember(id, state.indexVersion, state.editVersion) {
        Aspects.compose(id, state.overlayIndex(), state.schemas, state.entityTypes) { f -> state.ensureDoc(f).root }
    }
    val occurrences = state.overlayIndex().occurrences(id)
    val showOccurrences = state.memory.entityOccurrencesOpen[id] == true  // 出現別フォームの切替は画面の覚え（ID ごと）
    var opDialog by remember(id) { mutableStateOf<EntityOp?>(null) }
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp)) {
            // ---------------- ヘッダ（ID・型・タイトル・操作） ----------------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(id, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        composite?.typeLabel?.let { label ->
                            Spacer(Modifier.width(8.dp))
                            Text(
                                label, fontSize = 10.sp, color = Palette.Primary,
                                modifier = Modifier
                                    .border(1.dp, Palette.Primary, PanelShape)
                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                        }
                    }
                    composite?.title?.let { Text(it, fontSize = 12.sp, color = Palette.SubText) }
                }
                TextButton(onClick = { state.openGraphLens(id) }) { Text("グラフで見る", fontSize = 11.sp) }
                // 操作メニュー（R21-02 の rename / merge / delete を変更セットプレビュー経由で）
                if (!state.readOnlyMode) {
                    Box {
                        var menuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "操作", Modifier.size(16.dp))
                        }
                        // スキーマに定義した操作（型の操作と、この型を受け手にするアクション。architecture/actions.md）
                        val actions = remember(id, state.indexVersion) { state.actionsForEntity(id) }
                        DropdownMenu(menuOpen, { menuOpen = false }) {
                            actions.forEach { entry ->
                                DropdownMenuItem(text = { Text(entry.label ?: entry.name, fontSize = 12.sp) },
                                    onClick = { menuOpen = false; state.startAction(entry, id) })
                            }
                            if (actions.isNotEmpty()) HorizontalDivider()
                            DropdownMenuItem(text = { Text("改名…", fontSize = 12.sp) },
                                onClick = { menuOpen = false; opDialog = EntityOp.RENAME })
                            DropdownMenuItem(text = { Text("別IDへ統合…", fontSize = 12.sp) },
                                onClick = { menuOpen = false; opDialog = EntityOp.MERGE })
                            DropdownMenuItem(text = { Text("削除…", fontSize = 12.sp) },
                                onClick = { menuOpen = false; opDialog = EntityOp.DELETE })
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // ---------------- 合成全体像（アスペクト見出しつき） ----------------
            if (composite != null && composite.sections.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CrossSection("全体像（${composite.sections.size} アスペクト）")
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { state.memory.entityOccurrencesOpen[id] = !showOccurrences },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Text(if (showOccurrences) "合成表示に戻す" else "出現別フォームで表示", fontSize = 11.sp)
                    }
                }
                if (showOccurrences) {
                    occurrences.forEach { entry -> OccurrenceCard(state, entry) }
                } else {
                    composite.sections.forEach { section -> AspectCard(state, section) }
                    if (composite.unassigned.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        CrossSection("その他の出現（スキーマなし・${composite.unassigned.size}）")
                        composite.unassigned.forEach { entry -> OccurrenceCard(state, entry) }
                    }
                }
            } else {
                // 合成できる出現がない（スキーマなし文書など）: 従来の出現カード表示のまま（退行させない）
                CrossSection("出現（${occurrences.size}）")
                occurrences.forEach { entry -> OccurrenceCard(state, entry) }
            }

            // ---------------- カバレッジ ----------------
            // activeFrom のフェーズ判定には「IDを定義している文書」のルートを渡す（未到達ルールは灰色で区別表示）
            val statuses = remember(id, state.indexVersion, state.editVersion) {
                occurrences.mapNotNull { e -> state.schemas.firstOrNull { it.id == e.schemaId }?.let { it to e } }
                    .distinctBy { it.first.id }
                    .flatMap { (sc, e) ->
                        Validator.ruleStatuses(
                            id, sc, state.overlayIndex(), state.ensureDoc(e.document).root,
                            state.schemas, state.unsavedFirstTree(),
                        )
                    }
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
                            when {
                                status.active && status.satisfied -> Icons.Default.CheckCircle
                                // 確かめられなかった検査（保留・対象外）は、充足とも不足とも違う見せ方にする（P27 段A）
                                status.active && status.undetermined -> Icons.Default.Info
                                else -> Icons.Default.Warning
                            },
                            contentDescription = null, Modifier.size(14.dp),
                            tint = when {
                                !status.active || status.undetermined -> Palette.SubText
                                status.satisfied -> Palette.OkGreen
                                else -> Palette.WarnOrange
                            },
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            status.text,
                            fontSize = 12.sp,
                            color = when {
                                !status.active || status.undetermined -> Palette.SubText
                                status.satisfied -> Palette.Text
                                else -> Palette.WarnOrange
                            },
                        )
                    }
                }
            }

            // ---------------- 関係パネル（両方向） ----------------
            Spacer(Modifier.height(12.dp))
            RelationsPanel(state, id)
            Spacer(Modifier.height(40.dp))
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
    opDialog?.let { op ->
        EntityOpDialog(state, id, op, onDismiss = { opDialog = null })
    }
}

// ================================================================ アスペクトカード

/**
 * 1アスペクト（1出現）のカード。所有フィールドだけを ObjectFields の選別描画（include）で編集する。
 * 文書は `state.ensureDoc` で docCache へ取り込み、編集は他のフォームと同じ Slot＋markEdited() の経路
 * （ビュー専用の書込経路は作らない。architecture/graph-query.md「エンティティページ」）。
 */
/**
 * 出現要素の親ノード情報（UX-68）: パスを上へ辿り、最初にID・タイトルを持つ祖先要素の1行要約を返す。
 * 出現がどの親（テーブル定義・画面一覧など）の配下にあるかをカードで示す。ルート出現は null。
 */
private fun parentSummary(doc: OpenDocument, path: YPath): String? {
    val root = doc.root ?: return null
    if (path.isEmpty()) return null
    var p: YPath = path.dropLast(1)
    while (true) {
        val node = if (p.isEmpty()) root else getAt(root, p)
        if (node is YNode.Map) {
            val fieldDef = doc.schema?.let { Views.fieldAt(it, root, p) }
            val s = Display.summarize(node, doc.schema, fieldDef)
            if (s.id != null || s.title != null) {
                return listOfNotNull(s.id, s.title).joinToString(" ")
            }
        }
        if (p.isEmpty()) return null
        p = p.dropLast(1)
    }
}

@Composable
private fun AspectCard(state: AppState, section: AspectSection) {
    val doc = state.ensureDoc(section.entry.document)
    doc.version
    state.indexVersion
    val root = doc.root
    val map = root?.let { getAt(it, section.entry.path) as? YNode.Map }
    val objectField = remember(doc, doc.version) {
        root?.let { r -> doc.schema?.let { Views.fieldAt(it, r, section.entry.path) } }
    }
    // AI生成へ渡す関連文書（R24-02）: このエンティティの出現・参照先・参照元のファイル
    val relatedFiles = remember(doc, state.indexVersion, state.editVersion) {
        state.entityRelatedFiles(section.entry.id, doc.file)
    }
    // 出現単位の生成対象（R24-02）: 所有キーに限定した object（id 型は除外。残らなければボタンなし）。
    // 呼び名はタイトル・既定指示にそのまま出るため「REQ-001 の要件定義」の形にする（UX試用フィードバック）
    val genField = remember(doc, doc.version, state.indexVersion) {
        doc.schema?.let { sc -> objectField?.let { mergeGenField(sc, it, section.ownedKeys, "${section.entry.id} の${section.label}") } }
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
                    section.label + (if (section.entry.role == EntityRole.DEFINING) "（定義）" else ""),
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    listOfNotNull(
                        section.entry.document.value,
                        section.schemaId?.let { "スキーマ: $it" },
                        section.entry.path.pretty().takeIf { it != "(root)" },
                        // 親ノード情報（UX-68）: この出現がどの親要素の配下かを示す
                        parentSummary(doc, section.entry.path)?.let { "親: $it" },
                    ).joinToString(" ／ "),
                    fontSize = 10.sp, color = Palette.SubText,
                )
            }
            if (!state.readOnlyMode && genField != null && map != null) {
                IconButton(
                    onClick = {
                        state.requestAi(doc, genField, section.entry.path, relatedFiles = relatedFiles, mergeObjectKeys = true)
                    },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = "この出現をAIで作成", Modifier.size(15.dp), tint = Palette.Primary)
                }
            }
            IconButton(onClick = { state.navigateToOccurrence(section.entry) }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "開く", Modifier.size(16.dp), tint = Palette.Primary)
            }
        }
        Spacer(Modifier.height(6.dp))
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
        val ctx = remember(doc, doc.version, state.indexVersion, state.readOnlyMode) {
            FormCtx(state, doc, localIds, baseDepth = section.entry.path.size, aiRelatedFiles = relatedFiles)
        }
        when {
            map == null -> Text(
                "この要素が見つかりません（構造が変わった可能性があります）",
                fontSize = 11.sp, color = Palette.WarnOrange,
            )
            objectField?.type == FieldType.OBJECT && section.ownedKeys.isNotEmpty() ->
                ObjectFields(ctx, objectField.fields, map, section.entry.path, include = section.ownedKeys)
            objectField?.type == FieldType.OBJECT ->
                Text("この出現の所有フィールドはありません（owns の宣言を確認してください）", fontSize = 11.sp, color = Palette.SubText)
            else -> {
                val slot = if (section.entry.path.isEmpty()) RootSlot(doc) else slotAt(root, section.entry.path)
                if (slot != null) GenericEditor(ctx, slot, section.entry.path, refHint = true)
                else Text("この要素を編集できません", fontSize = 11.sp, color = Palette.WarnOrange)
            }
        }
        // 重複所有の注記（検証と同じ観点。値は隠さない。architecture/graph-query.md「エンティティページ」）
        section.fields.filter { it.duplicateOf != null }.forEach { f ->
            Text(
                "「${f.key}」は ${f.duplicateOf!!.first.value} の出現でも所有されています（重複所有）",
                fontSize = 10.sp, color = Palette.WarnOrange,
            )
        }
    }
}

// ================================================================ 関係パネル

/**
 * 関係パネル（R23-02）: レジストリの label / inverseLabel で relation 別に両方向を表示する。
 * 行クリックは相手エンティティのページへ（openCrossView）、矢印アイコンは参照が書かれた座標へ。
 */
@Composable
private fun RelationsPanel(state: AppState, id: String) {
    val overlay = state.overlayIndex()
    val refsOut = remember(id, state.indexVersion, state.editVersion) { overlay.outgoingRefs(id) }
    val backlinks = remember(id, state.indexVersion, state.editVersion) { overlay.backlinksTo(setOf(id)) }
    // 保有と所属（入れ子で書かれた要素。P40）: 名前は項目の relation か、書いている項目のキー名
    val holdings = remember(id, state.indexVersion, state.editVersion) { overlay.graph.holdingsOf(id) }
    val holders = remember(id, state.indexVersion, state.editVersion) { overlay.graph.holdersOf(id) }
    CrossSection("関係（参照 ${refsOut.size}・被参照 ${backlinks.size}・保有 ${holdings.size}・所属 ${holders.size}）")
    if (refsOut.isEmpty() && backlinks.isEmpty() && holdings.isEmpty() && holders.isEmpty()) {
        Text("この要素の参照・被参照・保有・所属はありません", fontSize = 11.sp, color = Palette.SubText)
        return
    }
    // 保有（この要素の中に書かれた要素）: 関係名の label でグループ化。子の役割（定義／詳細化）と書かれた場所を添える
    holdings.groupBy { Relations.labelOf(state.relations, it.relation) ?: it.relation }.forEach { (label, lines) ->
        RelationGroupHeader("◆ 保有: $label（${lines.size}）")
        lines.forEach { h ->
            RelationRow(
                main = h.child.id + (h.child.title?.let { " — $it" } ?: ""),
                sub = (if (h.child.role == EntityRole.DEFINING) "定義" else "詳細化") +
                    " ／ ${h.child.document.value} ／ ${h.child.path.pretty()}",
                onClick = { state.openCrossView(h.child.id) },
                onJump = { state.openFile(h.child.document, h.child.path) },
            )
        }
    }
    // 所属（この要素を中に書いている外側の要素）: inverseLabel でグループ化。親のどの側面の中に書かれているかを添える
    holders.groupBy { Relations.inverseLabelOf(state.relations, it.relation) ?: it.relation }.forEach { (label, lines) ->
        RelationGroupHeader("◇ 所属: $label（${lines.size}）")
        lines.forEach { h ->
            RelationRow(
                main = h.parent.id + (h.parent.title?.let { " — $it" } ?: ""),
                sub = "${Aspects.aspectLabelOf(h.parent, state.schemas, state.entityTypes)} ／ ${h.child.document.value} ／ ${h.child.path.pretty()}",
                onClick = { state.openCrossView(h.parent.id) },
                onJump = { state.openFile(h.child.document, h.child.path) },
            )
        }
    }
    // 参照方向（この要素から）: relation の label でグループ化。文書参照（R21-03）は別グループ
    val outGroups = refsOut.groupBy { r ->
        if (r.targetKind == RefTarget.DOCUMENT) "文書参照"
        else Relations.labelOf(state.relations, r.relation) ?: r.viaKey
    }
    outGroups.forEach { (label, refs) ->
        RelationGroupHeader("→ $label（${refs.size}）")
        refs.forEach { r ->
            val title = overlay.occurrences(r.targetId).firstNotNullOfOrNull { it.title }
            RelationRow(
                main = r.targetId + (title?.let { " — $it" } ?: "") +
                    (r.attributeSummary()?.let { "（$it）" } ?: ""),
                sub = "${r.viaKey} ／ ${r.document.value} ／ ${r.path.pretty()}",
                onClick = {
                    if (r.targetKind == RefTarget.DOCUMENT) {
                        overlay.graph.documents[r.targetId]?.let { state.openFile(it.document) }
                    } else state.openCrossView(r.targetId)
                },
                onJump = { state.openFile(r.document, r.path) },
            )
        }
    }
    // 被参照方向（この要素へ）: inverseLabel でグループ化。主表示は「参照を書いた要素」（R10-01）
    val inGroups = backlinks.groupBy { b ->
        Relations.inverseLabelOf(state.relations, b.relation) ?: b.viaKey
    }
    inGroups.forEach { (label, refs) ->
        RelationGroupHeader("← $label（${refs.size}）")
        refs.forEach { bl ->
            val source = overlay.sourceEntryOf(bl)
            RelationRow(
                main = (source?.let { listOfNotNull(it.id, it.title).joinToString(" — ") }
                    ?: bl.document.value) +
                    (bl.attributeSummary()?.let { "（$it）" } ?: ""),
                sub = "${bl.viaKey} ／ ${bl.document.value} ／ ${bl.path.pretty()}",
                onClick = {
                    if (source != null) state.openCrossView(source.id) else state.openFile(bl.document, bl.path)
                },
                onJump = { state.openFile(bl.document, bl.path) },
            )
        }
    }
}

@Composable
private fun RelationGroupHeader(text: String) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Palette.SubText,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
}

@Composable
private fun RelationRow(main: String, sub: String, onClick: () -> Unit, onJump: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(main, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(sub, fontSize = 10.sp, color = Palette.SubText, maxLines = 1)
        }
        IconButton(onClick = onJump, modifier = Modifier.size(26.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "参照箇所へ", Modifier.size(13.dp), tint = Palette.SubText)
        }
    }
}

// ================================================================ 操作（rename / merge / delete）

private enum class EntityOp { RENAME, MERGE, DELETE }

/**
 * エンティティ操作の確認ダイアログ（R23-02。破壊的操作の確認＝architecture/ui.md「UI シェル」 の許容範囲）。
 * 計画（R21-02 → B-1 でカーネル生成）をプレビューし、適用はセッションの1トランザクション
 * （AppState.applyEntityPlan。Undo 1回で戻る）。
 */
@Composable
private fun EntityOpDialog(state: AppState, id: String, op: EntityOp, onDismiss: () -> Unit) {
    var arg by remember { mutableStateOf("") }
    var deleteOccurrences by remember { mutableStateOf(false) }
    var applyError by remember { mutableStateOf<String?>(null) }
    state.indexVersion
    val plan = remember(op, arg, deleteOccurrences, state.indexVersion) {
        when (op) {
            EntityOp.RENAME -> arg.trim().takeIf { it.isNotBlank() }?.let { state.planEntityRename(id, it) }
            EntityOp.MERGE -> arg.trim().takeIf { it.isNotBlank() }?.let { state.planEntityMerge(id, it, deleteOccurrences) }
            EntityOp.DELETE -> state.planEntityDelete(id)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (op) {
                    EntityOp.RENAME -> "エンティティを改名"
                    EntityOp.MERGE -> "エンティティを統合"
                    EntityOp.DELETE -> "エンティティを削除"
                }
            )
        },
        text = {
            Column(Modifier.width(560.dp).heightIn(max = 480.dp)) {
                Text("対象: $id", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                when (op) {
                    EntityOp.RENAME -> CompactTextField(
                        arg, { arg = it }, singleLine = true,
                        placeholder = { Text("改名先のID") }, modifier = Modifier.fillMaxWidth(),
                    )
                    EntityOp.MERGE -> {
                        CompactTextField(
                            arg, { arg = it }, singleLine = true,
                            placeholder = { Text("統合先のID（既存ID）") }, modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(deleteOccurrences, { deleteOccurrences = it })
                            Text("この要素の出現を削除する（既定は統合先の詳細化として残す）", fontSize = 11.sp)
                        }
                    }
                    EntityOp.DELETE -> Unit
                }
                Spacer(Modifier.height(8.dp))
                when {
                    plan == null -> Text("IDを入力するとプレビューを表示します", fontSize = 12.sp, color = Palette.SubText)
                    plan.isFailure -> Text(plan.exceptionOrNull()?.message ?: "プレビューを生成できません", fontSize = 12.sp, color = Palette.ErrorRed)
                    else -> {
                        val p = plan.getOrThrow()
                        p.summary.forEach { Text(it, fontSize = 12.sp) }
                        p.warnings.forEach { Text("注意: $it", fontSize = 12.sp, color = Palette.WarnOrange) }
                        Spacer(Modifier.height(6.dp))
                        Text("変更されるファイル:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        p.changes.forEach { c ->
                            Text("・${c.document.value}（${c.operations.size} 操作）", fontSize = 12.sp, color = Palette.SubText)
                        }
                    }
                }
                applyError?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 12.sp, color = Palette.ErrorRed)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = plan?.isSuccess == true,
                onClick = {
                    val p = plan?.getOrNull() ?: return@TextButton
                    state.applyEntityPlan(p)
                        .onSuccess {
                            onDismiss()
                            when (op) {
                                EntityOp.RENAME -> state.openCrossView(arg.trim())
                                EntityOp.MERGE -> state.openCrossView(arg.trim())
                                EntityOp.DELETE -> state.crossViewId = null
                            }
                        }
                        .onFailure { applyError = it.message ?: "適用に失敗しました" }
                },
            ) { Text("適用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

// ================================================================ 出現カード（従来表示・スキーマなし出現）

/**
 * 1 出現のカード。要素をその場で編集できるフォーム（R7-04）と、その要素サブツリーからの参照を表示する。
 * 文書は `state.ensureDoc` でdocCacheへ取り込み、編集は他のフォームと同じ Slot＋markEdited() の経路を通す
 * （`_views` のようなビュー定義は不要。ビュー専用の書込経路は作らない。architecture/views.md「ビュー機構」 の原則をここにも適用）。
 */
@Composable
private fun OccurrenceCard(state: AppState, entry: IdEntry) {
    val doc = state.ensureDoc(entry.document)
    doc.version
    state.indexVersion
    val root = doc.root
    // AI生成へ渡す関連文書と、出現単位の生成対象（R24-02。全宣言フィールド対象・id 型は除外）
    val relatedFiles = remember(doc, state.indexVersion, state.editVersion) {
        state.entityRelatedFiles(entry.id, doc.file)
    }
    val occurrenceMap = root?.let { getAt(it, entry.path) as? YNode.Map }
    val genField = remember(doc, doc.version, state.indexVersion) {
        root?.let { r ->
            doc.schema?.let { sc ->
                Views.fieldAt(sc, r, entry.path)?.let {
                    mergeGenField(sc, it, ownedKeys = null, label = "${entry.id} の内容")
                }
            }
        }
    }

    val refsOut = remember(entry, state.indexVersion, state.editVersion) {
        state.overlayIndex().refs.filter {
            it.document == entry.document && it.path.size >= entry.path.size &&
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
                    listOfNotNull(entry.title, entry.document.value).joinToString(" — "),
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    listOfNotNull(
                        "文書: ${entry.documentId}",
                        entry.schemaId?.let { "スキーマ: $it" },
                        entry.path.pretty().takeIf { it != "(root)" },
                        // 親ノード情報（UX-68）: この出現がどの親要素の配下かを示す
                        parentSummary(doc, entry.path)?.let { "親: $it" },
                    ).joinToString(" ／ "),
                    fontSize = 10.sp, color = Palette.SubText,
                )
            }
            if (!state.readOnlyMode && genField != null && occurrenceMap != null) {
                IconButton(
                    onClick = { state.requestAi(doc, genField, entry.path, relatedFiles = relatedFiles, mergeObjectKeys = true) },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = "この出現をAIで作成", Modifier.size(15.dp), tint = Palette.Primary)
                }
            }
            IconButton(onClick = { state.navigateToOccurrence(entry) }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "開く", Modifier.size(16.dp), tint = Palette.Primary)
            }
        }
        Spacer(Modifier.height(6.dp))
        if (root == null) {
            Text(
                "YAML を解析できないため編集できません: ${doc.parseError ?: "不明なエラー"}",
                fontSize = 11.sp, color = Palette.ErrorRed,
            )
        } else {
            OccurrenceForm(state, doc, root, entry, relatedFiles)
        }
        if (refsOut.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("この要素からの参照", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Palette.SubText)
            refsOut.forEach { ref ->
                Row(
                    Modifier.fillMaxWidth().clickable { state.navigateToId(ref.targetId) }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, Modifier.size(12.dp), tint = Palette.Primary)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        // 参照方向は label（R20-02）＋属性注記（R20-03）
                        ref.targetId +
                            (Relations.labelOf(state.relations, ref.relation)?.let { "（関係: $it）" } ?: "") +
                            (ref.attributeSummary()?.let { "（$it）" } ?: "") +
                            " ／ ${ref.viaKey}",
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
private fun OccurrenceForm(
    state: AppState,
    doc: OpenDocument,
    root: YNode,
    entry: IdEntry,
    relatedFiles: List<Path> = emptyList(),
) {
    val localIds = remember(doc, doc.version) {
        val documentId = IdIndex.documentIdOf(root, state.relPath(doc.file))
        DocScanner.scan(root, state.documentOf(doc.file), doc.schema, documentId).first
    }
    // 既定展開の深さは文書ルートではなく表示対象要素（entry.path）を基準に判定する
    val ctx = remember(doc, doc.version, state.indexVersion, state.readOnlyMode) {
        FormCtx(state, doc, localIds, baseDepth = entry.path.size, aiRelatedFiles = relatedFiles)
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
