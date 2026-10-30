package dev.toros.yde.desktop.ui.form

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.validate.Severity
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.app.schema.effectiveFields
import dev.toros.yde.desktop.ui.common.FieldHelp
import dev.toros.yde.desktop.ui.common.FormSpacing
import dev.toros.yde.desktop.ui.common.MapSlot
import dev.toros.yde.desktop.ui.common.Palette
import dev.toros.yde.desktop.ui.common.PanelShape
import dev.toros.yde.desktop.ui.common.Slot
import dev.toros.yde.desktop.ui.file.FileEditor
import dev.toros.yde.desktop.ui.schema.LocalSchemaAuthoring
import dev.toros.yde.desktop.ui.schema.SchemaDeleteButton
import dev.toros.yde.desktop.ui.schema.SchemaValueEditor

/*
 * スキーマ駆動フォームの骨組み: 項目の並び（ObjectFields）・1 行の形（FieldRow・FormFieldLayout）・値の種類で編集欄を選ぶ（ValueEditor）・型の不一致の直し。
 * FormView.kt から分けた（R47-22）。部品の地図は docs/architecture/ui.md「フォームと FormCtx」。
 */

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

private val LocalFormIndent = staticCompositionLocalOf { 0.dp }

/** 深い構造でも入力幅を残し、境界は控えめな縦線で示す。 */
@Composable
internal fun FormGroup(nested: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val used = LocalFormIndent.current
    val indent = if (nested) (FormSpacing.MaxIndent - used).coerceIn(0.dp, FormSpacing.Indent) else 0.dp
    val guide = Palette.Border
    CompositionLocalProvider(LocalFormIndent provides used + indent) {
        Column(Modifier.fillMaxWidth().drawBehind {
            if (indent > 0.dp) drawLine(guide, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx())
        }.padding(start = indent, top = 4.dp, bottom = 4.dp), content = content)
    }
}

@Composable
internal fun FormFieldLayout(
    modifier: Modifier = Modifier,
    labelWidth: Dp = 148.dp,
    structured: Boolean = false,
    label: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    // 入れ子ごとの実際の幅で判断し、項目名の固定幅が入力欄を押し潰さないようにする。
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (stackFormField(structured, maxWidth.value, labelWidth.value)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (structured) 8.dp else 4.dp)) {
                Box(Modifier.fillMaxWidth()) { label() }
                Box(Modifier.fillMaxWidth()) { content() }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(Modifier.width(labelWidth)) { label() }
                Box(Modifier.weight(1f)) { content() }
            }
        }
    }
}

