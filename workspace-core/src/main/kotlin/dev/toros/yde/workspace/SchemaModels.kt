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
        val bundle = input as? YNode.Map
        if (bundle == null) return BusinessModel.of(load(input), "input")
        require(bundle.entries.keys.all { it in setOf("schemas", "views") }) { "\$schemaModel: schemas と views 以外のキーがあります" }
        val schemas = bundle.entries["schemas"] ?: error("\$schemaModel: schemas が必要です")
        return BusinessModel.of(load(schemas, views = viewsOf(bundle, "\$schemaModel")), "input")
    }

    /** 文書の内容を変えず、渡された定義と文書だけで構造・参照・成立条件を検査する。 */
    fun validate(input: YNode): YNode {
        val root = input as? YNode.Map ?: error("\$validate: 入力は schemas と documents のマップです")
        require(root.entries.keys.all { it in setOf("schemas", "views", "documents") }) { "\$validate: schemas・views・documents 以外のキーがあります" }
        val schemas = root.entries["schemas"] ?: error("\$validate: schemas が必要です")
        val documents = root.entries["documents"] as? YNode.Map ?: error("\$validate: documents は文書の場所と内容のマップです")
        val texts = documents.entries.mapValues { (path, value) ->
            require(path.endsWith(".yaml") && path.split('/').all { it.isNotEmpty() && !it.startsWith('.') } && ':' !in path && '\\' !in path) {
                "\$validate: 文書の場所は予約領域の外の相対 YAML パスです: $path"
            }
            YamlIO.dumpPlain(value)
        }
        load(schemas, "\$validate", texts, viewsOf(root, "\$validate"))
        return YamlIO.fromAny(YamlIO.toAny(documents))
    }

    private fun viewsOf(input: YNode.Map, operation: String): List<YNode> = input.entries["views"]?.let {
        (it as? YNode.Seq)?.items ?: error("$operation: views はビュー定義の配列です")
    }.orEmpty()

    internal fun load(input: YNode, operation: String = "\$schemaModel", values: Map<String, String> = emptyMap(), views: List<YNode> = emptyList()): LoadedWorkspace {
        val documents = (input as? YNode.Seq)?.items
            ?: error("$operation: 入力はスキーマ文書の配列です")
        val ids = mutableSetOf<String>()
        val texts = linkedMapOf(".yde/workspace.yaml" to "scopes: [{id: input, include: ['schemas/input/**', 'views/input/**']}]\n")
        documents.forEachIndexed { index, node ->
            val raw = YamlIO.toAny(node) as? Map<*, *>
                ?: error("$operation[$index]: スキーマ文書はマップです")
            val id = (raw["id"] as? String)?.takeIf { it.isNotBlank() }
                ?: error("$operation[$index]: 空でない文字の id が必要です")
            require(ids.add(id)) { "$operation[$index]: スキーマ ID「$id」が重複しています" }
            // ID をファイル名にせず、入力の位置を使う。解決後の宣言の document もこの仮の座標になる。
            texts[".yde/schemas/input/$index.yaml"] = YamlIO.dumpPlain(node)
        }
        val viewIds = mutableSetOf<String>()
        views.forEachIndexed { index, node ->
            val raw = YamlIO.toAny(node) as? Map<*, *> ?: error("$operation.views[$index]: ビュー定義はマップです")
            val id = (raw["id"] as? String)?.takeIf { it.isNotBlank() } ?: error("$operation.views[$index]: 空でない文字の id が必要です")
            require(viewIds.add(id)) { "$operation.views[$index]: ビュー ID「$id」が重複しています" }
            texts[".yde/views/input/$index.yaml"] = YamlIO.dumpPlain(node)
        }
        val store = InMemoryDocumentStore(texts + values)
        val loaded = WorkspaceReader.load(store, builtins = BuiltinSchemaResources.kernelSchemas(WorkspaceReader.detectLayout(store)))
        val errors = loaded.schemaErrors + loaded.parseErrors.map { (document, message) -> "${document.value}: $message" }
        require(errors.isEmpty()) { "$operation: スキーマを解決できません\n${errors.joinToString("\n")}" }
        require(values.keys.all { path -> loaded.snapshot.documents.entries.any { it.key.value == path && it.value.schemaId != null } }) { "$operation: 文書に対応するスキーマが必要です" }
        val checks = WorkspaceCheckRunner.validateAll(loaded)
        val invalid = checks.flatMap { (document, result) ->
            result.issues.filter { it.severity == Severity.ERROR }.map { "${document.value}:${it.path}: ${it.message}" } +
                result.withheld.map { "${document.value}: ${it.text}" }
        }
        require(invalid.isEmpty()) { "$operation: 検査に失敗しました\n${invalid.joinToString("\n")}" }
        return loaded
    }
}
