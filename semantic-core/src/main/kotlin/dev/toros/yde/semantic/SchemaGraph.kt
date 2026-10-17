package dev.toros.yde.semantic

import dev.toros.yde.semantic.schema.DefinitionKey
import dev.toros.yde.semantic.schema.EntityDecl
import dev.toros.yde.semantic.schema.EntityTypeDef
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.RefTarget
import dev.toros.yde.semantic.schema.RelationDef
import dev.toros.yde.semantic.schema.SchemaTree

// ================================================================ スキーマの図（型レベルのグラフ。architecture/graph-query.md「スキーマレンズ」）
//
// エンティティグラフ（箱＝エンティティ・線＝参照）の一段上: 箱＝エンティティ型・線＝関係・入れ子＝保有の宣言。
// 文書を読まず、スキーマと関係レジストリだけから導出する（文書の無いワークスペースでも図が出る）。
// 線の出どころは 2 つ: 宣言（レジストリの source → target）と、導出（スキーマの参照項目を囲む要素の型 → 相手の型）。
// 実績（索引の参照を型で集計した本数）は含めない（infer-relations が同じ集計を持つ。重ねるのは後の段階）。

/** 図の材料となる 1 スキーマ（文書スキーマ。関係レジストリと library は渡さない）。 */
data class SchemaGraphSource(
    val id: String,
    val name: String,
    val tree: SchemaTree,
    /** スキーマの `entity:` 宣言（ID の型は解決済み）。 */
    val entities: List<EntityDecl>,
)

/** 型がスキーマに現れる場所（ID を持つ要素 1 つ）。 */
data class SchemaGraphAppearance(
    val schemaId: String,
    /** ID を持つ要素の場所（`/`＝ルート、`items[]`＝配列の要素、`attrs.*`＝map の値）。 */
    val path: String,
    val defines: Boolean,
    val aspect: String?,
    val idPattern: String?,
    /** object の entity も単一の宣言も無く、宣言の並び（外側から順）で推定した。 */
    val inferred: Boolean,
)

/** 型（箱）。 */
data class SchemaGraphType(
    val type: String,
    val label: String?,
    val appearances: List<SchemaGraphAppearance>,
) {
    val displayLabel: String get() = label ?: type

    /** この型を定義するスキーマ（defines の場所。無ければ最初に現れるスキーマ）。 */
    val definingSchemaId: String? get() = appearances.firstOrNull { it.defines }?.schemaId ?: appearances.firstOrNull()?.schemaId
}

/** 線の出どころ。 */
enum class SchemaGraphEdgeSource(val label: String) {
    /** 関係レジストリの宣言（source → target）だけ。 */
    DECLARED("宣言"),

    /** スキーマの参照項目から導いただけ（宣言に無い）。 */
    DERIVED("導出"),

    /** 宣言にあり、スキーマの参照項目からも導ける。 */
    BOTH("宣言・導出"),
}

/** スキーマの中の場所（線や入れ子の根拠）。 */
data class SchemaGraphSite(val schemaId: String, val path: String)

/** 型どうしの線（関係）。relation が無いのは relation を書いていない参照項目から導いた線。 */
data class SchemaGraphEdge(
    val from: String,
    val to: String,
    val relation: String?,
    val source: SchemaGraphEdgeSource,
    /** 導出の根拠（参照項目の場所。宣言だけなら空）。 */
    val sites: List<SchemaGraphSite>,
)

/** 保有の宣言（ID を持つ要素の中に ID を持つ要素を書く項目）。名前は項目の `relation` か、無ければキー名。 */
data class SchemaGraphHolding(
    val parent: String,
    val child: String,
    val relation: String,
    val sites: List<SchemaGraphSite>,
    /** 子の型をここで定義している（誕生の場所）。レンズはこの保有だけを箱の入れ子にする。 */
    val childDefines: Boolean,
)

/** 線にできなかった参照項目と、その理由。 */
data class SchemaGraphUnresolved(val site: SchemaGraphSite, val relation: String?, val reason: String)

/**
 * スキーマから導出した型レベルのグラフ（読み取り専用）。節点は型名の昇順、線は (from, to, relation) の昇順で決定的。
 */
