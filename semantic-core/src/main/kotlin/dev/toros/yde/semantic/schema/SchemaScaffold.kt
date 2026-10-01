package dev.toros.yde.semantic.schema

import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.YamlIO

/**
 * 利用者が書いたスキーマの木から、文書の中身を作る（P27 段D の D-c で、root の core/Views と schema/Schema から移した）。
 * - defaultTree: 新しい文書・新しい要素の初期値（必須の項目だけ。default を尊重・cases に対応）
 * - sample: プレビュー用のサンプル（任意の項目も含めて、型ごとの例の値を入れる）
 * どちらも、項目の表示と編集のためのヒント（FieldHints の default）を読む——文書を作るための機能で、検証・走査・検査ではない。
 */
object SchemaScaffold {

    /** スキーマ定義から必須フィールドの初期値ツリーを生成する（default 尊重・cases 対応）。 */
    fun defaultTree(schema: SchemaTree, field: FieldDef = schema.root, depth: Int = 0): YNode {
        if (depth > 24) return YNode.Map()
        val f = schema.resolve(field) ?: field
        if (f.default != null) return YamlIO.fromAny(f.default)
        return when (f.type) {
            FieldType.OBJECT -> {
                val entries = LinkedHashMap<String, YNode>().apply {
                    f.fields.filter { it.required && it.key != null }
                        .forEach { put(it.key!!, defaultTree(schema, it, depth + 1)) }
                }
                val map = YNode.Map(entries)
                schema.effectiveFields(f.fields, map).fields.forEach { child ->
                    val key = child.key ?: return@forEach
                    if (child.required && key !in entries) entries[key] = defaultTree(schema, child, depth + 1)
                }
                map
            }
            FieldType.ARRAY -> YNode.Seq()
            FieldType.MAP -> YNode.Map()
            FieldType.BOOLEAN -> YNode.Scalar(false)
            FieldType.INT, FieldType.NUMBER -> YNode.Scalar(0)
            else -> YNode.Scalar("")
        }
    }

    /** プレビューや新規作成に使うサンプルYAMLツリーを生成する。 */
    fun sample(schema: SchemaTree): YNode {
        fun create(original: FieldDef, depth: Int, stack: Set<DefinitionKey>): YNode {
            if (depth > 16) return YNode.Map()
            val reference = schema.definitionTarget(original)?.first
            if (reference != null && reference in stack) return YNode.Map()
            val nextStack = if (reference == null) stack else stack + reference
            val field = schema.resolve(original) ?: original
            if (field.default != null) return YamlIO.fromAny(field.default)
            // サンプル値のヒント（型から引き継ぐ。例 組込みの型 date の 2026-01-01）
            field.sample?.let { return YamlIO.fromAny(it) }
            return when (field.type) {
                FieldType.OBJECT -> {
                    val entries = LinkedHashMap<String, YNode>().apply {
                        field.fields.forEach { child -> child.key?.let { put(it, create(child, depth + 1, nextStack)) } }
                    }
                    // enum の生成値（default または先頭の選択肢）に応じた case フィールドを実効順で合成する
                    val ordered = schema.effectiveFields(field.fields, YNode.Map(entries)).fields
                    YNode.Map(LinkedHashMap<String, YNode>().apply {
                        ordered.forEach { child ->
                            val key = child.key ?: return@forEach
                            put(key, entries[key] ?: create(child, depth + 1, nextStack))
                        }
                    })
                }
                FieldType.ARRAY -> YNode.Seq(
                    if (field.item?.let { item -> schema.definitionTarget(item)?.first?.let { it in nextStack } } == true) mutableListOf()
                    else field.item?.let { mutableListOf(create(it, depth + 1, nextStack)) } ?: mutableListOf()
                )
                FieldType.MAP -> YNode.Map()
                FieldType.BOOLEAN -> YNode.Scalar(false)
                FieldType.INT, FieldType.NUMBER -> YNode.Scalar(0)
                FieldType.ENUM -> YNode.Scalar(field.values.firstOrNull() ?: "")
                // 意味の基底型のサンプル値は型の既定（上の sample のヒントで拾われるが、網羅のため）
                FieldType.DATE, FieldType.TIME, FieldType.DATETIME -> YNode.Scalar(field.type.value?.sample ?: "")
                FieldType.ID -> YNode.Scalar("SAMPLE-001")
                // 必須スカラーは空文字にせず型別の例値を入れる（UX-28。空文字は必須エラーになりプレビューが赤くなる）
                FieldType.REF -> YNode.Scalar(if (field.required || field.requiredFrom != null) "SAMPLE-001" else "")
                FieldType.STRING ->
                    YNode.Scalar(if (field.required || field.requiredFrom != null) "サンプル" else "")
                FieldType.ANY -> YNode.Scalar("")
            }
        }
        return create(schema.root, 0, emptySet())
    }
}
