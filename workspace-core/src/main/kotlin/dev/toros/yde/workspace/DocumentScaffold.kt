package dev.toros.yde.workspace

import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.semantic.ScannedId
import dev.toros.yde.semantic.SemanticEntityGraph
import dev.toros.yde.semantic.SemanticExpression
import dev.toros.yde.semantic.SemanticQueryNodeRef
import dev.toros.yde.semantic.SemanticTransform
import dev.toros.yde.semantic.SemanticValues
import dev.toros.yde.semantic.schema.ExpressionMessages
import dev.toros.yde.semantic.schema.ExpressionValue
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.FileNames
import dev.toros.yde.semantic.schema.QueryNodeKind
import dev.toros.yde.semantic.schema.SchemaScaffold

/** 新しく作る文書のひな型: 作り先（ワークスペース相対パス）と、初期の内容。 */
data class DocumentScaffold(val rel: String, val root: YNode)

/**
 * 新しい文書の元になる要素（P31）: ファイル名の式（filePattern）と、ひな型の中身（scaffold）が読む「いまの要素」。
 * ふつうは ID の定義出現（一覧側の要素）。ID だけ（ビューの newRow のファイルモード。行がまだ無い）でも作れる——
 * そのときは `{id}` 以外の穴は埋まらない。
 */
class ScaffoldSource(
    val id: String,
    /** 表示の解決結果（予約の `{title}`）。 */
    val title: String? = null,
    /** 元の要素の出現（値を読む場所。無ければ、要素の項目の穴は埋まらない）。 */
    val occurrence: SemanticExpression.Occurrence? = null,
    val tree: (DocumentId) -> YNode? = { null },
    /** グラフ（参照の本数・書かれた場所の数・`in` 付きの読み出しに使う。無くてもよい）。 */
    val graph: SemanticEntityGraph? = null,
    val rel: (DocumentId) -> String = { it.value },
) {
    companion object {
        /** ID だけの元（newRow のファイルモード）。 */
        fun idOnly(id: String): ScaffoldSource = ScaffoldSource(id)

        /** 索引の出現から: 定義出現（無ければ先頭の出現。CALC-19 の選び方）を元にする。 */
        fun of(
            id: String,
            occurrences: List<ScannedId>,
            tree: (DocumentId) -> YNode?,
            graph: SemanticEntityGraph? = null,
            rel: (DocumentId) -> String = { it.value },
        ): ScaffoldSource {
            val occ = SemanticExpression.preferDefining(occurrences).firstOrNull()
            return ScaffoldSource(
                id = id,
                title = occ?.title ?: occurrences.firstNotNullOfOrNull { it.title },
                occurrence = occ?.let { SemanticExpression.Occurrence(it.document, it.path) },
                tree = tree,
                graph = graph,
                rel = rel,
            )
        }
    }
}

/**
 * ファイル名として使う式の決まり（P31。「値の使い方」の側の決まりで、式の仕組みの外）と、その文面の1つの表。
 * スキーマの `filePattern`・ビューの `detail.filePattern` / `newRow.filePattern` が共用する。
 * - 書き方（formIssues）: 文字列で書いたなら YAML ファイル名で終わる・穴が1つ以上（無ければどの要素も同じファイルになる）・
 *   元が ID だけ（newRow）なら穴は予約の `{id}` だけ
 * - 作り先（placementIssues）: 代表値で解決した作り先が match に合う（合わなければ作った文書にスキーマが適用されない）・
 *   `{id}` の有無（無ければ同じ名前になる要素があり得る＝注意）
 */
object FileNameRules {

    enum class Kind { NOT_YAML, NO_HOLE, ONLY_RESERVED, UNRESOLVABLE, MISMATCH, NO_ID, NOT_DOCUMENT_SCHEMA, BAD_ID, BAD_PATH }

    /** 決まりに外れた1件。caution は「注意」（読み込みは止めない）。 */
    class Issue(val kind: Kind, val message: String) {
        val caution: Boolean get() = kind == Kind.NO_ID
    }

    /** 文面の表。 */
    fun message(kind: Kind, where: String = "filePattern", text: String = "", path: String = "", match: String = ""): String = when (kind) {
        Kind.NOT_YAML -> "$where はYAMLファイル（.yaml / .yml）で終わる必要があります: $text"
        Kind.NO_HOLE -> "$where には穴（{id} など、要素の値で埋める場所）が必要です: $text"
        Kind.ONLY_RESERVED -> "$where に書ける穴は {id} だけです（行がまだ無く、他の値は決まりません）: $text"
        Kind.UNRESOLVABLE -> "$where の作成先を決められません: $text"
        Kind.MISMATCH -> "$where の作成先「$path」は、このスキーマの match（$match）に合いません（作った文書にこのスキーマが適用されません）"
        Kind.NO_ID -> "$where に {id} がありません（同じ名前になる要素があると、作成は「既に同名のファイルが存在します」で止まります）"
        Kind.NOT_DOCUMENT_SCHEMA -> "$where は、文書のスキーマ（derive 宣言でないもの）にだけ書けます"
        Kind.BAD_ID -> "ファイル名に使用できないIDです: $text"
        Kind.BAD_PATH -> "作成先のパスが不正です: $path"
    }

