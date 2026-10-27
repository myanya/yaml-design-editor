package dev.toros.yde.app.tools

import dev.toros.yde.app.core.Dashboards
import dev.toros.yde.app.core.Disclosure
import dev.toros.yde.app.core.DocViewDef
import dev.toros.yde.app.core.FlowReports
import dev.toros.yde.app.core.TraverseSpec
import dev.toros.yde.app.core.TraverseStep
import dev.toros.yde.app.core.Views
import dev.toros.yde.app.index.IdIndex
import dev.toros.yde.app.index.idMatchesPattern
import dev.toros.yde.app.schema.Display
import dev.toros.yde.app.validate.Validator
import dev.toros.yde.semantic.schema.EntityRole
import dev.toros.yde.semantic.schema.Relations
import dev.toros.yde.semantic.schema.globToRegex
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.yaml.validate.Severity

/**
 * 読み出しの文: ツール（`Toolset`）の結果と、エージェントの初期文脈（レシピ項目）が共用する、人と LLM が同じ 1 枚を見るための文字。
 * 読むのは作業コピー（`ToolContext.copy`）越しで、重ねた編集が見える。出力は決定的に保つ（prompt caching・再現性・テスト容易性）。
 * - `catalogText`（文書カタログ）・`phaseText`（フェーズ状態）・`viewText`（ビューの解決）・`traverseText`（グラフトラバース）
 * - `relatedText`（隣接情報）・`entityText`（エンティティの全体像）・`refsFooterText`（参照の解決フッタ）・`sampleText`（記述例）・`validationText`
 */
class Readouts(private val ctx: ToolContext) {
    private val ws = ctx.ws
    private val copy = ctx.copy
    private val policy = ctx.policy

    /**
     * カタログから除外するか（read_outline 等での読み取りは可）。
     * schemas / runs ゾーン・構成ファイルは常に除外。定義ゾーン（agents / activities / evals / skills / views）は
     * メタアクティビティ（meta: true。R4-03）では作業対象なので除外しない。
     */
    private fun catalogExcluded(rel: String): Boolean {
        val layout = ws.layout
        return layout.indexExcluded(rel) || (!policy.meta && layout.isReserved(rel))
    }

    /** カタログ（オーバーレイ反映・予約領域除外）。match（glob）で対象を絞れる（R19-02）。 */
    fun catalogText(match: String? = null): String {
        val regex = match?.let { globToRegex(it) }
        val files = (ws.files + copy.touchedFiles().map { it.file }).distinct()
            .filter { f -> !catalogExcluded(ws.relPath(f)) }
            .filter { f -> regex == null || regex.matches(ws.relPath(f)) }
        if (files.isEmpty()) return "（文書がありません）"
        return files.joinToString("\n") { f ->
            val rel = ws.relPath(f)
            val root = copy.tree(rel)
            val name = root?.let(IdIndex::documentNameOf)
            val description = root?.let(IdIndex::documentDescriptionOf)?.replace("\n", " ")?.take(120)
            val schemaDef = ws.schemaFor(f)
            buildString {
                append("- ").append(rel)
                schemaDef?.let { append(" [schema: ").append(it.id).append(']') }
                name?.let { append(" — ").append(it) }
                description?.let { append(": ").append(it) }
                // phases を宣言したスキーマの文書は、段階的詳細化の現在地をカタログの時点から見せる
                schemaDef?.let { sc -> Validator.phaseSummaryText(root, sc) }?.let { append(" [").append(it).append(']') }
            }
        }
    }

    /** 文書ごとのフェーズ状態（phases 宣言のあるスキーマの文書のみ）。context.include の "phase" から使う。 */
    fun phaseText(targetMatch: String?): String {
        val files = (ws.files + copy.touchedFiles().map { it.file }).distinct()
            .filter { f -> !catalogExcluded(ws.relPath(f)) }
            .filter { f -> targetMatch == null || globToRegex(targetMatch).matches(ws.relPath(f)) }
        val lines = files.mapNotNull { f ->
            val schemaDef = ws.schemaFor(f) ?: return@mapNotNull null
            val root = copy.tree(ws.relPath(f)) ?: return@mapNotNull null
            val summary = Validator.phaseSummaryText(root, schemaDef) ?: return@mapNotNull null
            "- ${ws.relPath(f)}: $summary"
        }
        return if (lines.isEmpty()) "（phases を宣言したスキーマの対象文書がありません）" else lines.joinToString("\n")
    }

