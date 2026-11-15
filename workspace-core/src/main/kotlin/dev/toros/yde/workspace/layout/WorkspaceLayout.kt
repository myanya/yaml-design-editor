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
    /** 定義体のスコープ（`scopes:`。業務モデルなど）。空なら全体が 1 つのまとまり。 */
    val scopes: DefinitionScopes = DefinitionScopes.NONE,
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
        /** 生成の定義（業務モデルから文書を作る変換。CLI generate）。 */
        TRANSFORMS("_transforms", "transforms"),
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
    val transformsDir: String get() = dir(Zone.TRANSFORMS)

    /** 配布できる表示状態。定義ゾーン・台帳・編集履歴には載せない。 */
    val stateDir: String get() = configDir?.let { "$it/state" } ?: "_state"
    val diagramStateDir: String get() = "$stateDir/diagrams"
    fun isState(rel: String): Boolean = under(norm(rel), stateDir)

    private fun norm(rel: String): String = rel.replace('\\', '/').trimStart('/')

    private fun under(rel: String, dir: String): Boolean = rel == dir || rel.startsWith("$dir/")

    /** 相対パスが属するゾーン（定義・記録ディレクトリの種別）。属さなければ null。 */
    fun zoneOf(rel: String): Zone? {
        val n = norm(rel)
        return Zone.entries.firstOrNull { under(n, dir(it)) }
    }

    /** 定義体（ワークスペース相対の場所）が入るスコープ。構成ディレクトリの外・どのスコープにも入らなければ空。 */
    fun scopesOf(rel: String): List<DefinitionScope> {
        val config = configDir ?: return emptyList()
        val n = norm(rel)
        if (!n.startsWith("$config/")) return emptyList()
        return scopes.scopesOf(n.removePrefix("$config/"))
    }

    /** 定義体が入るスコープ（重なっていれば最初のもの。重なりは ScopeChecks が誤りにする）。 */
    fun scopeOf(rel: String): DefinitionScope? = scopesOf(rel).firstOrNull()

    /** 予約領域（定義・記録ゾーンおよび構成ディレクトリ全体）か。台帳の文書ではない。 */
    fun isReserved(rel: String): Boolean {
        val n = norm(rel)
        if (isState(n)) return true
        if (configDir != null && under(n, configDir)) return true
        return zoneOf(n) != null
    }

    /**
     * 定義ゾーンの YAML ファイル（下のフォルダも読む。順はゾーンの中の場所＝`business/todo.yaml` の文字の順）。
     * フォルダは置き場所の整理だけで、定義の ID はゾーン全体で 1 つ（名前空間にしない。重なりは各読み込みが誤りにする）。
     * 点で始まるフォルダ・ファイルと、ワークスペースの外を指すリンクは読まない。定義を読む入口はここを通す
     * （`Files.list` でゾーンのすぐ下だけを読む書き方を各所に持たない）。
     */
    fun definitionFiles(root: Path, zone: Zone): List<Path> {
        val dir = runCatching { dev.toros.yde.workspace.WorkspacePaths.resolve(root, dir(zone)) }.getOrNull() ?: return emptyList()
        if (!Files.isDirectory(dir)) return emptyList()
        val boundary = dev.toros.yde.workspace.WorkspacePaths.ReadBoundary(root)
        val base = root.toAbsolutePath().normalize()
        return Files.walk(dir).use { stream ->
            stream.filter { p ->
                val n = p.fileName.toString()
                Files.isRegularFile(p) && (n.endsWith(".yaml") || n.endsWith(".yml")) &&
                    dir.relativize(p).none { it.toString().startsWith(".") }
            }.toList()
        }.mapNotNull { p ->
            val rel = base.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/')
            runCatching { boundary.resolve(rel) }.getOrNull()?.let { rel to it }
        }.sortedBy { (rel, _) -> nameInZone(rel) }.map { it.second }
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
        if (isState(n)) return true
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
         * 定義の誤りの文に出す名前: ゾーンのディレクトリから見た場所（`todo.yaml`・下のフォルダなら `business/todo.yaml`）。
         * ゾーンの外ならファイル名。`.yde` 構成とレガシー構成のどちらのゾーンの場所も読む（ディレクトリ名は Zone が正）。
         */
        fun nameInZone(rel: String): String {
            val n = rel.replace('\\', '/').trimStart('/')
            val prefix = Zone.entries.flatMap { listOf("$CONFIG_DIR/${it.ydeName}/", "${it.legacyDir}/") }.firstOrNull { n.startsWith(it) }
            return prefix?.let { n.removePrefix(it) } ?: n.substringAfterLast('/')
        }

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
            return WorkspaceLayout(configDir = CONFIG_DIR, ledger = ledger, files = files, scopes = parseScopesText(text))
        }

        /** 構成ファイル本文から定義体のスコープ（`scopes:`）を読む。読めない本文は「スコープ無し」。 */
        fun parseScopesText(text: String?): DefinitionScopes =
            text?.let { runCatching { DefinitionScopes.parse(YamlIO.parseFirst(it) as? YNode.Map) }.getOrNull() } ?: DefinitionScopes.NONE

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
