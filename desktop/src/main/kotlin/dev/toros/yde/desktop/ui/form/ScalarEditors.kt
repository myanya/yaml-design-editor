package dev.toros.yde.desktop.ui.form

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.app.index.IdEntry
import dev.toros.yde.app.index.idMatchesPattern
import dev.toros.yde.semantic.schema.ValueFormat
import dev.toros.yde.app.schema.IdSuggest
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.desktop.ui.common.ChoiceMenu
import dev.toros.yde.desktop.ui.common.ChoiceOption
import dev.toros.yde.desktop.ui.common.CompactTextField
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.desktop.ui.common.PanelShape
import dev.toros.yde.desktop.ui.common.Slot
import dev.toros.yde.desktop.ui.common.historyHandler

/*
 * 一つの値の編集欄: 文字（ScalarTextEditor）・真偽（BooleanEditor）・選択肢（EnumEditor）・ID（IdEditor。採番の提案つき）。
 * FormView.kt から分けた（R47-22）。部品の地図は docs/architecture/ui.md「フォームと FormCtx」。
 */

@Composable
internal fun ScalarTextEditor(
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
        onHistory = ctx.historyHandler,
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
internal fun BooleanEditor(ctx: FormCtx, slot: Slot) {
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
internal fun EnumEditor(ctx: FormCtx, field: FieldDef, slot: Slot) {
    val current = (slot.get() as? YNode.Scalar)?.value?.toString()
    val currentOption = field.optionFor(current)
    // 説明はボタンの右に1行で出す。全文は選択一覧の説明ボタンから読める。
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
            ChoiceMenu(
                expanded = open, onDismissRequest = { open = false },
                options = field.enumOptions().map { option ->
                    ChoiceOption<String?>(option.value, option.displayLabel,
                        subtitle = option.value.takeIf { it != option.displayLabel }.orEmpty(),
                        description = option.description, selected = option.value == current, searchText = option.value)
                } + ChoiceOption(null, "（未設定にする）", selected = current == null),
                onSelect = { value ->
                    if (value == null) slot.remove() else slot.set(YNode.Scalar(value))
                    ctx.markEdited()
                },
            )
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
internal fun IdEditor(ctx: FormCtx, field: FieldDef?, slot: Slot) {
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
            val candidates: List<IdEntry> = remember(ctx.state.indexVersion, ctx.state.editVersion, field?.pattern) {
                ctx.state.overlayIndex().entries.values.asSequence()
                    .mapNotNull { it.firstOrNull() }
                    .filter { e ->
                        when {
                            patternRegex != null -> patternRegex.matches(e.id)
                            entityGlobs.isNotEmpty() -> entityGlobs.any { idMatchesPattern(e.id, it) }
                            else -> true
                        }
                    }
                    .sortedBy { it.id }
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
                    onHistory = ctx.historyHandler,
                    value = text,
                    onValueChange = { ctx.setScalar(slot, it, FieldType.ID) },
                    singleLine = true,
                    textStyle = fieldTextStyle,
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
                    options = listOfNotNull(suggestion?.let { ChoiceOption(it, "採番: $it", "次の連番", searchText = it) }) +
                        candidates.map { e -> ChoiceOption(e.id, e.id,
                            listOfNotNull(e.title, e.document.value).joinToString(" — "), selected = e.id == text) },
                    note = "既存IDを選ぶと同一IDの出現（詳細化・写像行）になります。",
                    onSelect = { ctx.setScalar(slot, it, FieldType.ID) },
                )
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