class SemanticSchemaGraph(
    val types: List<SchemaGraphType>,
    val edges: List<SchemaGraphEdge>,
    val holdings: List<SchemaGraphHolding>,
    val unresolved: List<SchemaGraphUnresolved>,
    /**
     * 宣言より広い導出: source / target を宣言した関係で、スキーマの参照項目から導いた相手（か書く側）が宣言に無いもの。
     * 線にはしない（宣言が線を決める）が、スキーマの `scope` がレジストリより緩いことの材料として残す。
     */
    val beyondDeclaration: List<SchemaGraphEdge> = emptyList(),
) {
    val typeMap: Map<String, SchemaGraphType> by lazy { types.associateBy { it.type } }

    /** 型の表示名（レジストリの label、無ければ型名）。 */
    fun labelOf(type: String): String = typeMap[type]?.displayLabel ?: type

    companion object {
        val EMPTY = SemanticSchemaGraph(emptyList(), emptyList(), emptyList(), emptyList())

        /** 再帰する定義（`item: {$ref: node}`）で止まる深さ（effectiveFields と同じ程度）。 */
        private const val MAX_DEPTH = 32

        /**
         * スキーマと関係レジストリから図を導く。
         * - 型: レジストリの `entities:`・スキーマの `entity:`・object の `entity`・線の端に現れる型名
         * - 線（宣言）: レジストリの関係の source × target
         * - 線（導出）: 参照項目を囲む ID を持つ要素の型 → 相手の型（`scope.schemaIds` のスキーマに現れる型を、`idPattern` の層で絞る。
         *   ID の形を宣言していない型は scope の中にいるときだけ相手になる）。同じ (from, to, relation) が宣言にもあれば 1 本にまとめる（出どころ＝宣言・導出）。
         *   両端の型を宣言した関係では宣言が線を決め、宣言より広い導出は beyondDeclaration に残す
         * - 入れ子: ID を持つ要素の中に ID を持つ要素を書く項目（名前は項目の `relation` かキー名。走査の保有と同じ決まり）
         * 要素の型は object の `entity` ＞ ID の項目の型（`idType`）＞ 単一の宣言 ＞ 宣言の並び（外側から順。推定）。
         */
        fun build(
            sources: List<SchemaGraphSource>,
            relations: Collection<RelationDef>,
            entityTypes: Collection<EntityTypeDef>,
        ): SemanticSchemaGraph {
            val walked = sources.sortedBy { it.id }.map(::walk)
            val appearances = walked.flatMap { it.elements }.filter { it.type != null }
            val typesBySchema: Map<String, List<Element>> = appearances.groupBy { it.site.schemaId }
            val patternsOfType: Map<String, Set<String>> = appearances.groupBy { it.type!! }
                .mapValues { (_, list) -> list.mapNotNull { it.decl?.idPattern }.toSet() }
            val allTyped = appearances.map { it.type!! }.distinct().sorted()

            // ---- 線: 宣言
            val merged = LinkedHashMap<Triple<String, String, String?>, Pair<SchemaGraphEdgeSource, MutableList<SchemaGraphSite>>>()
            relations.sortedBy { it.name }.forEach { r ->
                r.source.forEach { s -> r.target.forEach { t -> merged[Triple(s, t, r.name)] = SchemaGraphEdgeSource.DECLARED to mutableListOf() } }
            }
            val declaredByName = relations.associateBy { it.name }
            val beyond = LinkedHashMap<Triple<String, String, String?>, MutableList<SchemaGraphSite>>()
            // ---- 線: 導出
            val unresolved = mutableListOf<SchemaGraphUnresolved>()
            walked.flatMap { it.refs }.forEach { ref ->
                val from = ref.fromType
                if (from == null) {
                    unresolved += SchemaGraphUnresolved(ref.site, ref.relation, "参照を書く要素の型が決まらない（object の entity かスキーマの entity 宣言が無い）")
                    return@forEach
                }
                val layers = ref.layers.filter { it.isNotEmpty() }
                if (ref.scopeSchemaIds.isEmpty() && layers.isEmpty()) {
                    unresolved += SchemaGraphUnresolved(ref.site, ref.relation, "相手を絞っていない参照（scope も idPattern も無い）")
                    return@forEach
                }
                val candidates = if (ref.scopeSchemaIds.isNotEmpty()) {
                    ref.scopeSchemaIds.flatMap { sid -> typesBySchema[sid].orEmpty().map { it.type!! } }.distinct()
                } else allTyped
                // idPattern の層で絞る: 型の ID の形（宣言の idPattern）が全ての層に合うものだけ。形を宣言していない型（object の entity など）は、
                // scope でスキーマを絞っているときだけ残す（形が分からないからといって scope の中の型を落とさない。scope が無いときは残さない）
                val targets = candidates.filter { t ->
                    val patterns = patternsOfType[t].orEmpty()
                    if (patterns.isEmpty()) ref.scopeSchemaIds.isNotEmpty() else layers.all { layer -> layer.any { it in patterns } }
                }.sorted()
                if (targets.isEmpty()) {
                    val why = buildString {
                        append("相手の型が見つからない（")
                        if (ref.scopeSchemaIds.isNotEmpty()) append("scope: ${ref.scopeSchemaIds.joinToString(", ")}")
                        if (layers.isNotEmpty()) { if (ref.scopeSchemaIds.isNotEmpty()) append("・"); append("idPattern: ${layers.flatten().joinToString(", ")}") }
                        append("）")
                    }
                    unresolved += SchemaGraphUnresolved(ref.site, ref.relation, why)
                    return@forEach
                }
                // 両端の型を宣言した関係では宣言が線を決める。宣言より広い導出（scope がレジストリより緩い）は線にせず別に残す
                val declared = ref.relation?.let { declaredByName[it] }?.takeIf { it.source.isNotEmpty() && it.target.isNotEmpty() }
                targets.forEach { t ->
                    val key = Triple(from, t, ref.relation)
                    if (declared != null && (from !in declared.source || t !in declared.target)) {
                        beyond.getOrPut(key) { mutableListOf() } += ref.site
                        return@forEach
                    }
                    val current = merged[key]
                    if (current == null) merged[key] = SchemaGraphEdgeSource.DERIVED to mutableListOf(ref.site)
                    else {
                        current.second += ref.site
                        if (current.first == SchemaGraphEdgeSource.DECLARED) merged[key] = SchemaGraphEdgeSource.BOTH to current.second
                    }
                }
            }
            val edgeOrder = compareBy<SchemaGraphEdge>({ it.from }, { it.to }, { it.relation ?: "" })
            val siteOrder = compareBy<SchemaGraphSite>({ it.schemaId }, { it.path })
            val edges = merged.map { (k, v) -> SchemaGraphEdge(k.first, k.second, k.third, v.first, v.second.sortedWith(siteOrder)) }.sortedWith(edgeOrder)
            val beyondDeclaration = beyond.map { (k, sites) -> SchemaGraphEdge(k.first, k.second, k.third, SchemaGraphEdgeSource.DERIVED, sites.sortedWith(siteOrder)) }
                .sortedWith(edgeOrder)

            // ---- 入れ子（保有の宣言）: (親, 子, 名前) ごとにまとめる
            val holdings = walked.flatMap { it.holdings }
                .groupBy { Triple(it.parent, it.child, it.relation) }
                .map { (k, list) ->
                    SchemaGraphHolding(
                        k.first, k.second, k.third,
                        list.map { it.site }.sortedWith(compareBy({ it.schemaId }, { it.path })),
                        childDefines = list.any { it.childDefines },
                    )
                }
                .sortedWith(compareBy({ it.parent }, { it.child }, { it.relation }))

            // ---- 型: 宣言された型・現れた型・線の端の型
            val labels = entityTypes.associate { it.type to it.label }
            val names = (labels.keys + allTyped + edges.flatMap { listOf(it.from, it.to) } + holdings.flatMap { listOf(it.parent, it.child) }).toSortedSet()
            val types = names.map { t ->
                SchemaGraphType(
                    t, labels[t],
                    appearances.filter { it.type == t }
                        .map { SchemaGraphAppearance(it.site.schemaId, it.site.path, it.decl?.defines ?: false, it.decl?.aspect, it.decl?.idPattern, it.inferred) }
                        .sortedWith(compareBy({ !it.defines }, { it.schemaId }, { it.path })),
                )
            }
            return SemanticSchemaGraph(types, edges, holdings, unresolved.sortedWith(compareBy({ it.site.schemaId }, { it.site.path })), beyondDeclaration)
        }

        // ---------------- 1 スキーマの歩き（文書は読まない）

        private class Element(val site: SchemaGraphSite, val type: String?, val decl: EntityDecl?, val inferred: Boolean)
        private class Ref(val site: SchemaGraphSite, val fromType: String?, val relation: String?, val scopeSchemaIds: List<String>, val layers: List<List<String>>)
        private class Holding(val parent: String, val child: String, val relation: String, val site: SchemaGraphSite, val childDefines: Boolean)
        private class Walked(val elements: List<Element>, val refs: List<Ref>, val holdings: List<Holding>)

        private fun walk(source: SchemaGraphSource): Walked {
            val tree = source.tree
            val elements = mutableListOf<Element>()
            val refs = mutableListOf<Ref>()
            val holdings = mutableListOf<Holding>()

            /** 項目に宣言した保有の関係名（使う側の宣言、無ければ型の定義の宣言。走査と同じ）。 */
            fun relationOf(def: FieldDef?): String? = def?.relation ?: tree.resolve(def)?.relation

            /** ID を持つ要素の型を決める（object の entity ＞ ID の項目の型 ＞ 単一の宣言 ＞ 宣言の並びで推定）。 */
            fun declFor(eff: FieldDef, idField: FieldDef, depth: Int): Pair<EntityDecl?, Boolean> {
                eff.entity?.let { return it to false }
                val decls = source.entities
                val lineage = (tree.resolve(idField) ?: idField).lineage
                decls.firstOrNull { d ->
                    val idType = d.idType ?: return@firstOrNull false
                    lineage.any { it == idType || it.substringAfter('#') == idType.substringAfter('#') }
                }?.let { return it to false }
                if (decls.size == 1) return decls[0] to false
                if (decls.isEmpty()) return null to false
                return decls[minOf(depth, decls.size - 1)] to true
            }

            /**
             * node（項目の定義）を歩く。parent は外側の ID を持つ要素（無ければルートの外）、depth は ID を持つ要素の入れ子の深さ。
             * holderKey / holderRelation は、この要素を書いている項目のキーと、その項目に宣言した保有の関係名（走査と同じ写し方）。
             */
            fun visit(
                field: FieldDef, path: String, parent: Element?, depth: Int,
                holderKey: String?, holderRelation: String?, activeDefs: Set<DefinitionKey>, level: Int,
            ) {
                if (level > MAX_DEPTH) return
                val defKey = tree.definitionTarget(field)?.first
                if (defKey != null && defKey in activeDefs) return  // 再帰する定義は 1 回だけ歩く
                val active = if (defKey != null) activeDefs + defKey else activeDefs
                val eff = tree.resolve(field) ?: field
                val site = SchemaGraphSite(source.id, path.ifEmpty { "/" })
                when (eff.type) {
                    FieldType.REF -> {
                        if (eff.refTarget == RefTarget.DOCUMENT) return  // 文書への参照は文書ノードの線（型の図には出さない）
                        val layers = listOf(eff.idPatterns) + eff.outerIdPatterns
                        refs += Ref(site, parent?.type, eff.relation, eff.scope.schemaIds, layers)
                    }
                    FieldType.OBJECT -> {
                        val fields = eff.fields + eff.cases.values.flatten()
                        val idField = eff.fields.firstOrNull { (tree.resolve(it) ?: it).type == FieldType.ID }
                        var current = parent
                        var nextDepth = depth
                        if (idField != null) {
                            val (decl, inferred) = declFor(eff, idField, depth)
                            val element = Element(site, decl?.type, decl, inferred)
                            elements += element
                            if (parent?.type != null && element.type != null && path.isNotEmpty()) {
                                holdings += Holding(parent.type, element.type, holderRelation ?: holderKey ?: "contains", site, decl?.defines ?: false)
                            }
                            current = element
                            nextDepth = depth + 1
                        }
                        fields.forEach { child ->
                            val key = child.key ?: return@forEach
                            visit(child, if (path.isEmpty()) key else "$path.$key", current, nextDepth, key, relationOf(child), active, level + 1)
                        }
                    }
                    FieldType.ARRAY -> eff.item?.let { item ->
                        visit(item, "$path[]", parent, depth, field.key ?: holderKey, relationOf(field) ?: holderRelation, active, level + 1)
                    }
                    FieldType.MAP -> eff.value?.let { value ->
                        visit(value, "$path.*", parent, depth, field.key ?: holderKey, relationOf(field) ?: holderRelation, active, level + 1)
                    }
                    else -> Unit
                }
            }
            visit(tree.root, "", null, 0, null, null, emptySet(), 0)
            return Walked(elements, refs, holdings)
        }
    }
}
