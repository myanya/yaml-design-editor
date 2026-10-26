package dev.toros.yde.workspace.layout

import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YNode
import java.nio.file.Files
import java.nio.file.Path

/**
 * ワークスペースの物理配置ポリシー（R13-02。architecture/workspace.md「ワークスペース構造と layout」）。
 * 「どこが定義・記録ディレクトリか」「どこが台帳か」「索引から除外するか」の判定を一元化する。
 * 機能側でプレフィックスやディレクトリ名の直書き判定を書かないこと。
 *
 * - レガシー構成（`.yde/` なし）: ルート直下の `_schemas` 等7予約ディレクトリ。台帳=ワークスペース全体（従来動作）。
 * - `.yde` 構成: 定義・記録は `.yde/schemas` 等。`.yde/workspace.yaml` の `ledger:`（文字列またはリスト）で
 *   台帳ディレクトリを宣言する（未宣言なら全体）。
 */
data class WorkspaceLayout(
    /** 構成ディレクトリの相対パス（`.yde`）。null はレガシー構成。 */
    val configDir: String?,
    /** 台帳ディレクトリ（相対・'/'区切り）。空 = ワークスペース全体が台帳。 */
    val ledger: List<String>,
    /** file 型の値（ファイルの場所）の許可（`files:`。外・許す場所・無いファイル）。 */
    val files: FilePolicy = FilePolicy.DEFAULT,
) {

    /**
     * 定義・記録のゾーン（旧・予約ディレクトリ）。レガシー名はここが正
     * （ルート SchemaLoader の互換定数は同じリテラルを保持する。3層コア移行 WS-001）。
     */
    enum class Zone(val legacyDir: String, val ydeName: String) {
        SCHEMAS("_schemas", "schemas"),
        AGENTS("_agents", "agents"),
        ACTIVITIES("_activities", "activities"),
        RUNS("_runs", "runs"),
        EVALS("_evals", "evals"),
        SKILLS("_skills", "skills"),
        VIEWS("_views", "views"),
        REPORTS("_reports", "reports"),
    }

    /** ゾーンの相対ディレクトリ（例: `_schemas` または `.yde/schemas`）。 */
    fun dir(zone: Zone): String = if (configDir == null) zone.legacyDir else "$configDir/${zone.ydeName}"

    val schemasDir: String get() = dir(Zone.SCHEMAS)
    val agentsDir: String get() = dir(Zone.AGENTS)
    val activitiesDir: String get() = dir(Zone.ACTIVITIES)
    val runsDir: String get() = dir(Zone.RUNS)
    val evalsDir: String get() = dir(Zone.EVALS)
    val skillsDir: String get() = dir(Zone.SKILLS)
    val viewsDir: String get() = dir(Zone.VIEWS)
    val reportsDir: String get() = dir(Zone.REPORTS)

    private fun norm(rel: String): String = rel.replace('\\', '/').trimStart('/')

    private fun under(rel: String, dir: String): Boolean = rel == dir || rel.startsWith("$dir/")

    /** 相対パスが属するゾーン（定義・記録ディレクトリの種別）。属さなければ null。 */
    fun zoneOf(rel: String): Zone? {
        val n = norm(rel)
        return Zone.entries.firstOrNull { under(n, dir(it)) }
    }

    /** 予約領域（定義・記録ゾーンおよび構成ディレクトリ全体）か。台帳の文書ではない。 */
    fun isReserved(rel: String): Boolean {
        val n = norm(rel)
        if (configDir != null && under(n, configDir)) return true
        return zoneOf(n) != null
    }

    /** メタアクティビティ（meta: true）が書込を提案できるゾーンか（schemas / runs・構成ファイルは常に不可）。 */
    fun writableByMeta(rel: String): Boolean =
        zoneOf(rel) in setOf(Zone.AGENTS, Zone.ACTIVITIES, Zone.EVALS, Zone.SKILLS, Zone.VIEWS, Zone.REPORTS)

    /**
     * 台帳の文書か（台帳層＝ID索引・参照・カバレッジ・エージェント・自動化の対象）。
     * 予約領域・隠しパスは常に台帳外。`ledger` 宣言があるときはその配下のみ。
     */
    fun inLedger(rel: String): Boolean {
        val n = norm(rel)
        if (isReserved(n)) return false
        if (n.split('/').any { it.startsWith(".") }) return false
        if (ledger.isEmpty()) return true
        return ledger.any { under(n, it) }
    }

    /**
     * ID索引の走査から除外するか（schemas / runs ゾーンと、ゾーン外の構成ファイル）。
     * agents 等の定義ゾーンは id で参照されうるため索引に載せる（従来どおり）。
     */
    fun indexExcluded(rel: String): Boolean {
        val n = norm(rel)
        val zone = zoneOf(n)
        if (zone == Zone.SCHEMAS || zone == Zone.RUNS) return true
        return zone == null && configDir != null && under(n, configDir)
    }

    companion object {
        /** 構成ディレクトリ名（R13-01）。 */
        const val CONFIG_DIR = ".yde"

        /** 構成ファイル名（`ledger:` 宣言を持つ）。 */
        const val CONFIG_FILE = "workspace.yaml"

        /** レガシー構成（従来動作）。 */
        val LEGACY = WorkspaceLayout(configDir = null, ledger = emptyList())

        /**
         * ワークスペースの構成を判定する。`.yde/` が無ければレガシー構成。
         * 構成ファイルの読取・パースに失敗した場合は `ledger` 未宣言（全体が台帳）として続行する。
         */
        fun of(root: Path): WorkspaceLayout {
            val config = root.resolve(CONFIG_DIR)
            if (!Files.isDirectory(config)) return LEGACY
            val text = dev.toros.yde.workspace.WorkspacePaths.resolve(root, "$CONFIG_DIR/$CONFIG_FILE").takeIf { Files.isRegularFile(it) }
                ?.let { runCatching { Files.readString(it) }.getOrNull() }
            val ledger = text?.let { runCatching { parseLedgerText(it) }.getOrDefault(emptyList()) }.orEmpty()
            val files = text?.let { runCatching { parseFilesText(it) }.getOrDefault(FilePolicy.DEFAULT) } ?: FilePolicy.DEFAULT
            return WorkspaceLayout(configDir = CONFIG_DIR, ledger = ledger, files = files)
        }

        /** 構成ファイル本文から file 型の値の許可（`files:`）を読む。無ければ既定。 */
        fun parseFilesText(text: String): FilePolicy = FilePolicy.parse(YamlIO.parseFirst(text) as? YNode.Map)

        /** 構成ファイル本文から `ledger:` 宣言を読む（DocumentStore 経由のロード＝WS-002 と共有）。 */
        fun parseLedgerText(text: String): List<String> {
            val root = YamlIO.parseFirst(text) as? YNode.Map ?: return emptyList()
            val value = root.entries["ledger"] ?: return emptyList()
            val raw = when (value) {
                is YNode.Scalar -> listOfNotNull(value.value?.toString())
                is YNode.Seq -> value.items.mapNotNull { (it as? YNode.Scalar)?.value?.toString() }
                else -> emptyList()
            }
            return raw.map { it.replace('\\', '/').trim().trimStart('/').trimEnd('/') }
                .filter { it.isNotBlank() && !it.contains("..") }
        }
    }
}
