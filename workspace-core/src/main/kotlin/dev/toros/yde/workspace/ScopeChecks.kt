package dev.toros.yde.workspace

import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.StateDeclaration
import dev.toros.yde.workspace.layout.WorkspaceLayout
import java.util.IdentityHashMap

/**
 * 定義体のスコープの点検（ADR-0032）。結果はスキーマの読み込みの誤りと同じところに出す。
 * - スコープの宣言の誤り（workspace.yaml の `scopes:`）
 * - 1 つのスキーマが 2 つ以上のスコープに入る（スコープは 1 つだけ）
 * - スコープの定義体が、`uses` に書いていない別のスコープのスキーマを使う（型・参照先のスキーマ・エンティティ型・操作の受け手）。
 *   使う向きは一方向で、業務モデルから設計を使ったら誤り。どのスコープにも入らないスキーマは、誰が使っても・誰を使ってもよい
 */
object ScopeChecks {

    fun loadErrors(schemas: List<WorkspaceSchema>, layout: WorkspaceLayout): List<String> {
        if (layout.scopes.scopes.isEmpty() && layout.scopes.errors.isEmpty()) return emptyList()
        val errors = layout.scopes.errors.toMutableList()
        fun fileName(s: WorkspaceSchema) = WorkspaceLayout.nameInZone(s.source.value)
        val scopeOf = schemas.associate { s ->
            val found = layout.scopesOf(s.source.value)
            if (found.size > 1) errors += "${fileName(s)}: スキーマ「${s.id}」が ${found.size} つのスコープ（${found.joinToString("・") { it.name }}）に入ります。スコープは 1 つだけにします（workspace.yaml の scopes の include）"
            s.id to found.firstOrNull()
        }
        val byId = schemas.associateBy { it.id }
        val definers = entityDefiners(schemas)
        for (s in schemas) {
            val own = scopeOf[s.id] ?: continue
            val used = schemasUsedBy(s, definers)
            for (otherId in used.sorted()) {
                val other = byId[otherId] ?: continue
                val theirs = scopeOf[otherId] ?: continue
                if (theirs.id == own.id || theirs.id in own.uses) continue
                errors += "${fileName(s)}: スコープ「${own.name}」のスキーマ「${s.id}」が、使ってよいとしていないスコープ「${theirs.name}」のスキーマ「${other.id}」を使っています" +
                    "（使う向きは workspace.yaml の scopes の uses に書きます）"
            }
        }
        return errors
    }

    /** エンティティ型 → それを定義する（defines: true）スキーマの ID。 */
    private fun entityDefiners(schemas: List<WorkspaceSchema>): Map<String, Set<String>> {
        val out = LinkedHashMap<String, MutableSet<String>>()
        for (s in schemas) {
            s.entities.filter { it.defines }.forEach { out.getOrPut(it.type) { linkedSetOf() } += s.id }
            walkFields(s) { f -> f.entity?.takeIf { it.defines }?.let { out.getOrPut(it.type) { linkedSetOf() } += s.id } }
        }
        return out
    }

    /** スキーマが使う別のスキーマの ID（型の名前の `スキーマ#型`・参照先のスキーマ・使うエンティティ型を定義するスキーマ）。 */
    private fun schemasUsedBy(s: WorkspaceSchema, definers: Map<String, Set<String>>): Set<String> {
        val out = linkedSetOf<String>()
        val entities = linkedSetOf<String>()
        fun typeRef(name: String?) {
            val schema = name?.substringBefore('#', "")?.takeIf { it.isNotEmpty() && !it.startsWith("builtin-") } ?: return
            out += schema
        }
        walkFields(s) { f ->
            typeRef(f.typeName)
            f.lineage.forEach(::typeRef)
            out += f.scope.schemaIds
            f.entity?.takeIf { !it.defines }?.let { entities += it.type }
        }
        s.entities.filter { !it.defines }.forEach { entities += it.type }
        s.actions.forEach { a -> a.target?.entity?.let(entities::add); a.target?.schema?.let(out::add) }
        s.lifecycles.forEach { entities += it.entity }
        s.states.forEach { d ->
            when (d) {
                is StateDeclaration.Named -> entities += d.definition.entity
                is StateDeclaration.Composite -> entities += d.entity
                is StateDeclaration.FromEnum -> { entities += d.entity; typeRef(d.enumRef) }
            }
        }
        entities.forEach { e -> definers[e]?.let(out::addAll) }
        return out - s.id
    }

    /** スキーマの木の項目を、書かれた形と型を解いた形の両方で 1 度ずつたどる。 */
    private fun walkFields(s: WorkspaceSchema, visit: (FieldDef) -> Unit) {
        val seen = IdentityHashMap<FieldDef, Unit>()
        fun walk(f0: FieldDef, depth: Int) {
            if (depth > 16 || seen.put(f0, Unit) != null) return
            visit(f0)
            val f = s.tree.resolve(f0) ?: f0
            if (f !== f0 && seen.put(f, Unit) == null) visit(f)
            f.fields.forEach { walk(it, depth + 1) }
            f.item?.let { walk(it, depth + 1) }
            f.value?.let { walk(it, depth + 1) }
        }
        walk(s.tree.root, 0)
    }
}
