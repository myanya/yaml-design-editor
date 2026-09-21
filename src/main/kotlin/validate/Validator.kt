package validate

import index.IdEntry
import index.IdIndex
import index.toKernelView
import model.YNode
import model.YPath
import schema.EnforcementDef
import schema.RelationDef
import schema.SchemaDef
import schema.phaseProgress
import schema.phaseSummaryText
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path

/**
 * エラー時の対応（SP-09。P29 Phase 6 第1段）: 対応の種類は閉じた一覧で、どれを・どのラベルで示すかは
 * 検査の定義（YAML）が宣言する。UIは指摘のメニューに、宣言された順で並べる。
 */
sealed interface IssueResponse {
    val label: String

    /**
     * その場所へ移動する（値の出どころ——テンプレートや、差し込んだ値が書かれた場所、参照を書き足せそうな文書など）。
     * path が無ければ文書を開く。note はメニューでラベルの下に添える補足。
     */
    data class Jump(override val label: String, val file: Path, val path: YPath?, val note: String? = null) : IssueResponse

    /** 値を、指摘の場所へ書き込む（未保存の編集として。Undo 可）。 */
    data class ApplyValue(override val label: String, val value: String) : IssueResponse

    /** この ID の詳細文書を、指定のスキーマの置き場所に新しく作って開く（R28-05a・UX-97 の導線）。 */
    data class CreateDetail(override val label: String, val schemaId: String, val id: String, val note: String? = null) : IssueResponse
}

// 指摘の配り方（IssueDelivery）と指摘の出どころ（IssueSource）は、カーネル（workspace-core の validate/WorkspaceChecks.kt）にある

data class Issue(
    val severity: Severity,
    val message: String,
    val path: YPath,
    /** エラー時の対応（SP-09）。検査の定義が宣言したものを、宣言順に持つ。 */
    val responses: List<IssueResponse> = emptyList(),
    /** 指摘に関連するID（UX-15 の不足案内など）。UIはクリックで各IDの定義へジャンプできる。 */
    val relatedIds: List<String> = emptyList(),
    /**
     * 指摘の出どころ。定義体の検査から出た指摘も、コードで実装された検査（構造の検証・参照の解決など）の指摘も持つ
     * （後者はカーネルの CodedChecks が作る。P27 段B の残り・意図的差分 D-29）。
     */
    val source: IssueSource? = null,
)

// 判定を保留した検査（WithheldCheck）と検査の状況（RuleStatus）も、カーネル（workspace-core の validate/WorkspaceChecks.kt）にある

/** 文書1つの検証の結果: 指摘と、判定を保留した検査。 */
data class ValidationResult(val issues: List<Issue>, val withheld: List<WithheldCheck>)

/** フェーズ1つ分の記入進捗。実体は yaml-core の schema.PhaseProgress（B-3・RB-04 で移設。FQN 互換の別名）。 */
typealias PhaseProgress = schema.PhaseProgress

/**
 * ドキュメントの検証。UI・アプリ状態に依存しない（core / CLI からも使う）。検証の組み立ての実体は、カーネルの
 * validate.DocumentValidator（workspace-core。P27 段D の D-b）で、ここは、座標（Path と IdIndex）を写して呼ぶ窓口。
 * - スキーマ適合（必須／型／enum／日付形式）
 * - 参照解決（索引 + 同一ドキュメント内の未保存 ID を考慮）
 * - ID 重複（同一ドキュメント内）
 * - カバレッジ（スキーマの coverage ルールによる被参照数の充足。索引＝保存済み状態に基づく）
 */
object Validator {

    /** 文書1つの検証（指摘だけ）。判定を保留した検査も要るときは validateDetailed。 */
    fun validate(
        root: YNode?,
        parseError: String?,
        file: Path,
        schema: SchemaDef?,
        indexObj: IdIndex,
        indexed: Boolean = true,
        workspace: Path? = null,
        loader: (Path) -> YNode? = { f -> runCatching { YamlIO.parseFirst(Files.readString(f)) }.getOrNull() },
        allSchemas: List<SchemaDef> = emptyList(),
        relations: Map<String, RelationDef> = emptyMap(),
        enforcement: EnforcementDef = EnforcementDef(),
        shareEvaluations: Boolean = false,
    ): List<Issue> = validateDetailed(
        root, parseError, file, schema, indexObj, indexed, workspace, loader, allSchemas, relations, enforcement, shareEvaluations,
    ).issues