@Composable
internal fun FieldRow(ctx: FormCtx, field: FieldDef, slot: Slot, path: YPath) {
    @Suppress("UNUSED_VARIABLE") val version = ctx.doc.version
    val effective = ctx.doc.schema?.resolve(field) ?: field
    // 閲覧専用モード（R10-04）はパディングを圧縮して表示密度を上げる
    val structured = isStructuredFormValue(effective, slot.get())
    val labelTop = if (structured) 0.dp else if (ctx.readOnly) 2.dp else 8.dp
    Highlightable(ctx.state, path) {
        FormFieldLayout(
            Modifier.padding(top = if (structured) FormSpacing.GroupGap else FormSpacing.FieldGap,
                bottom = FormSpacing.FieldGap),
            structured = structured,
            label = {
                Column(Modifier.padding(top = labelTop, end = 8.dp)) {
                    FieldHelp(effective.displayLabel, effective.description, showPreview = !ctx.readOnly) { labelModifier, helpButton ->
                        FlowRow {
                            Text(effective.displayLabel, labelModifier, fontSize = if (structured) 14.sp else 13.sp,
                                fontWeight = FontWeight.SemiBold)
                            if (effective.required) Text(" *", color = Palette.ErrorRed, fontSize = 12.sp)
                            // requiredFrom はフェーズ到達で必須になる項目（無条件必須の * とは区別して注記する）
                            effective.requiredFrom?.let {
                                Text(" *$it〜", color = Palette.WarnOrange, fontSize = 10.sp)
                            }
                            helpButton()
                            if (LocalSchemaAuthoring.current && !ctx.readOnly && slot.get() != null) {
                                SchemaDeleteButton("設定「${effective.displayLabel}」を削除") {
                                    slot.remove(); ctx.markEdited()
                                }
                            }
                            if (effective.type != FieldType.ARRAY && !ctx.readOnly) {
                                IconButton(
                                    onClick = { ctx.state.requestAi(ctx.doc, effective, path, relatedFiles = ctx.aiRelatedFiles) },
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
                    }
                    if (!ctx.readOnly) {
                        // 保存時に自動で入る値（auto）: 手で直せるが、保存のときに機械が入れることを知らせる
                        effective.auto?.let { Text(autoValueHint(it), fontSize = 10.sp, color = Palette.SubText) }
                    }
                }
            },
        ) {
            // 検証指摘のあるフィールドは入力枠を色づけする（UX-14: エラー=赤・警告=オレンジ）
            val issueColor = when (ctx.issueAt(path)) {
                Severity.ERROR -> Palette.ErrorRed
                Severity.WARNING -> Palette.WarnOrange
                else -> null
            }
            Box(
                Modifier.fillMaxWidth().then(
                    if (issueColor != null) Modifier.border(1.dp, issueColor, PanelShape) else Modifier
                )
            ) { ValueEditor(ctx, effective, slot, path) }
        }
    }
}

/** 型に応じたエディタの振り分け。 */
@Composable
fun ValueEditor(ctx: FormCtx, field: FieldDef, slot: Slot, path: YPath) {
    val effective = ctx.doc.schema?.resolve(field) ?: field
    if (LocalSchemaAuthoring.current && effective.expression) {
        SchemaValueEditor(ctx, slot, path)
        return
    }
    // 型変更後の残留値（UX-70）: スキーマの型と現在値の構造が合わないと各エディタが編集不能になる
    // （例 string→array 変更後の文字列値）。ここで検知して「消す／変換」つきの修復表示へ切り替える
    val node = slot.get()
    if (node != null && structureMismatch(effective, node)) {
        TypeMismatchRepair(ctx, effective, slot, path, node)
        return
    }
    // 複数行の文字列は、型から引き継いだヒント（display: multiline。組込みの型 text）で判定する。基底の種類の分岐は増やさない
    if (effective.multiline) {
        ScalarTextEditor(ctx, effective, slot, singleLine = false)
        return
    }
    when (effective.type) {
        FieldType.STRING -> ScalarTextEditor(ctx, effective, slot, singleLine = true)
        // 意味の基底型（日付・時刻・日時）は正準の形の文字で編集する（案内はヒント→型の既定）。右端のボタンでピッカー
        FieldType.DATE, FieldType.TIME, FieldType.DATETIME ->
            ScalarTextEditor(ctx, effective, slot, singleLine = true, trailing = { TemporalPickerButton(ctx, effective, slot) })
        FieldType.INT, FieldType.NUMBER -> ScalarTextEditor(ctx, effective, slot, singleLine = true)
        // ファイルの場所: 入力欄＋選択＋OS で開く。画像なら下に表示（ui/file/FileEditor.kt）
        FieldType.FILE -> FileEditor(ctx, effective, slot, path)
        FieldType.BOOLEAN -> BooleanEditor(ctx, slot)
        FieldType.ENUM -> EnumEditor(ctx, effective, slot)
        FieldType.ID -> IdEditor(ctx, effective, slot)
        FieldType.REF -> RefEditor(ctx, effective, slot, path)
        FieldType.ARRAY -> ArrayEditor(ctx, effective, slot, path)
        FieldType.MAP -> MapEditor(ctx, effective, slot, path)
        FieldType.OBJECT -> NestedObjectEditor(ctx, effective, slot, path)
        // any は型を定めないため汎用エディタで編集する（R11-02）
        FieldType.ANY -> if (LocalSchemaAuthoring.current) SchemaValueEditor(ctx, slot, path)
            else GenericEditor(ctx, slot, path, refHint = false)
    }
}

/** スキーマ型と現在値の構造の不一致判定（UX-70）。スカラー型はスカラー以外、array は Seq 以外、object / map は Map 以外。 */
internal fun structureMismatch(field: FieldDef, node: YNode): Boolean = when (field.type) {
    FieldType.ANY -> false
    FieldType.ARRAY -> node !is YNode.Seq
    FieldType.OBJECT, FieldType.MAP -> node !is YNode.Map
    // 属性付き参照（fields 宣言あり）は {to, 属性} の Map も正しい形（R20-03）
    FieldType.REF -> !(node is YNode.Scalar || (field.fields.isNotEmpty() && node is YNode.Map))
    else -> node !is YNode.Scalar
}

/**
 * 型変更後の残留値の修復UI（UX-70）: 現在値を汎用エディタで見せたまま、
 * 「値を消す」と、可能な組だけ「変換して引き継ぐ」を提示する。
 */
@Composable
private fun TypeMismatchRepair(ctx: FormCtx, field: FieldDef, slot: Slot, path: YPath, node: YNode) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "スキーマの型（${field.type.id}）と現在の値の形が一致しません（型変更後の残留値）。値を消すか、変換して引き継いでください。",
            fontSize = 11.sp, color = Palette.WarnOrange,
        )
        if (!ctx.readOnly) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { slot.remove(); ctx.markEdited() }) {
                    Text("値を消す", fontSize = 11.sp, color = Palette.ErrorRed)
                }
                if (field.type == FieldType.ARRAY && node is YNode.Scalar && node.value?.toString()?.isNotBlank() == true) {
                    TextButton(onClick = {
                        slot.set(YNode.Seq(mutableListOf<YNode>(node)))
                        ctx.markEdited()
                    }) { Text("配列に変換（現在値を1要素に）", fontSize = 11.sp) }
                }
                if (field.type !in setOf(FieldType.ARRAY, FieldType.OBJECT, FieldType.MAP) && node is YNode.Seq) {
                    val scalars = node.items.mapNotNull { (it as? YNode.Scalar)?.value?.toString() }
                    if (scalars.isNotEmpty() && scalars.size == node.items.size) {
                        TextButton(onClick = { ctx.setScalar(slot, scalars.first(), field.type) }) {
                            Text(
                                if (scalars.size == 1) "スカラーに変換（要素の値を引き継ぐ）"
                                else "スカラーに変換（先頭の値だけ引き継ぐ）",
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
        // 残留値の中身は汎用エディタで確認・部分編集できる（閲覧専用モードは表示のみ）
        GenericEditor(ctx, slot, path, refHint = false)
    }
}

/** 閲覧専用モードの値表示（R10-04）。未設定は淡色で示す。 */
@Composable
internal fun ReadOnlyValueText(text: String?, modifier: Modifier = Modifier) {
    val blank = text.isNullOrBlank()
    Text(
        if (blank) "（未設定）" else text!!,
        fontSize = 13.sp,
        color = if (blank) Palette.SubText else Palette.Text,
        modifier = modifier.padding(top = 2.dp, bottom = 2.dp),
    )
}
