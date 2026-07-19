package agent

import core.ChangeSet
import core.ChangeSets
import core.FileChange
import core.PatchOp
import core.Patcher
import core.Workspace
import index.DocScanner
import index.IdIndex
import model.YNode
import schema.SchemaLoader
import validate.Issue
import validate.Severity
import validate.Validator
import yaml.YamlIO
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

    /** オーバーレイを重ねた索引。ステージ済みの編集・新規文書を含めて解決する。 */
    fun index(): IdIndex {
        stagedIndex?.let { return it }
        val overlay = entries.values.associate { it.file to it.root }
        val files = (ws.files + overlay.keys).distinct()
        val built = IdIndex().apply {
            rebuild(ws.root, files, ws.schemas) { f ->
                overlay[f] ?: runCatching { YamlIO.parse(Files.readString(f)) }.getOrNull()
            }
        }
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
                    affectedIds += DocScanner.scan(beforeRoot, e.file, schemaDef, docId).first.map { it.id }
                }
            }
            val docId = IdIndex.documentIdOf(e.root, e.rel)
            affectedIds += DocScanner.scan(e.root, e.file, schemaDef, docId).first.map { it.id }
        }
        // それらのIDを参照しているファイルを検証対象に加える（ディスク索引＋オーバーレイ索引の双方から）
        (ws.index.refs + idx.refs).asSequence()
            .filter { it.targetId in affectedIds }
            .map { it.file }
            .distinct()
            .forEach { f ->
                val rel = ws.relPath(f)
                if (rel !in targets) tree(rel)?.let { targets[rel] = f to it }
            }
        val results = targets.mapValues { (_, target) ->
            Validator.validate(target.second, null, target.first, ws.schemaFor(target.first), idx)
        }.toMutableMap()
        // メタアクティビティ（R4-03）: ステージされた定義文書は実際のローダでパースできることもゲート条件にする
        // （スキーマ検証だけでは「schedule に everyMinutes がない」等の意味的エラーを見逃すため）
        entries.values.forEach { e ->
            definitionParseError(e)?.let { message ->
                results[e.rel] = (results[e.rel] ?: emptyList()) + Issue(Severity.ERROR, message, emptyList())
            }
        }
        return results
    }

    /** ステージされた定義文書（_agents / _activities / _evals / _skills）のパースエラー。対象外は null。 */
    private fun definitionParseError(e: Entry): String? {
        val rel = e.rel.replace('\\', '/')
        val parse: (Map<*, *>) -> Unit = when {
            rel.startsWith(SchemaLoader.AGENTS_DIR + "/") -> { raw -> Definitions.parseAgent(raw, e.file) }
            rel.startsWith(SchemaLoader.ACTIVITIES_DIR + "/") -> { raw -> Definitions.parseActivity(raw, e.file) }
            rel.startsWith(SchemaLoader.EVALS_DIR + "/") -> { raw -> Evals.parseEval(raw, e.file) }
            rel.startsWith(SchemaLoader.SKILLS_DIR + "/") -> { raw -> skill.Skills.parseSkill(raw, e.file) }
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
