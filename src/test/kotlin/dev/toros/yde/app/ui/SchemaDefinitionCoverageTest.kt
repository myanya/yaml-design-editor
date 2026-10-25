package dev.toros.yde.app.ui

import dev.toros.yde.app.schema.BuiltinSchemas
import dev.toros.yde.semantic.schema.*
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertTrue

/** 意味モデルへ項目を追加してメタスキーマと設計UIを更新し忘れた場合に止める契約テスト。 */
class SchemaDefinitionCoverageTest {
    private val tree get() = BuiltinSchemas.all.first { it.id == BuiltinSchemas.META_SCHEMA_ID }.tree
    private fun members(type: Class<*>) = type.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }.map { it.name }

    @Test
    fun `意味モデルの項目と表示ヒントはメタスキーマに対応を持つ`() {
        val fields = tree.definitions.getValue("field").fields
        val keys = (fields + fields.flatMap { it.cases.values.flatten() }).map { it.key }.toSet()
        val aliases = mapOf("refTarget" to "target", "checks" to "constraints", "insertRule" to "methods",
            "options" to "values", "numberDisplay" to "display", "textDisplay" to "display")
        // 読み取り・型解決でのみ作る内部情報。新しい項目をここへ逃がす場合は理由をレビューする。
        val internal = setOf("definitionScope", "typeName", "inheritedPatterns", "outerIdPatterns", "lineage", "hints", "typeGiven")
        val missing = (members(FieldDef::class.java) + members(FieldHints::class.java)).filter { it !in internal && (aliases[it] ?: it) !in keys }
        assertTrue(missing.isEmpty(), "メタスキーマと設計UIの対応を追加してください: $missing")
    }

    @Test
    fun `操作と採番の意味モデルを拡張したら入力定義も必要になる`() {
        fun check(type: Class<*>, definition: String, aliases: Map<String, String> = emptyMap()) {
            val keys = tree.definitions.getValue(definition).fields.map { it.key }
            // origin は読込時の出どころ、MethodDef.name は methods マップのキーであり値の入力欄ではない。
            val missing = members(type).filter { it !in setOf("origin", "name") && (aliases[it] ?: it) !in keys }
            assertTrue(missing.isEmpty(), "$definition のメタ定義と入力UIが不足: $missing")
        }
        check(ActionDef::class.java, "action", mapOf("steps" to "do"))
        check(ActionInput::class.java, "action-input")
        check(MethodDef::class.java, "method", mapOf("steps" to "do", "builds" to "build"))
        check(InsertRule::class.java, "insert-rule")
        val numbering = tree.definitions.getValue("insert-rule").fields.first { it.key == "id" }.fields.map { it.key }
        assertTrue(members(IdNumbering::class.java).all { it in numbering })
    }
}
