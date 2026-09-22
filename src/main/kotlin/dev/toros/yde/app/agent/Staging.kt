package dev.toros.yde.app.agent

import dev.toros.yde.app.core.Views
import dev.toros.yde.app.skill.Skills
import dev.toros.yde.workspace.changeset.ChangeSet
import dev.toros.yde.workspace.changeset.ChangeSets
import dev.toros.yde.workspace.changeset.FileChange
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.workspace.validate.CodedChecks
import dev.toros.yde.workspace.validate.IssueDelivery
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.patch.PatchOp
import dev.toros.yde.yaml.patch.Patcher
import dev.toros.yde.app.core.Workspace
import dev.toros.yde.app.index.DocScanner
import dev.toros.yde.app.index.IdIndex
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.app.schema.SchemaLoader
import dev.toros.yde.app.validate.Issue
import dev.toros.yde.yaml.validate.Severity
import dev.toros.yde.app.validate.Validator
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path

/**
 * エージェントの作業オーバーレイ（R2-05）。
 * 編集は原本（ディスク）に触れず、ベース内容のスナップショットに対するツリー＋パッチ操作の記録として積む。
 * - 複数文書へのパッチ・新規文書作成をひとつの変更セット（P1形式）にまとめて提出できる
 * - 索引・検証はオーバーレイ上で再構築するため、ID改名→参照元修正のような文書横断の整合を提出前に確認できる
 * - 実行が上限で打ち切られても、内容は未提出ドラフト（変更セット）として取り出せる
 */
class Staging(val ws: Workspace) {

    class Entry(
        val rel: String,
        val file: Path,
        val baseText: String?,      // 取得時点のディスク内容（新規作成は null）。差分表示と base ハッシュに使う
        val root: YNode,            // 編集後のツリー
        val ops: MutableList<PatchOp> = mutableListOf(),
        val created: Boolean = false,
    )

    private val entries = LinkedHashMap<String, Entry>()
    private var stagedIndex: IdIndex? = null

    fun touchedFiles(): List<Entry> = entries.values.toList()
    fun isEmpty(): Boolean = entries.isEmpty()

    /** 読み取り用ツリー: ステージ済みならその内容、なければディスクを読む（編集は記録しない）。 */
    fun tree(rel: String): YNode? {
        entries[rel]?.let { return it.root }
        val file = ws.resolve(rel)
        if (!Files.isRegularFile(file)) return null
        return runCatching { YamlIO.parse(Files.readString(file)) }.getOrNull()
    }

    /** 編集対象のエントリを取得する（初回アクセス時にディスク内容を取り込む）。 */
    private fun entryForEdit(rel: String): Entry = entries.getOrPut(rel) {
        val file = ws.resolve(rel)
        require(Files.isRegularFile(file)) { "ファイルが存在しません: $rel（新規作成は create_document を使ってください）" }
        val text = Files.readString(file)
        Entry(rel = rel, file = file, baseText = text, root = YamlIO.parse(text))
    }

    /** パッチ列を適用し、操作を記録する。途中で失敗した場合、成功済みの操作は反映されたまま例外を投げる。 */
    fun applyOps(rel: String, ops: List<PatchOp>) {
        val entry = entryForEdit(rel)
        for (op in ops) {
            Patcher.apply(entry.root, op)
            entry.ops += op
        }
        stagedIndex = null
    }

    /** 新規文書をステージする（ディスク・ステージングの双方に存在しないこと）。 */
    fun createDocument(rel: String, root: YNode) {
        require(rel !in entries) { "既にステージングに存在します: $rel" }
        val file = ws.resolve(rel)
        require(!Files.exists(file)) { "既に同名のファイルが存在します: $rel" }
        require(rel.endsWith(".yaml") || rel.endsWith(".yml")) { "YAMLファイル（.yaml / .yml）を指定してください: $rel" }
        entries[rel] = Entry(rel = rel, file = file, baseText = null, root = root, created = true)
        stagedIndex = null
    }

    /**
     * オーバーレイを重ねた索引。ステージ済みの編集・新規文書を含めて解決する。
     * 供給は Workspace.overlayIndex（RB-07 でセッション派生＝カーネルの機能的オーバーレイ）に一本化（RB-08）。
     */
    fun index(): IdIndex {
        stagedIndex?.let { return it }
        val built = ws.overlayIndex(entries.values.associate { it.file to it.root as YNode? })
        stagedIndex = built
        return built
    }

