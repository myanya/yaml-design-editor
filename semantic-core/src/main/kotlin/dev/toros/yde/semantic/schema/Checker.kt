package dev.toros.yde.semantic.schema

/**
 * 専用チェッカーを使う検査の宣言（意味プリミティブ SP-13。P29 Phase 6 第4段。CORE-CALCULUS §25）。
 *
 * アルゴリズムが複雑で、クエリの部品の組合せで書くと不自然になる検査は、コードで個別に実装する（専用チェッカー。
 * semantic パッケージ）。何段でも辿るだけなら、クエリの edge の repeat（辿る計算だけが専用アルゴリズム）で YAML に書ける——
 * 循環の検出は R29-15 でそちらへ移し、専用チェッカーから外した。ただし、どの語彙から・どの値を渡して使うか、
 * 指摘の文面・重大度・いつから判定するかは、他の検査と同じく定義（YAML）の側が宣言する:
 *
 * ```yaml
 * constraints:
 *   - checker: flow                 # 専用チェッカーの名前
 *     args: { nodes: ..., edges: ... }   # チェッカーへ渡す値（チェッカーごとに決まっている）
 *     messages:                     # 指摘の種類ごとの文面（書かなかった種類は指摘しない）
 *       unreachable: ID「{id}」は開始ノードから到達できません（開始: {related}）
 *     level: warning
 * ```
 *
 * 専用チェッカーの条件: 純粋で決定的であること・判定を保留する規則を持つこと・単独でテストできること。
 * 種類は閉じた一覧（足すときは、ここへ種類と渡す値の型を足し、semantic.SemanticCheckers に評価を足す）。
 */
enum class CheckerKind(
    val id: String,
    /** このチェッカーが返す指摘の種類。 */
    val findingKinds: List<String>,
    /** 指摘を出す場所: true = 指摘に付いた参照が書かれた場所／false = 対象の要素を定義した場所（他の出現には案内）。今は false だけ。 */
    val refLocated: Boolean,
) {
    /** 遷移検証: 到達できない・行き止まり・宣言にない遷移（semantic.SemanticFlow）。 */
    FLOW("flow", listOf("unreachable", "deadEnd", "undeclared"), refLocated = false);

    companion object {
        fun parse(raw: String, where: String): CheckerKind = entries.firstOrNull { it.id == raw }
            ?: error("$where.checker は ${entries.joinToString(" / ") { it.id }} のいずれかです: $raw")
    }
}

/** 専用チェッカーへ渡す値（チェッカーごとの型）。 */
sealed interface CheckerArgs

/**
 * 専用チェッカーを使う検査1件。messages は「指摘の種類 → 文面のひな型」で、書かなかった種類は指摘しない。
 * ひな型に書けるもの: `{id}`＝対象の ID・`{related}`＝関連するノードの並び（先頭5件と残りの件数）・
 * `{count}`＝関連するノードの件数・チェッカーが返す値。
 */
data class CheckerRule(
    val id: String? = null,
    val description: String? = null,
    val kind: CheckerKind,
    val args: CheckerArgs,
    val messages: Map<String, String>,
    val level: CoverageLevel = CoverageLevel.WARNING,
    val levelFrom: String? = null,
    val activeFrom: String? = null,
    /** 検査の出どころ（スキーマの読み込みが、展開の記録から付ける。単体でパースした検査は null）。 */
    val origin: RuleOrigin? = null,
) {
    companion object {
        private val KEYS =setOf("id", "description", "checker", "args", "messages", "level", "levelFrom", "activeFrom")

        fun parse(raw: Any?, where: String): CheckerRule {
            val m = raw as? Map<*, *> ?: error("$where はobjectである必要があります")
            m.keys.map { it.toString() }.firstOrNull { it !in KEYS }
                ?.let { error("$where: 未知のキーです: $it（専用チェッカーを使う検査に使えるキー: ${KEYS.joinToString(", ")}）") }
            val kind = CheckerKind.parse(m["checker"].toString(), where)
            val argsRaw = m["args"] as? Map<*, *> ?: error("$where.args（チェッカーへ渡す値）はobjectである必要があります")
            val args: CheckerArgs = when (kind) {
                CheckerKind.FLOW -> FlowArgs.parse(argsRaw, "$where.args")
            }
            val messages = (m["messages"] as? Map<*, *> ?: error("$where.messages（指摘の種類ごとの文面）はobjectである必要があります"))
                .entries.filter { it.value != null }.associate { it.key.toString() to it.value.toString() }
            require(messages.isNotEmpty()) { "$where.messages には1つ以上の文面が必要です（種類: ${kind.findingKinds.joinToString(" / ")}）" }
            messages.keys.firstOrNull { it !in kind.findingKinds }
                ?.let { error("$where.messages: チェッカー「${kind.id}」に「$it」という指摘の種類はありません（種類: ${kind.findingKinds.joinToString(" / ")}）") }
            val level = when (val v = m["level"]?.toString()) {
                null -> CoverageLevel.WARNING
                else -> CoverageLevel.entries.firstOrNull { it.id == v } ?: error("$where.level は warning / error のいずれかです: $v")
            }
            return CheckerRule(
                id = m["id"]?.toString()?.takeIf { it.isNotBlank() },
                description = m["description"]?.toString()?.takeIf { it.isNotBlank() },
                kind = kind,
                args = args,
                messages = messages,
                level = level,
                levelFrom = m["levelFrom"]?.toString()?.takeIf { it.isNotBlank() }?.also {
                    require(it in EnforcementDef.KEYS) { "$where.levelFrom は ${EnforcementDef.KEYS.joinToString(" / ")} のいずれかです: $it" }
                },
                activeFrom = m["activeFrom"]?.toString()?.takeIf { it.isNotBlank() },
            )
        }
    }
}
