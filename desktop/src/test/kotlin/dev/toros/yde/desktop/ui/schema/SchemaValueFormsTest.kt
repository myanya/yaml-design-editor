package dev.toros.yde.desktop.ui.schema

import dev.toros.yde.semantic.schema.*
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchemaValueFormsTest {
    @Test fun `図の部品と線にポートの追加候補がある`() {
        val row = YamlIO.parse("kind: group") as YNode.Map
        assertEquals(listOf("port", "portSide"), SchemaValueForms.suggestedKeys(listOf("build"), row))
        val edge = YamlIO.parse("sourcePort: fk") as YNode.Map
        assertEquals(listOf("targetPort"), SchemaValueForms.suggestedKeys(listOf("build"), edge))
        assertEquals("auto", (SchemaValueForms.propertyNode("portSide") as YNode.Scalar).value)
        assertTrue(SchemaValueForms.label("targetPort").contains("終点"))
    }
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
    fun `入れ子の連結例は閉じた変換で往復し修飾子を正しい型で追加できる`() {
        val preset = SchemaValueForms.presets.single { it.label == "親の値を使って子配列を連結する" }
        val roundTrip = YamlIO.parseToRaw(YamlIO.dumpPlain(preset.node()))
        assertEquals(preset.value, roundTrip)
        TransformDef.ofTemplate("nested", roundTrip, "入れ子の連結例")
        val each = YamlIO.parse("${'$'}each: items\n${'$'}build: []") as YNode.Map
        assertTrue(SchemaValueForms.suggestedKeys(listOf("scaffold"), each).containsAll(listOf("${'$'}bind", "${'$'}flatten")))
        val entry = YamlIO.parse("${'$'}eachEntry: items\n${'$'}build: {}") as YNode.Map
        val keys = SchemaValueForms.suggestedKeys(listOf("scaffold"), entry)
        assertTrue("${'$'}bind" in keys && "${'$'}flatten" !in keys)
        assertEquals(true, (SchemaValueForms.propertyNode("${'$'}flatten") as YNode.Scalar).value)
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

    /**
     * 設計画面が持つ語（表示名の表と入力例）は、仕様（devtrack/spec/keywords）の語に含まれる。
     * 語の一覧は読み取りの実装・仕様・この画面の 3 か所にあるので、画面だけが知らない語・古い語を持たないことを確かめる。
     * 入力例は、最上位のキーと、手順の中身（set / insert などの直下）のキーを語として見る（それより深い所は利用者のデータ）。
     */
    @Test
    fun `表示名と入力例の語は仕様の語に含まれる`() {
        var dir = java.nio.file.Path.of("").toAbsolutePath()
        while (!java.nio.file.Files.isDirectory(dir.resolve("devtrack/spec/keywords"))) dir = dir.parent ?: error("devtrack/spec/keywords が見つかりません")
        val specKeys = java.nio.file.Files.list(dir.resolve("devtrack/spec/keywords")).use { files ->
            files.map { f -> ((YamlIO.parseToRaw(java.nio.file.Files.readString(f)) as Map<*, *>)["key"]).toString() }.toList().toSet()
        }
        val used = LinkedHashMap<String, String>()
        SchemaValueForms.labelKeys.forEach { used[it] = "表示名の表" }
        SchemaValueForms.presets.forEach { p ->
            val m = p.value as? Map<*, *> ?: return@forEach
            m.forEach { (k, v) ->
                used.putIfAbsent(k.toString(), "入力例「${p.label}」")
                if (p.group == "手順") (v as? Map<*, *>)?.keys?.forEach { used.putIfAbsent(it.toString(), "入力例「${p.label}」の $k") }
            }
        }
        // 仕様にまだ位置が無い語（操作の手・照合の線・図の部品）。仕様に足したら、ここから外す（外し忘れは下で止める）
        val notYetInSpec = setOf(
            "insert", "delete", "create", "call", "method",          // 操作の手（do の要素）
            "edge",                                                  // 照合の連鎖の線
            "port", "portSide", "sourcePort", "targetPort", "children", "stroke", // 図のひな型の部品
        )
        val stale = notYetInSpec.filter { it in specKeys }
        assertTrue(stale.isEmpty(), "仕様に足された語が、まだ仕様に無い語の一覧に残っています: $stale")
        val unknown = used.filterKeys { it !in specKeys && it !in notYetInSpec }
        assertTrue(unknown.isEmpty(), "仕様（devtrack/spec/keywords）に無い語を設計画面が持っています: " + unknown.entries.joinToString(" / ") { "${it.key}（${it.value}）" })
    }
}
