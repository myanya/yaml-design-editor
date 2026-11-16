package dev.toros.yde.semantic

import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.edit.EditBatch
import dev.toros.yde.yaml.edit.YamlEdit
import dev.toros.yde.yaml.edit.YamlOperation
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.deepCopy
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.pretty

/**
 * 選択済みの項への変更候補を、上書きする前に合成する（ADR-0039）。
 * Query / Construct が対象・追加・変更を列挙し、ここでは対象と場所ごとに候補をまとめて競合を判定する。
 * 採用した変更は既存の YamlOperation に落とし、所有する写しへだけ適用する。
 * 型の狭め方・参照・成立条件はこの処理の外で検査する。成功は設計項目の「確定」を意味しない。
 */
object TermComposition {
    data class Source(val id: String, val document: DocumentId? = null, val path: YPath = emptyList())

    /** 同じ範囲で重複できないキー。別の object・選択肢の項目には別の scope を渡す。 */
    data class KeySpace(val scope: String, val path: YPath)

    /** id は生成中の対象を指す名前。通常の ID 索引や前回の生成物は使わない。 */
    data class Record(val id: String, val value: YNode.Map, val source: Source, val keySpace: KeySpace? = null)

    enum class Priority { RULE, INDIVIDUAL }

    data class Change(val target: String, val path: YPath, val value: YNode, val priority: Priority, val source: Source)

    /** 同じ場所への全候補と採用候補。採用しなかった規則の値・根拠も捨てない。 */
    data class Decision(val target: String, val path: YPath, val candidates: List<Change>, val selected: List<Change>)

    enum class Problem { DUPLICATE_ID, DUPLICATE_KEY, MISSING_TARGET, CONFLICT, OVERLAPPING_PATH, INVALID_PATH }
    data class Issue(val problem: Problem, val target: String, val path: YPath, val sources: List<Source>, val message: String)

    /** 誤りが一つでもあれば records は null。途中まで変更した項を後段へ渡さない。 */
    data class Result(val records: List<Record>?, val decisions: List<Decision>, val issues: List<Issue>)

    private val sourceOrder = compareBy<Source>({ it.id }, { it.document?.value.orEmpty() }, { it.path.pretty() })

