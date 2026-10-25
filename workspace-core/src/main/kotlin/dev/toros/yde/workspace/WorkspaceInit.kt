package dev.toros.yde.workspace

import dev.toros.yde.semantic.SemanticTransform
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.Projection
import dev.toros.yde.semantic.schema.SchemaScaffold
import dev.toros.yde.semantic.schema.SchemaTreeParser
import dev.toros.yde.semantic.schema.TransformDef
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YNode
import java.nio.file.Files
import java.nio.file.Path

/**
 * ワークスペースの初期化（新しいワークスペースを作る。1 つのワークスペースに 1 度だけ動かす）。
 * 求めは 2 つ: テンプレートの展開（答え・サンプルデータの有無）と、既存のフォルダへの後入れ（台帳の宣言だけ）。
 * 計画（どのファイルにどんな中身を書くか）はここだけが作り、画面と CLI はそれを見せて書く。
 * 答えから YAML を組み立てるのは変換定義のひな型（`SemanticTransform`）で、Kotlin で文字列を組み立てない。
 * ワークスペースを開く前に書くので履歴（Undo）は持たず、既存のファイルは上書きしない。
 */
sealed interface InitRequest {
    /** テンプレートを展開する。問いを持つテンプレートには答えを渡す。 */
    data class FromTemplate(
        val template: WorkspaceTemplate,
        val answers: Map<String, Any?> = emptyMap(),
        val includeSampleData: Boolean = true,
    ) : InitRequest

    /** 既存のフォルダに後入れする（`.yde/workspace.yaml` に台帳を宣言するだけ）。空なら全体が台帳。 */
    data class Adopt(val ledger: List<String> = emptyList()) : InitRequest
}

/** 計画した 1 ファイル。`exists` は書き込み先に既にあって書かないもの、`sample` は台帳の中のサンプルデータ。 */
data class PlannedFile(val path: String, val text: String, val exists: Boolean = false, val sample: Boolean = false)

/** 初期化の計画。`directories` は中身が無くても作るフォルダ（台帳）。 */
data class InitPlan(val files: List<PlannedFile>, val directories: List<String> = emptyList(), val notes: List<String> = emptyList()) {
    val toWrite: List<PlannedFile> get() = files.filter { !it.exists }
    val skipped: List<String> get() = files.filter { it.exists }.map { it.path }
}

data class InitResult(val written: List<String>, val skipped: List<String>)

object WorkspaceInit {

    /** 初期化済みか（`.yde/` かレガシーの `_schemas/` がある）。印は残さず、配置の有無だけで判定する。 */
    fun isInitialized(root: Path): Boolean =
        Files.isDirectory(root.resolve(WorkspaceLayout.CONFIG_DIR)) || Files.isDirectory(root.resolve(WorkspaceLayout.Zone.SCHEMAS.legacyDir))

    /** フォルダの中の YAML の数（隠しフォルダは除く）。後入れできるかの目安。 */
    fun yamlCount(root: Path): Int = if (!Files.isDirectory(root)) 0 else countYaml(root)

    /** 後入れの台帳の候補: 直下のフォルダと、その中の YAML の数（隠しフォルダと予約ディレクトリは除く）。 */
    fun ledgerCandidates(root: Path): List<Pair<String, Int>> {
        if (!Files.isDirectory(root)) return emptyList()
        val reserved = WorkspaceLayout.Zone.entries.map { it.legacyDir }.toSet() + WorkspaceLayout.CONFIG_DIR
        return Files.list(root).use { stream ->
            stream.filter { Files.isDirectory(it) }
                .map { it.fileName.toString() }
                .filter { !it.startsWith(".") && it !in reserved }
                .sorted()
                .toList()
        }.map { name -> name to countYaml(root.resolve(name)) }
    }

