package dev.toros.yde.app.core

import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.SchemaKind
import dev.toros.yde.semantic.LensFilters
import dev.toros.yde.workspace.ActionFailure
import dev.toros.yde.workspace.ActionPlanner
import dev.toros.yde.workspace.ActionRequest
import dev.toros.yde.workspace.BuiltinSchemaResources
import dev.toros.yde.workspace.FileSystemDocumentStore
import dev.toros.yde.workspace.Generators
import dev.toros.yde.workspace.WorkspaceSession
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.scalarText
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.yaml.validate.Severity
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 仕様（devtrack/spec/。定義に書ける語）が今の実装と同じであることを CI で守る（devtrack/README.md「仕様」）。
 * 検証のエラーが無いことと、docs/spec/ の参照文書が最新であることは DevtrackTest が見る。ここは実装との一致:
 * - 位置「項目定義」の語と書ける基底の種類は、メタスキーマ（builtin-schemas/schema.yaml の definitions.field）と一致する
 * - 基底の種類は、実装（FieldType）・メタスキーマの type の選択肢と一致する
 * - 位置「スキーマ文書」の語は、スキーマと関係レジストリのメタスキーマの root と一致し、関係レジストリに書ける語も一致する
 * - スキーマの種類は、実装（SchemaKind）・メタスキーマの kind の選択肢と一致する
 * - メタスキーマの中の場所（meta）を書いた位置（関係・振る舞い・操作）は、語と必須がその場所の項目の並びと一致する
 * - 実装の語の集まり（code）を書いた位置（クエリ・条件・値の比較・値の式）は、語が読み取りの実装のキーの集まりと列挙と同じ
 * - どの位置も、参照文書のレポートのどれかに載っている
 * - 値の式の語は、どれも例で示されている（列挙やキーの集まりに無い語も、動きで実装と結ぶ）
 * - 仕様の例（spec/examples）は、一時のワークスペースに書いて開くと、書いてあるとおりの結果（通る・読み込みの誤り・エラー・警告）になる
 * - 語の ID の形の検査が働く。ダッシュボード「仕様」が解決できる
 */
class SpecTest {

    private val root: Path = Path.of("devtrack")

    private fun errorsOf(ws: Workspace): List<String> = ws.files.flatMap { file ->
        val rel = ws.relPath(file)
        ws.validate(rel).filter { it.severity == Severity.ERROR }.map { "$rel ${it.path.pretty()}: ${it.message}" }
    }

    private fun raw(path: Path): Map<*, *> = YamlIO.parseToRaw(path.readText()) as Map<*, *>

    /** 位置 CTX-field の語: キー → 書ける基底の種類（null＝どの種類にも書ける）。 */
    private fun specFieldKeywords(): Map<String, Set<String>?> =
        root.resolve("spec/keywords").listDirectoryEntries("*.yaml").map(::raw).filter { it["context"] == "CTX-field" }.associate { kw ->
            val forms = (kw["forms"] as List<*>).map { (it as Map<*, *>)["applies_to"] as List<*>? }
            (kw["key"] as String) to if (forms.any { it == null }) null else forms.flatMap { it!!.map(Any?::toString) }.toSet()
        }

