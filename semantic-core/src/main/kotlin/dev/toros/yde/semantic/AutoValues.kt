package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.AutoValue
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.SchemaTree
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.edit.EditBatch
import dev.toros.yde.yaml.edit.YamlEdit
import dev.toros.yde.yaml.edit.YamlOperation
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.deepCopy
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.pretty
import java.time.LocalDateTime

/**
 * 保存時に自動で入る値（印 `auto`。architecture/schema.md「保存時に自動で入る値」）の計画。
 *
 * Rewrite の形（CORE-CALCULUS「Rewrite Calculus」）: 印を持つ項目の要素をスキーマに沿って列挙し（Query）、
 * その要素の中身が保存済みの内容から変わっているか（Predicate）、変わっていれば項目の型に合う値を SetScalar で入れる（Edit）。
 * 「今」は体系の外から渡す明示の入力で（recordEdit の nowMillis・自動実行の tick(now) と同じ流儀）、ここは時計を持たない。
 *
 * - 要素＝印を持つ項目の親の object（配列の要素・ルート）。要素の対応は、`type: id` の項目があれば ID、無ければ位置
 * - 「変わった」は値と構造の比較（YamlIO.dumpPlain。コメントや表記の違いは数えない）。自動の項目自体は比較から外す
 * - created: 新しい要素で値が空のときだけ「今」。既存の要素は触らない
 * - updated: 新しい要素で値が空のときだけ「今」。変わった要素は「今」で上書き。変わらなければ触らない
 * - revision: 新しい要素で値が空のときだけ 1。変わった要素は保存済みの値 +1（読めなければ 1）。変わらなければ触らない
 * - 入れる値が今の値と同じなら操作を作らない（同じ日に何度保存しても履歴は増えない）
 */
object SemanticAutoValues {

    /** 1 つの項目に入れた値（履歴の意図・保存の状態表示の材料）。 */
    data class Stamp(val path: YPath, val field: FieldDef, val value: Any) {
        /** 要素の場所（項目のキーを除いた親の場所）。 */
        val elementPath: YPath get() = path.dropLast(1)
    }

    /** 計画: 当てる操作と、入れた値の一覧。 */
    data class Plan(val operations: List<YamlOperation>, val stamps: List<Stamp>) {
        val isEmpty: Boolean get() = operations.isEmpty()

        /** 履歴の意図・状態表示の 1 行（例 `sections[1]: 更新日・版`）。 */
        fun describe(): String = stamps.groupBy { it.elementPath }.entries.joinToString("、") { (element, list) ->
            val where = if (element.isEmpty()) "文書" else element.pretty()
            "$where: " + list.joinToString("・") { it.field.displayLabel }
        }

        companion object {
            val EMPTY = Plan(emptyList(), emptyList())
        }
    }

    /**
     * 新しい要素の初期値（ひな型「追加」「作成」が埋める値）。印の無い項目は null。
     * 日付・時刻・日時は「今」、版は 1。保存の計画（新しい要素で値が空のとき）と同じ値。
     */
    fun initialValue(field: FieldDef, now: LocalDateTime): Any? {
        val auto = field.auto ?: return null
        return when (auto) {
            AutoValue.CREATED, AutoValue.UPDATED -> field.type.value?.stamp(now)
            AutoValue.REVISION -> if (field.type == FieldType.INT) 1L else null
        }
    }

    /**
     * 計画を作る。base は保存済みの内容（新しい文書は null）、working は保存しようとしている内容。操作は working の座標。
     */
    fun plan(schema: SchemaTree, base: YNode?, working: YNode, now: LocalDateTime): Plan {
        val current = Walk.of(schema, working)
        if (current.elements.isEmpty()) return Plan.EMPTY
        val saved = base?.let { Walk.of(schema, it) }
        val operations = mutableListOf<YamlOperation>()
        val stamps = mutableListOf<Stamp>()
        for (element in current.elements) {
            val stable = current.stablePath(element.path)
            val baseElement = saved?.byStable?.get(stable)
            val isNew = baseElement == null
            val changed = baseElement != null &&
                current.plainWithoutAuto(element.path, element.map) != saved!!.plainWithoutAuto(baseElement.path, baseElement.map)
            for (field in element.autoFields) {
                val key = field.key ?: continue
                val existing = (element.map.entries[key] as? YNode.Scalar)?.value
                val blank = existing == null || existing.toString().isBlank()
                val value: Any = when (field.auto) {
                    AutoValue.CREATED -> if (isNew && blank) initialValue(field, now) else null
                    AutoValue.UPDATED -> when {
                        isNew -> if (blank) initialValue(field, now) else null
                        changed -> initialValue(field, now)
                        else -> null
                    }
                    AutoValue.REVISION -> when {
                        field.type != FieldType.INT -> null
                        isNew -> if (blank) 1L else null
                        changed -> savedRevision(baseElement!!.map, key) + 1
                        else -> null
                    }
                    null -> null
                } ?: continue
                if (!blank && sameValue(existing, value)) continue
                val path = element.path + key
                operations += YamlOperation.SetScalar(path, value)
                stamps += Stamp(path, field, value)
            }
        }
        return Plan(operations, stamps)
    }

