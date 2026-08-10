package schema

/**
 * 二層モデルの宣言語彙（P20: R20-01 / R20-02。ARCHITECTURE §4.30）。
 * エンティティ型（スキーマ文書ルートの `entity:`）と関係レジストリ（`kind: relations` の `relations:`）の
 * モデルをここに置く。表示解決が index / UI 双方から使うため schema パッケージに配置する（Display §4.22 と同じ理由）。
 */

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
 * 複数型を持つスキーマは idPattern で型を判別する。defines=true の文書群の出現が「定義（誕生）」。
 */
data class EntityDecl(
    val type: String,
    val idPattern: String? = null,   // この型のID命名規約（省略可。省略時は全IDに一致）
    val defines: Boolean = false,
) {
    private val regex: Regex? by lazy { idPattern?.let { runCatching { idGlobToRegex(it) }.getOrNull() } }

    /** ID がこの型の命名規約に一致するか（idPattern 省略時は常に一致）。 */
    fun matchesId(id: String): Boolean = regex?.matches(id) ?: (idPattern == null)
}

/** ID に対応するエンティティ宣言を解決する（idPattern 一致が優先、なければパターンなしの宣言）。 */
fun SchemaDef.entityFor(id: String): EntityDecl? =
    entities.firstOrNull { it.idPattern != null && it.matchesId(id) }
        ?: entities.firstOrNull { it.idPattern == null }

/** 関係の多重度宣言。P20 では宣言のみ（検証への接続は P21 R21-01）。 */
data class CardinalityDef(
    val inMin: Int? = null,
    val inMax: Int? = null,
    val outMin: Int? = null,
    val outMax: Int? = null,
)

/**
 * 関係レジストリの1関係（R20-02）。`_schemas` の `kind: relations` 文書の `relations:` に列挙する。
 * P20 での消費は表示（label / inverseLabel）と lint のみで、検証・解決の意味論には関与しない。
 */
data class RelationDef(
    val name: String,
    val label: String? = null,          // 参照方向の表示ラベル（例: 利用する）
    val inverseLabel: String? = null,   // 被参照方向の表示ラベル（例: 利用される）
    val source: List<String> = emptyList(),  // 参照を書く側のエンティティ型（省略可。強制は P21）
    val target: List<String> = emptyList(),  // 参照先のエンティティ型（省略可。強制は P21）
    val cardinality: CardinalityDef? = null, // in / out の下限上限（P20 では宣言のみ）
    val acyclic: Boolean = false,            // 非循環（P20 では宣言のみ）
    val description: String? = null,
) {
    val displayLabel: String get() = label ?: name
    val displayInverseLabel: String get() = inverseLabel ?: label ?: name
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