    /** 書き方の点検（定義の読み込み時）。idOnly は、元が ID だけの場面（newRow のファイルモード）。 */
    fun formIssues(value: ExpressionValue, idOnly: Boolean = false): List<Issue> {
        val issues = mutableListOf<Issue>()
        fun add(kind: Kind) { issues += Issue(kind, message(kind, value.where, value.text)) }
        if (value.isString && !(value.text.endsWith(".yaml") || value.text.endsWith(".yml"))) add(Kind.NOT_YAML)
        if (value.reads.isEmpty()) add(Kind.NO_HOLE)
        else if (idOnly && value.reads.any { it != SemanticValues.RESERVED_ID }) add(Kind.ONLY_RESERVED)
        return issues
    }

    /** 作り先の点検（定義の読み込み時）: 代表 ID で解決した作り先が match に合うこと・`{id}` の有無（注意）。 */
    fun placementIssues(value: ExpressionValue, representativeId: String, match: String, matches: (String) -> Boolean): List<Issue> {
        val issues = mutableListOf<Issue>()
        val rel = DocumentScaffolds.representativePath(value, representativeId)
        when {
            rel == null -> issues += Issue(Kind.UNRESOLVABLE, message(Kind.UNRESOLVABLE, value.where, value.text))
            !matches(rel) -> issues += Issue(Kind.MISMATCH, message(Kind.MISMATCH, value.where, path = rel, match = match))
        }
        if (!value.usesId) issues += Issue(Kind.NO_ID, message(Kind.NO_ID, value.where))
        return issues
    }

    /** 書き方の点検を通らなければ IllegalArgumentException（定義の読み込みを止める）。 */
    fun requireWellFormed(value: ExpressionValue, idOnly: Boolean = false): ExpressionValue {
        formIssues(value, idOnly).firstOrNull()?.let { throw IllegalArgumentException(it.message) }
        return value
    }
}

/**
 * 新しい文書のひな型を組み立てる（P27 段D の D-c で、root の core/Views から移した。P31 で、ファイル名を式にした）。
 * 作成そのものは呼び出し側が行う（アプリはセッションの CreateDocument トランザクション、エージェントは create_document）。
 * - ファイル名: 式（ExpressionValue。スキーマの filePattern など「式を受け取る項目」の値）を、元の要素（ScaffoldSource）の環境で
 *   評価する（SemanticValues）。予約の `{id}` `{title}` が先・要素の項目の穴はその値・穴に入った値はファイル名に使える文字に直す
 *   （FileNames.safeToken。判断点 E-05）。穴が埋まらなければ、作成をエラーにする（文面は ExpressionMessages。判断点 E-04）
 * - 中身: 作り先に合うスキーマの必須の項目の初期値（SchemaScaffold.defaultTree）に、スキーマの `scaffold`（変換のひな型。
 *   いまの場所＝元の要素。R31-03）の出力を重ね、ルート直下の ID の項目へ ID を入れる
 */
object DocumentScaffolds {

    /** ファイル名の式を評価する環境。fallback は、定義の点検で代表値を得るときだけ渡す。 */
    private fun environment(source: ScaffoldSource, fallback: String? = null): SemanticValues.Environment {
        val occ = source.occurrence
        return SemanticValues.Environment(
            tree = source.tree,
            rel = source.rel,
            current = SemanticQueryNodeRef(QueryNodeKind.ENTITY, source.id, source.title, null, occ?.document, occ?.path.orEmpty()),
            currentOccurrence = occ,
            graph = source.graph,
            reserved = buildMap {
                put(SemanticValues.RESERVED_ID, source.id)
                source.title?.let { put(SemanticValues.RESERVED_TITLE, it) }
            },
            holeValue = FileNames::safeToken,
            fallback = fallback,
        )
    }

    /**
     * 作り先（ワークスペース相対パス）。穴が埋まらなければ IllegalArgumentException（文面に、埋まらなかった穴の名前）。
     * 予約の `{id}` は、ファイル名に使えない文字を含むと拒否する（今までどおり。ID は直さない）。
     */
    fun pathFor(pattern: ExpressionValue, source: ScaffoldSource): String {
        validateId(source.id)
        val result = SemanticValues.evaluateDetailed(pattern.expr, environment(source))
        val rel = result.side?.text
            ?: throw IllegalArgumentException(ExpressionMessages.unfilled(pattern.label, pattern.where, result.missing, pattern.text))
        return validatePath(rel)
    }

    /**
     * 定義の点検用の代表の作り先: 予約の `{id}` は代表 ID・その他の穴は「X」で埋める（決められなければ null）。
     * 読み込み時に「作り先が match に合うか」を調べるためのもの。
     */
    fun representativePath(pattern: ExpressionValue, id: String): String? =
        SemanticValues.evaluate(pattern.expr, environment(ScaffoldSource.idOnly(id), fallback = "X"))?.text