    /** 計画を作って、その木に当てる（新しい文書の作成・変更セットの適用の在庫の木。セッションの文書は EditDocument で当てる）。 */
    fun stamp(schema: SchemaTree, base: YNode?, working: YNode, now: LocalDateTime): Plan {
        val plan = plan(schema, base, working, now)
        if (!plan.isEmpty) YamlEdit.applyBatch(working, EditBatch(plan.operations))
        return plan
    }

    // ---------------------------------------------------------------- 内部

    private fun savedRevision(baseMap: YNode.Map, key: String): Long {
        val raw = (baseMap.entries[key] as? YNode.Scalar)?.value ?: return 0L
        return (raw as? Number)?.toLong() ?: raw.toString().trim().toLongOrNull() ?: 0L
    }

    /** 同じ値か（版は数として、日付などは文字として比べる）。 */
    private fun sameValue(existing: Any?, value: Any): Boolean = when (value) {
        is Long -> ((existing as? Number)?.toLong() ?: existing?.toString()?.trim()?.toLongOrNull()) == value
        else -> existing?.toString()?.trim() == value.toString()
    }

    /** 印を持つ項目の親の object（要素）。 */
    private class Element(val path: YPath, val map: YNode.Map, val autoFields: List<FieldDef>)

    /**
     * 文書をスキーマに沿って 1 回歩いた結果: 要素の一覧・要素の対応の鍵・自動の項目の場所。
     * 配列の要素の鍵は、その object に `type: id` の項目があって値が書かれていれば ID、無ければ位置。
     */
    private class Walk private constructor(
        val elements: List<Element>,
        private val elementKeys: Map<YPath, String>,
        private val autoPaths: Set<YPath>,
    ) {
        /** 要素の対応の鍵で引く（保存済みの側）。 */
        val byStable: Map<List<String>, Element> by lazy { elements.associateBy { stablePath(it.path) } }

        /** 配列の位置を要素の鍵（ID か位置）に置き換えた場所。 */
        fun stablePath(path: YPath): List<String> = path.mapIndexed { i, seg ->
            if (seg is Int) elementKeys[path.subList(0, i + 1)] ?: "[$seg]" else seg.toString()
        }

        /** 要素の中身を、自動の項目（入れ子の要素のものも）を除いて、値と構造だけの文字にする。 */
        fun plainWithoutAuto(path: YPath, map: YNode.Map): String {
            val copy = map.deepCopy()
            autoPaths.filter { it.size > path.size && it.subList(0, path.size) == path }
                .forEach { auto ->
                    val relative = auto.subList(path.size, auto.size)
                    val parent = if (relative.size == 1) copy else getAt(copy, relative.dropLast(1))
                    (parent as? YNode.Map)?.entries?.remove(relative.last().toString())
                }
            return YamlIO.dumpPlain(copy)
        }

        companion object {
            fun of(schema: SchemaTree, root: YNode): Walk {
                val elements = mutableListOf<Element>()
                val keys = HashMap<YPath, String>()
                val autoPaths = HashSet<YPath>()
                SemanticRewrite.walkDocument(
                    schema, root,
                    onObject = { path, map, fields ->
                        val autoFields = fields.filter { it.auto != null && it.key != null }
                        if (autoFields.isNotEmpty()) elements += Element(path, map, autoFields)
                        autoFields.forEach { autoPaths += path + it.key!! }
                        if (path.lastOrNull() is Int) {
                            val idField = fields.firstOrNull { it.type == FieldType.ID && it.key != null }
                            val id = idField?.let { (map.entries[it.key] as? YNode.Scalar)?.value?.toString()?.trim() }
                            keys[path] = if (!id.isNullOrEmpty()) "#$id" else "[${path.last()}]"
                        }
                    },
                )
                return Walk(elements, keys, autoPaths)
            }
        }
    }
}