    /**
     * ビューの解決テキスト（read_view と初期文脈の開示で共用）。
     * 行・出現はステージングのオーバーレイ索引、セル値はステージング済みツリーから読む。
     * rowsPattern（行IDの絞り込み）・columns（列ラベルの選別）は横断グリッドのみ（R19-02）。
     */
    fun viewText(viewId: String?, fileRel: String? = null, rowsPattern: String? = null, columns: List<String>? = null, date: String? = null): String {
        val loaded = Views.load(ws.root, ws.schemas) { copy.tree(it.value) }
        require(date == null || viewId?.let(loaded::dashboard) != null) { "dateはダッシュボードを指定したときだけ使えます" }
        if (viewId.isNullOrBlank()) {
            if (loaded.views.isEmpty() && loaded.docViews.isEmpty() && loaded.flowViews.isEmpty() && loaded.lensViews.isEmpty() && loaded.dashboards.isEmpty()) return "（ビュー定義がありません）"
            val lines = loaded.views.map { v ->
                val rowsLabel = v.rows?.let { "idPattern=${it.idPattern}" } ?: "matchクエリ（行キー: ${v.rowKey}）"
                val columnsLabel = if (v.matrix != null) "クロス集計（列は cells.columns から）" else v.columns.joinToString("/") { it.label }
                "- ${v.id}（${v.name}）: $rowsLabel, 列=$columnsLabel"
            } + loaded.docViews.map { v ->
                "- ${v.id}（${v.name}）: 文書ビュー 適用先=" +
                    listOfNotNull(v.match, v.schemaId?.let { "schema:$it" }).joinToString(" & ")
            } + loaded.flowViews.map { v ->
                "- ${v.id}（${v.name}）: フロービュー 適用先=" +
                    listOfNotNull(v.match, v.schemaId?.let { "schema:$it" }).joinToString(" & ")
            } + loaded.dashboards.map { v ->
                "- ${v.id}（${v.name}）: ダッシュボード 区画=" + v.sections.joinToString(" / ") { it.label }
            } + loaded.lensViews.map { v ->
                "- ${v.id}（${v.name}）: レンズの見え方（表示だけ。読めるデータは無い） 関係=" +
                    v.relations.joinToString(", ") { "${it.name}:${it.arrow.id}" }.ifEmpty { "なし" }
            } + loaded.chartViews.map { v ->
                "- ${v.id}（${v.name}）: チャート（${v.kind.label}） 元の表=${v.view}" +
                    (if (v.series.isEmpty()) "" else " 系列=" + v.series.joinToString("/"))
            }
            return lines.joinToString("\n") +
                (if (loaded.errors.isEmpty()) "" else "\n定義エラー:\n" + loaded.errors.joinToString("\n") { "- $it" })
        }
        // チャート（P46）: 元の表をステージングの索引で解決し、項目×系列の数を返す
        loaded.chartView(viewId)?.let { chart ->
            require(fileRel == null && rowsPattern == null && columns == null) { "チャートではfile / rows / columnsを指定しません" }
            val data = Views.resolveChart(chart, loaded, copy.index(), { d -> copy.tree(d.value) }, ws::schemaFor)
                ?: error("チャート「${chart.id}」の元の表「${chart.view}」が見つかりません")
            return dev.toros.yde.semantic.ChartViews.renderText(data)
        }
        loaded.dashboard(viewId)?.let { dashboard ->
            require(fileRel == null && rowsPattern == null && columns == null) { "ダッシュボードではfile / rows / columnsを指定しません" }
            val at = date?.let { runCatching { java.time.LocalDate.parse(it) }.getOrElse { error("dateはYYYY-MM-DDで指定してください") } }
                ?: java.time.LocalDate.now()
            // 共有の入力（inputs）は定義の default で解決する（エージェントからの指定は今はない）
            val result = dev.toros.yde.app.core.Dashboards.resolve(dashboard, at, loaded,
                copy.index(), ws::schemaFor, loader = { copy.tree(it.value) })
            return YamlIO.dump(YamlIO.fromAny(dev.toros.yde.app.core.Dashboards.report(dashboard, result, loaded.errors)))
        }
        loaded.docView(viewId)?.let {
            require(rowsPattern == null && columns == null) { "rows / columns の絞り込みは横断グリッドのビューでのみ使えます" }
            return docViewText(it, fileRel)
        }
        loaded.flowView(viewId)?.let { flow ->
            require(rowsPattern == null && columns == null) { "rows / columns の絞り込みは横断グリッドのビューでのみ使えます" }
            val candidates = ws.files.map { ws.relPath(it) }
                .filter { flow.appliesTo(DocumentId(it), ws.schemaFor(ws.resolve(it))?.id) }.sorted()
            val rel = fileRel?.let {
                val path = ws.resolve(it).toAbsolutePath().normalize()
                require(path.startsWith(ws.root.toAbsolutePath().normalize())) { "フローの原本はワークスペース内で指定してください" }
                ws.relPath(path).replace('\\', '/')
            } ?: candidates.singleOrNull() ?: return "file で対象文書を指定してください:\n" + candidates.joinToString("\n")
            val document = DocumentId(rel)
            val result = Views.resolveFlow(flow, document, copy.index(), ws::schemaFor) { copy.tree(it.value) }
            return YamlIO.dump(YamlIO.fromAny(dev.toros.yde.app.core.FlowReports.toMap(flow, document, result, loaded.errors)))
        }
        val view = loaded.view(viewId)
            ?: error(
                "ビュー「$viewId」が見つかりません" +
                    "（利用可能: ${(loaded.views + loaded.docViews + loaded.flowViews).joinToString(", ") { it.id }.ifEmpty { "なし" }}）"
            )
        val index = copy.index()
        val rows = Views.resolve(view, index, { d -> copy.tree(d.value) }, ws::schemaFor)
            .filter { rowsPattern == null || idMatchesPattern(it.id, rowsPattern) }
        return Views.renderText(view, rows, columns, index, tree = { d -> copy.tree(d.value) })
    }

