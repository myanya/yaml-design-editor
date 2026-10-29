package dev.toros.yde.desktop.ui.schema

import dev.toros.yde.semantic.schema.*
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchemaValueFormsTest {
    @Test
    fun `入力支援の手順と条件とひな型は既存パーサーで読める`() {
        SchemaValueForms.presets.forEach { p ->
            val raw = p.value
            when (p.group) {
                "手順" -> ActionSteps.parse(listOf(raw), p.label, StepScope.ACTION)
                "条件" -> TransformDef.parseConditions(raw, p.label)
                "判定" -> ConstraintCheck.parse(raw, p.label, current = true)
                "型の検査" -> ElementRule.parse(raw, p.label)
                "検査" -> ConstraintRule.parse(raw, p.label)
                "ひな型" -> TransformDef.parseTemplate(raw, p.label)
                "値の式" -> ValueExpr.parse(raw, p.label, current = true)
            }
        }
    }

    @Test
    fun `型の操作には横断操作を提案せず追加した形は相互に独立している`() {
        val choices = SchemaValueForms.choices("手順", listOf("definitions", "Task", "methods", "update", "do", 0))
        assertEquals(4, choices.size)
        choices.forEach { ActionSteps.parse(listOf(it.value), it.label, StepScope.METHOD) }
        val preset = choices.first()
        val a = preset.node() as YNode.Map
        val before = YamlIO.dumpPlain(preset.node())
        a.entries.clear()
        assertEquals(before, YamlIO.dumpPlain(preset.node()))
    }

    @Test
    fun `全ての比較演算子に判定入力があり前提には禁止された指摘設定を提案しない`() {
        val supported = SchemaValueForms.presets.filter { it.group == "判定" }.flatMap { (it.value as Map<*, *>).keys }
        assertTrue(CheckOp.entries.all { it.id in supported })
        val keys = SchemaValueForms.suggestedKeys(listOf("actions", 0, "require", 0), YNode.Map())
        assertTrue("level" !in keys && "responses" !in keys)
        assertEquals("型の検査", SchemaValueForms.groupFor(listOf("definitions", "Task", "constraints", 0)))
        assertEquals("値の式", SchemaValueForms.groupFor(listOf("constraints", 0, "check", 0, "eq", 0)))
    }

    @Test
    fun `処理手順の意味モデルを増やしたら入力候補も必要になる`() {
        val keys = SchemaValueForms.presets.filter { it.group == "手順" }.flatMap { (it.value as Map<*, *>).keys }.toSet()
        val supported = keys.map { it.toString().replaceFirstChar(Char::uppercaseChar) }.toSet() + setOf("EachPath", "EachMatch")
        val missing = ActionStep::class.java.declaredClasses.filter { ActionStep::class.java.isAssignableFrom(it) }
            .map { it.simpleName }.filter { it !in supported }
        assertTrue(missing.isEmpty(), "手順の入力候補を追加してください: $missing")
    }
}
