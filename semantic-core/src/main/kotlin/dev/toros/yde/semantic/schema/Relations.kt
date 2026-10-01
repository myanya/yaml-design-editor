package dev.toros.yde.semantic.schema

import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.validate.Severity

/**
 * 二層モデルの宣言語彙（P20: R20-01 / R20-02。architecture/graph-query.md「エンティティ型と関係レジストリ」）。
 * エンティティ型（スキーマ文書ルートの `entity:`）と関係レジストリ（`kind: relations` の `relations:`）の
 * モデルをここに置く。表示解決が index / UI 双方から使うため schema パッケージに配置する（Display architecture/index-references.md「表示解決」 と同じ理由）。
 */

/** カバレッジ・整合・関係制約の指摘重大度（3層コア移行 SM-000 で Schema.kt から移動）。 */
enum class CoverageLevel(val id: String) {
    WARNING("warning"),
    ERROR("error");

    /** 規則の宣言重大度 → 指摘の重大度（全検証語彙で共通の単一写像。R29-06）。 */
    fun toSeverity(): Severity =
        if (this == ERROR) Severity.ERROR else Severity.WARNING
}

/** ID用 glob（`*` `?`）を正規表現へ変換する。パス用の globToRegex と異なり `/` を特別扱いしない。 */
fun idGlobToRegex(pattern: String): Regex {
    val regex = buildString {
        append('^')
        pattern.forEach { ch ->
            when (ch) {
                '*' -> append(".*")
                '?' -> append('.')
                '.', '(', ')', '+', '|', '^', '$', '@', '%', '[', ']', '{', '}', '\\' -> append('\\').append(ch)
                else -> append(ch)
            }
        }
        append('$')
    }
    return Regex(regex)
}

/** 索引上の出現の役割。null（IdEntry.role）は「エンティティ型の宣言なし」。 */
enum class EntityRole {
    DEFINING,   // 定義（誕生）の出現
    DETAIL,     // 詳細化の出現
}

/**
 * エンティティ型の宣言（R20-01）。スキーマ文書ルートの `entity:`（単一 object または複数型のリスト）。
 * 複数型を持つスキーマは ID の決まり（idPattern＝glob、または idType＝ID の型の名前。P33）で型を判別する。
 * defines=true の文書群の出現が「定義（誕生）」。
 * aspect / owns はアスペクト宣言（R23-01。architecture/graph-query.md「エンティティページ」）: この出現がエンティティの
 * どの側面を所有するか。owns=null（未宣言）は「この出現要素の宣言フィールド全部」。
 */
data class EntityDecl(
    val type: String,
    val idPattern: String? = null,   // この型のID命名規約（glob。省略可）
    val defines: Boolean = false,
    val aspect: String? = null,      // この出現が所有する側面名（省略時はスキーマID。R23-01）
    val owns: List<String>? = null,  // 所有フィールドキーの限定（null=宣言なし＝宣言フィールド全部）
    /** ID の型の名前（definitions の、id を狭めた型。その形式で ID を判別する。idPattern と併用すれば両方に合う ID）。 */
    val idType: String? = null,
    /** idType を解決した形式（正規表現。全てに合う ID がこの型）。resolvedIn が埋める。 */
    val idTypePatterns: List<String> = emptyList(),
    /** idType が解決できなかった理由（定義の点検が誤りにする）。 */
    val idTypeError: String? = null,
) {
    private val regex: Regex? by lazy { idPattern?.let { runCatching { idGlobToRegex(it) }.getOrNull() } }
    private val typeRegexes: List<Regex> by lazy { idTypePatterns.mapNotNull { runCatching { Regex(it) }.getOrNull() } }

    /** ID の決まり（idPattern か idType）を持つか。持たない宣言は全ての ID に一致する（1 スキーマに 1 つまで）。 */
    val hasIdRule: Boolean get() = idPattern != null || idType != null

    /** ID の決まりの文面（指摘に添える。idPattern はそのまま・idType は「型 名前」）。 */
    val idRuleText: String? get() = idPattern ?: idType?.let { "型 $it" }

    /** ID がこの型の決まりに一致するか（idPattern は glob・idType は型の形式。どちらも無ければ常に一致）。 */
    fun matchesId(id: String): Boolean =
        (if (idPattern == null) true else regex?.matches(id) ?: false) && typeRegexes.all { it.matches(id) }

    /**
     * idType をスキーマの木で解決する（型の形式を写す）。見つからない・id の型でない名前は誤りの文面を持つ宣言になり、
     * 形式は無い（誤りは定義の点検が報告する）。
     */
    fun resolvedIn(tree: SchemaTree): EntityDecl {
        val name = idType ?: return this
        val def = tree.resolveTypeName(name)
            ?: return copy(idTypePatterns = emptyList(), idTypeError = "エンティティ型「$type」の idType「$name」は未知の型です")
        if (def.type != FieldType.ID) {
            return copy(idTypePatterns = emptyList(), idTypeError = "エンティティ型「$type」の idType「$name」は id の型ではありません（基底の種類: ${def.type.id}）")
        }
        return copy(idTypePatterns = def.patterns, idTypeError = null)
    }
}

/** ID に対応するエンティティ宣言を解決する（ID の決まりに一致する宣言が優先、なければ決まりのない宣言）。 */
fun entityFor(entities: List<EntityDecl>, id: String): EntityDecl? =
    entities.firstOrNull { it.hasIdRule && it.matchesId(id) }
        ?: entities.firstOrNull { !it.hasIdRule }