    fun compose(derived: List<Record>, additions: List<Record>, changes: List<Change>): Result {
        val issues = mutableListOf<Issue>()
        val records = derived + additions.sortedBy { it.id }
        val grouped = records.groupBy { it.id }
        fun issue(problem: Problem, target: String, path: YPath, sources: List<Source>, message: String) {
            issues += Issue(problem, target, path.toList(), sources.distinct().sortedWith(sourceOrder), message)
        }
        grouped.filterValues { it.size > 1 }.toSortedMap().forEach { (id, duplicates) ->
            issue(Problem.DUPLICATE_ID, id, emptyList(), duplicates.map { it.source }, "生成対象「$id」が重複しています。値が同じでも追加を統合できません")
        }
        val targets = grouped.filterValues { it.size == 1 }.mapValues { it.value.single() }
        val decisions = changes.groupBy { it.target to it.path }.entries
            .sortedWith(compareBy({ it.key.first }, { it.key.second.pretty() })).map { (key, unsorted) ->
                val (target, path) = key
                val candidates = unsorted.sortedWith(compareBy<Change>({ it.priority.ordinal }).thenComparator { a, b -> sourceOrder.compare(a.source, b.source) })
                val individual = candidates.filter { it.priority == Priority.INDIVIDUAL }
                val preferred = individual.ifEmpty { candidates }
                val distinctValues = preferred.map { YamlIO.toAny(it.value) }.distinct()
                val selected = if (distinctValues.size == 1) preferred else emptyList()
                if (target !in grouped) issue(Problem.MISSING_TARGET, target, path, candidates.map { it.source }, "変更対象「$target」が今回の導出・追加にありません")
                if (path.isEmpty() || path.any { it !is String && it !is Int }) {
                    issue(Problem.INVALID_PATH, target, path, candidates.map { it.source }, "「$target」の変更には属性の場所を書きます")
                }
                if (selected.isEmpty()) {
                    val kind = if (individual.isEmpty()) "規則の既定値" else "個別指定"
                    issue(Problem.CONFLICT, target, path, preferred.map { it.source }, "「$target」の ${path.pretty()} に異なる$kind があります")
                }
                // 結果のノードを呼び出し側で変えても入力候補は変わらない。
                val snapshots = candidates.map { it.copy(path = it.path.toList(), value = it.value.deepCopy()) }
                Decision(target, path.toList(), snapshots, snapshots.filterIndexed { index, _ -> candidates[index] in selected })
            }

        // 親の置き換えと子の変更は適用順で結果が変わる。勝手に順番を決めず、対象属性をそろえて指定し直す。
        decisions.groupBy { it.target }.forEach { (target, list) ->
            for (i in list.indices) for (j in i + 1 until list.size) {
                val a = list[i]; val b = list[j]
                val shorter = if (a.path.size < b.path.size) a else b
                val longer = if (shorter === a) b else a
                if (shorter.path.isNotEmpty() && longer.path.take(shorter.path.size) == shorter.path) {
                    issue(Problem.OVERLAPPING_PATH, target, shorter.path, (a.candidates + b.candidates).map { it.source },
                        "「$target」の ${shorter.path.pretty()} と ${longer.path.pretty()} が重なっています。変更する属性の場所をそろえます")
                }
            }
        }
        // 入力のキーの重複を補正で隠さない。値が等しい追加も重複である。
        checkKeys(records, ::issue)
        val composed = records.map { it.copy(value = it.value.deepCopy() as YNode.Map) }
        for (record in composed) {
            if (record.id !in targets) continue
            val changesForRecord = decisions.filter { it.target == record.id && it.selected.isNotEmpty() && it.path.isNotEmpty() }
            for (decision in changesForRecord) {
                // 中間の入れ物を補正から推測して作らない。追加は先に独立した候補として渡す。
                val parent = getAt(record.value, decision.path.dropLast(1))
                val last = decision.path.last()
                val valid = (parent is YNode.Map && last is String) || (parent is YNode.Seq && last is Int && last in parent.items.indices)
                if (!valid) issue(Problem.INVALID_PATH, record.id, decision.path, decision.candidates.map { it.source },
                    "「${record.id}」の ${decision.path.pretty()} を変更できません。親の入れ物か配列の位置がありません")
            }
            if (issues.any { it.target == record.id }) continue
            val operations = changesForRecord.map { YamlOperation.Replace(it.path, it.selected.first().value.deepCopy()) }
            YamlEdit.applyBatch(record.value, EditBatch(operations))
        }
        // キー自体への補正で起きた重複も見逃さない。
        checkKeys(composed, ::issue)
        val errors = issues.distinct().sortedWith(compareBy({ it.target }, { it.path.pretty() }, { it.problem.ordinal }))
        return Result(if (errors.isEmpty()) composed else null, decisions, errors)
    }

    private fun checkKeys(records: List<Record>, issue: (Problem, String, YPath, List<Source>, String) -> Unit) {
        records.filter { it.keySpace != null }.groupBy { record ->
            record.keySpace!!.scope to YamlIO.toAny(getAt(record.value, record.keySpace.path) ?: YNode.Scalar(null))
        }.filterValues { it.size > 1 }.forEach { (key, duplicates) ->
            // キーの必須・型は保存スキーマが検査する。キーが無いもの同士を同名とは扱わない。
            if (key.second == null) return@forEach
            val ids = duplicates.map { it.id }.sorted().joinToString("・")
            issue(Problem.DUPLICATE_KEY, key.first, duplicates.first().keySpace!!.path, duplicates.map { it.source },
                "「${key.first}」のキー「${key.second}」が重複しています（$ids）")
        }
    }
}