    /** 文書ビュー（R14-01）の解決テキスト。対象文書は file 指定＞適用対象が1件なら自動選択。 */
    private fun docViewText(view: DocViewDef, fileRel: String?): String {
        val candidates = ws.files.map { ws.relPath(it) }
            .filter { rel -> view.appliesTo(rel, ws.schemaFor(ws.resolve(rel))?.id) }
            .sorted()
        val rel = when {
            fileRel != null -> {
                val n = fileRel.replace('\\', '/')
                require(view.appliesTo(n, ws.schemaFor(ws.resolve(n))?.id)) {
                    "文書「$n」はビュー「${view.id}」の適用対象ではありません" +
                        "（適用対象: ${candidates.joinToString(", ").ifEmpty { "なし" }}）"
                }
                n
            }
            candidates.size == 1 -> candidates.first()
            candidates.isEmpty() -> return "ビュー「${view.id}」の適用対象の文書がありません"
            else -> return "ビュー「${view.id}」は文書ビューです。file で対象文書を指定してください:\n" +
                candidates.joinToString("\n") { "- $it" }
        }
        val document = DocumentId(ws.relPath(ws.resolve(rel)))
        val index = copy.index()
        val loader = { d: DocumentId -> copy.tree(d.value) }
        val sections = Views.resolveDocument(view, document, index, loader = loader)
        val attachments = Views.resolveAttachments(view, document, index, loader = loader)
        val catalog = Views.load(ws.root, ws.schemas, loader)
        val flows = sections.mapNotNull { it.section.flowView }.map { id ->
            YamlIO.dump(YamlIO.fromAny(dev.toros.yde.app.core.FlowReports.embedded(
                id, document, catalog, index, ws::schemaFor, loader,
            )))
        }
        return (listOf(Views.renderDocText(view, rel, sections, attachments)) + flows).joinToString("\n")
    }

    /** グラフトラバース（R19-04）。ツール・レシピ項目（agent）の双方から呼ぶ（評価器は core.Traverse の1実装）。 */
    fun traverseText(start: String, steps: List<dev.toros.yde.app.core.TraverseStep>, budgetChars: Int): String =
        dev.toros.yde.app.core.Traverse.run(
            copy.index(),
            loader = { d -> copy.tree(d.value) },
            spec = TraverseSpec(start = start, steps = steps, budgetChars = budgetChars),
        )

    /** 同スキーマの既存文書N件を記述例として返す（記述粒度・文体の手本。R19-02）。 */
    fun sampleText(schemaId: String, count: Int): String {
        val rels = ws.files.asSequence()
            .map { ws.relPath(it) }
            .filter { !catalogExcluded(it) }
            .filter { rel -> ws.schemaFor(ws.resolve(rel))?.id == schemaId }
            .sorted()
            .take(count)
            .toList()
        if (rels.isEmpty()) return "（schema: $schemaId の文書がまだありません）"
        return rels.joinToString("\n") { rel ->
            val root = copy.tree(rel)
            "### $rel\n" + (root?.let { Disclosure.nodeYaml(it, parseYPath(""), maxChars = 4_000) } ?: "（読めません）")
        }
    }