    private fun countYaml(dir: Path): Int = Files.walk(dir, 16).use { stream ->
        stream.filter { p ->
            val rel = dir.relativize(p)
            Files.isRegularFile(p) && (p.toString().endsWith(".yaml") || p.toString().endsWith(".yml")) &&
                (0 until rel.nameCount).none { rel.getName(it).toString().startsWith(".") }
        }.count().toInt()
    }

    // ---------------------------------------------------------------- 答え

    /**
     * 答えを整える: 文字の前後の空白を落とし、空の答えは外し、無い答えには問いの既定値を入れる（入れ子の項目も同じ）。
     */
    fun normalizeAnswers(template: WorkspaceTemplate, answers: Map<String, Any?>): Map<String, Any?> {
        fun clean(field: FieldDef?, value: Any?): Any? {
            val f = field?.let { template.resolvedParam(it) }
            return when (value) {
                null -> null
                is String -> value.trim().takeIf { it.isNotEmpty() }
                is Map<*, *> -> {
                    val out = LinkedHashMap<String, Any?>()
                    val children = f?.fields.orEmpty()
                    children.forEach { c ->
                        val key = c.key ?: return@forEach
                        val v = clean(c, value[key]) ?: c.default
                        if (v != null) out[key] = v
                    }
                    value.entries.filter { (k, _) -> children.none { it.key == k.toString() } }
                        .forEach { (k, v) -> clean(null, v)?.let { out[k.toString()] = it } }
                    out.takeIf { it.isNotEmpty() }
                }
                is List<*> -> value.mapNotNull { clean(f?.item, it) }.takeIf { it.isNotEmpty() }
                else -> value
            }
        }
        val out = LinkedHashMap<String, Any?>()
        template.params.forEach { p ->
            val key = p.key ?: return@forEach
            val v = clean(p, answers[key]) ?: p.default
            if (v != null) out[key] = v
        }
        answers.keys.filter { k -> template.params.none { it.key == k } }.forEach { k -> clean(null, answers[k])?.let { out[k] = it } }
        return out
    }

    /** 答えの点検（必須・形式・選択肢・数）。問題が無ければ空。整えた答えに対して行う。 */
    fun checkAnswers(template: WorkspaceTemplate, answers: Map<String, Any?>): List<String> {
        val out = mutableListOf<String>()
        fun check(field: FieldDef, value: Any?, where: String) {
            val f = template.resolvedParam(field)
            val label = f.label ?: f.key ?: where
            val blank = value == null || (value is String && value.isBlank()) || (value is List<*> && value.isEmpty())
            if (blank) {
                if (f.required) out += "「$label」を入力してください"
                return
            }
            when (f.type) {
                FieldType.ARRAY -> {
                    val list = value as? List<*> ?: run { out += "「$label」は一覧で書きます"; return }
                    f.item?.let { item -> list.forEachIndexed { i, v -> check(item, v, "$where[${i + 1}]") } }
                }
                FieldType.OBJECT -> {
                    val m = value as? Map<*, *> ?: run { out += "「$label」は項目の組で書きます"; return }
                    f.fields.forEach { c -> c.key?.let { k -> check(c, m[k], "$where.$k") } }
                }
                FieldType.ENUM -> {
                    val allowed = f.enumOptions().map { it.value }
                    if (value.toString() !in allowed) out += "「$label」は ${allowed.joinToString(" / ")} から選びます（$where）"
                }
                FieldType.INT -> if (value.toString().toLongOrNull() == null) out += "「$label」は整数で書きます（$where）"
                FieldType.NUMBER -> if (value.toString().toDoubleOrNull() == null) out += "「$label」は数で書きます（$where）"
                FieldType.BOOLEAN -> if (value !is Boolean && value.toString() !in setOf("true", "false")) out += "「$label」は true / false で書きます（$where）"
                else -> {
                    val text = value.toString()
                    f.patterns.forEach { p ->
                        if (!Regex(p).containsMatchIn(text)) out += "「$label」は形式（$p）に合いません: $text"
                    }
                }
            }
        }
        template.params.forEach { p -> p.key?.let { k -> check(p, answers[k], k) } }
        answers.keys.filter { k -> template.params.none { it.key == k } }.forEach { out += "「$it」は問いにありません" }
        return out
    }