    /** メタスキーマの項目定義（definitions.field）: キー → 書ける基底の種類（null＝type の cases の外＝どの種類にも書ける）。 */
    private fun metaFieldKeywords(): Map<String, Set<String>?> {
        val field = ((raw("schema")["definitions"] as Map<*, *>)["field"] as Map<*, *>)["fields"] as List<*>
        val common = field.map { (it as Map<*, *>)["key"] as String }.associateWith<String, Set<String>?> { null }
        val byType = metaTypeField()["cases"] as Map<*, *>
        val cased = byType.flatMap { (type, fields) -> (fields as List<*>).map { ((it as Map<*, *>)["key"] as String) to type.toString() } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
        return cased + common
    }

    /** 組込みのメタスキーマ以外の定義の形（あとから足せる種類のビューの断片と、フローの図のモデル。app-core の資源）。 */
    private val extraResources = mapOf("view-chart" to "/view/chart/schema.yaml", "view-flow" to "/view/flow/schema.yaml", "flow-model" to "/view/flow/flow-model.yaml")

    private fun raw(builtin: String): Map<*, *> {
        val text = extraResources[builtin]?.let { path ->
            requireNotNull(Workspace::class.java.getResourceAsStream(path)) { "資源が見つかりません: $path" }.use { it.readBytes().decodeToString() }
        } ?: BuiltinSchemaResources.text(builtin)
        return YamlIO.parseToRaw(text) as Map<*, *>
    }

    private fun metaTypeField(): Map<*, *> =
        ((((raw("schema")["definitions"] as Map<*, *>)["field"] as Map<*, *>)["fields"] as List<*>).single { (it as Map<*, *>)["key"] == "type" }) as Map<*, *>

    @Test
    fun `ダッシュボード「仕様」の区画が解決できる`() {
        val ws = Workspace.open(root)
        val views = Views.load(root, ws.schemas)
        val dashboard = assertNotNull(views.dashboard("spec-dashboard"))
        val resolved = Dashboards.resolve(dashboard, LocalDate.of(2026, 11, 16), views, ws.index, { ws.schemaFor(it) }, ws::treeOf)
        resolved.sections.forEach { s -> assertEquals<String?>(null, s.error, s.definition.id) }
    }

    @Test
    fun `項目定義の語と書ける基底の種類は メタスキーマと同じ`() {
        val spec = specFieldKeywords()
        val meta = metaFieldKeywords()
        assertEquals(meta.keys.sorted(), spec.keys.sorted(), "仕様（devtrack/spec/keywords の CTX-field）とメタスキーマ（definitions.field）で、語が違います")
        val differing = meta.keys.filter { meta[it] != spec[it] }.map { "$it: メタスキーマ ${meta[it] ?: "すべて"}・仕様 ${spec[it] ?: "すべて"}" }
        assertTrue(differing.isEmpty(), "書ける基底の種類が、仕様とメタスキーマで違います:\n" + differing.joinToString("\n"))
    }

    @Test
    fun `基底の種類は 実装とメタスキーマの選択肢と仕様の型と文書で同じ`() {
        val implemented = FieldType.entries.map { it.id }
        assertEquals(implemented, (metaTypeField()["values"] as List<*>).map(Any?::toString), "メタスキーマの type の選択肢")
        val specType = ((raw(root.resolve(".yde/schemas/spec.yaml"))["definitions"] as Map<*, *>)["基底の種類"] as Map<*, *>)["values"] as List<*>
        assertEquals(implemented, specType.map(Any?::toString), "devtrack の型 spec#基底の種類 の選択肢")
        val documented = root.resolve("spec/base-types").listDirectoryEntries("*.yaml").map(::raw)
        assertEquals(implemented.sorted(), documented.map { it["name"].toString() }.sorted(), "devtrack/spec/base-types の文書")
        documented.forEach { assertEquals("BT-${it["name"]}", it["id"], "基底の種類の ID は BT-＋名前") }
    }

    /** メタスキーマの root に並ぶキー。 */
    private fun rootKeys(builtin: String): Set<String> = ((raw(builtin)["root"] as Map<*, *>)["fields"] as List<*>).map { (it as Map<*, *>)["key"] as String }.toSet()

    @Test
    fun `スキーマ文書の語と関係レジストリに書ける語は メタスキーマと同じ`() {
        // 語 → 書けるスキーマの種類（null＝どの種類にも書ける）
        val spec = root.resolve("spec/keywords").listDirectoryEntries("*.yaml").map(::raw).filter { it["context"] == "CTX-schema" }.associate { kw ->
            val forms = (kw["forms"] as List<*>).map { (it as Map<*, *>)["kinds"] as List<*>? }
            (kw["key"] as String) to if (forms.any { it == null }) null else forms.flatMap { it!!.map(Any?::toString) }.toSet()
        }
        val schema = rootKeys("schema")
        val registry = rootKeys("relations")
        assertEquals((schema + registry).sorted(), spec.keys.sorted(), "仕様（devtrack/spec/keywords の CTX-schema）とメタスキーマの root で、語が違います")
        val inRegistry = spec.filterValues { it == null || "relations" in it }.keys
        assertEquals(registry.sorted(), inRegistry.sorted(), "関係レジストリ（kind: relations）に書ける語が、仕様と builtin-schemas/relations.yaml で違います")
        val elsewhere = spec.filterValues { it == null || it.any { k -> k != "relations" } }.keys
        assertEquals(schema.sorted(), elsewhere.sorted(), "関係レジストリ以外に書ける語が、仕様と builtin-schemas/schema.yaml で違います")
    }

    @Test
    fun `関係レジストリのメタスキーマの検査のキーは スキーマ文書のメタスキーマの写しと同じ`() {
        fun fields(builtin: String) = ((raw(builtin)["root"] as Map<*, *>)["fields"] as List<*>).map { it as Map<*, *> }.associateBy { it["key"].toString() }
        val schema = fields("schema")
        val registry = fields("relations")
        listOf("constraints", "functions", "vocabulary", "matrix", "flow").forEach { key ->
            assertEquals(schema[key], registry[key], "builtin-schemas/relations.yaml の $key が、schema.yaml の $key と違います（両方を同じに直す）")
        }
    }

    @Test
    fun `スキーマの種類は 実装とメタスキーマの選択肢と仕様の型と文書で同じ`() {
        val implemented = SchemaKind.entries.map { it.id }
        val metaKind = ((raw("schema")["root"] as Map<*, *>)["fields"] as List<*>).single { (it as Map<*, *>)["key"] == "kind" } as Map<*, *>
        assertEquals(implemented, (metaKind["values"] as List<*>).map(Any?::toString), "メタスキーマの kind の選択肢")
        val specType = ((raw(root.resolve(".yde/schemas/spec.yaml"))["definitions"] as Map<*, *>)["スキーマの種類"] as Map<*, *>)["values"] as List<*>
        assertEquals(implemented, specType.map(Any?::toString), "devtrack の型 spec#スキーマの種類 の選択肢")
        val documented = root.resolve("spec/schema-kinds").listDirectoryEntries("*.yaml").map(::raw)
        assertEquals(implemented.sorted(), documented.map { it["name"].toString() }.sorted(), "devtrack/spec/schema-kinds の文書")
        documented.forEach { assertEquals("KIND-${it["name"]}", it["id"], "スキーマの種類の ID は KIND-＋名前") }
    }

    /**
     * メタスキーマの中の場所（位置の meta.path）をたどり、そこの項目の並びを返す。項目の並び（fields）を持つ場所では項目のキーで、
     * そうでない場所ではマップのキーでたどる（配列の要素は item）。
     */
    private fun metaFields(builtin: String, path: List<String>): List<Map<*, *>> {
        val node = path.fold<String, Map<*, *>>(raw(builtin)) { at, step ->
            // 項目の並びにそのキーの項目があればそれ、無ければマップのキー（断片の section のように、項目の並びの隣に置かれたもの）
            val fields = at["fields"] as List<*>?
            (fields?.map { it as Map<*, *> }?.singleOrNull { it["key"] == step } ?: at[step] as? Map<*, *>)
                ?: error("メタスキーマ $builtin に ${path.joinToString(".")} の $step がありません")
        }
        return (node["fields"] as List<*>).map { it as Map<*, *> }
    }

    @Test
    fun `メタスキーマの中の場所を書いた位置は 語と必須がメタスキーマと同じ`() {
        val keywords = root.resolve("spec/keywords").listDirectoryEntries("*.yaml").map(::raw).groupBy { it["context"].toString() }
        val contexts = root.resolve("spec/contexts").listDirectoryEntries("*.yaml").map(::raw).filter { it["meta"] != null }
        assertTrue(contexts.size >= 20, "メタスキーマの中の場所を書いた位置が読めていること: ${contexts.size}")
        // 同じ場所を分け合う位置（ビューの種類ごとの位置が、ビューのメタスキーマの root を分け合う）は、語を合わせて突き合わせる
        val differing = contexts.groupBy { ctx -> (ctx["meta"] as Map<*, *>).let { "${it["schema"]} ${(it["path"] as List<*>).joinToString(".")}" } }.mapNotNull { (place, sharing) ->
            val ids = sharing.map { it["id"].toString() }
            val meta = sharing.first()["meta"] as Map<*, *>
            val fields = metaFields(meta["schema"].toString(), (meta["path"] as List<*>).map(Any?::toString))
            // 分かっているずれ（読み取りは読むが、メタスキーマに無い語）: メタスキーマに足されたら、位置の meta.missing から外す
            val missing = sharing.flatMap { ((it["meta"] as Map<*, *>)["missing"] as List<*>?).orEmpty() }.map(Any?::toString)
            val stale = missing.filter { key -> fields.any { it["key"] == key } }
            if (stale.isNotEmpty()) return@mapNotNull "$ids: meta.missing の $stale は、もうメタスキーマにあります（missing から外す）"
            val metaKeys = fields.associate { it["key"].toString() to (it["required"] == true) } + missing.associateWith { false }
            val specKeys = ids.flatMap { keywords[it].orEmpty() }.groupBy { it["key"].toString() }.mapValues { (_, same) ->
                same.any { kw -> (kw["forms"] as List<*>).any { (it as Map<*, *>)["required"] == true } }
            }
            if (metaKeys == specKeys) null
            else "$ids（$place）: メタスキーマにあって仕様に無い ${(metaKeys.keys - specKeys.keys).sorted()}・仕様にあってメタスキーマに無い ${(specKeys.keys - metaKeys.keys).sorted()}・" +
                "必須が違う ${metaKeys.keys.intersect(specKeys.keys).filter { metaKeys[it] != specKeys[it] }.sorted()}"
        }
        assertTrue(differing.isEmpty(), "語か必須が、仕様とメタスキーマで違います:\n" + differing.joinToString("\n"))
    }

    /** 読み取りの実装が「使えるキー」として持つ集まり（クラス名.名前。companion の private val＝外側のクラスの static の欄）。 */
    private fun codeSet(name: String): Set<String> {
        // 頭の小文字で始まる部分はパッケージ（省くと semantic.schema）。残りがクラス（入れ子は $ でつなぐ）と欄の名前
        val parts = name.split('.')
        val pkg = parts.takeWhile { it.first().isLowerCase() }.ifEmpty { listOf("semantic", "schema") }
        val classes = parts.dropWhile { it.first().isLowerCase() }.dropLast(1)
        val type = Class.forName("dev.toros.yde." + pkg.joinToString(".") + "." + classes.joinToString("$"))
        val field = type.getDeclaredField(parts.last()).apply { isAccessible = true }
        return (field.get(null) as Collection<*>).map(Any?::toString).toSet()
    }

    /** 値がそのまま語になる列挙の id の集まり（クラス名。入れ子は ValueExpr.UnaryFn の形）。 */
    private fun codeEnum(name: String): Set<String> {
        val type = Class.forName("dev.toros.yde.semantic.schema." + name.replace('.', '$'))
        return type.enumConstants.map { type.getMethod("getId").invoke(it).toString() }.toSet()
    }

    @Test
    fun `実装の語の集まりを書いた位置は 語が読み取りの実装と同じ`() {
        val keywords = root.resolve("spec/keywords").listDirectoryEntries("*.yaml").map(::raw).groupBy { it["context"].toString() }
        val contexts = root.resolve("spec/contexts").listDirectoryEntries("*.yaml").map(::raw).filter { it["code"] != null }
        assertTrue(contexts.size >= 5, "実装の語の集まりを書いた位置が読めていること: ${contexts.size}")
        val differing = contexts.mapNotNull { ctx ->
            val code = ctx["code"] as Map<*, *>
            fun names(key: String) = (code[key] as List<*>?).orEmpty().map(Any?::toString)
            val implemented = names("sets").flatMap(::codeSet).toSet() + names("enums").flatMap(::codeEnum) + names("extra")
            val spec = keywords[ctx["id"].toString()].orEmpty().map { it["key"].toString() }.toSet()
            if (implemented == spec) null
            else "${ctx["id"]}: 実装にあって仕様に無い ${(implemented - spec).sorted()}・仕様にあって実装に無い ${(spec - implemented).sorted()}"
        }
        assertTrue(differing.isEmpty(), "語が、仕様と読み取りの実装で違います:\n" + differing.joinToString("\n"))
    }

    /** 例を一時のワークスペースに書いて開き、結果（通る・スキーマの読み込みの誤り・検証のエラー・警告）と、その文面を返す。 */
    private fun runExample(example: Map<*, *>, base: Path): Pair<String, List<String>> {
        val dir = base.resolve(example["id"].toString())
        Files.createDirectories(dir.resolve(".yde"))
        (example["files"] as List<*>).map { it as Map<*, *> }.forEach { file ->
            val to = dir.resolve(file["path"].toString())
            Files.createDirectories(to.parent)
            to.writeText(file["text"].toString() + "\n")
        }
        val ws = Workspace.open(dir)
        if (ws.schemaErrors.isNotEmpty()) return "schema-error" to ws.schemaErrors
        val views = Views.load(dir, ws.schemas)
        if (views.errors.isNotEmpty()) return "view-error" to views.errors.map { it.toString() }
        val reports = Reports.load(dir)
        if (reports.errors.isNotEmpty()) return "report-error" to reports.errors.map { it.toString() }
        val issues = ws.files.flatMap { file -> ws.validate(ws.relPath(file)).map { it.severity to "${ws.relPath(file)} ${it.path.pretty()}: ${it.message}" } }
        val errors = issues.filter { it.first == Severity.ERROR }.map { it.second }
        val warnings = issues.filter { it.first == Severity.WARNING }.map { it.second }
        return when {
            errors.isNotEmpty() -> "error" to errors
            warnings.isNotEmpty() -> "warning" to warnings
            else -> "ok" to emptyList()
        }
    }

    /** セルの値の文字（配列は「・」でつなぐ。値が無ければ空）。 */
    private fun cellText(node: YNode?): String = when (node) {
        null -> ""
        is YNode.Seq -> node.items.joinToString("・") { cellText(it) }
        else -> scalarText(node) ?: ""
    }

    /** 例のビューを解決して、書いてある行（並びの順）と値を突き合わせる。同じなら null、違えばその文。 */
    private fun viewDiff(example: Map<*, *>, expected: Map<*, *>, base: Path): String? {
        val ws = Workspace.open(base.resolve(example["id"].toString()))
        val view = Views.load(ws.root, ws.schemas).view(expected["id"].toString()) ?: return "${example["id"]}: ビュー ${expected["id"]} がありません"
        val rows = Views.resolve(view, ws.index, ws::treeOf, { ws.schemaFor(it) })
        val actual = rows.map { row -> row.id to row.cells.associate { it.column.label to cellText(it.value) } }
        val wanted = (expected["rows"] as List<*>).map { it as Map<*, *> }
        if (actual.map { it.first } != wanted.map { it["id"].toString() }) return "${example["id"]}: 行は ${actual.map { it.first }}（書いてあるのは ${wanted.map { it["id"] }}）"
        val differing = wanted.zip(actual).flatMap { (want, got) ->
            (want["values"] as Map<*, *>?).orEmpty().filter { (label, value) -> got.second[label.toString()] != value.toString() }
                .map { (label, value) -> "${got.first} の「$label」は「${got.second[label.toString()]}」（書いてあるのは「$value」）" }
        }
        return if (differing.isEmpty()) null else "${example["id"]}: $differing（実際の行: $actual）"
    }

    /** 例のレポートを作って、書いてある出力と突き合わせる（末尾の空白と改行は比べない）。同じなら null、違えばその文。 */
    private fun reportDiff(example: Map<*, *>, expected: Map<*, *>, base: Path): String? {
        val ws = Workspace.open(base.resolve(example["id"].toString()))
        val report = Reports.load(ws.root).report(expected["id"].toString()) ?: return "${example["id"]}: レポート ${expected["id"]} がありません"
        val params = (expected["params"] as Map<*, *>?).orEmpty().entries.associate { it.key.toString() to it.value }
        val result = Reports.run(report, params, Views.load(ws.root, ws.schemas), ws.index, ws::treeOf, ws::schemaFor)
        val wanted = expected["text"].toString().trimEnd()
        return when {
            result.missing.isNotEmpty() -> "${example["id"]}: 埋まらなかった穴 ${result.missing}（出力:\n${result.text}）"
            result.text.trimEnd() != wanted -> "${example["id"]}: 出力が違います。実際:\n${result.text.trimEnd()}\n書いてあるのは:\n$wanted"
            else -> null
        }
    }

    /**
     * 例の操作を計画して適用し、書いてある結果と突き合わせる。断られる例は断りの文面、通る例は適用のあとのファイルの中身
     * （YAML として同じであること）。同じなら null、違えばその文。「今」は決まった日時。
     */
    private fun actionDiff(example: Map<*, *>, expected: Map<*, *>, base: Path): String? {
        val dir = base.resolve(example["id"].toString())
        val ws = Workspace.open(dir)
        val params = (expected["params"] as Map<*, *>?).orEmpty().entries.associate { it.key.toString() to it.value }
        val request = ActionRequest(expected["name"].toString(), expected["target"]?.toString(), params)
        val fails = expected["fails"]?.toString()
        val plan = try {
            ActionPlanner.plan(ws.mirror, request, LocalDateTime.of(2026, 11, 16, 9, 0))
        } catch (e: ActionFailure) {
            return when {
                fails == null -> "${example["id"]}: 操作が断られました: ${e.message}"
                fails !in e.message.orEmpty() -> "${example["id"]}: 断りの文面に「$fails」がありません: ${e.message}"
                else -> null
            }
        }
        if (fails != null) return "${example["id"]}: 操作が断られませんでした（書いてあるのは「$fails」で断られること）"
        val applied = ws.apply(plan.changeSet, requireValid = true)
        if (!applied.ok) return "${example["id"]}: 適用できませんでした: ${applied.results}"
        val differing = (expected["files"] as List<*>?).orEmpty().map { it as Map<*, *> }.mapNotNull { file ->
            val at = dir.resolve(file["path"].toString())
            val actual = if (Files.exists(at)) at.readText() else return@mapNotNull "${file["path"]} がありません"
            if (YamlIO.parseToRaw(actual) == YamlIO.parseToRaw(file["text"].toString())) null else "${file["path"]} の中身は:\n$actual"
        }
        return if (differing.isEmpty()) null else "${example["id"]}: $differing"
    }

    /**
     * 例の生成を計画して、書いてある結果と突き合わせる。誤りになる例は誤りの文面、通る例は作る文書の中身（YAML として同じであること）。
     * 同じなら null、違えばその文。
     */
    private fun generateDiff(example: Map<*, *>, expected: Map<*, *>, base: Path): String? {
        val plan = Generators.plan(WorkspaceSession(FileSystemDocumentStore(base.resolve(example["id"].toString()))))
        val fails = expected["fails"]?.toString()
        if (plan.errors.isNotEmpty()) return when {
            fails == null -> "${example["id"]}: 生成が誤りになりました: ${plan.errors}"
            plan.errors.none { fails in it } -> "${example["id"]}: 誤りの文面に「$fails」がありません: ${plan.errors}"
            else -> null
        }
        if (fails != null) return "${example["id"]}: 生成が誤りになりませんでした（書いてあるのは「$fails」で誤りになること）。作る文書: ${plan.outputs.map { it.document.value }}"
        val outputs = plan.outputs.associate { it.document.value to it.text }
        val differing = (expected["files"] as List<*>?).orEmpty().map { it as Map<*, *> }.mapNotNull { file ->
            val actual = outputs[file["path"].toString()] ?: return@mapNotNull "${file["path"]} を作りません（作る文書: ${outputs.keys}）"
            if (YamlIO.parseToRaw(actual) == YamlIO.parseToRaw(file["text"].toString())) null else "${file["path"]} の中身は:\n$actual"
        }
        return if (differing.isEmpty()) null else "${example["id"]}: $differing"
    }

    @Test
    fun `仕様の例は 開いて検証すると書いてあるとおりの結果になる`() {
        val examples = root.resolve("spec/examples").listDirectoryEntries("*.yaml").map(::raw)
        assertTrue(examples.size >= 10, "例が読めていること: ${examples.size}")
        val base = createTempDirectory("spec-examples")
        try {
            val differing = examples.mapNotNull { example ->
                val expect = example["expect"] as Map<*, *>
                val (result, messages) = runExample(example, base)
                val wanted = expect["message"]?.toString()
                when {
                    result != expect["result"] -> "${example["id"]}: 結果は $result（書いてあるのは ${expect["result"]}）: $messages"
                    wanted != null && messages.none { wanted in it } -> "${example["id"]}: 文面に「$wanted」がありません: $messages"
                    else -> (expect["view"] as Map<*, *>?)?.let { viewDiff(example, it, base) }
                        ?: (expect["report"] as Map<*, *>?)?.let { reportDiff(example, it, base) }
                        ?: (expect["action"] as Map<*, *>?)?.let { actionDiff(example, it, base) }
                        ?: (expect["generate"] as Map<*, *>?)?.let { generateDiff(example, it, base) }
                }
            }
            assertTrue(differing.isEmpty(), "仕様の例が、書いてあるとおりの結果になりません:\n" + differing.joinToString("\n"))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `値の式の語は どれも例で示されている`() {
        // 値の式の語のうち、列挙にもキーの集まりにも無いもの（format・first・all など）は、実装と直接は突き合わせられない。
        // 語ごとに、実際に評価して結果を確かめる例を 1 つ以上求める（語の有無だけでなく、動きも実装と結ぶ）
        val illustrated = root.resolve("spec/examples").listDirectoryEntries("*.yaml").flatMap { (raw(it)["illustrates"] as List<*>).map(Any?::toString) }.toSet()
        val missing = root.resolve("spec/keywords").listDirectoryEntries("KW-expr.*.yaml").map { raw(it)["id"].toString() }.filter { it !in illustrated }.sorted()
        assertTrue(missing.isEmpty(), "例の無い値の式の語があります（devtrack/spec/examples に、その語を illustrates に書いた例を足す）: $missing")
    }

    @Test
    fun `参照文書に 字下げで始まる行が無い`() {
        // 4 文字の字下げは Markdown でコードの塊になり、見出しと表が崩れる（ひな形の字下げの写り込みを止める）
        val indented = Path.of("docs/spec").listDirectoryEntries("*.md").flatMap { file ->
            // コードの塊（``` で囲んだ例の YAML）の中は見ない
            var fenced = false
            file.readText().lines().withIndex().filter { (_, line) ->
                if (line.startsWith("```")) fenced = !fenced
                !fenced && line.startsWith("    ")
            }.map { "${file.fileName}:${it.index + 1}" }
        }
        assertTrue(indented.isEmpty(), "字下げで始まる行があります（ひな形 devtrack/.yde/reports/*-md.md を直す）: ${indented.take(5)}")
    }

    @Test
    fun `どの位置も 仕様書の目次と参照文書に同じ順序で載っている`() {
        val ws = Workspace.open(root)
        val catalog = Views.load(root, ws.schemas)
        val reports = Reports.load(root)
        val guides = root.resolve("spec/guides").listDirectoryEntries("*.yaml").map(::raw)
        val contexts = root.resolve("spec/contexts").listDirectoryEntries("*.yaml").map(::raw)
        assertEquals(guides.size, guides.map { it["order"] }.distinct().size, "仕様書の目次の順序は重複しない")
        assertEquals(guides.map { it["id"] }.toSet(), contexts.map { it["guide"] }.toSet(), "所属先の無い位置や空の仕様書を残さない")
        guides.forEach { guide ->
            val owned = contexts.filter { it["guide"] == guide["id"] }.sortedBy { (it["order"] as Number).toInt() }
            assertEquals(owned.size, owned.map { it["order"] }.distinct().size, "${guide["id"]} の節順は重複しない")
            val reader = Views.resolveDocument(
                assertNotNull(catalog.docView("spec-guide-reader")), DocumentId("spec/guides/${guide["id"]}.yaml"),
                ws.index, loader = ws::treeOf, schemaFor = ws::schemaFor, catalog = catalog,
            )
            assertTrue(reader.all { it.reason == null }, reader.toString())
            assertEquals(owned.map { it["id"] }, reader.single { it.gridRows != null }.gridRows!!.map { it.id })
            val report = assertNotNull(reports.report(guide["report"].toString()))
            val rendered = Reports.run(report, emptyMap(), catalog, ws.index, ws::treeOf, ws::schemaFor)
            assertTrue(rendered.missing.isEmpty(), rendered.missing.toString())
            // トップレベルと項目定義は一つの位置を語の分類ごとに分ける。ほかは位置ごとの見出しを共有する。
            if (guide["id"] in setOf("GUIDE-schema-document", "GUIDE-schema-fields")) {
                assertEquals(1, owned.size)
                val keywords = Views.resolve(assertNotNull(catalog.view("keyword-list")), ws.index, ws::treeOf, ws::schemaFor, mapOf("guide" to guide["id"]))
                keywords.forEach { assertTrue("`${it.title}`" in rendered.text, "参照文書に ${it.id} がありません") }
            } else {
                assertEquals(owned.map { "## ${it["title"]}" }, rendered.text.lines().filter { it.startsWith("## ") }, guide["id"].toString())
            }
        }
    }

    @Test
    fun `語の検索は同名の語を位置で区別し 未入力では全ての語を保つ`() {
        val ws = Workspace.open(root)
        val catalog = Views.load(root, ws.schemas)
        val view = assertNotNull(catalog.view("keyword-list"))
        fun search(vararg params: Pair<String, Any?>) = Views.resolve(view, ws.index, ws::treeOf, ws::schemaFor, mapOf(*params))
        val all = search()
        assertEquals(root.resolve("spec/keywords").listDirectoryEntries("*.yaml").size, all.size)
        assertEquals(all.size, all.map { it.id }.distinct().size)
        val required = search("context" to "CTX-field", "term" to "required")
        assertEquals(setOf("KW-field.required", "KW-field.requiredFrom"), required.map { it.id }.toSet())
        assertTrue(search("context" to "CTX-field", "language" to "view").isEmpty())
        assertEquals(listOf("KW-view-grid.rows"), search("guide" to "GUIDE-views", "context" to "CTX-view-grid", "term" to "rows").map { it.id })
        val pattern = search("context" to "CTX-field", "term" to "pattern").single()
        assertEquals("4", scalarText(pattern.cells.last().value), "一覧では先頭の形だけを出すので、形が複数あることを示す")
        assertEquals(DocumentId("spec/keywords/KW-field.pattern.yaml"), pattern.cells.first().document, "元の仕様へ戻れる座標を保つ")
        val lens = catalog.selectableLenses.single { it.id == "spec-reading-lens" }
        val filters = lens.filters.filter { it.default }
        val candidates = ws.index.graph.entities.map { (id, entity) ->
            LensFilters.Candidate(id, entity.entityType) {
                DiagramInputs.entity(id, null, ws.index, ws.schemas, ws.entityTypes, ws::treeOf).place
            }
        }
        val hidden = LensFilters.hidden(filters, "entity", candidates)
        val specTypes = setOf("spec-guide", "context", "keyword", "example", "base-type", "schema-kind")
        val visible = ws.index.graph.entities.filterValues { it.entityType in specTypes }.keys
        assertEquals(visible, candidates.map { it.id }.toSet() - hidden, "仕様の箱を消さず、開発管理の箱を外す")
        assertTrue(LensFilters.hidden(filters, "type", candidates).isEmpty(), "実体の ID の絞り込みを型の段にかけない")
    }

    @Test
    fun `語から例と比較相手へ進め 読み込み成功と操作の拒否を区別できる`() {
        val ws = Workspace.open(root)
        val catalog = Views.load(root, ws.schemas)
        fun read(view: String, file: String) = Views.resolveDocument(
            assertNotNull(catalog.docView(view)), DocumentId(file), ws.index,
            loader = ws::treeOf, schemaFor = ws::schemaFor, catalog = catalog,
        ).also { sections -> assertTrue(sections.all { it.reason == null }, sections.toString()) }
        val keyword = read("spec-keyword-reader", "spec/keywords/KW-field.required.yaml")
        val examples = keyword.single { it.gridRows != null }.gridRows!!
        assertEquals(setOf("EX-field-required-ok", "EX-field-required-missing", "EX-action-required-param"), examples.map { it.id }.toSet())
        val example = read("spec-example-reader", "spec/examples/EX-action-required-param.yaml")
        val outcome = example.single { it.gridRows != null }.gridRows!!.single()
        assertEquals("ok", scalarText(outcome.cells[0].value))
        assertEquals("操作を拒否: title", scalarText(outcome.cells[1].value))
        val comparisons = example.flatMap { it.rows.orEmpty() }.flatMap { it.backlinks }.map { it.sourceId }
        assertTrue("EX-field-required-ok" in comparisons, "比較の線を片側に書くだけで、反対側からも戻れる")
        val context = read("spec-context-reader", "spec/contexts/CTX-field.yaml")
        val indirect = context.single { it.gridView?.id == "spec-context-keyword-examples" }.gridRows!!
        assertTrue(indirect.any { it.id == "EX-action-required-param" && it.bindings?.get("k") == "KW-field.required" })
    }

    @Test
    fun `語の ID が位置とキーから決まる形と違えば 検査のエラーになる`() {
        val base = createTempDirectory("spec-test")
        try {
            val dir = base.resolve("devtrack")
            Files.walk(root).use { s ->
                s.forEach { p -> val to = dir.resolve(root.relativize(p).toString()); if (Files.isDirectory(p)) Files.createDirectories(to) else Files.copy(p, to) }
            }
            val file = dir.resolve("spec/keywords/KW-field.required.yaml")
            file.writeText(file.readText().replace("key: \"required\"", "key: \"must\""))
            val errors = errorsOf(Workspace.open(dir))
            assertTrue(errors.any { "KW-field.required" in it }, "ID とキーの食い違いを検査が見つけること: $errors")
        } finally {
            base.toFile().deleteRecursively()
        }
    }
}
