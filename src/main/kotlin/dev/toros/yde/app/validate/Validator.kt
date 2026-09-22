package dev.toros.yde.app.validate

import dev.toros.yde.app.index.IdEntry
import dev.toros.yde.app.index.IdIndex
import dev.toros.yde.semantic.SemanticCheckSession
import dev.toros.yde.semantic.SemanticDeclarations
import dev.toros.yde.workspace.validate.CheckIssue
import dev.toros.yde.workspace.validate.CheckResponse
import dev.toros.yde.workspace.validate.DocumentValidation
import dev.toros.yde.workspace.validate.DocumentValidator
import dev.toros.yde.workspace.validate.RuleStatus
import dev.toros.yde.workspace.validate.WorkspaceChecks
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.semantic.schema.EnforcementDef
import dev.toros.yde.semantic.schema.RelationDef
import dev.toros.yde.app.schema.SchemaDef
import dev.toros.yde.semantic.schema.phaseProgress
import dev.toros.yde.semantic.schema.phaseSummaryText

/**
 * 検証の指摘・エラー時の対応・文書1つの検証の結果は、カーネルの型（workspace-core の validate パッケージ）の別名
 * （P27 段D の D-d・D-e。以前は、ファイルの場所を Path で持つ同じ形の型を root にも持ち、検証のたびに写していた）。
 * - Issue: 重大度・文面・場所（文書の中のパス）・エラー時の対応・関連する ID・出どころ（IssueSource）
 * - IssueResponse（SP-09）: 対応の種類は閉じた一覧（Jump＝その場所へ移動・ApplyValue＝値を書き込む・CreateDetail＝詳細文書を作る）で、
 *   どれを・どのラベルで示すかは、検査の定義（YAML）が宣言する。Jump の行き先は文書ID（document）——画面は、開く所で Path へ直す
 * - ValidationResult: 指摘と、判定を保留した検査
 */
typealias Issue = CheckIssue
typealias IssueResponse = CheckResponse
typealias ValidationResult = DocumentValidation

/** フェーズ1つ分の記入進捗。実体は semantic-core の schema.PhaseProgress（B-3・RB-04 で移設。FQN 互換の別名）。 */
typealias PhaseProgress = dev.toros.yde.semantic.schema.PhaseProgress

/**
 * ドキュメントの検証を、索引（IdIndex）とアプリ側のスキーマの型（SchemaDef）から呼ぶ窓口。UI・アプリ状態に依存しない
 * （core / CLI からも使う）。検証の組み立ての実体は、カーネルの validate.DocumentValidator（workspace-core。P27 段D の D-b）で、
 * 場所は、文書ID（ワークスペース相対パス）で表す（P27 段D の D-d・D-e）。
 * - スキーマ適合（必須／型／enum／日付形式）
 * - 参照解決（索引 + 同一ドキュメント内の未保存 ID を考慮）
 * - ID 重複（同一ドキュメント内）
 * - 定義体の検査（詳細文書の存在・整合・多重度・対象型・網羅・遷移・利用者の検査）と、エンティティ型の点検・テンプレートの健全性
 */
object Validator {

    /** 文書1つの検証（指摘だけ）。判定を保留した検査も要るときは validateDetailed。 */
    fun validate(
        root: YNode?,
        parseError: String?,
        document: DocumentId,
        schema: SchemaDef?,
        indexObj: IdIndex,
        indexed: Boolean = true,
        loader: (DocumentId) -> YNode? = { null },
        allSchemas: List<SchemaDef> = emptyList(),
        relations: Map<String, RelationDef> = emptyMap(),
        enforcement: EnforcementDef = EnforcementDef(),
        shareEvaluations: Boolean = false,
    ): List<Issue> = validateDetailed(
        root, parseError, document, schema, indexObj, indexed, loader, allSchemas, relations, enforcement, shareEvaluations,
    ).issues