    fun relatedText(id: String, backlinkLimit: Int = 20): String {
        val idx = copy.index()
        val occurrences = idx.occurrences(id)
        if (occurrences.isEmpty()) return "ID「$id」は見つかりません"
        val backlinks = idx.backlinksTo(setOf(id))
        return buildString {
            appendLine("出現（${occurrences.size}件）:")
            occurrences.forEach { e ->
                append("- ").append(e.document.value).append(" :: ").append(e.path.pretty())
                e.title?.let { append(" — ").append(it) }
                e.schemaId?.let { append(" [schema: ").append(it).append(']') }
                appendLine()
                // 出現の中身を要約表示する（R7-04の横ぐしビューと同等の情報をツール1回で得られるようにする）
                val docRoot = copy.tree(e.document.value)
                ws.schemaFor(e.document)?.let { sc -> Validator.phaseSummaryText(docRoot, sc) }
                    ?.let { appendLine("    $it") }
                val element = docRoot?.let { getAt(it, e.path) } as? YNode.Map
                element?.entries?.asSequence()
                    ?.filter { (_, v) -> v is YNode.Scalar }
                    ?.take(6)
                    ?.forEach { (k, v) ->
                        val text = (v as YNode.Scalar).value?.toString().orEmpty().replace("\n", " ")
                        appendLine("    $k: ${if (text.length > 60) text.take(60) + "…" else text}")
                    }
            }
            // 出次エッジは導出グラフの隣接リスト（R20-04。従来の occurrences×refs 突合と同一の出力）
            val refsOut = idx.outgoingRefs(id)
            if (refsOut.isNotEmpty()) {
                appendLine("この要素からの参照:")
                refsOut.forEach { r ->
                    append("- ").append(r.targetId)
                    // 参照先のタイトル（表示解決）を添え、IDの意味を別のツール呼び出しなしで閉じる（R19-05）
                    idx.occurrences(r.targetId).firstNotNullOfOrNull { it.title }?.let { append(" = ").append(it) }
                    append("（").append(r.viaKey)
                    // 関係レジストリの表示ラベル（R20-02。未宣言 relation は名前のまま）・属性付き参照の属性注記（R20-03）
                    Relations.labelOf(ws.relations, r.relation)?.let { append("・関係: ").append(it) }
                    r.attributeSummary()?.let { append("・").append(it) }
                    appendLine("）")
                }
            }
            // 保有と所属（この要素の中に書かれた要素と、この要素を中に書いている外側の要素。P40）
            val holdings = idx.graph.holdingsOf(id)
            if (holdings.isNotEmpty()) {
                appendLine("保有する要素（${holdings.size}件）:")
                holdings.forEach { h ->
                    append("- ").append(h.child.id)
                    h.child.title?.let { append(" = ").append(it) }
                    append("（").append(Relations.labelOf(ws.relations, h.relation) ?: h.relation)
                    if (h.child.role == EntityRole.DEFINING) append("・定義")
                    appendLine("）")
                }
            }
            val holders = idx.graph.holdersOf(id)
            if (holders.isNotEmpty()) {
                appendLine("所属（${holders.size}件）:")
                holders.forEach { h ->
                    append("- ").append(h.parent.id)
                    h.parent.title?.let { append(" = ").append(it) }
                    appendLine("（${Relations.inverseLabelOf(ws.relations, h.relation) ?: h.relation}・${h.child.document.value}）")
                }
            }
            appendLine("被参照（${backlinks.size}件）:")
            backlinks.take(backlinkLimit).forEach { b ->
                append("- ").append(b.document.value).append(" :: ").append(b.path.pretty())
                    .append("（").append(b.viaKey)
                Relations.inverseLabelOf(ws.relations, b.relation)?.let { append("・関係: ").append(it) }
                b.attributeSummary()?.let { append("・").append(it) }
                // 参照を書いた要素の ID・タイトル（索引の表示解決結果）を添え、read_node なしで参照元を特定できるようにする
                idx.sourceEntryOf(b)?.let { src ->
                    append("・元要素: ").append(listOfNotNull(src.id, src.title).joinToString(" "))
                }
                appendLine("）")
            }
            // 被参照の全列挙は膨張の主因（例: 全ユースケースが参照するアクター）のため既定20件で打切る（R19-05）
            if (backlinks.size > backlinkLimit) {
                appendLine("…他 ${backlinks.size - backlinkLimit} 件（related の limit 引数で拡張できます）")
            }
        }.trimEnd()
    }

