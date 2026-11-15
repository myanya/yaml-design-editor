package dev.toros.yde.workspace

import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.IdNumbering
import dev.toros.yde.semantic.schema.SchemaKind
import dev.toros.yde.semantic.schema.SchemaTree
import dev.toros.yde.semantic.schema.StateDeclaration
import dev.toros.yde.semantic.schema.TransitionCause
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode

/**
 * 業務モデルを展開した形（業務モデルの項）。スキーマに分けて書いた業務モデルの定義を、生成（変換の定義）が読める 1 つの項にする。
 * 生成はこの項を「いまの場所」として、設計書の文書を組み立てる（YAML の計算で、業務モデルから設計体系へ）。
 *
 * - **entities**: エンティティ型と、その側面（どのスキーマの文書の、どこに書かれ、どの項目を持つか）。項目は型を解いた形
 *   （型の名前・基底の種類・必須・文字数・選択肢など。型の連鎖から引き継いだ値も入る）
 * - **actions**: 操作（スキーマの actions と、型の操作）。受け取る値・前提（名前つき）・従う Lifecycle（展開した遷移）と、
 *   **効果**（do の手・Lifecycle が書く状態の値と履歴の行・保存時に自動で入る値の 3 か所を 1 列にまとめたもの）
 * - **states / lifecycles**: State（列挙型の値・詳細化の親子）と、詳細化を展開した Lifecycle
 *
 * 値の書き方は変換のひな型と同じ（受け取った値 `$date`・今日 `{$copy: today, $of: context}`・今の値 `{$copy: パス, $of: self}`）。
 * 項の出どころは業務モデルの定義だけで、文書（データ）は読まない。計算は決定的で、同じ定義からは同じ項になる。
 */
object BusinessModel {

    /** 読み込んだワークスペースの業務モデルの項（scope を渡すと、そのスコープのスキーマだけから）。 */
    fun of(ws: LoadedWorkspace, scope: String? = null): YNode = term(inScope(ws.schemas, ws.layout, scope), schemaDocuments(ws.snapshot, ws.layout))

    /** セッション（未保存の編集を含む）の業務モデルの項（scope を渡すと、そのスコープのスキーマだけから）。 */
    fun of(session: WorkspaceSession, scope: String? = null): YNode =
        term(inScope(session.schemas, session.layout, scope), schemaDocuments(session.snapshot, session.layout))

    /** スコープ（workspace.yaml の scopes）に入るスキーマ。scope が null なら全部（スコープを書かないワークスペースと同じ）。 */
    private fun inScope(schemas: List<WorkspaceSchema>, layout: dev.toros.yde.workspace.layout.WorkspaceLayout, scope: String?): List<WorkspaceSchema> {
        if (scope == null) return schemas
        requireNotNull(layout.scopes.byId(scope)) {
            "スコープ「$scope」がありません（workspace.yaml の scopes: ${layout.scopes.scopes.joinToString(", ") { it.id }.ifEmpty { "なし" }}）"
        }
        return schemas.filter { layout.scopeOf(it.source.value)?.id == scope }
    }

    private fun schemaDocuments(snapshot: WorkspaceSnapshot, layout: dev.toros.yde.workspace.layout.WorkspaceLayout): Map<DocumentId, YNode> =
        snapshot.documents.filterKeys { layout.zoneOf(it.value) == dev.toros.yde.workspace.layout.WorkspaceLayout.Zone.SCHEMAS }
            .mapNotNull { (id, d) -> d.document.documents.firstOrNull()?.let { id to it } }.toMap()

