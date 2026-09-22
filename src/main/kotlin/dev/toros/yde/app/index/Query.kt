package dev.toros.yde.app.index

import dev.toros.yde.semantic.SemanticCheckEvaluation
import dev.toros.yde.semantic.SemanticCheckViolation
import dev.toros.yde.semantic.SemanticExpression
import dev.toros.yde.semantic.SemanticForAllResult
import dev.toros.yde.semantic.SemanticQueryNodeRef
import dev.toros.yde.semantic.SemanticQueryResult
import dev.toros.yde.semantic.SemanticQueryRow
import dev.toros.yde.semantic.schema.ConstraintCheck
import dev.toros.yde.semantic.schema.ConstraintForAll
import dev.toros.yde.semantic.schema.ValueExpr
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.semantic.schema.QuerySpec
import dev.toros.yde.semantic.SemanticQuery

/**
 * パターン照合エンジン（R22-01。ARCHITECTURE §4.32）を、索引（IdIndex）から呼ぶ窓口。
 * 評価アルゴリズムの本体は semantic-core の SemanticQuery（3層コア移行 Phase 6C）。索引も結果も、カーネルの型そのもの
 * （P27 段D の D-d・D-e。以前は、結果をファイルの座標 Path へ写し戻す型 QueryNodeRef などを root にも持っていた）。
 * 決定性・判定保留・循環照合の停止・loader 注入の契約はカーネル側に記載。
 * Traverse（core）の隣接1段（`neighborIds`）もここ経由でカーネルに一元化する（R22-02③）。
 */

/** 結果の1ノード。エンティティの場所は定義出現（なければ先頭出現）、文書は文書ID＋空パス。カーネルの型の別名。 */
typealias QueryNodeRef = SemanticQueryNodeRef

/** 結果の1行（束縛名 → ノード。列順は束縛の宣言順）。refs は edge の bind で束縛した参照。カーネルの型の別名。 */
typealias QueryRow = SemanticQueryRow

/** 照合結果。total は limit 打切り前の全行数。カーネルの型の別名。 */
typealias QueryResult = SemanticQueryResult

object Query {

    /** パターンを評価して束縛の表を返す。loader は、文書ID から文書の中身を返す（値の条件 where の評価に使う）。 */
    fun run(index: IdIndex, spec: QuerySpec, loader: (DocumentId) -> YNode?): QueryResult =
        SemanticQuery.run(index.entries, index.refs, index.graph, spec, loader)

    /**
     * 起点を固定した存在判定（R22-03 パターン制約用）: startId を起点束縛に固定したとき、
     * パターン全体（起点フィルタ・hops・also・where）に解が1つ以上あるか。
     */
    fun anyMatchFrom(index: IdIndex, spec: QuerySpec, startId: String, loader: (DocumentId) -> YNode?): Boolean =
        SemanticQuery.anyMatchFrom(index.entries, index.refs, index.graph, spec, startId, loader)

    /**
     * 起点を固定した解の件数（R28-02 count 制約用）: 束縛の組（bind 列）の distinct 件数
     * （limit 打切り前の total）。数えたい対象のノードに bind を書くこと。
     */
    fun countMatchesFrom(index: IdIndex, spec: QuerySpec, startId: String, loader: (DocumentId) -> YNode?): Int =
        SemanticQuery.countMatchesFrom(index.entries, index.refs, index.graph, spec, startId, loader)

    /** 起点を固定した解の列挙（R28-05: 制約指摘の説明用）: 束縛の表（limit まで。total は打切り前）。 */
    fun matchesFrom(index: IdIndex, spec: QuerySpec, startId: String, loader: (DocumentId) -> YNode?): QueryResult =
        SemanticQuery.matchesFrom(index.entries, index.refs, index.graph, spec, startId, loader)

    /**
     * 起点を固定した check 判定（R28-08）: 解ごとに判定述語（束縛間の値比較）を
     * 出現割当て σ で反例量化し、違反した組（束縛・出現・値）を返す。
     * 空リスト＝違反なし（充足、または評価可能な組がない判定保留）。
     */
    fun checkFrom(
        index: IdIndex,
        spec: QuerySpec,
        checks: List<ConstraintCheck>,
        startId: String,
        loader: (DocumentId) -> YNode?,
        /** 値を読む出現の固定（指摘を「値が書かれた場所」へ出す検査用。束縛と文書の指定 → その出現）。 */
        pin: Pair<ValueExpr.ScopeRef, Pair<DocumentId, YPath>>? = null,
    ): List<SemanticCheckViolation> =
        SemanticQuery.checkFrom(
            index.entries, index.refs, index.graph, spec, checks, startId,
            rel = { d -> d.value },
            pin = pin?.let { (source, occ) -> source to SemanticExpression.Occurrence(occ.first, occ.second) },
            loader = loader,
        )

    /** 起点を固定した check の評価（検査の状況の表示用）: 条件を満たしている項目も値つきで返す。 */
    fun checkEvaluationsFrom(
        index: IdIndex,
        spec: QuerySpec,
        checks: List<ConstraintCheck>,
        startId: String,
        loader: (DocumentId) -> YNode?,
    ): List<SemanticCheckEvaluation> =
        SemanticQuery.checkEvaluationsFrom(index.entries, index.refs, index.graph, spec, checks, startId, rel = { d -> d.value }, loader = loader)

    /**
     * 起点を固定した「すべての要素について」の判定（SP-04。網羅マトリクス R28-03 の一般形）: 集まりの要素の数と、
     * 解のなかった要素（ID昇順）。起点が母集団に入らない・集まりが空のときは null（判定保留）。
     */
    fun forAllFrom(
        index: IdIndex,
        spec: QuerySpec,
        forAll: ConstraintForAll,
        startId: String,
        loader: (DocumentId) -> YNode?,
    ): SemanticForAllResult? =
        SemanticQuery.forAllFrom(index.entries, index.refs, index.graph, spec, forAll, startId, rel = { d -> d.value }, loader = loader)

    /**
     * startId が起点束縛の母集団（起点 node のフィルタ＋起点束縛の where 述語）に入るか（R22-03）。
     * 制約の「対象外は黙る」（判定保留 §4.26）と「解なし」を区別するために使う。
     */
    fun startMatches(index: IdIndex, spec: QuerySpec, startId: String, loader: (DocumentId) -> YNode?): Boolean =
        SemanticQuery.startMatches(index.entries, index.refs, index.graph, spec, startId, loader)

    /**
     * Traverse 互換の隣接1段（R22-02③）。out = フロンティア配下に書かれた参照の targetId、
     * in = フロンティアIDへの被参照の書き手（最内のID要素）。distinct＋ID昇順（visited・limit は呼び出し側）。
     */
    fun neighborIds(
        index: IdIndex,
        frontier: List<String>,
        follow: String,
        relation: String? = null,
        via: String? = null,
        schemaId: String? = null,
    ): List<String> =
        SemanticQuery.neighborIds(index.entries, index.refs, index.graph, frontier, follow, relation, via, schemaId)
}