    /**
     * エンティティ全体像の開示テキスト（R23-04。architecture/graph-query.md「エンティティページ」）。read_entity・レシピの entity 項目が共用する。
     * 合成（core.Aspects）はエンティティページ・CLI `entity show` と同一実装＝人間とAIが同じ1枚を見る。
     */
    fun entityText(id: String, relationLimit: Int = 20): String {
        val idx = copy.index()
        val loader: (DocumentId) -> YNode? = { d -> copy.tree(d.value) }
        val composite = dev.toros.yde.app.core.Aspects.compose(id, idx, ws.schemas, ws.entityTypes, loader)
            ?: return "ID「$id」は見つかりません"
        return buildString {
            append(composite.id)
            composite.typeLabel?.let { append("（").append(it).append("）") }
            composite.title?.let { append(" — ").append(it) }
            appendLine()
            // ---- アスペクト合成（所有フィールドと原本座標。座標はそのまま apply_patch の対象にできる）
            composite.sections.forEach { s ->
                append("## ").append(s.label)
                if (s.entry.role == EntityRole.DEFINING) append("（定義）")
                appendLine(" [${s.entry.document.value} :: ${s.entry.path.pretty()}]")
                val unset = mutableListOf<String>()
                s.fields.forEach { f ->
                    val value = f.value
                    if (value == null) {
                        unset += f.key
                        return@forEach
                    }
                    val text = when (value) {
                        is YNode.Scalar -> (value.value?.toString() ?: "").replace("\n", " ").let {
                            if (it.length > 120) it.take(120) + "…" else it
                        }
                        else -> Display.inline(value)
                    }
                    append("- ").append(f.key).append(": ").append(text)
                    f.duplicateOf?.let { append("（重複所有: ${it.first.value} と重複）") }
                    appendLine()
                }
                if (unset.isNotEmpty()) appendLine("- 未記入: ${unset.joinToString(", ")}")
                ws.schemaFor(s.entry.document)?.let { sc -> Validator.phaseSummaryText(loader(s.entry.document), sc) }
                    ?.let { appendLine("  $it") }
            }
            if (composite.unassigned.isNotEmpty()) {
                appendLine("## その他の出現（スキーマなし）")
                composite.unassigned.forEach { e -> appendLine("- ${e.document.value} :: ${e.path.pretty()}") }
            }
            // ---- 関係（両方向。関係レジストリの label / inverseLabel と属性注記）
            val refsOut = idx.outgoingRefs(id)
            if (refsOut.isNotEmpty()) {
                appendLine("## 関係（この要素からの参照 ${refsOut.size} 件）")
                refsOut.take(relationLimit).forEach { r ->
                    append("- → ").append(r.targetId)
                    idx.occurrences(r.targetId).firstNotNullOfOrNull { it.title }?.let { append(" = ").append(it) }
                    append("（").append(r.viaKey)
                    Relations.labelOf(ws.relations, r.relation)?.let { append("・関係: ").append(it) }
                    r.attributeSummary()?.let { append("・").append(it) }
                    appendLine("）")
                }
                if (refsOut.size > relationLimit) appendLine("…他 ${refsOut.size - relationLimit} 件")
            }
            val backlinks = idx.backlinksTo(setOf(id))
            appendLine("## 関係（この要素への参照 ${backlinks.size} 件）")
            backlinks.take(relationLimit).forEach { b ->
                append("- ← ")
                idx.sourceEntryOf(b)?.let { src -> append(listOfNotNull(src.id, src.title).joinToString(" ")) }
                    ?: append(b.document.value)
                append("（").append(b.viaKey)
                Relations.inverseLabelOf(ws.relations, b.relation)?.let { append("・関係: ").append(it) }
                b.attributeSummary()?.let { append("・").append(it) }
                appendLine("）[${b.document.value} :: ${b.path.pretty()}]")
            }
            if (backlinks.size > relationLimit) appendLine("…他 ${backlinks.size - relationLimit} 件（limit 引数で拡張できます）")
            // ---- 保有と所属（入れ子で書かれた要素。P40。名前は項目の relation か、書いている項目のキー名）
            val holdings = idx.graph.holdingsOf(id)
            if (holdings.isNotEmpty()) {
                appendLine("## 保有する要素（この要素の中に書かれた要素 ${holdings.size} 件）")
                holdings.take(relationLimit).forEach { h ->
                    append("- ◆ ").append(h.child.id)
                    h.child.title?.let { append(" = ").append(it) }
                    append("（").append(Relations.labelOf(ws.relations, h.relation) ?: h.relation)
                    if (h.child.role == EntityRole.DEFINING) append("・定義")
                    appendLine("）[${h.child.document.value} :: ${h.child.path.pretty()}]")
                }
                if (holdings.size > relationLimit) appendLine("…他 ${holdings.size - relationLimit} 件")
            }
            val holders = idx.graph.holdersOf(id)
            if (holders.isNotEmpty()) {
                appendLine("## 所属（この要素を中に書いている外側の要素 ${holders.size} 件）")
                holders.forEach { h ->
                    append("- ◇ ").append(h.parent.id)
                    h.parent.title?.let { append(" = ").append(it) }
                    appendLine("（${Relations.inverseLabelOf(ws.relations, h.relation) ?: h.relation}）[${h.child.document.value} :: ${h.child.path.pretty()}]")
                }
            }
            // ---- 検査の状況（status を宣言した検査＝詳細文書の存在など。判定は Validator と共有）
            val statuses = idx.occurrences(id)
                .mapNotNull { e -> ws.schemas.firstOrNull { it.id == e.schemaId }?.let { it to e } }
                .distinctBy { it.first.id }
                .flatMap { (sc, e) -> Validator.ruleStatuses(id, sc, idx, loader(e.document), ws.schemas, loader) }
            if (statuses.isNotEmpty()) {
                appendLine("## カバレッジ（${statuses.count { it.active && it.satisfied }}/${statuses.count { it.active }} 充足）")
                statuses.forEach { st ->
                    val state = when {
                        !st.active -> "フェーズ未到達"
                        st.satisfied -> "充足"
                        // 確かめられなかった検査は、充足とも不足とも言わない（P27 段A）
                        st.withheld -> "判定保留: ${st.reason?.text ?: "確かめられませんでした"}"
                        st.undetermined -> "対象外: ${st.reason?.text ?: "確かめるものがありません"}"
                        else -> "不足"
                    }
                    appendLine("- [$state] " + (st.summary ?: "（評価できません）") +
                        ((st.rule.description ?: st.rule.id)?.let { " — $it" } ?: ""))
                }
            }
        }.trimEnd()
    }