    /** schemaDocuments はスキーマの文書の原文（操作の do・入力の初期値などを書かれたとおりに写すため）。 */
    fun term(schemas: List<WorkspaceSchema>, schemaDocuments: Map<DocumentId, YNode>): YNode {
        val catalog = runCatching { BehaviorChecks.compile(schemas) }.getOrNull()
        val aspects = aspectsOf(schemas)
        val raw = schemas.associate { it.id to (schemaDocuments[it.source] as? YNode.Map) }
        // 参照先のスキーマ → そのスキーマが定義するエンティティ型（参照の項目の target）
        val targets = aspects.filter { it.defines }.groupBy { it.schema.id }.mapValues { (_, list) -> list.first().entity }
        // 列挙型 → その値を State として持つエンティティ型（states の source。項目の stateOf）
        val stateEnums = schemas.flatMap { it.states }.filterIsInstance<StateDeclaration.FromEnum>().associate { it.enumRef to it.entity }
        val ctx = Ctx(targets, stateEnums)
        val actions = schemas.flatMap { s -> actionTerms(s, raw[s.id], catalog, aspects, ctx) } +
            schemas.flatMap { s -> methodTerms(s, raw[s.id], aspects) }
        // 文書を作る手（create）の採番は、作る文書のエンティティ型の採番（配列の追加の決まりが無い型）
        @Suppress("UNCHECKED_CAST")
        val createNumbering = actions.flatMap { a -> (a["effects"] as? List<Map<String, Any?>>).orEmpty() }
            .filter { it["op"] == "create" && it["numbering"] != null && it["entity"] != null }
            .associate { it["entity"].toString() to it["numbering"] }
        val model = LinkedHashMap<String, Any?>()
        model["entities"] = aspects.groupBy { it.entity }.map { (entity, list) ->
            entityTerm(entity, list.sortedByDescending { it.defines }, createNumbering[entity], ctx)
        }
        model["actions"] = actions
        model["states"] = catalog?.let { stateTerms(schemas, it) }.orEmpty()
        model["lifecycles"] = catalog?.let { lifecycleTerms(schemas, it) }.orEmpty()
        return YamlIO.fromAny(model)
    }

    // ================================================================ エンティティ型と側面

    /**
     * エンティティ型の 1 つの側面: どのスキーマの文書の、どこ（`todos[*]`・ルートは空）に、どの項目で書かれるか。
     * owner は、この側面を入れ子で持つ外側のエンティティの側面（作業項目なら TODO の個票）と、その項目のキー（ownerField）。
     * numbering は、この側面の要素を足すときの採番（入っている配列の追加の決まり `methods.insert` の id）。
     */
    private class Aspect(
        val entity: String, val schema: WorkspaceSchema, val at: String, val defines: Boolean, val def: FieldDef, val label: String?,
        val owner: Aspect? = null, val ownerField: String? = null, val numbering: IdNumbering? = null,
    )

    /** 文書のスキーマの木を歩き、エンティティ型を持つ object（オブジェクト型か、スキーマの entity の宣言で ID を持つ object）を集める。 */
    private fun aspectsOf(schemas: List<WorkspaceSchema>): List<Aspect> {
        val out = mutableListOf<Aspect>()
        for (s in schemas.filter { it.kind == SchemaKind.DOCUMENT && !it.isTemplate }) {
            val tree = s.tree
            val schemaLevel = s.entities.firstOrNull()
            fun walk(f0: FieldDef, at: String, depth: Int, owner: Aspect?, numbering: IdNumbering?) {
                if (depth > 12) return
                val f = tree.resolve(f0) ?: f0
                when (f.type) {
                    FieldType.OBJECT -> {
                        val decl = f.entity ?: schemaLevel?.takeIf { f.fields.any { c -> (tree.resolve(c) ?: c).type == FieldType.ID } }
                        var here = owner
                        // 定義の種類（yde-*）を詳細化する文書（設計書など）は、業務のエンティティ型ではない
                        if (decl != null && !decl.type.startsWith(DefinitionIndex.PREFIX)) {
                            val ownerField = owner?.let { o -> (if (o.at.isEmpty()) at else at.removePrefix("${o.at}.")).removeSuffix("[*]") }
                            here = Aspect(decl.type, s, at, decl.defines, f, f.label ?: f0.label, owner, ownerField, numbering).also { out += it }
                        }
                        f.fields.forEach { c -> c.key?.let { k -> walk(c, if (at.isEmpty()) k else "$at.$k", depth + 1, here, null) } }
                    }
                    FieldType.ARRAY -> f.item?.let { walk(it, "$at[*]", depth + 1, owner, f.insertRule?.id) }
                    FieldType.MAP -> f.value?.let { walk(it, "$at.*", depth + 1, owner, null) }
                    else -> Unit
                }
            }
            walk(tree.root, "", 0, null, null)
        }
        return out
    }