    /** 新しい文書のひな型: 作り先と中身。 */
    fun scaffold(pattern: ExpressionValue, source: ScaffoldSource, schemas: List<WorkspaceSchema>): DocumentScaffold {
        val rel = pathFor(pattern, source)
        return DocumentScaffold(rel, contents(schemas.firstOrNull { it.matches(rel) }, source))
    }

    /** 文字列のパターンと ID だけから（newRow のファイルモード・互換）。 */
    fun scaffold(pattern: String, id: String, schemas: List<WorkspaceSchema>): DocumentScaffold =
        scaffold(FileNameRules.requireWellFormed(ExpressionValue.of(pattern, "filePattern")), ScaffoldSource.idOnly(id), schemas)

    /**
     * スキーマの、新しい文書の置き場所: 式を受け取る項目 `filePattern` があればそれ。無ければ、match（glob）から `{id}` を
     * 置き換える形を導く（後退。ディレクトリにワイルドカードの無い単純な glob だけ。導けなければ null）。
     */
    fun filePatternOf(schema: WorkspaceSchema): ExpressionValue? =
        schema.expressionAt("filePattern") ?: detailPatternFromMatch(schema.match)?.let { ExpressionValue.of(it, "match") }

    /**
     * 詳細文書の存在の検査の作成導線（R28-05a・UX-97）: 詳細スキーマの match（glob）から、`{id}` を置き換える形のファイルパターンを導く。
     * 対応するのは、ファイル名の部分が「*.yaml」「*.yml」「*.{yaml,yml}」で、ディレクトリの部分にワイルドカードを含まない単純な形だけ
     * （それ以外は null＝作り先を機械的に決められない。スキーマに filePattern を書けば決められる）。
     */
    fun detailPatternFromMatch(match: String): String? {
        val slash = match.lastIndexOf('/')
        val dir = if (slash >= 0) match.substring(0, slash + 1) else ""
        val name = match.substring(slash + 1)
        if (dir.any { it == '*' || it == '?' || it == '{' }) return null
        val m = Regex("""^\*\.(yaml|yml|\{yaml,yml\}|\{yml,yaml\})$""").matchEntire(name) ?: return null
        val ext = m.groupValues[1].takeUnless { it.startsWith("{") } ?: "yaml"
        return "$dir{id}.$ext"
    }

    /**
     * 中身: 必須の項目の初期値の上に、スキーマの `scaffold`（変換のひな型。入力＝元の要素）の出力を重ね、
     * ルート直下の ID の項目へ ID を入れる（合うスキーマが無ければ、id だけの文書）。
     */
    private fun contents(schema: WorkspaceSchema?, source: ScaffoldSource): YNode {
        val tree = schema?.tree
        val defaults = tree?.let { SchemaScaffold.defaultTree(it) } ?: YNode.Map()
        val built = schema?.scaffold?.let { def ->
            val occ = source.occurrence ?: return@let null
            val input = source.tree(occ.document)?.let { getAt(it, occ.path) } ?: return@let null
            SemanticTransform.run(def, input, occ.document).term
        }
        val root = if (built != null) overlay(defaults, built) else defaults
        (root as? YNode.Map)?.let { map ->
            val idKey = tree?.let { t ->
                (t.resolve(t.root) ?: t.root).fields.firstOrNull { (t.resolve(it) ?: it).type == FieldType.ID }?.key
            } ?: "id"
            map.entries[idKey] = YNode.Scalar(source.id)
        }
        return root
    }

    /** ひな型の出力を初期値の上に重ねる（マップどうしは項目ごとに重ね、それ以外は出力が勝つ）。 */
    private fun overlay(base: YNode, over: YNode): YNode {
        if (base !is YNode.Map || over !is YNode.Map) return over
        val entries = LinkedHashMap(base.entries)
        for ((key, value) in over.entries) {
            val existing = entries[key]
            entries[key] = if (existing != null) overlay(existing, value) else value
        }
        return YNode.Map(entries)
    }

    private fun validateId(id: String) {
        require(id.isNotBlank() && !id.contains('/') && !id.contains('\\') && !id.contains("..")) {
            FileNameRules.message(FileNameRules.Kind.BAD_ID, text = id)
        }
    }

    /** 作り先の形の点検: ワークスペース相対・`..` と空の区切りを含まない・YAML ファイル。 */
    private fun validatePath(rel: String): String {
        val normalized = rel.replace('\\', '/')
        require(!normalized.startsWith("/") && normalized.split('/').none { it == ".." || it.isBlank() }) {
            FileNameRules.message(FileNameRules.Kind.BAD_PATH, path = rel)
        }
        require(normalized.endsWith(".yaml") || normalized.endsWith(".yml")) {
            FileNameRules.message(FileNameRules.Kind.NOT_YAML, "作成先", rel)
        }
        return normalized
    }
}