    /**
     * ステージ済みファイル＋影響ファイルをオーバーレイ索引で検証する。
     * 影響ファイル = ステージ対象が「変更前または変更後に定義しているID」を参照しているファイル。
     * ID改名で壊れる未修正の参照元を、ステージングに含まれていなくても検出する
     * （ワークスペース全体は検証しない: エージェントと無関係な既存エラーで提出を塞がないため）。
     */
    fun validateAll(): Map<String, List<Issue>> {
        val idx = index()
        val targets = LinkedHashMap<String, Pair<Path, YNode>>()
        entries.values.forEach { targets[it.rel] = it.file to it.root }

        // 変更前後に定義されていたIDを集める
        val affectedIds = mutableSetOf<String>()
        entries.values.forEach { e ->
            val schemaDef = ws.schemaFor(e.file)
            e.baseText?.let { base ->
                runCatching { YamlIO.parse(base) }.getOrNull()?.let { beforeRoot ->
                    val docId = IdIndex.documentIdOf(beforeRoot, e.rel)
                    affectedIds += DocScanner.scan(beforeRoot, DocumentId(e.rel), schemaDef, docId).first.map { it.id }
                }
            }
            val docId = IdIndex.documentIdOf(e.root, e.rel)
            affectedIds += DocScanner.scan(e.root, DocumentId(e.rel), schemaDef, docId).first.map { it.id }
        }
        // それらのIDを参照しているファイルを検証対象に加える（ディスク索引＋オーバーレイ索引の双方から）
        (ws.index.refs + idx.refs).asSequence()
            .filter { it.targetId in affectedIds }
            .map { it.document }
            .distinct()
            .forEach { d ->
                val rel = d.value
                if (rel !in targets) tree(rel)?.let { targets[rel] = ws.fileOf(d) to it }
            }
        val results = targets.mapValues { (_, target) ->
            // 整合検証の材料もステージング（オーバーレイ）優先で読む（R16-03a）
            Validator.validate(
                target.second, null, ws.documentOf(target.first), ws.schemaFor(target.first), idx,
                allSchemas = ws.schemas,
                loader = { d -> tree(d.value) },
                // グラフ整合検証（R21-01）も提出ゲートに乗せる（循環を作る変更セットの拒否。§4.31）
                relations = ws.relations, enforcement = ws.enforcement,
                shareEvaluations = true,  // ステージングを重ねた索引と、ステージングの内容を読む loader
            )
        }.toMutableMap()
        // メタアクティビティ（R4-03）: ステージされた定義文書は実際のローダでパースできることもゲート条件にする
        // （スキーマ検証だけでは「schedule に everyMinutes がない」等の意味的エラーを見逃すため）
        entries.values.forEach { e ->
            definitionParseError(e)?.let { message ->
                // 定義文書のローダの読み取りエラー（コードで実装された検査。出どころは、文書そのもの）
                val source = CodedChecks.source("definition", "load-error", IssueDelivery.REPORT, emptyList(), emptyList())
                results[e.rel] = (results[e.rel] ?: emptyList()) + Issue(Severity.ERROR, message, emptyList(), source = source)
            }
        }
        return results
    }