    fun validateDetailed(
        root: YNode?,
        parseError: String?,
        /** この文書の文書ID（ワークスペース相対パス）。 */
        document: DocumentId,
        schema: SchemaDef?,
        indexObj: IdIndex,
        /**
         * この文書が索引（台帳）に載っているか（R13-03）。台帳外の文書（ledger 宣言の対象外）では
         * 索引前提の判定（未解決参照・カバレッジ）が成立しないため、未解決参照を情報レベルへ降格し、
         * カバレッジ判定を行わない。単一ファイルで完結する検証（型・必須・enum・フェーズ・ファイル内重複）は
         * そのまま行う（ARCHITECTURE §4.25 のエディタ層）。
         */
        indexed: Boolean = true,
        /**
         * 他の文書の中身を読む loader（整合検証・重複所有・テンプレートの健全性が使う）。索引と同じ基準の内容を返すこと
         * （ディスクの索引ならディスクの内容・未保存の編集を重ねた索引なら未保存を優先・ステージングの索引ならステージングの内容）。
         */
        loader: (DocumentId) -> YNode? = { null },
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
        // 索引も結果も、カーネルの型そのもの——ここは、アプリ側のスキーマの型（SchemaDef）を、カーネルの型へ読み替えて呼ぶだけ
        val validator = DocumentValidator(
            schemas = allSchemas.map { it.kernel },
            relations = relations,
            enforcement = enforcement,
            ids = indexObj.entries, refs = indexObj.refs, graph = indexObj.graph,
            // 検査の評価の覚え書き: 共有するなら索引のもの、しないならこの検証1回の中だけのもの
            session = if (shareEvaluations) indexObj.checkSession
            else SemanticCheckSession(indexObj.entries, indexObj.refs, indexObj.graph),
            // 宣言のない関係名の指摘は、索引につき1回だけ求めたものから、この文書の分を取り出す
            integrity = { indexObj.integrityIssues(SemanticDeclarations(relations = relations, enforcement = enforcement)) },
            rel = { d -> d.value },
            tree = loader,
        )
        return validator.validate(
            document = document,
            schema = schema?.kernel,
            root = root,
            parseError = parseError,
            documentId = root?.let { indexObj.documentIdFor(document, it) } ?: document.value,
            indexed = indexed,
        )
    }

    /**
     * 検査の報告先（意図的差分 D-21 ④）。決め方の実体はカーネルの WorkspaceChecks.reportAnchorOf（同じ規則）。
     * スキーマの検査（owner = 検査を宣言したスキーマ）は、定義出現がそのスキーマの文書にあればそこ、なければそのスキーマの文書の
     * 先頭の出現（無ければ null＝報告しない）。特定のスキーマに属さない検査（owner = null）は、定義出現（なければ先頭の出現）。
     */
    fun reportAnchorOf(indexObj: IdIndex, id: String, owner: SchemaDef?): IdEntry? {
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
        loader: (DocumentId) -> YNode? = { null },
    ): List<RuleStatus> {
        if (schema.constraints.none { it.status != null }) return emptyList()
        val trees = mutableMapOf<DocumentId, YNode?>()
        val checks = WorkspaceChecks(
            allSchemas.map { it.kernel }, emptyMap(), EnforcementDef(), indexObj.entries, indexObj.refs, indexObj.graph,
            SemanticCheckSession(indexObj.entries, indexObj.refs, indexObj.graph),
            rel = { d -> d.value },
            tree = { d -> trees.getOrPut(d) { loader(d) } },
        )
        return checks.statuses(id, schema.kernel, definingRoot)
    }

    /**
     * フェーズ別の記入進捗（requiredFrom を持つフィールドの記入済み／総数）。phases 未宣言のスキーマでは空。
     * 走査の実体は semantic-core の `SchemaTree.phaseProgress`（B-3・RB-04 で移設。§4.15）。
     */
    fun phaseProgress(root: YNode?, schema: SchemaDef): List<PhaseProgress> =
        schema.tree.phaseProgress(root)

    /**
     * 現在フェーズと記入進捗の1行サマリ（UI・CLI・エージェントの表示で共用）。
     * `phases` 未宣言のスキーマでは null（呼び出し側は表示自体を省略する）。
     */
    fun phaseSummaryText(root: YNode?, schema: SchemaDef): String? =
        schema.tree.phaseSummaryText(root)
}