    fun validateDetailed(
        root: YNode?,
        parseError: String?,
        file: Path,
        schema: SchemaDef?,
        indexObj: IdIndex,
        /**
         * この文書が索引（台帳）に載っているか（R13-03）。台帳外の文書（ledger 宣言の対象外）では
         * 索引前提の判定（未解決参照・カバレッジ）が成立しないため、未解決参照を情報レベルへ降格し、
         * カバレッジ判定を行わない。単一ファイルで完結する検証（型・必須・enum・フェーズ・ファイル内重複）は
         * そのまま行う（ARCHITECTURE §4.25 のエディタ層）。
         */
        indexed: Boolean = true,
        /** ワークスペースのルート（整合検証 R16-03a で selector の fileGlob 判定に使う相対パス化。null ならパス文字列をそのまま使う）。 */
        workspace: Path? = null,
        /** 整合検証で他文書のツリーを読む loader。既定はディスク（索引と同じ基準）。変更セット適用・ステージングはオーバーレイを渡す。 */
        loader: (Path) -> YNode? = { f -> runCatching { YamlIO.parseFirst(Files.readString(f)) }.getOrNull() },
        /** 全スキーマ（テンプレート健全性検査 UX-12 が他スキーマの整合ルールを参照するため。空なら自スキーマのみ）。 */
        allSchemas: List<SchemaDef> = emptyList(),
        /** 関係レジストリ（R21-01）: グラフ整合検証（対象型・多重度・非循環）の制約宣言。空なら判定しない。 */
        relations: Map<String, RelationDef> = emptyMap(),
        /** 強制スイッチ（R21-01。ARCHITECTURE §4.31）: 未宣言キーは P20 の挙動のまま。 */
        enforcement: EnforcementDef = EnforcementDef(),
        /**
         * 検査の評価を、同じ索引の他の文書の検証と共有する（P27 段B。全体を回す経路用）。true にしてよいのは、root と loader の中身が
         * indexObj と同じ内容のときだけ（ディスクの索引とディスクの内容・未保存の編集を重ねた索引と未保存を優先する loader・
         * ステージングの索引とステージングの内容）。索引に重ねていない内容（提案のプレビューなど）を検証するときは false のままにする。
         */
        shareEvaluations: Boolean = false,
    ): ValidationResult {
        // 検証の組み立て（構造の検証 → ID と参照 → エンティティ型の点検 → 宣言のない関係名 → 定義体の検査 → テンプレートの健全性 →
        // 直せる場所への案内・台帳の外の文書での降格・同じ指摘の重複の除去）は、カーネルの validate.DocumentValidator が持つ
        // （P27 段D の D-b・意図的差分 D-34。カーネル側の CLI と、変更セットの適用も、同じ組み立てで検証する）。
        // ここは、索引（IdIndex）とファイルの座標（Path）をカーネルの形へ写して呼び、結果を Issue へ写すだけ
        val view = indexObj.toKernelView()
        fun relOf(f: Path): String =
            workspace?.let { IdIndex.relPathOf(it, f) } ?: f.toString().replace('\\', '/')
        val validator = DocumentValidator(
            schemas = allSchemas.map { it.kernel },
            relations = relations,
            enforcement = enforcement,
            ids = view.entries, refs = view.refs, graph = view.graph,
            // 検査の評価の覚え書き: 共有するなら索引のもの、しないならこの検証1回の中だけのもの
            session = if (shareEvaluations) view.checkSession else semantic.SemanticCheckSession(view.entries, view.refs, view.graph),
            // 宣言のない関係名の指摘は、索引につき1回だけ求めたものから、この文書の分を取り出す
            integrity = { view.integrityIssues(semantic.SemanticDeclarations(relations = relations, enforcement = enforcement)) },
            rel = { d -> relOf(Path.of(d.value)) },
            tree = { d -> loader(Path.of(d.value)) },
            hasDocumentId = { indexObj.graph.documents.containsKey(it) },
        )
        val result = validator.validate(
            // 呼び出し側の Path の表記が索引と違っても報告先の判定がずれないよう、文書ID は索引の側のものに揃える
            document = view.documentIdOf(file),
            schema = schema?.kernel,
            root = root,
            parseError = parseError,
            documentId = root?.let { indexObj.documentIdFor(file, it) } ?: file.toString(),
            indexed = indexed,
        )
        return ValidationResult(result.issues.map { it.toIssue() }, result.withheld)
    }