    /**
     * エンティティ型の項: 名前・採番（足すときの配列の決まりか、作る手の id。無ければ書かない）・入れ子で持つ外側のエンティティ
     * （owner: {entity, field}）・側面（どの文書のどこに、どの項目で書かれるか）。
     */
    private fun entityTerm(entity: String, aspects: List<Aspect>, created: Any?, ctx: Ctx): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["id"] = entity
        m["label"] = aspects.firstNotNullOfOrNull { it.label } ?: entity
        (aspects.firstNotNullOfOrNull { it.numbering }?.let(::numberingTerm) ?: created)?.let { m["numbering"] = it }
        aspects.firstNotNullOfOrNull { a -> a.owner?.let { o -> linkedMapOf("entity" to o.entity, "field" to a.ownerField) } }?.let { m["owner"] = it }
        m["aspects"] = aspects.map { a ->
            linkedMapOf<String, Any?>(
                "schema" to a.schema.id,
                "defines" to a.defines,
                "at" to a.at,
                "owner" to a.owner?.let { o -> linkedMapOf("entity" to o.entity, "field" to a.ownerField) },
                "fields" to a.def.fields.map { fieldTerm(a.schema.tree, it, 0, ctx) },
            ).filterValues { it != null }
        }
        return m
    }

    /** 採番の決まり（ID の項目・接頭辞は書かれたまま・桁）。 */
    private fun numberingTerm(n: IdNumbering): Map<String, Any?> =
        linkedMapOf("path" to n.path.joinToString("."), "prefix" to n.prefixRaw, "digits" to n.digits)

    /**
     * 項目 1 つを型を解いた形で（型の名前は書かれたとおり `type`、基底の種類は `base`）。要素がエンティティの配列・object は、
     * 項目を広げずエンティティ型の名前（`entity`）だけを書く（その項目はエンティティの側面にある）。
     */
    /** 項目の項を作るときの、ワークスペース全体から引く事実（参照先のエンティティ型・State の値を持つ列挙型）。 */
    private class Ctx(val targets: Map<String, String>, val stateEnums: Map<String, String>) {
        companion object { val NONE = Ctx(emptyMap(), emptyMap()) }
    }

    private fun fieldTerm(tree: SchemaTree, f0: FieldDef, depth: Int, ctx: Ctx = Ctx.NONE): Map<String, Any?> {
        val f = tree.resolve(f0) ?: f0
        val m = LinkedHashMap<String, Any?>()
        f0.key?.let { m["key"] = it }
        (f0.label ?: f.label)?.let { m["label"] = it }
        m["type"] = typeNameOf(f0, f)
        m["base"] = f.type.id
        if (f.required || f0.required) m["required"] = true
        (f0.auto ?: f.auto)?.let { m["auto"] = it.id }
        f.default?.let { m["default"] = it }
        f.minLength?.let { m["minLength"] = it }
        f.maxLength?.let { m["maxLength"] = it }
        f.patterns.takeIf { it.isNotEmpty() }?.let { m["pattern"] = if (it.size == 1) it[0] else it }
        f.min?.let { m["min"] = it }
        f.max?.let { m["max"] = it }
        f.format?.let { m["format"] = it }
        if (f.multiline) m["multiline"] = true
        if (f.type == FieldType.ENUM) {
            m["values"] = f.enumOptions().map { o -> linkedMapOf("value" to o.value, "label" to (o.label ?: o.value)) }
            // 値が State の値の列挙型（状態の流れの source。状態の履歴の前後の状態も同じ型）なら、その State のエンティティ型
            (f.lineage + listOfNotNull(f0.typeName, f.typeName)).firstNotNullOfOrNull { ctx.stateEnums[it] }?.let { m["stateOf"] = it }
        }
        if (f.type == FieldType.REF) {
            f.relation?.let { m["relation"] = it }
            if (f.idPatterns.isNotEmpty()) m["idPatterns"] = f.idPatterns
            if (f.scope.schemaIds.isNotEmpty()) m["schemas"] = f.scope.schemaIds
            // 参照先のエンティティ型（参照先のスキーマが定義する型）
            f.scope.schemaIds.mapNotNull { ctx.targets[it] }.distinct().takeIf { it.isNotEmpty() }?.let { m["target"] = if (it.size == 1) it[0] else it }
        }
        if (f.unique.isNotEmpty()) m["unique"] = if (f.unique.size == 1) f.unique[0].ifEmpty { true } else f.unique
        if (depth < 8) {
            when (f.type) {
                FieldType.ARRAY -> f.item?.let { m["item"] = nested(tree, it, depth, ctx) }
                FieldType.MAP -> f.value?.let { m["value"] = nested(tree, it, depth, ctx) }
                FieldType.OBJECT -> if (f.entity != null) m["entity"] = f.entity!!.type else m["fields"] = f.fields.map { fieldTerm(tree, it, depth + 1, ctx) }
                else -> Unit
            }
        }
        return m
    }

    /**
     * 型の名前（モジュールを付けた名前。型の連鎖の先頭＝使う側に書いた名前。組込みの型は `text` のように短く）。
     * 名前を書いていなければ基底の種類。
     */
    private fun typeNameOf(f0: FieldDef, f: FieldDef): String =
        f.lineage.firstOrNull()?.removePrefix("builtin-types#") ?: f0.typeName ?: f.typeName ?: f.type.id

    private fun nested(tree: SchemaTree, f0: FieldDef, depth: Int, ctx: Ctx): Map<String, Any?> {
        val f = tree.resolve(f0) ?: f0
        return if (f.type == FieldType.OBJECT && f.entity != null) {
            linkedMapOf("type" to typeNameOf(f0, f), "base" to f.type.id, "entity" to f.entity!!.type)
        } else fieldTerm(tree, f0, depth + 1, ctx)
    }

    // ================================================================ 操作

    private fun actionTerms(
        s: WorkspaceSchema, raw: YNode.Map?, catalog: BehaviorChecks.Catalog?, aspects: List<Aspect>, ctx: Ctx,
    ): List<Map<String, Any?>> {
        val rawById = (raw?.entries?.get("actions") as? YNode.Seq)?.items.orEmpty()
            .mapNotNull { it as? YNode.Map }.associateBy { (it.entries["id"] as? YNode.Scalar)?.value?.toString() }
        return s.actions.map { a ->
            val r = rawById[a.id]
            val m = LinkedHashMap<String, Any?>()
            m["id"] = a.id
            m["kind"] = "action"
            m["label"] = a.label ?: a.id
            a.description?.let { m["description"] = it }
            a.target?.let { t -> m["target"] = linkedMapOf<String, Any?>("entity" to t.entity, "schema" to t.schema).filterValues { it != null } }
            m["params"] = a.params.map { fieldTerm(s.tree, it, 0, ctx) }
            r?.entries?.get("input")?.let { m["input"] = YamlIO.toAny(it) }
            m["require"] = a.require.map { q ->
                linkedMapOf<String, Any?>("id" to q.id, "message" to q.raw["message"], "rule" to q.raw.filterKeys { it != "id" && it != "message" })
                    .filterValues { it != null }
            }
            val lifecycle = a.lifecycle?.let { lc -> catalog?.lifecycles?.firstOrNull { it.id == lc.id } }
            if (lifecycle != null && catalog != null) m["lifecycle"] = lifecycleOfAction(a, lifecycle, catalog, r)
            val effects = mutableListOf<Map<String, Any?>>()
            (r?.entries?.get("do") as? YNode.Seq)?.items.orEmpty().forEach { step -> stepTerm(YamlIO.toAny(step))?.let { effects += it + ("source" to "do") } }
            if (lifecycle != null && catalog != null) effects += lifecycleEffects(a.id, lifecycle, catalog, r, aspects)
            effects += autoEffects(effects, aspects)
            m["effects"] = effects.map { withField(it, aspects) }
            m["creates"] = createsOf(m["effects"])
            subjectOf(a.target?.entity, a.target?.schema, effects, aspects)?.let { m["subject"] = it }
            m
        }
    }

    /** 型の操作（`エンティティ型.操作名`）。受け手はその型の要素 1 つで、書き換えるのは受け手の中だけ。 */
    private fun methodTerms(s: WorkspaceSchema, raw: YNode.Map?, aspects: List<Aspect>): List<Map<String, Any?>> {
        val out = mutableListOf<Map<String, Any?>>()
        (raw?.entries?.get("definitions") as? YNode.Map)?.entries?.forEach { (typeName, def) ->
            val d = def as? YNode.Map ?: return@forEach
            val entity = ((d.entries["entity"] as? YNode.Map)?.entries?.get("type") as? YNode.Scalar)?.value?.toString() ?: return@forEach
            val methods = d.entries["methods"] as? YNode.Map ?: return@forEach
            val parsed = s.tree.definitions[typeName]
            methods.entries.forEach { (name, mraw) ->
                val mm = mraw as? YNode.Map ?: return@forEach
                val m = LinkedHashMap<String, Any?>()
                m["id"] = "$entity.$name"
                m["kind"] = "method"
                m["label"] = (mm.entries["label"] as? YNode.Scalar)?.value?.toString() ?: "$entity.$name"
                (mm.entries["description"] as? YNode.Scalar)?.value?.let { m["description"] = it.toString() }
                m["target"] = linkedMapOf("entity" to entity)
                m["params"] = (mm.entries["params"] as? YNode.Seq)?.items.orEmpty().map { YamlIO.toAny(it) }
                val schemaOf = aspects.firstOrNull { it.entity == entity }?.schema?.id
                val effects = (mm.entries["do"] as? YNode.Seq)?.items.orEmpty()
                    .mapNotNull { stepTerm(YamlIO.toAny(it))?.let { e -> e + ("source" to "do") + (if ("schema" !in e && schemaOf != null) mapOf("schema" to schemaOf, "within" to entity) else emptyMap()) } }
                m["effects"] = (effects + autoEffects(effects, aspects)).map { withField(it, aspects) }
                m["creates"] = createsOf(m["effects"])
                m["subject"] = entity
                if (parsed == null) m["type"] = typeName
                out += m
            }
        }
        return out
    }

    /** 操作が新しく作るエンティティ型（文書を作る手の型と、エンティティの要素を配列へ足す手の要素の型。現れた順）。 */
    @Suppress("UNCHECKED_CAST")
    private fun createsOf(effects: Any?): List<String> = (effects as? List<Map<String, Any?>>).orEmpty().mapNotNull { e ->
        when (e["op"]) {
            "create" -> e["entity"]?.toString()
            "insert" -> ((e["field"] as? Map<String, Any?>)?.get("item") as? Map<String, Any?>)?.get("entity")?.toString()
            else -> null
        }
    }.distinct()

    /** do の手 1 つを、種類（op）と対象のスキーマ（schema）とパスの形にそろえる（値は書かれたとおり）。 */
    @Suppress("UNCHECKED_CAST")
    private fun stepTerm(raw: Any?): Map<String, Any?>? {
        val step = raw as? Map<String, Any?> ?: return null
        val op = listOf("set", "insert", "delete", "create", "call", "each").firstOrNull { it in step } ?: return null
        val body = step[op] as? Map<String, Any?> ?: return null
        val m = LinkedHashMap<String, Any?>()
        m["op"] = op
        when (op) {
            "set", "insert", "delete" -> {
                ((body["in"] as? Map<String, Any?>)?.get("schemaId"))?.let { m["schema"] = it }
                // そのほかの項目（value・params・of など）は書かれたとおり
                body.filterKeys { it != "in" }.let(m::putAll)
            }
            "create" -> {
                m["schema"] = body["schema"]
                body["id"]?.let { m["id"] = it }
                // 採番（id: {prefix, digits}）は、配列の追加の決まりと同じ形（path・prefix・digits）でも持つ
                (body["id"] as? Map<String, Any?>)?.takeIf { "prefix" in it }?.let { n ->
                    m["numbering"] = linkedMapOf("path" to (n["path"] ?: "id"), "prefix" to n["prefix"], "digits" to (n["digits"] ?: 3))
                }
                body["bind"]?.let { m["bind"] = it }
                body["params"]?.let { m["values"] = it }
            }
            "call" -> { m.putAll(body) }
            "each" -> {
                body.filterKeys { it != "do" }.let(m::putAll)
                m["do"] = (body["do"] as? List<*>).orEmpty().mapNotNull(::stepTerm)
            }
        }
        step["when"]?.let { m["when"] = it }
        return m
    }

    /**
     * 操作から見た Lifecycle: 入口（作るときの State）と遷移（できる State の並びと移り先）。State は {id, label, value}。
     * 断りの文（refusal）は、できない状態で操作したときに操作の計画が出す文と同じ（`{id}` は受け手の ID・`{state}` は今の状態の名前）。
     */
    private fun lifecycleOfAction(
        action: dev.toros.yde.semantic.schema.ActionDef, lc: dev.toros.yde.semantic.schema.LifecycleDef, catalog: BehaviorChecks.Catalog, raw: YNode.Map?,
    ): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["id"] = lc.id
        lc.entry.filter { it.by is TransitionCause.Action && it.by.id == action.id }.map { stateRef(it.to, catalog) }
            .takeIf { it.isNotEmpty() }?.let { m["entry"] = it }
        val moves = lc.transitions.filter { it.by is TransitionCause.Action && it.by.id == action.id }
        moves.groupBy { it.to }
            .map { (to, list) -> linkedMapOf("from" to list.map { it.from }.distinct().map { stateRef(it, catalog) }, "to" to stateRef(to, catalog)) }
            .takeIf { it.isNotEmpty() }?.let { m["transitions"] = it }
        if (moves.isNotEmpty()) {
            val allowed = catalog.collapse(moves.map { it.from }.distinct()).map { stateLabel(it, catalog) }
            m["refusal"] = "「{id}」は「{state}」なので、「${action.displayName}」はできません（できるのは「${allowed.joinToString("」「")}」のとき）"
        }
        lifecycleHistoryTemplate(raw)?.let { m["history"] = it }
        return m
    }

    /** State の組（ID・名前・保存する値）。値は列挙型の State だけ（詳細化の親には無い）。 */
    private fun stateRef(id: String, catalog: BehaviorChecks.Catalog): Map<String, Any?> {
        val s = catalog.states.firstOrNull { it.id == id }
        return linkedMapOf<String, Any?>("id" to id, "label" to stateLabel(id, catalog), "value" to s?.enumValue).filterValues { it != null }
    }

    private fun stateLabel(id: String, catalog: BehaviorChecks.Catalog): String = catalog.states.firstOrNull { it.id == id }?.label ?: id

    /**
     * 操作の主体のエンティティ型: 受け手の型、受け手が文書なら文書のスキーマが定義する型、受け手が無ければ作る・足す要素の型
     * （作る文書のルートの型か、足す配列の要素の型）。決まらなければ null。
     */
    private fun subjectOf(targetEntity: String?, targetSchema: String?, effects: List<Map<String, Any?>>, aspects: List<Aspect>): String? {
        targetEntity?.let { return it }
        targetSchema?.let { s -> aspects.firstOrNull { it.schema.id == s && it.defines }?.let { return it.entity } }
        for (e in effects.filter { it["source"] == "do" }) {
            val schema = e["schema"]?.toString() ?: continue
            when (e["op"]) {
                "create" -> aspects.firstOrNull { it.schema.id == schema && it.at.isEmpty() }?.let { return it.entity }
                "insert" -> aspects.firstOrNull { it.schema.id == schema && it.at == "${e["path"]}[*]" }?.let { return it.entity }
            }
        }
        return null
    }

    /**
     * 効果に、書き込む項目の業務上の事実を添える: どのエンティティ型の項目か（entity）と、その項目の基底の種類（field: {key, base,
     * item: {base, entity}}）。設計の規則（列か・子の表か）はここでは決めない（生成の定義が決める）。
     */
    private fun withField(effect: Map<String, Any?>, aspects: List<Aspect>): Map<String, Any?> {
        val schema = effect["schema"]?.toString() ?: return effect
        // 文書を作る: 書くのは、その文書のルートのエンティティの要素（値の項目ごとの基底の種類を written に）
        if (effect["op"] == "create") {
            val a = aspects.filter { it.schema.id == schema && it.at.isEmpty() }.maxByOrNull { if (it.defines) 1 else 0 } ?: return effect
            return effect + linkedMapOf("entity" to a.entity, "written" to writtenOf(effect["values"], a.def, a.schema.tree))
        }
        val key = effect["path"]?.toString()?.substringBefore('.')?.substringBefore('[') ?: return effect
        val within = effect["within"]?.toString()
        val inserted = effect["value"] ?: effect["params"]
        // エンティティの一覧そのもの（`todos[*]` の要素がエンティティ）へ足す・消す: 書くのはその型の要素。
        // 外側のエンティティの項目の配列（個票の tasks）は一覧ではなく、外側の要素の項目を書く（下の場合）
        aspects.firstOrNull { it.schema.id == schema && it.at == "$key[*]" && within == null && it.owner == null }?.let { a ->
            val extra = LinkedHashMap<String, Any?>()
            if (effect["op"] == "insert") {
                extra["written"] = writtenOf(inserted, a.def, a.schema.tree)
                a.numbering?.let { extra["numbering"] = numberingTerm(it) }
            }
            return effect + linkedMapOf("entity" to a.entity, "field" to linkedMapOf("key" to key, "base" to "array", "item" to linkedMapOf("base" to "object", "entity" to a.entity))) + extra
        }
        val aspect = aspects.filter { it.schema.id == schema && (within == null || it.entity == within) }
            .firstOrNull { a -> a.def.fields.any { it.key == key } } ?: return effect
        val f0 = aspect.def.fields.first { it.key == key }
        val tree = aspect.schema.tree
        val f = tree.resolve(f0) ?: f0
        val field = linkedMapOf<String, Any?>("key" to key, "base" to f.type.id)
        val extra = LinkedHashMap<String, Any?>()
        f.item?.let { i0 ->
            val i = tree.resolve(i0) ?: i0
            field["item"] = linkedMapOf<String, Any?>("base" to i.type.id, "entity" to i.entity?.type).filterValues { it != null }
            // 配列へ 1 つ足す: 足す要素の項目ごとの基底の種類と、採番（要素がエンティティならその型の側面の採番）
            if (effect["op"] == "insert" && i.type == FieldType.OBJECT) {
                val itemAspect = i.entity?.let { e -> aspects.firstOrNull { it.schema.id == schema && it.entity == e.type && it.at.endsWith("$key[*]") } }
                extra["written"] = writtenOf(inserted, itemAspect?.def ?: i, tree)
                (itemAspect?.numbering ?: f.insertRule?.id)?.let { extra["numbering"] = numberingTerm(it) }
            }
        }
        return effect + linkedMapOf("entity" to aspect.entity, "field" to field) + extra
    }

    /** 書く値（項目名 → 値）を、項目ごとの {key, value, base, item} の並びにする（基底の種類はその要素の項目の定義から）。 */
    private fun writtenOf(values: Any?, def: FieldDef, tree: SchemaTree): List<Map<String, Any?>> {
        val m = values as? Map<*, *> ?: return emptyList()
        return m.entries.map { (k, v) ->
            val f0 = def.fields.firstOrNull { it.key == k.toString() }
            val f = f0?.let { tree.resolve(it) ?: it }
            val item = f?.item?.let { i0 ->
                val i = tree.resolve(i0) ?: i0
                linkedMapOf<String, Any?>("base" to i.type.id, "entity" to i.entity?.type).filterValues { it != null }
            }
            linkedMapOf("key" to k.toString(), "value" to v, "base" to f?.type?.id, "item" to item).filterValues { it != null }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun lifecycleHistoryTemplate(raw: YNode.Map?): Map<String, Any?>? =
        ((raw?.entries?.get("lifecycle") as? YNode.Map)?.entries?.get("history"))?.let { YamlIO.toAny(it) as? Map<String, Any?> }

    /**
     * Lifecycle が書く効果: 状態の値（列挙型の State の値を持つ項目へ）と、履歴の行（Lifecycle の history の配列へ 1 行）。
     * 書く先のスキーマは、その項目を持つエンティティの側面から決める（操作の計画と同じ考え方）。
     */
    private fun lifecycleEffects(
        action: String, lc: dev.toros.yde.semantic.schema.LifecycleDef, catalog: BehaviorChecks.Catalog, raw: YNode.Map?, aspects: List<Aspect>,
    ): List<Map<String, Any?>> {
        val states = catalog.states.associateBy { it.id }
        val entries = lc.entry.filter { it.by is TransitionCause.Action && it.by.id == action }.map { it.to }
        val targets = lc.transitions.filter { it.by is TransitionCause.Action && it.by.id == action }.map { it.to }.distinct()
        val toStates = (entries + targets).distinct().mapNotNull { states[it] }
        val enumPath = toStates.firstNotNullOfOrNull { it.enumPath }
        fun schemaHolding(path: String): String? {
            val key = path.substringBefore('.').substringBefore('[')
            return aspects.filter { it.entity == lc.entity && it.def.fields.any { f -> f.key == key } }
                .sortedByDescending { it.defines }.firstOrNull()?.schema?.id
        }
        val out = mutableListOf<Map<String, Any?>>()
        val created = entries.isNotEmpty() && targets.isEmpty()
        if (enumPath != null) {
            val value = toStates.mapNotNull { it.enumValue }.distinct().let { if (it.size == 1) it[0] else it }
            out += linkedMapOf("op" to "set", "schema" to schemaHolding(enumPath), "path" to enumPath, "value" to value, "source" to "lifecycle")
                .plus(if (created) mapOf("on" to "create") else emptyMap())
        }
        lc.history?.let { h ->
            val row = LinkedHashMap<String, Any?>()
            row["date"] = mapOf("\$copy" to "today", "\$of" to "context")
            row["action"] = action
            if (!created && enumPath != null) row["from"] = mapOf("\$copy" to enumPath, "\$of" to "self")
            row["to"] = toStates.mapNotNull { it.enumValue }.distinct().let { if (it.size == 1) it[0] else it }
            lifecycleHistoryTemplate(raw)?.let(row::putAll)
            out += linkedMapOf("op" to "insert", "schema" to schemaHolding(h.path), "path" to h.path, "value" to row, "source" to "lifecycle")
                .plus(if (created) mapOf("on" to "create") else emptyMap())
        }
        return out
    }

    /**
     * 保存時に自動で入る値の効果: 効果が書き換えるスキーマの、側面の直下の `auto` の項目（更新日は書き換えるたび・作成日と版は作るとき）。
     * 値は今日（`{$copy: today, $of: context}`）か、版は 1 つ進める（`next`）。
     */
    private fun autoEffects(effects: List<Map<String, Any?>>, aspects: List<Aspect>): List<Map<String, Any?>> {
        val touched = effects.mapNotNull { it["schema"]?.toString() }.distinct()
        val creates = effects.filter { it["op"] == "create" || it["on"] == "create" }.mapNotNull { it["schema"]?.toString() }.toSet()
        val out = mutableListOf<Map<String, Any?>>()
        for (schema in touched) {
            val fields = aspects.filter { it.schema.id == schema }.flatMap { a -> a.def.fields.map { a.schema.tree to it } }
            for ((tree, f0) in fields) {
                val f = tree.resolve(f0) ?: f0
                val auto = (f0.auto ?: f.auto)?.id ?: continue
                val applies = when (auto) { "updated" -> true; "created", "revision" -> schema in creates; else -> false }
                if (!applies) continue
                val value: Any = if (auto == "revision") "next" else mapOf("\$copy" to "today", "\$of" to "context")
                out += linkedMapOf("op" to "set", "schema" to schema, "path" to f0.key, "value" to value, "source" to "auto", "auto" to auto)
            }
        }
        return out.distinctBy { listOf(it["schema"], it["path"]) }
    }

    // ================================================================ State と Lifecycle

    private fun stateTerms(schemas: List<WorkspaceSchema>, catalog: BehaviorChecks.Catalog): List<Map<String, Any?>> {
        val parentOf = catalog.children.flatMap { (parent, kids) -> kids.map { it to parent } }.toMap()
        val composites = schemas.flatMap { it.states }.filterIsInstance<StateDeclaration.Composite>().associateBy { it.id }
        val leaves = catalog.states.filter { it.id !in catalog.children }.map { s ->
            linkedMapOf<String, Any?>(
                "id" to s.id, "entity" to s.entity, "label" to (s.label ?: s.enumValue ?: s.id),
                "value" to s.enumValue, "path" to s.enumPath, "parent" to parentOf[s.id],
            ).filterValues { it != null }
        }
        val parents = catalog.children.keys.map { id ->
            val c = composites[id]
            linkedMapOf<String, Any?>(
                "id" to id, "entity" to c?.entity, "label" to (c?.label ?: id), "parent" to parentOf[id],
                "children" to catalog.children[id], "leaves" to catalog.leavesOf(id),
            ).filterValues { it != null }
        }
        return leaves + parents
    }

    private fun lifecycleTerms(schemas: List<WorkspaceSchema>, catalog: BehaviorChecks.Catalog): List<Map<String, Any?>> {
        val coarse = schemas.flatMap { it.lifecycles }.associateBy { it.id }
        return catalog.lifecycles.map { lc ->
            val groups = coarse[lc.id]?.states.orEmpty().filter { it in catalog.children }
            linkedMapOf<String, Any?>(
                "id" to lc.id,
                "entity" to lc.entity,
                "states" to lc.states.map { stateRef(it, catalog) },
                "groups" to groups.map { g -> linkedMapOf("id" to g, "label" to stateLabel(g, catalog), "states" to catalog.leavesOf(g).map { stateRef(it, catalog) }) },
                "entry" to lc.entry.map { e -> linkedMapOf("to" to stateRef(e.to, catalog), (if (e.by is TransitionCause.Action) "action" else "event") to e.by.id) },
                "transitions" to lc.transitions.groupBy { it.by.id to it.to }.map { (key, list) ->
                    linkedMapOf(
                        (if (list[0].by is TransitionCause.Action) "action" else "event") to key.first,
                        "from" to list.map { it.from }.distinct().map { stateRef(it, catalog) },
                        "to" to stateRef(key.second, catalog),
                    )
                },
                "terminal" to lc.terminal.map { stateRef(it, catalog) },
                "history" to lc.history?.let { linkedMapOf("path" to it.path) },
            ).filterValues { it != null }
        }
    }
}