    // ---------------------------------------------------------------- 計画

    /** 計画を作る。`root` は書き込み先（まだ無くてもよい。あれば既存のファイルを `exists` に印す）。 */
    fun plan(root: Path?, request: InitRequest): InitPlan = when (request) {
        is InitRequest.FromTemplate -> planTemplate(root, request)
        is InitRequest.Adopt -> planAdoption(root, request)
    }

    private fun configPath() = "${WorkspaceLayout.CONFIG_DIR}/${WorkspaceLayout.CONFIG_FILE}"

    private fun planAdoption(root: Path?, request: InitRequest.Adopt): InitPlan {
        val ledger = request.ledger.map { it.replace('\\', '/').trim().trim('/') }.filter { it.isNotBlank() }
        ledger.firstOrNull { it.contains("..") }?.let { error("台帳のフォルダに使えないパスです: $it") }
        val text = buildString {
            appendLine("# ワークスペース構成。ledger は台帳（ID 索引・検証の集計・エージェントの対象）にするフォルダ。未宣言なら全体が台帳")
            if (ledger.isEmpty()) appendLine("# ledger: doc")
            else if (ledger.size == 1) appendLine("ledger: ${ledger.single()}")
            else {
                appendLine("ledger:")
                ledger.forEach { appendLine("  - $it") }
            }
        }
        val path = configPath()
        return InitPlan(
            files = listOf(PlannedFile(path, text, exists = root?.let { Files.exists(it.resolve(path)) } ?: false)),
            notes = listOf("開いたあと、設計スペースの「既存文書から推測」で、いまある YAML からスキーマのドラフトを作れます。"),
        )
    }

    private fun planTemplate(root: Path?, request: InitRequest.FromTemplate): InitPlan {
        val template = request.template
        val answers = normalizeAnswers(template, request.answers)
        val problems = checkAnswers(template, answers)
        require(problems.isEmpty()) { problems.joinToString("\n") }
        val input = YamlIO.fromAny(answers)
        val built = mutableListOf<PlannedFile>()
        for (file in template.files) {
            val path = fillPath(template, file.path, input)
            val text = when {
                !template.hasParams -> file.text
                isYaml(path) -> buildYaml(template, file, input, answers) ?: continue
                else -> Projection.evaluate(file.text, listOf(input)).text
            }
            built += PlannedFile(path, withNewline(text))
        }
        val ledger = built.firstOrNull { it.path == configPath() }?.let { WorkspaceLayout.parseLedgerText(it.text) }.orEmpty()
        fun isData(path: String): Boolean =
            !path.startsWith("${WorkspaceLayout.CONFIG_DIR}/") && !path.endsWith(".md") &&
                (ledger.isEmpty() || ledger.any { path == it || path.startsWith("$it/") })
        val marked = built.map { it.copy(sample = isData(it.path)) }
        val kept = if (request.includeSampleData) marked
        else marked.filter { !it.sample } + emptyDocuments(marked.filter { !it.sample })
        val files = kept.map { f -> f.copy(exists = root?.let { Files.exists(it.resolve(f.path)) } ?: false) }
        val notes = buildList {
            if (!request.includeSampleData && marked.any { it.sample }) add("サンプルデータは入れず、一覧のファイルはスキーマの初期値で空に作ります。")
        }
        return InitPlan(files, directories = ledger, notes = notes)
    }

    private fun isYaml(path: String) = path.endsWith(".yaml") || path.endsWith(".yml")

    private fun withNewline(text: String) = if (text.endsWith("\n")) text else text + "\n"