    /**
     * no-new-errors ゲート用（R28-05b・UX-47）: ステージ適用後の検証エラーのうち、同じ文書を
     * ディスク原本（適用前）で検証したときには現れないもの。既存エラーは許容し、増えた分だけを返す。
     * 新規作成文書は原本がないため、そのエラーはすべて新規。
     *
     * 「同じエラー」の見分け方（P27 段A・段B の残り。意図的差分 D-26 ①・D-29 ①）:
     * - 指摘は出どころ（IssueSource.identity）で見分ける。場所の番号と文面は見ない——要素の挿入で場所の番号がずれても、
     *   文面の件数が変わっても、同じエラーとして扱う
     *   - 定義体の検査の指摘: （検査, 対象の要素, 配り方）
     *   - コードで実装された検査の指摘（構造の検証・ID の重複・参照の解決など）: （検査の名前, 指摘の種類, 指摘の場所を含む
     *     最も内側の ID を持つ要素, 配り方, その要素の中の場所＝番号を除いたキーの並び）
     * - 同じ出どころのエラーは件数で比べる。そのうえで、**根拠に関わる要素が増えたもの**（不足が2件→3件・参照の相手が
     *   前には無かった ID）は、件数にかかわらず新しいエラーとする。指摘の並び順には左右されない
     * - 出どころの分からない指摘（単体でパースした検査）だけは、(パスの表示形, メッセージ) の多重集合で見分ける
     */
    fun newErrors(): Map<String, List<Issue>> {
        val out = LinkedHashMap<String, List<Issue>>()
        for ((rel, issues) in validateAll()) {
            val errors = issues.filter { it.severity == Severity.ERROR }
            if (errors.isEmpty()) continue
            val before = if (Files.exists(ws.root.resolve(rel))) ws.validate(rel).filter { it.severity == Severity.ERROR } else emptyList()
            // 出どころで見分けるキー → 適用前の件数と、根拠に関わった要素
            val sourcedCount = HashMap<String, Int>()
            val sourcedEvidence = HashMap<String, MutableSet<String>>()
            // 出どころの分からない指摘: (場所, 文面) → 適用前の件数
            val plain = HashMap<Pair<String, String>, Int>()
            for (issue in before) {
                val key = issue.source?.identity
                if (key == null) {
                    plain.merge(issue.path.pretty() to issue.message, 1, Int::plus)
                } else {
                    sourcedCount.merge(key, 1, Int::plus)
                    sourcedEvidence.getOrPut(key) { mutableSetOf() } += issue.source.evidence
                }
            }
            val freshAt = HashSet<Int>()
            val byKey = LinkedHashMap<String, MutableList<Int>>()
            errors.forEachIndexed { i, issue ->
                val key = issue.source?.identity
                if (key == null) {
                    val plainKey = issue.path.pretty() to issue.message
                    val remaining = plain[plainKey] ?: 0
                    if (remaining > 0) plain[plainKey] = remaining - 1 else freshAt += i
                } else {
                    byKey.getOrPut(key) { mutableListOf() } += i
                }
            }
            for ((key, at) in byKey) {
                val known = sourcedEvidence[key].orEmpty()
                // 根拠に関わる要素が増えたものは、悪化＝新しいエラー。残りは、適用前の件数を超えた分だけが新しいエラー
                val (same, grown) = at.partition { known.containsAll(errors[it].source?.evidence.orEmpty()) }
                freshAt += grown
                freshAt += same.drop(sourcedCount[key] ?: 0)
            }
            val fresh = errors.filterIndexed { i, _ -> i in freshAt }
            if (fresh.isNotEmpty()) out[rel] = fresh
        }
        return out
    }

    /** ステージされた定義文書（agents / activities / evals / skills / views ゾーン）のパースエラー。対象外は null。 */
    private fun definitionParseError(e: Entry): String? {
        val rel = e.rel.replace('\\', '/')
        val parse: (Map<*, *>) -> Unit = when (ws.layout.zoneOf(rel)) {
            WorkspaceLayout.Zone.AGENTS -> { raw -> Definitions.parseAgent(raw, e.file) }
            WorkspaceLayout.Zone.ACTIVITIES -> { raw -> Definitions.parseActivity(raw, e.file) }
            WorkspaceLayout.Zone.EVALS -> { raw -> Evals.parseEval(raw, e.file) }
            WorkspaceLayout.Zone.SKILLS -> { raw -> Skills.parseSkill(raw, e.file) }
            WorkspaceLayout.Zone.VIEWS -> { raw -> Views.parseView(raw, e.file, ws.schemas) }
            else -> return null
        }
        val raw = YamlIO.toAny(e.root) as? Map<*, *> ?: return "定義のルートはobjectである必要があります"
        return runCatching { parse(raw) }.exceptionOrNull()
            ?.let { "定義として解釈できません: ${it.message ?: it::class.simpleName}" }
    }

    /** 提出用の変更セット。base ハッシュは取り込み時点のディスク内容から計算する（適用時の競合検出）。 */
    fun toChangeSet(): ChangeSet = ChangeSet(
        entries.values.map { e ->
            if (e.created) FileChange(file = e.rel, baseHash = null, ops = emptyList(), create = true, content = e.root)
            else FileChange(file = e.rel, baseHash = e.baseText?.let(ChangeSets::contentHash), ops = e.ops.toList())
        }
    )

    /** レビュー表示用: 編集後ツリーの正規化テキスト。 */
    fun proposedText(rel: String): String? = entries[rel]?.root?.let(YamlIO::dump)
}
