package dev.toros.yde.desktop.ui.form

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Hub
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.index.IdEntry
import dev.toros.yde.app.index.IdIndex
import dev.toros.yde.app.index.isAllowedBy
import dev.toros.yde.semantic.schema.RefTarget
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.desktop.UiSpace
import dev.toros.yde.desktop.ui.common.ChoiceMenu
import dev.toros.yde.desktop.ui.common.ChoiceOption
import dev.toros.yde.desktop.ui.common.CompactTextField
import dev.toros.yde.desktop.ui.common.MapSlot
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.desktop.ui.common.Slot
import dev.toros.yde.desktop.ui.common.historyHandler

/*
 * 参照の編集欄: エンティティへの参照（RefEditor・RefScalarEditor。参照先の名前と飛び先）・文書への参照（DocRefScalarEditor）。
 * FormView.kt から分けた（R47-22）。部品の地図は docs/architecture/ui.md「フォームと FormCtx」。
 */

/**
 * 横ぐし（エンティティページ）への直行ボタン。ビューグリッドの ID セルと同じ Hub アイコンで、
 * 押すと `openCrossView(id)`（移動として履歴に積まれる）。ID 欄と閲覧専用のテーブルセルで共用する。
 */
@Composable
internal fun EntityPageButton(
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
internal fun RefScalarEditor(ctx: FormCtx, field: FieldDef, slot: Slot, titleInField: Boolean = true, jumpButtonSize: Dp = 38.dp) {
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
    val candidates: List<IdEntry> = remember(ctx.state.indexVersion, ctx.state.editVersion, field.idPatterns, field.scope) {
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
                onHistory = ctx.historyHandler,
                value = current,
                onValueChange = { ctx.setScalar(slot, it, FieldType.REF) },
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
            if (menuOpen) ChoiceMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                options = candidates.map { e -> ChoiceOption(e.id, e.id,
                    listOfNotNull(e.title, e.document.value).joinToString(" — "), selected = e.id == current) },
                onSelect = { ctx.setScalar(slot, it, FieldType.REF) },
            )
        }
        RefJumpButton(ctx, current, enabled = resolved, iconButtonSize = jumpButtonSize, iconSize = if (jumpButtonSize < 38.dp) 18.dp else 24.dp)
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
        IconButton(enabled = enabled, onClick = {
            if (ctx.state.uiSpace == dev.toros.yde.desktop.UiSpace.DASHBOARD) ctx.state.openDashboardEntity(id)
            else open = true
        }, modifier = Modifier.size(iconButtonSize)) {
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
                if (ctx.state.uiSpace == dev.toros.yde.desktop.UiSpace.DASHBOARD) {
                    TextButton(onClick = { open(current) }) { Text("原本を開く", fontSize = 11.sp) }
                } else IconButton(onClick = { open(current) }, modifier = Modifier.size(28.dp)) {
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
                onHistory = ctx.historyHandler,
                value = current,
                onValueChange = { ctx.setScalar(slot, it, FieldType.REF) },
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
            if (menuOpen) ChoiceMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                options = documents.values.sortedBy { it.documentId }.map { node ->
                    val name = loader(node.document)?.let { IdIndex.documentNameOf(it) }
                    ChoiceOption(node.documentId, node.documentId,
                        listOfNotNull(name, node.document.value).joinToString(" — "), selected = node.documentId == current)
                },
                onSelect = { ctx.setScalar(slot, it, FieldType.REF) },
            )
        }
        if (ctx.state.uiSpace == dev.toros.yde.desktop.UiSpace.DASHBOARD) {
            TextButton(enabled = resolved && documents.containsKey(current), onClick = { open(current) }) { Text("原本を開く", fontSize = 11.sp) }
        } else IconButton(
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
