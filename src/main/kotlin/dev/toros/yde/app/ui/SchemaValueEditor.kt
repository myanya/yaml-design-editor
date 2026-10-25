package dev.toros.yde.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath

/** スキーマの自由な値にも、形の選択・条件・手順の入力支援を提供する。業務フォームには適用しない。 */
internal val LocalSchemaAuthoring = compositionLocalOf { false }

@Composable
internal fun SchemaValueEditor(ctx: FormCtx, slot: Slot, path: YPath) {
    FormAnchor(path) { SchemaValueContent(ctx, slot, path) }
}

@Composable
private fun SchemaValueContent(ctx: FormCtx, slot: Slot, path: YPath) {
    ctx.doc.version
    val node = slot.get()
    var replacement by remember(path) { mutableStateOf<SchemaValuePreset?>(null) }
    fun replace(preset: SchemaValuePreset) {
        slot.set(preset.node())
        ctx.setExpanded(path, true)
        ctx.markEdited()
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!ctx.readOnly) {
            SchemaValueChooser(path, "値・構造を選ぶ") { preset ->
                if (node == null || node is YNode.Scalar && node.value == "") replace(preset)
                else replacement = preset
            }
        }
        when (node) {
            is YNode.Map -> {
                val expanded = ctx.expanded(path)
                FormFoldHeader("項目 ${node.entries.size} 件", expanded) { ctx.setExpanded(path, !expanded) }
                if (expanded) FormGroup(nested = true) {
                    node.entries.keys.toList().forEach { key -> key(key) {
                        FormFieldLayout(structured = node.entries[key] is YNode.Map || node.entries[key] is YNode.Seq,
                            label = { Text(SchemaValueForms.label(key), fontSize = 13.sp) }) {
                            Column {
                                SchemaValueEditor(ctx, MapSlot(node, key), path + key)
                                if (!ctx.readOnly) SchemaDeleteButton("項目「$key」を削除") {
                                    MapSlot(node, key).remove(); ctx.markEdited()
                                }
                            }
                        }
                    } }
                    if (!ctx.readOnly) {
                        val suggestions = SchemaValueForms.suggestedKeys(path, node)
                        if (suggestions.isNotEmpty()) ChoiceButton("設定項目を追加", listOf("設定項目を追加") + suggestions) { chosen ->
                            if (chosen in suggestions) {
                                node.entries[chosen] = SchemaValueForms.propertyNode(chosen)
                                ctx.setExpanded(path + chosen, true)
                                ctx.markEdited()
                            }
                        }
                        var keyText by remember(path, node) { mutableStateOf("") }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SchemaTextField(keyText, { keyText = it }, singleLine = true, workspaceHistory = false,
                                placeholder = { Text("新しい項目名") }, modifier = Modifier.weight(1f))
                            SchemaEditButton(enabled = keyText.isNotBlank() && keyText.trim() !in node.entries,
                                onClick = {
                                    node.entries[keyText.trim()] = YNode.Scalar("")
                                    keyText = ""; ctx.markEdited()
                                }) { Text("項目を追加") }
                        }
                    }
                }
            }
            is YNode.Seq -> {
                val expanded = ctx.expanded(path)
                FormFoldHeader("配列 ${node.items.size} 件", expanded) { ctx.setExpanded(path, !expanded) }
                if (expanded) FormGroup(nested = true) {
                    node.items.toList().forEachIndexed { index, _ -> key(index) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${index + 1}", fontSize = 13.sp)
                            if (!ctx.readOnly) {
                                SchemaEditButton(enabled = index > 0, onClick = {
                                    node.items.add(index - 1, node.items.removeAt(index)); ctx.markEdited()
                                }) { Text("上へ") }
                                SchemaEditButton(enabled = index < node.items.lastIndex, onClick = {
                                    node.items.add(index + 1, node.items.removeAt(index)); ctx.markEdited()
                                }) { Text("下へ") }
                                ArrayDeleteButton(ctx.doc, node, index, ctx.readOnly, textButton = true,
                                    markEdited = { ctx.markEdited() })
                            }
                        }
                        SchemaValueEditor(ctx, SeqSlot(node, index), path + index)
                        HorizontalDivider(color = Palette.Border)
                    } }
                    if (!ctx.readOnly) SchemaValueChooser(path + node.items.size, "要素を追加") { preset ->
                        node.items.add(preset.node())
                        ctx.setExpanded(path + node.items.lastIndex, true)
                        ctx.markEdited()
                    }
                }
            }
            is YNode.Scalar -> {
                // 数値の入力途中（空欄・符号など）で文字列になっても、この欄の数値種別を保つ。
                val numericType = remember(path, node) {
                    when (node.value) {
                        is Int, is Long -> FieldType.INT
                        is Number -> FieldType.NUMBER
                        else -> null
                    }
                }
                if (numericType != null && !ctx.readOnly) {
                    SchemaTextField(node.value?.toString().orEmpty(), { ctx.setScalar(slot, it, numericType) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                } else GenericEditor(ctx, slot, path, refHint = false)
            }
            null -> GenericEditor(ctx, slot, path, refHint = false)
        }
    }
    replacement?.let { preset ->
        AlertDialog(onDismissRequest = { replacement = null }, title = { Text("値・構造を置き換える") },
            text = { Text("現在の内容を「${preset.label}」の入力用の形で置き換えます。取り消すと現在の内容を保持します。") },
            confirmButton = { SchemaEditButton(onClick = { replace(preset); replacement = null }) {
                Text("置き換える", color = Palette.ErrorRed)
            } }, dismissButton = { TextButton(onClick = { replacement = null }) { Text("キャンセル") } })
    }
}

@Composable
private fun SchemaValueChooser(path: YPath, title: String, onChoose: (SchemaValuePreset) -> Unit) {
    var group by remember(path) { mutableStateOf(SchemaValueForms.groupFor(path)) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceButton(group, SchemaValueForms.presets.map { it.group }.distinct()) { group = it }
        val choices = SchemaValueForms.choices(group, path)
        ChoiceButton(title, listOf(title) + choices.map { it.label }) { chosen ->
            choices.firstOrNull { it.label == chosen }?.let(onChoose)
        }
    }
}
