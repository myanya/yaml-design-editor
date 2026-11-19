package dev.toros.yde.workspace

import dev.toros.yde.semantic.SemanticTransform
import dev.toros.yde.semantic.TextTemplate
import dev.toros.yde.semantic.ReportRows
import dev.toros.yde.semantic.VirtualViews
import dev.toros.yde.semantic.schema.TransformDef
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.DefaultYamlCodec
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath

/** 生成の定義 1 件（`.yde/transforms/` の文書。メタスキーマ builtin-transform）。 */
data class GeneratorDef(
    val id: String,
    val name: String,
    val description: String?,
    /** 業務モデルのどの並びの要素ごとに 1 文書を作るか（null＝業務モデル全体から 1 文書）。 */
    val each: String?,
    /** 業務モデルを、どのスコープ（workspace.yaml の scopes）の定義体から作るか（null＝ワークスペースのすべてのスキーマ）。 */
    val scope: String?,
    /** 作る文書の場所（ワークスペース相対の YAML。穴 `{パス}` は要素の値）。 */
    val output: String,
    /** 文書の中身を組み立てる変換（いまの場所＝要素。業務モデル全体は `$of: model`）。 */
    val transform: TransformDef,
    val source: DocumentId,
    /**
     * どの段（workspace.yaml の stages）の項を読むか（null＝業務モデルの項そのもの）。段の項は、業務モデルの項に、その段までの
     * 規則と補正を当てたもの（[Stages]。形は同じで、足したものに出どころが付く）。
     */
    val stage: String? = null,
    /** 入力を組み立てる前に同じ計画で生成する定義。 */
    val dependsOn: List<String> = emptyList(),
    /** 段の項とビューの行から、生成に使う入力を組み立てる変換。 */
    val input: TransformDef? = null,
)

/** 生成した文書 1 つ（文字は保存する形＝見出しのコメントつき）。 */
data class GeneratedDocument(val generator: String, val document: DocumentId, val text: String)

/**
 * 生成の計画: 作る・作り直す・消す・変わらない文書と、定義や組み立ての誤り。適用はセッションの 1 トランザクション（Undo 1 回で戻る）。
 * 生成物が最新か（[upToDate]）は、作る文書がすべて保存された内容と同じで、消すものが無く、誤りも無いこと。
 */
data class GenerationPlan(
    val outputs: List<GeneratedDocument>,
    val created: List<DocumentId>,
    val updated: List<DocumentId>,
    val deleted: List<DocumentId>,
    val unchanged: List<DocumentId>,
    val errors: List<String>,
    val transaction: WorkspaceTransaction,
) {
    val upToDate: Boolean get() = created.isEmpty() && updated.isEmpty() && deleted.isEmpty() && errors.isEmpty()
}

/**
 * 生成（業務モデルから文書へ）。生成の定義（変換のひな型）を、業務モデルを展開した形（[BusinessModel]）に当てて、YAML の文書を作る。
 * - 作った文書は生成物: 先頭に見出しのコメント（[marker]）を付け、手で直さない。業務モデルか生成の定義を直して作り直す
 * - 作り直すたびに、出力の場所（穴を * にした場所）にある、同じ定義の生成物で作らなかったものは消す（業務モデルから消えた操作など）
 * - 書き込みはセッションの取引（作成・置き換え・削除）で、検証も保存も今までの経路。計算は決定的で、同じ定義からは同じ文書になる
 */
object Generators {
    /** 生成の定義が `$of` で読む、業務モデル全体の場所の名前。 */
    const val MODEL = "model"

    /** 生成の定義が `$of` で読む、いまの要素（each の要素。繰り返しの中から外側の要素を読む）の名前。 */
    const val ELEMENT = "element"

    private val KEYS = setOf("id", "name", "description", "scope", "stage", "each", "output", "build", "rules", "dependsOn", "input")

    /** 生成物の見出しの始まり（この定義の生成物かを見分ける）。 */
    fun marker(generator: String): String = "# 生成物（$generator）"