    /** ファイル名の `{名前}` を答えで埋める（問いを持たないテンプレートではそのまま）。埋まらない穴があれば誤り。 */
    private fun fillPath(template: WorkspaceTemplate, path: String, input: YNode): String {
        if (!template.hasParams) return path
        val evaluated = Projection.evaluate(path, listOf(input))
        require(evaluated.complete) { "テンプレート「${template.id}」のファイル名の穴が埋まりません: $path" }
        require(!evaluated.text.contains("..") && !evaluated.text.startsWith("/")) { "テンプレート「${template.id}」のファイル名が不正です: ${evaluated.text}" }
        return evaluated.text
    }

    /**
     * YAML のファイルを、答えを「いまの場所」として変換定義のひな型で組み立てる。
     * 組み立てた結果が無い（条件に合わない）か空のマップなら、そのファイルは作らない。
     */
    private fun buildYaml(template: WorkspaceTemplate, file: TemplateFile, input: YNode, answers: Map<String, Any?>): String? {
        val raw = YamlIO.parseToRaw(file.text)
        val def = TransformDef.parse(
            linkedMapOf("id" to "${template.id}/${file.path}", "params" to template.params.mapNotNull { it.key }, "build" to raw),
            "templates/${template.id}/${file.path}",
        )
        val term = SemanticTransform.run(def, input, null, answers).term ?: return null
        if (term is YNode.Map && term.entries.isEmpty()) return null
        return YamlIO.dumpPlain(term)
    }

    /**
     * サンプルデータを入れないときの、空の文書: 組み立てたスキーマのうち `match` が 1 つのファイルを指す文書スキーマごとに、
     * スキーマの初期値（必須の項目と、既定値を持つ root 直下の項目）で作る。glob で複数のファイルを受けるスキーマのぶんは作らない。
     */
    private fun emptyDocuments(planned: List<PlannedFile>): List<PlannedFile> {
        val schemasDir = "${WorkspaceLayout.CONFIG_DIR}/${WorkspaceLayout.Zone.SCHEMAS.ydeName}/"
        val taken = planned.map { it.path }.toMutableSet()
        val out = mutableListOf<PlannedFile>()
        for (file in planned.filter { it.path.startsWith(schemasDir) && isYaml(it.path) }) {
            val raw = runCatching { YamlIO.parseToRaw(file.text) as? Map<*, *> }.getOrNull() ?: continue
            val kind = raw["kind"]?.toString()
            if (kind != null && kind != "document") continue
            val match = raw["match"] as? String ?: continue
            if (match.any { it in "*?[{" } || match in taken) continue
            val tree = runCatching { SchemaTreeParser.parse(file.text, file.path) }.getOrNull() ?: continue
            val node = SchemaScaffold.defaultTree(tree)
            if (node is YNode.Map) {
                val root = tree.resolve(tree.root) ?: tree.root
                root.fields.forEach { f ->
                    val key = f.key ?: return@forEach
                    val resolved = tree.resolve(f) ?: f
                    if (key !in node.entries && resolved.default != null) node.entries[key] = YamlIO.fromAny(resolved.default)
                }
            }
            taken += match
            out += PlannedFile(match, YamlIO.dumpPlain(node))
        }
        return out
    }

    // ---------------------------------------------------------------- 書き込み

    /** 計画を書く。既存のファイルは書かずに `skipped` へ。フォルダは無ければ作る。 */
    fun apply(root: Path, plan: InitPlan): InitResult {
        val base = root.toAbsolutePath().normalize()
        Files.createDirectories(base)
        fun inside(rel: String): Path {
            val target = base.resolve(rel).normalize()
            require(target.startsWith(base)) { "ワークスペースの外には書けません: $rel" }
            return target
        }
        plan.directories.forEach { Files.createDirectories(inside(it)) }
        val written = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for (file in plan.files) {
            val target = inside(file.path)
            if (Files.exists(target)) {
                skipped += file.path
                continue
            }
            target.parent?.let { Files.createDirectories(it) }
            Files.writeString(target, file.text, Charsets.UTF_8)
            written += file.path
        }
        return InitResult(written, skipped)
    }
}