    /**
     * ノード配下の参照IDを「ID = タイトル [定義元]」へ解決するフッタ（R19-05）。
     * read_node / doc 項目の出力末尾に付け、「このIDは何か」を追加のツール呼び出しなしで閉じる。
     */
    fun refsFooterText(rel: String, path: dev.toros.yde.yaml.model.YPath): String {
        val idx = copy.index()
        val targetIds = idx.refs
            .filter { it.document.value == rel && it.path.size >= path.size && it.path.subList(0, path.size) == path }
            .map { it.targetId }
            .distinct()
        if (targetIds.isEmpty()) return ""
        val lines = targetIds.take(30).map { id ->
            val occurrences = idx.occurrences(id)
            if (occurrences.isEmpty()) "- $id（未解決）"
            else {
                val title = occurrences.firstNotNullOfOrNull { it.title }
                "- $id" + (title?.let { " = $it" } ?: "") + " [${occurrences.first().document.value}]"
            }
        }
        return buildString {
            appendLine()
            appendLine()
            appendLine("参照の解決:")
            lines.forEach { appendLine(it) }
            if (targetIds.size > 30) appendLine("…他 ${targetIds.size - 30} 件")
        }.trimEnd()
    }

    fun validationText(): String {
        if (copy.isEmpty()) return "ステージングに変更がありません"
        val results = copy.validateAll()
        val lines = results.flatMap { (rel, issues) ->
            issues.map { "- [$rel] ${it.severity} ${it.path.pretty()}: ${it.message}" }
        }
        val errors = results.values.flatten().count { it.severity == Severity.ERROR }
        val warnings = results.values.flatten().count { it.severity == Severity.WARNING }
        return if (lines.isEmpty()) "問題なし（対象 ${results.size} ファイル）"
        else "エラー $errors 件・警告 $warnings 件:\n" + lines.take(40).joinToString("\n")
    }

    companion object {
        /** 予算（文字数）超過の決定的な打切り。 */
        fun capped(text: String, budget: Int?): String =
            if (budget == null || text.length <= budget) text
            else text.take(budget) + "\n…（予算 ${budget} 文字で打切り）"
    }
}
