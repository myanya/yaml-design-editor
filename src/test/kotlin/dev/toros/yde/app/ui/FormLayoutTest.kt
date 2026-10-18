package dev.toros.yde.app.ui

import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldHints
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.TextDisplay
import dev.toros.yde.yaml.model.YNode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FormLayoutTest {
    @Test
    fun `未作成と空の複合値も全幅を使い作成時に配置が変わらない`() {
        for (type in listOf(FieldType.OBJECT, FieldType.MAP, FieldType.ARRAY)) {
            val field = FieldDef(type = type)
            assertTrue(isStructuredFormValue(field, null))
            assertTrue(stackFormField(isStructuredFormValue(field, null), 1200f, 148f))
        }
        assertTrue(isStructuredFormValue(FieldDef(type = FieldType.ARRAY), YNode.Seq()))
        assertTrue(isStructuredFormValue(FieldDef(type = FieldType.MAP), YNode.Map()))
    }

    @Test
    fun `汎用値と属性付き参照と型不一致の残留値は実際の構造に従う`() {
        for (field in listOf(null, FieldDef(type = FieldType.ANY), FieldDef(type = FieldType.REF), FieldDef())) {
            assertTrue(isStructuredFormValue(field, YNode.Map()))
            assertTrue(isStructuredFormValue(field, YNode.Seq()))
            assertFalse(isStructuredFormValue(field, YNode.Scalar("値")))
            assertFalse(isStructuredFormValue(field, null))
        }
    }

    @Test
    fun `複数行文字列も単一値として扱い狭い場所でだけ縦に並ぶ`() {
        val field = FieldDef(type = FieldType.STRING, hints = FieldHints(textDisplay = TextDisplay.MULTILINE))
        val structured = isStructuredFormValue(field, YNode.Scalar("一行目\n二行目"))
        assertFalse(structured)
        assertTrue(stackFormField(structured, 507f, 148f))
        assertFalse(stackFormField(structured, 508f, 148f))
        assertTrue(stackFormField(structured, 550f, 214f))
    }
}