    private fun header(def: GeneratorDef): String =
        if (def.stage == null) "${marker(def.id)}: 手で直さず、業務モデルか生成の定義（${WorkspaceLayout.nameInZone(def.source.value)}）を直して作り直します（CLI generate）。"
        // 段の項を読む生成物は、段の規則・補正（.yde/stages）でも直せる（ADR-0034）
        else "${marker(def.id)}: 手で直さず、業務モデルか段の規則・補正（.yde/stages）か生成の定義（${WorkspaceLayout.nameInZone(def.source.value)}）を直して作り直します（CLI generate）。"

    /** 生成の定義 1 件を読む。 */
    fun parse(raw: Any?, source: DocumentId): GeneratorDef {
        val where = WorkspaceLayout.nameInZone(source.value)
        val m = raw as? Map<*, *> ?: error("$where: ルートは object で書きます")
        m.keys.map { it.toString() }.firstOrNull { it !in KEYS }?.let { error("$where: 未知のキーです: $it（使えるキー: ${KEYS.joinToString(", ")}）") }
        val id = m["id"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$where: id が必要です")
        val output = m["output"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$where: output（作る文書の場所）が必要です")
        require(output.endsWith(".yaml") || output.endsWith(".yml")) { "$where: output は YAML の文書の場所（.yaml）で書きます: $output" }
        val each = m["each"]?.toString()?.takeIf { it.isNotBlank() }
        if (each != null) require('{' in output) { "$where: each を書いたときは、output に要素ごとに変わる穴（例 {id}）を書きます: $output" }
        val transform = TransformDef.parse(
            linkedMapOf("id" to id, "description" to m["description"], "build" to m["build"], "rules" to m["rules"]).filterValues { it != null },
            where, places = setOf(MODEL, ELEMENT),
        )
        val scope = m["scope"]?.toString()?.takeIf { it.isNotBlank() }
        val stage = m["stage"]?.toString()?.takeIf { it.isNotBlank() }
        val dependencies = m["dependsOn"]?.let { value ->
            (value as? List<*>)?.map { it as? String ?: error("$where.dependsOn は生成の定義 ID の並びです") }
                ?: error("$where.dependsOn は生成の定義 ID の並びです")
        }.orEmpty()
        val input = m["input"]?.let { build -> TransformDef.parse(
            mapOf("id" to "$id-input", "build" to build, "rules" to m["rules"]).filterValues { it != null },
            "$where.input", places = setOf(MODEL, ELEMENT),
        ) }
        return GeneratorDef(id, m["name"]?.toString() ?: id, m["description"]?.toString(), each, scope, output, transform, source, stage, dependencies, input)
    }

    /** セッションの transforms ゾーンの生成の定義（下のフォルダも。ID の重なりは誤り）。 */
    fun definitions(session: WorkspaceSession): Pair<List<GeneratorDef>, List<String>> {
        val errors = mutableListOf<String>()
        val defs = session.snapshot.documents.entries
            .filter { session.layout.zoneOf(it.key.value) == WorkspaceLayout.Zone.TRANSFORMS }
            .sortedBy { WorkspaceLayout.nameInZone(it.key.value) }
            .mapNotNull { (id, d) ->
                val root = d.document.documents.firstOrNull() ?: return@mapNotNull null
                runCatching { parse(YamlIO.toAny(root), id) }.onFailure { errors += it.message ?: "生成の定義を読めません" }.getOrNull()
            }
        defs.groupBy { it.id }.filterValues { it.size > 1 }.forEach { (id, found) ->
            errors += "生成の定義ID「$id」が重複しています（${found.joinToString(", ") { WorkspaceLayout.nameInZone(it.source.value) }}）"
        }
        return defs.distinctBy { it.id } to errors
    }

    /**
     * 生成の系譜: 生成の定義ごとに、業務モデルの項のどこを読むか（[dev.toros.yde.semantic.TransformReads]）。定義を当てずに読むので、
     * 業務モデルが空でも分かる。concepts は読む場所の見出し（頭のキーと、その下の中を読む入れ物。`actions.effects`。設計書がどの概念から作られるか）。
     */
    fun lineage(session: WorkspaceSession): List<Map<String, Any?>> = definitions(session).first.map { def ->
        val element = def.each?.let { dev.toros.yde.semantic.schema.PathPattern.parse(it) + dev.toros.yde.semantic.schema.AnyIndex }
        val places = buildMap<String, List<Any>> {
            put("model", emptyList())
            element?.let { put("element", it) }
        }
        val reads = dev.toros.yde.semantic.TransformReads.of(def.transform, places, element ?: emptyList())
        // 概念: 頭のキーと、その下の入れ物（さらに中を読むもの。actions.effects・entities.aspects）。値（id・label）は頭のキーに含める
        val concepts = reads.mapNotNull { r ->
            val p = if (r == "(root)") emptyList() else dev.toros.yde.semantic.schema.PathPattern.parse(r)
            val names = p.filterIsInstance<String>()
            val second = names.getOrNull(1)
            when {
                names.isEmpty() -> null
                second != null && (names.size >= 3 || p.indexOf(second).let { i -> p.getOrNull(i + 1) === dev.toros.yde.semantic.schema.AnyIndex }) ->
                    "${names[0]}.$second"
                else -> names[0]
            }
        }.distinct().let { all -> all.filter { c -> '.' in c || all.none { it.startsWith("$c.") } } }
        linkedMapOf<String, Any?>(
            "id" to def.id, "name" to def.name, "scope" to def.scope, "stage" to def.stage, "each" to def.each, "output" to def.output,
            "concepts" to concepts, "reads" to reads,
        ).filterValues { it != null }
    }

    /** 生成を計画する（only を渡すとその定義だけ）。保存はしない。 */
    fun plan(session: WorkspaceSession, only: Set<String> = emptySet()): GenerationPlan {
        val (defs, defErrors) = definitions(session)
        val errors = defErrors.toMutableList()
        only.filter { name -> defs.none { it.id == name } }.forEach { errors += "生成の定義「$it」がありません（ある定義: ${defs.joinToString(", ") { d -> d.id }.ifEmpty { "なし" }}）" }
        val selected = mutableListOf<GeneratorDef>()
        val visiting = linkedSetOf<String>()
        val visited = mutableSetOf<String>()
        val dependencies = mutableMapOf<String, Set<String>>()
        fun select(id: String) {
            if (id in visited) return
            val def = defs.firstOrNull { it.id == id } ?: run { errors += "依存先の生成の定義「$id」がありません"; return }
            if (!visiting.add(id)) { errors += "生成の依存が循環しています: ${(visiting + id).joinToString(" → ")}"; return }
            def.dependsOn.forEach(::select)
            visiting.remove(id)
            dependencies[id] = def.dependsOn.toSet() + def.dependsOn.flatMap { dependencies[it].orEmpty() }
            if (visited.add(id)) selected += def
        }
        (if (only.isEmpty()) defs.map { it.id } else only.toList().sorted()).forEach(::select)
        if (errors.isNotEmpty()) return GenerationPlan(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), errors.distinct(),
            WorkspaceTransaction(session.version, ChangeIntent("生成の定義の誤り"), emptyList()))
        // 業務モデルは、生成の定義が読むスコープごとに 1 回だけ作る。段の項は、スコープと段の組ごとに 1 回（誤りも 1 回だけ出す）
        val business = HashMap<String?, YNode?>()
        val models = HashMap<Pair<String?, String?>, YNode?>()
        val outputs = mutableListOf<GeneratedDocument>()
        val completed = mutableSetOf<String>()
        val snapshot = session.snapshot
        // 前回の生成物は入力にしない。依存先の今回の出力だけを、後で読み取り用に重ねる。
        val generated = snapshot.documents.filterValues { document ->
            // 適用済みで未保存の生成物も含む。ディスクだけを調べると、その前回の結果を読んでしまう。
            val text = DefaultYamlCodec.dump(document.document)
            text.startsWith("# 生成物（") && '）' in text.lineSequence().first()
        }.keys
        val catalog by lazy {
            ViewDefinitions.load(snapshot.documents.filterKeys { session.layout.zoneOf(it.value) == WorkspaceLayout.Zone.VIEWS }
                .map { (id, doc) -> id to DefaultYamlCodec.dump(doc.document) }, session.schemas, session.viewKinds)
        }
        for (def in selected) {
            val failedDependencies = def.dependsOn.filter { it !in completed }
            if (failedDependencies.isNotEmpty()) {
                errors += "${def.id}: 依存先の生成が成功していません（${failedDependencies.joinToString("・")}）"
                continue
            }
            val errorsBefore = errors.size
            val stageBefore = def.stage.takeIf { def.input == null }
            val stagedModel = models.getOrPut(def.scope to stageBefore) {
                val base = business.getOrPut(def.scope) {
                    runCatching { BusinessModel.of(session, def.scope) }.getOrElse { errors += "${def.id}: ${it.message}"; null }
                } ?: return@getOrPut null
                if (stageBefore == null) base else Stages.term(session, base, stageBefore).let { staged ->
                    // 前の段の誤りは、後の段の項を作るときにも出るので、計画の最後に重なりを除く
                    errors += staged.errors
                    staged.term.takeIf { staged.errors.isEmpty() }
                }
            } ?: continue
            // ファイル名順の先行出力は見せず、明示した依存先（推移的な依存先を含む）だけを読む。
            val overlays by lazy {
                val result = linkedMapOf<DocumentId, dev.toros.yde.yaml.model.YDocument?>()
                generated.forEach { result[it] = null }
                outputs.filter { it.generator in dependencies.getValue(def.id) && it.generator in completed }
                    .forEach { result[it.document] = DefaultYamlCodec.parse(it.document, it.text) }
                result
            }
            val derived by lazy { session.derivedWith(overlays) }
            fun tree(id: DocumentId): YNode? = (if (id in overlays) overlays[id] else snapshot.documents[id]?.document)?.documents?.firstOrNull()
            fun schema(id: DocumentId) = session.schemas.firstOrNull { it.id == (snapshot.documents[id]?.schemaId
                ?: overlays[id]?.let { doc -> WorkspaceReader.assignSchemaId(id.value, doc, session.schemas, session.layout) }) }?.tree
            val viewCache = mutableMapOf<String, YNode>()
            fun viewRows(id: String): YNode = viewCache.getOrPut(id) {
                require(catalog.errors.isEmpty()) { catalog.errors.joinToString("\n") }
                val view = requireNotNull(catalog.view(id)) { "ビュー「$id」がありません" }
                val semantic = derived.semantic
                ReportRows.node(VirtualViews.resolveDetailed(view, semantic.ids, semantic.refs, semantic.graph,
                    { it.value }, ::tree, fieldAt = { document, path -> tree(document)?.let { schema(document)?.fieldAt(it, path) } }, schemaFor = ::schema).rows)
            }
            val model = if (def.input == null) stagedModel else runCatching {
                val place = SemanticTransform.TemplatePlace(stagedModel, null, emptyList())
                SemanticTransform.runAt(def.input, place, views = ::viewRows, places = mapOf(MODEL to place, ELEMENT to place), schemaModel = SchemaModels::resolve, schemaUses = SchemaUses::prepare, validate = SchemaModels::validate).term?.let { input ->
                    if (def.stage == null) input else Stages.term(session, input, def.stage).let { staged ->
                        errors += staged.errors
                        staged.term.takeIf { staged.errors.isEmpty() }
                    }
                }
            }.getOrElse { errors += "${def.id}.input: ${it.message}"; null } ?: continue
            val modelPlace = SemanticTransform.TemplatePlace(model, null, emptyList())
            for ((index, element) in elementsOf(def, model, errors).withIndex()) {
                val (node, path) = element
                val term = runCatching {
                    SemanticTransform.runAt(
                        def.transform, SemanticTransform.TemplatePlace(node, null, path),
                        views = ::viewRows,
                        schemaModel = SchemaModels::resolve,
                        schemaUses = SchemaUses::prepare,
                        validate = SchemaModels::validate,
                        loop = mapOf("index" to index, "position" to index + 1),
                        places = mapOf(MODEL to modelPlace, ELEMENT to SemanticTransform.TemplatePlace(node, null, path)),
                    ).term
                }.getOrElse { errors += "${def.id}: ${it.message}"; null } ?: continue
                val rendered = TextTemplate.render(def.output, node)
                if (rendered.missing.isNotEmpty()) {
                    errors += "${def.id}: 作る文書の場所「${def.output}」の穴（${rendered.missing.joinToString("・")}）が要素の値で埋まりません"
                    continue
                }
                val target = runCatching { documentOf(rendered.text, session.layout) }.getOrElse { errors += "${def.id}: ${it.message}"; null } ?: continue
                outputs += GeneratedDocument(def.id, target, header(def) + "\n" + YamlIO.dumpPlain(term))
            }
            if (errors.size == errorsBefore) completed += def.id
        }
        outputs.groupBy { it.document }.filterValues { it.size > 1 }.forEach { (doc, list) ->
            errors += "同じ場所「${doc.value}」に ${list.size} つの文書を作ります（${list.map { it.generator }.distinct().joinToString(", ")}）。output の穴で場所を分けます"
        }
        val unique = outputs.distinctBy { it.document }
        val created = mutableListOf<DocumentId>()
        val updated = mutableListOf<DocumentId>()
        val unchanged = mutableListOf<DocumentId>()
        val operations = mutableListOf<WorkspaceOperation>()
        for (out in unique) {
            val parsed = DefaultYamlCodec.parse(out.document, out.text)
            val stored = session.storedText(out.document)
            when {
                stored == null -> { created += out.document; operations += WorkspaceOperation.CreateDocument(out.document, parsed) }
                stored == DefaultYamlCodec.dump(parsed) -> unchanged += out.document
                else -> { updated += out.document; operations += WorkspaceOperation.ReplaceDocument(out.document, parsed) }
            }
        }
        // 作り直さなかった生成物（出力の場所にある、この定義の見出しを持つ文書）は消す
        val produced = unique.map { it.document }.toSet()
        val deleted = selected.flatMap { def ->
            val glob = dev.toros.yde.semantic.schema.globToRegex(HOLE.replace(def.output, "*"))
            session.snapshot.documents.keys.filter { id ->
                id !in produced && glob.matches(id.value) && session.storedText(id)?.startsWith(marker(def.id)) == true
            }
        }.distinct().sortedBy { it.value }
        deleted.forEach { operations += WorkspaceOperation.DeleteDocument(it) }
        return GenerationPlan(
            unique, created, updated, deleted, unchanged, errors.distinct(),
            WorkspaceTransaction(session.version, ChangeIntent("生成: ${selected.joinToString("・") { it.name }}"), if (errors.isEmpty()) operations else emptyList()),
        )
    }

    private val HOLE = Regex("""\{[^}]*\}""")

    /** 要素の並び（each の並びの各要素とその場所。each が無ければ業務モデル全体）。 */
    private fun elementsOf(def: GeneratorDef, model: YNode, errors: MutableList<String>): List<Pair<YNode, YPath>> {
        val each = def.each ?: return listOf(model to emptyList())
        val seq = (model as? YNode.Map)?.entries?.get(each) as? YNode.Seq
        if (seq == null) {
            errors += "${def.id}: 業務モデルに並び「$each」がありません（${(model as? YNode.Map)?.entries?.keys?.joinToString("・")}）"
            return emptyList()
        }
        return seq.items.mapIndexed { i, node -> node to listOf(each, i) }
    }

    /** 作る文書の場所を点検する（ワークスペース相対・.yde と定義の置き場の外）。 */
    private fun documentOf(text: String, layout: WorkspaceLayout): DocumentId {
        val rel = WorkspacePaths.relative(text).toString().replace('\\', '/')
        require(!layout.isReserved(rel)) { "作る文書の場所「$rel」は定義・記録の置き場です（.yde の下には書けません）" }
        require(rel.split('/').none { it.startsWith(".") }) { "作る文書の場所「$rel」に点で始まるフォルダは使えません" }
        return DocumentId(rel)
    }
}