    /**
     * 検査の報告先（意図的差分 D-21 ④）。決め方の実体はカーネルの WorkspaceChecks.reportAnchorOf（同じ規則）。
     * スキーマの検査（owner = 検査を宣言したスキーマ）は、定義出現がそのスキーマの文書にあればそこ、なければそのスキーマの文書の
     * 先頭の出現（無ければ null＝報告しない）。特定のスキーマに属さない検査（owner = null）は、定義出現（なければ先頭の出現）。
     */
    fun reportAnchorOf(indexObj: IdIndex, id: String, owner: SchemaDef?): index.IdEntry? {
        val node = indexObj.graph.entities[id] ?: return null
        if (owner == null) return node.defining ?: node.occurrences.firstOrNull()
        return node.defining?.takeIf { it.schemaId == owner.id } ?: node.occurrences.firstOrNull { it.schemaId == owner.id }
    }

    /**
     * ID 1件の「検査の状況」（SP-08）: このスキーマの検査のうち `status` を宣言したもの（詳細文書の存在＝coverage など）について、
     * 満たしているか・不足しているか・保留か・フェーズ未到達かを、いまの値つきで返す。判定は検証と同じ経路（カーネルの WorkspaceChecks）。
     * definingRoot は、この ID がこのスキーマの文書に書かれている、その文書のルート（activeFrom のフェーズ判定に使う。null なら常時判定）。
     */
    fun ruleStatuses(
        id: String,
        schema: SchemaDef,
        indexObj: IdIndex,
        definingRoot: YNode? = null,
        allSchemas: List<SchemaDef> = emptyList(),
        workspace: Path? = null,
        loader: (Path) -> YNode? = { f -> runCatching { YamlIO.parseFirst(Files.readString(f)) }.getOrNull() },
    ): List<RuleStatus> {
        if (schema.constraints.none { it.status != null }) return emptyList()
        val trees = mutableMapOf<Path, YNode?>()
        val view = indexObj.toKernelView()
        val checks = WorkspaceChecks(
            allSchemas.map { it.kernel }, emptyMap(), EnforcementDef(), view.entries, view.refs, view.graph,
            semantic.SemanticCheckSession(view.entries, view.refs, view.graph),
            rel = { d -> Path.of(d.value).let { f -> workspace?.let { IdIndex.relPathOf(it, f) } ?: f.toString().replace('\\', '/') } },
            tree = { d -> Path.of(d.value).let { f -> trees.getOrPut(f) { loader(f) } } },
        )
        return checks.statuses(id, schema.kernel, definingRoot)
    }

    /**
     * フェーズ別の記入進捗（requiredFrom を持つフィールドの記入済み／総数）。phases 未宣言のスキーマでは空。
     * 走査の実体は yaml-core の `SchemaTree.phaseProgress`（B-3・RB-04 で移設。§4.15）。
     */
    fun phaseProgress(root: YNode?, schema: SchemaDef): List<PhaseProgress> =
        schema.tree.phaseProgress(root)

    /**
     * 現在フェーズと記入進捗の1行サマリ（UI・CLI・エージェントの表示で共用）。
     * `phases` 未宣言のスキーマでは null（呼び出し側は表示自体を省略する）。
     */
    fun phaseSummaryText(root: YNode?, schema: SchemaDef): String? =
        schema.tree.phaseSummaryText(root)

    /** カバレッジの数え上げ対象の表示名（参照ベース=「参照」、出現ベース=「出現」）。UI表示で共用する。 */
}

/**
 * カーネルの指摘を、ファイルの座標（Path）の Issue へ写す。resolve は、文書ID をファイルの場所へ写す決め方
 * （既定は、文書ID の値がそのままパス＝索引 IdIndex の座標。ワークスペース相対の文書ID なら、ルートから解決する関数を渡す）。
 */
internal fun CheckIssue.toIssue(resolve: (model.DocumentId) -> Path = { Path.of(it.value) }): Issue = Issue(
    severity, message, path,
    responses = responses.map { r ->
        when (r) {
            is CheckResponse.Jump -> IssueResponse.Jump(r.label, resolve(r.document), r.path, r.note)
            is CheckResponse.ApplyValue -> IssueResponse.ApplyValue(r.label, r.value)
            is CheckResponse.CreateDetail -> IssueResponse.CreateDetail(r.label, r.schemaId, r.id, r.note)
        }
    },
    relatedIds = relatedIds,
    source = source,
)
