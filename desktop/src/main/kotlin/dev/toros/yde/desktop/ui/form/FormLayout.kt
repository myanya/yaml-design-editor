package dev.toros.yde.desktop.ui.form

import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.yaml.model.YNode

/** 解決済みの型と値から、項目名の下に全幅を渡す構造かを決める。未作成でも配置を安定させる。 */
internal fun isStructuredFormValue(field: FieldDef?, node: YNode?): Boolean =
    field?.type in setOf(FieldType.OBJECT, FieldType.MAP, FieldType.ARRAY) ||
        node is YNode.Map || node is YNode.Seq

/** 幅は入れ子の内側で測る。単一値だけ、入力に十分な幅があれば左右に並べる。 */
internal fun stackFormField(structured: Boolean, availableWidth: Float, labelWidth: Float): Boolean =
    structured || availableWidth < labelWidth + 360f
