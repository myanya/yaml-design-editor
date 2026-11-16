package dev.toros.yde.workspace

import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.validate.Severity

/**
 * Construct で組み立てたスキーマの項を、既存の読取・定義検査・型解決へ渡す（生成の `$schemaModel`）。
 * 外のワークスペースや以前の生成物を補わず、引数の文書と組込みの型だけで解決する。
 * 設計項目とスキーマの対応付けは変換定義の責務で、ここには持ち込まない。
 */
object SchemaModels {
    fun resolve(input: YNode): YNode {
        val documents = (input as? YNode.Seq)?.items
            ?: error("\$schemaModel: 入力はスキーマ文書の配列です")
        val ids = mutableSetOf<String>()
        val texts = linkedMapOf(".yde/workspace.yaml" to "scopes: [{id: input, include: ['schemas/input/**']}]\n")
        documents.forEachIndexed { index, node ->
            val raw = YamlIO.toAny(node) as? Map<*, *>
                ?: error("\$schemaModel[$index]: スキーマ文書はマップです")
            val id = (raw["id"] as? String)?.takeIf { it.isNotBlank() }
                ?: error("\$schemaModel[$index]: 空でない文字の id が必要です")
            require(ids.add(id)) { "\$schemaModel[$index]: スキーマ ID「$id」が重複しています" }
            // ID をファイル名にせず、入力の位置を使う。解決後の宣言の document もこの仮の座標になる。
            texts[".yde/schemas/input/$index.yaml"] = YamlIO.dumpPlain(node)
        }
        val store = InMemoryDocumentStore(texts)
        val loaded = WorkspaceReader.load(store, builtins = BuiltinSchemaResources.kernelSchemas(WorkspaceReader.detectLayout(store)))
        val errors = loaded.schemaErrors + loaded.parseErrors.map { (document, message) -> "${document.value}: $message" }
        require(errors.isEmpty()) { "\$schemaModel: スキーマを解決できません\n${errors.joinToString("\n")}" }
        val checks = WorkspaceCheckRunner.validateAll(loaded)
        val invalid = checks.flatMap { (document, result) ->
            result.issues.filter { it.severity == Severity.ERROR }.map { "${document.value}:${it.path}: ${it.message}" } +
                result.withheld.map { "${document.value}: ${it.text}" }
        }
        require(invalid.isEmpty()) { "\$schemaModel: 定義の検査に失敗しました\n${invalid.joinToString("\n")}" }
        return BusinessModel.of(loaded, "input")
    }
}
