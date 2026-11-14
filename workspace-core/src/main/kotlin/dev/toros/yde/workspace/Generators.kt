package dev.toros.yde.workspace

import dev.toros.yde.semantic.SemanticTransform
import dev.toros.yde.semantic.TextTemplate
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
    /** 作る文書の場所（ワークスペース相対の YAML。穴 `{パス}` は要素の値）。 */
    val output: String,
    /** 文書の中身を組み立てる変換（いまの場所＝要素。業務モデル全体は `$of: model`）。 */
    val transform: TransformDef,
    val source: DocumentId,
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

    private val KEYS = setOf("id", "name", "description", "each", "output", "build", "rules")

    /** 生成物の見出しの始まり（この定義の生成物かを見分ける）。 */
    fun marker(generator: String): String = "# 生成物（$generator）"

    private fun header(def: GeneratorDef): String =
        "${marker(def.id)}: 手で直さず、業務モデルか生成の定義（${WorkspaceLayout.nameInZone(def.source.value)}）を直して作り直します（CLI generate）。"

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
        return GeneratorDef(id, m["name"]?.toString() ?: id, m["description"]?.toString(), each, output, transform, source)
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

    /** 生成を計画する（only を渡すとその定義だけ）。保存はしない。 */
    fun plan(session: WorkspaceSession, only: Set<String> = emptySet()): GenerationPlan {
        val (defs, defErrors) = definitions(session)
        val errors = defErrors.toMutableList()
        only.filter { name -> defs.none { it.id == name } }.forEach { errors += "生成の定義「$it」がありません（ある定義: ${defs.joinToString(", ") { d -> d.id }.ifEmpty { "なし" }}）" }
        val selected = if (only.isEmpty()) defs else defs.filter { it.id in only }
        val model = BusinessModel.of(session)
        val modelPlace = SemanticTransform.TemplatePlace(model, null, emptyList())
        val outputs = mutableListOf<GeneratedDocument>()
        for (def in selected) {
            for ((index, element) in elementsOf(def, model, errors).withIndex()) {
                val (node, path) = element
                val term = runCatching {
                    SemanticTransform.runAt(
                        def.transform, SemanticTransform.TemplatePlace(node, null, path),
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
            unique, created, updated, deleted, unchanged, errors,
            WorkspaceTransaction(session.version, ChangeIntent("生成: ${selected.joinToString("・") { it.name }}"), operations),
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