/** 関係の多重度宣言。検証への接続は R21-01（GraphIntegrity）。 */
data class CardinalityDef(
    val inMin: Int? = null,
    val inMax: Int? = null,
    val outMin: Int? = null,
    val outMax: Int? = null,
)

/**
 * 関係レジストリの1関係（R20-02）。`_schemas` の `kind: relations` 文書の `relations:` に列挙する。
 * 表示（label / inverseLabel）に加え、source / target / cardinality / acyclic は
 * グラフ整合検証（R21-01。architecture/graph-query.md「エンティティ操作」）の制約宣言になる。
 */
data class RelationDef(
    val name: String,
    val label: String? = null,          // 参照方向の表示ラベル（例: 利用する）
    val inverseLabel: String? = null,   // 被参照方向の表示ラベル（例: 利用される）
    val source: List<String> = emptyList(),  // 参照を書く側のエンティティ型（対象型検証の宣言。R21-01）
    val target: List<String> = emptyList(),  // 参照先のエンティティ型（同上）
    val cardinality: CardinalityDef? = null, // in / out の下限上限（R21-01 で検証）
    val acyclic: Boolean = false,            // 非循環（R21-01 で検証。違反は常に ERROR）
    val level: CoverageLevel = CoverageLevel.WARNING, // 対象型・多重度の指摘の重大度（R21-01）
    val description: String? = null,
) {
    val displayLabel: String get() = label ?: name
    val displayInverseLabel: String get() = inverseLabel ?: label ?: name
}

/** アスペクト1件の宣言（R23-01）。name はスキーマの `entity.aspect`（省略時スキーマID）と一致させる。 */
data class AspectDecl(
    val name: String,
    val label: String? = null,
) {
    val displayLabel: String get() = label ?: name
}

/**
 * エンティティ型のレジストリ宣言（R23-01。architecture/graph-query.md「エンティティページ」）。関係レジストリ文書（kind: relations）の
 * `entities:` 節に列挙する。型の表示名と、エンティティページ・read_entity の合成で使うアスペクト順序を宣言する。
 */
data class EntityTypeDef(
    val type: String,
    val label: String? = null,
    val description: String? = null,
    val aspects: List<AspectDecl> = emptyList(),
) {
    val displayLabel: String get() = label ?: type

    /** 合成の並び順に使うアスペクト位置（未宣言のアスペクトは末尾＝Int.MAX_VALUE）。 */
    fun aspectOrder(name: String): Int =
        aspects.indexOfFirst { it.name == name }.let { if (it < 0) Int.MAX_VALUE else it }

    /** アスペクトの表示名（未宣言は名前のまま）。 */
    fun aspectLabel(name: String): String = aspects.firstOrNull { it.name == name }?.displayLabel ?: name
}

/**
 * 出現1件の所有フィールドキー（R23-01。architecture/graph-query.md「エンティティページ」）。エンティティ合成（core/Aspects）と
 * 重複所有の検証（Validator）の共有実装。明示 `owns` があればそのまま、なければ出現要素の
 * 宣言フィールド全部（enum cases 合成込み）から `type: id` のフィールド（合流キー）を除いたもの。
 * 要素が object に解決できない場合は空（判定保留 architecture/transform.md「計算列と決定論的射影」）。
 */
fun ownedFieldKeys(schema: SchemaTree, root: YNode, path: YPath, decl: EntityDecl?): List<String> {
    decl?.owns?.let { return it }
    val field = schema.fieldAt(root, path) ?: return emptyList()
    if (field.type != FieldType.OBJECT) return emptyList()
    val element = getAt(root, path) as? YNode.Map
    return schema.effectiveFields(field.fields, element).fields.mapNotNull { child ->
        val key = child.key ?: return@mapNotNull null
        if ((schema.resolve(child) ?: child).type == FieldType.ID) null else key
    }
}

/**
 * ワークスペース単位の強制スイッチ（R21-01。architecture/graph-query.md「エンティティ操作」）。レジストリ文書の `enforcement:` 節。
 * 各キーは3値: null（未宣言＝P20 の挙動のまま）／ WARNING ／ ERROR。
 * - undeclaredRelation: レジストリにない relation を使う参照を文書側でも指摘する
 * - definingUniqueness: 定義出現の一意性（浮遊詳細化・定義競合）の重大度＝「最初の出現＝定義」廃止スイッチ
 */
data class EnforcementDef(
    val undeclaredRelation: CoverageLevel? = null,
    val definingUniqueness: CoverageLevel? = null,
) {
    /** キー名で引く（検査の宣言 levelFrom＝重大度の切り替えが使う）。宣言されていなければ null。 */
    fun levelOf(key: String): CoverageLevel? = when (key) {
        "undeclaredRelation" -> undeclaredRelation
        "definingUniqueness" -> definingUniqueness
        else -> null
    }

    companion object {
        val KEYS = listOf("undeclaredRelation", "definingUniqueness")
    }
}

/**
 * relation 名 → 表示ラベルの解決（R20-02）。バックリンクを表示する全面（SidePanel・ID横ぐし・
 * ビュー・文書ビュー・エージェント related・グラフ・bundle）がこの2関数を共有し、
 * 未宣言の relation は従来どおり名前のまま表示する。
 */
object Relations {

    /** 参照方向（この要素からの参照）の表示ラベル。 */
    fun labelOf(relations: Map<String, RelationDef>, name: String?): String? =
        name?.let { relations[it]?.displayLabel ?: it }

    /** 被参照方向（この要素への参照）の表示ラベル。 */
    fun inverseLabelOf(relations: Map<String, RelationDef>, name: String?): String? =
        name?.let { relations[it]?.displayInverseLabel ?: it }
}
