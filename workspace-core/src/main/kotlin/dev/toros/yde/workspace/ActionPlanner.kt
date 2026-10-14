package dev.toros.yde.workspace

import dev.toros.yde.semantic.CheckOutcome
import dev.toros.yde.semantic.CheckReason
import dev.toros.yde.semantic.FieldResolver
import dev.toros.yde.semantic.SemanticExpression
import dev.toros.yde.semantic.SemanticQuery
import dev.toros.yde.semantic.SemanticQueryNodeRef
import dev.toros.yde.semantic.SemanticQueryResult
import dev.toros.yde.semantic.SemanticRules
import dev.toros.yde.semantic.SemanticTransform
import dev.toros.yde.semantic.SemanticTransform.TemplatePlace
import dev.toros.yde.semantic.schema.ActionDef
import dev.toros.yde.semantic.schema.ActionStep
import dev.toros.yde.semantic.schema.ConstraintExpect
import dev.toros.yde.semantic.schema.ConstraintRule
import dev.toros.yde.semantic.schema.DerivationSelector
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.IdSeries
import dev.toros.yde.semantic.schema.InsertRule
import dev.toros.yde.semantic.schema.MethodDef
import dev.toros.yde.semantic.schema.QueryNodeKind
import dev.toros.yde.semantic.schema.SchemaKind
import dev.toros.yde.semantic.schema.SchemaScaffold
import dev.toros.yde.semantic.schema.SchemaTree
import dev.toros.yde.semantic.schema.StepSite
import dev.toros.yde.semantic.schema.Template
import dev.toros.yde.semantic.schema.TemplateCondition
import dev.toros.yde.workspace.changeset.ChangeSet
import dev.toros.yde.workspace.changeset.ChangeSets
import dev.toros.yde.workspace.changeset.FileChange
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.deepCopy
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.yaml.model.scalarText
import dev.toros.yde.yaml.patch.PatchOp
import dev.toros.yde.yaml.patch.Patcher
import dev.toros.yde.yaml.validate.Severity
import java.time.LocalDateTime

/** 操作の実行の依頼: 名前（アクションの id か 型名.操作名）・受け手（要素の ID か、文書の相対パス）・受け取る値。 */
data class ActionRequest(val name: String, val target: String? = null, val params: Map<String, Any?> = emptyMap())

/** 計画できなかった理由（前提を満たさない・判定保留・受け手が見つからない・受け取る値の誤り・定義の不備）。 */
class ActionFailure(message: String) : IllegalArgumentException(message)

/** 操作の計画: 変更セット（既存の適用器 ChangeSetApplier へそのまま渡す）と、人が読む要約。 */
data class ActionPlan(val title: String, val changeSet: ChangeSet, val summary: List<String>) {
    val isEmpty: Boolean get() = changeSet.changes.isEmpty()
}

/** 使える操作の一覧の 1 行（アクションは id、型の操作は 型名.操作名）。 */
data class ActionEntry(
    val name: String,
    /** action（横断）／ method（型の操作）／ create（型の create。直接は呼べない） */
    val kind: String,
    val label: String?,
    val description: String?,
    /** 受け手（entity: 型／schema: スキーマID／型の操作なら型名。無ければワークスペース）。 */
    val target: String?,
    val params: List<FieldDef>,
    val definedIn: String,
)

/**
 * 操作の計画（P41。計算体系の Rewrite＝「選ぶ × 判定 × 場所ごとの操作 → 変更セット」の、利用者定義体の計画器）。
 * 宣言は semantic-core の schema/Action.kt。ここは、セッションの文書とスキーマの上で手を順に当て、変更セットを作る。
 * - 書き換えは作業用の写しの上で順に当てる（後の手が前の手の結果を読める）。原本には触れず、結果は変更セット（PatchOp と新しい文書）
 * - 前提（require）は検査と同じ判定（SemanticRules）。違反と判定保留は実行しない（保留は安全側）
 * - 型の操作は受け手の部分木に閉じる（パスは受け手からの相対。宣言の段階で of / in を禁じている）
 * - 新しい要素は型の create（必須の初期値＋受け取った値、または build のひな型）、配列への追加は配列の insert の決まり（採番・同じ明細）
 * - 「今」は now で渡す（保存時に自動で入る値の初期値。式には入れない）
 */
object ActionPlanner {

    /** 使える操作の一覧（横断の操作と、object の定義に書かれた型の操作）。 */
    fun catalog(schemas: List<WorkspaceSchema>): List<ActionEntry> {
        val out = mutableListOf<ActionEntry>()
        for (s in schemas) {
            s.actions.forEach { a ->
                val target = a.target?.let { t -> t.entity?.let { "entity: $it" } ?: "schema: ${t.schema}" }
                out += ActionEntry(a.id, "action", a.label, a.description, target, a.params, s.id)
            }
            for ((at, f) in s.tree.objectDeclarations()) {
                if (f.methods.isEmpty()) continue
                val typeName = typeNameOf(s, f, at)
                f.methods.forEach { m ->
                    out += ActionEntry("$typeName.${m.name}", if (m.isCreate) "create" else "method", m.label, m.description, typeName, m.params, s.id)
                }
            }
        }
        return out.distinctBy { it.name }
    }

    /** 操作を計画する。計画できなければ ActionFailure。 */
    fun plan(session: WorkspaceSession, request: ActionRequest, now: LocalDateTime = LocalDateTime.now()): ActionPlan =
        Planning(session, now).plan(request)

    /**
     * 要素に使える操作（画面の操作メニューが使う）: その型を受け手にするアクションと、要素の型の操作（create を除く）。
     * 型の操作の名前は `型名.操作名`（型名は要素のエンティティ型。plan の名前の解決と同じ）。
     */
    fun forEntity(session: WorkspaceSession, id: String): List<ActionEntry> {
        val node = session.derived.semantic.graph.entities[id] ?: return emptyList()
        val occ = SemanticExpression.preferDefining(node.occurrences).firstOrNull() ?: return emptyList()
        val document = session.snapshot.documents[occ.document]
        val schema = document?.schemaId?.let { sid -> session.schemas.firstOrNull { it.id == sid } }
        val root = document?.document?.documents?.firstOrNull()
        val def = if (schema != null && root != null) schema.tree.fieldAt(root, occ.path) else null
        val type = node.entityType ?: def?.entity?.type ?: return emptyList()
        val actions = session.schemas.flatMap { s ->
            s.actions.filter { a -> a.target?.entity == type }
                .map { a -> ActionEntry(a.id, "action", a.label, a.description, "entity: $type", a.params, s.id) }
        }
        val methods = def?.methods.orEmpty().filter { !it.isCreate }
            .map { m -> ActionEntry("$type.${m.name}", "method", m.label, m.description, type, m.params, schema?.id ?: "") }
        return actions + methods
    }

    /** 文書に使える操作（文書ヘッダのメニューが使う）: その文書のスキーマを受け手にするアクション。 */
    fun forDocument(session: WorkspaceSession, rel: String): List<ActionEntry> {
        val sid = session.snapshot.documents[DocumentId(rel.replace('\\', '/'))]?.schemaId ?: return emptyList()
        return session.schemas.flatMap { s ->
            s.actions.filter { a -> a.target?.schema == sid }
                .map { a -> ActionEntry(a.id, "action", a.label, a.description, "schema: $sid", a.params, s.id) }
        }
    }

    /** 受け手の無い操作（コマンドパレットが使う）。 */
    fun forWorkspace(schemas: List<WorkspaceSchema>): List<ActionEntry> =
        schemas.flatMap { s -> s.actions.filter { it.target == null }.map { a -> ActionEntry(a.id, "action", a.label, a.description, null, a.params, s.id) } }

    /** 配列へ足す新しい要素。node が null なら足さない（skipped にその理由＝同じ明細がある）。 */
    data class NewElement(val node: YNode?, val skipped: String?)

    /**
     * 配列へ足す新しい要素を作る（配列エディタの追加ボタン・ビューの新規行が使う。アクションの insert と同じ作り方）:
     * 要素の型の create（必須の初期値＋受け取った値、または build）→ 配列の insert の決まり（採番 id・同じ明細 key）。
     * arrayDef は解決済みの配列の項目、existingIds は索引の全 ID（採番の既存）。dedupe を false にすると同じ明細の見分けをしない
     * （受け取った値の無い追加では、空の要素どうしが「同じ」になってしまうため）。
     */
    fun newElement(
        tree: SchemaTree,
        arrayDef: FieldDef,
        array: YNode.Seq,
        params: Map<String, Any?>,
        existingIds: Collection<String>,
        now: LocalDateTime = LocalDateTime.now(),
        dedupe: Boolean = true,
    ): NewElement {
        val itemDef = arrayDef.item?.let { tree.resolve(it) ?: it } ?: throw ActionFailure("配列の要素の定義が見つかりません")
        val element = buildElement(tree, itemDef, YamlIO.fromAny(params) as? YNode.Map ?: YNode.Map(), "${arrayDef.label ?: arrayDef.key ?: "配列"} の要素", now)
        val rule = arrayDef.insertRule ?: return NewElement(element, null)
        val skipped = applyInsertRuleTo(rule, element, array, existingIds, dedupe)
        return if (skipped == null) NewElement(element, null) else NewElement(null, skipped)
    }

    /**
     * 型の呼び名: object の同一性（entity.type）→ 定義名 → スキーマの entity 宣言が 1 つで、その object が ID の項目を持つなら
     * その型（ID の形で判別する従来のスキーマ）→ 書かれた場所。
     */
    private fun typeNameOf(schema: WorkspaceSchema, f: FieldDef, at: String): String =
        f.entity?.type
            ?: at.removePrefix("definitions.").takeIf { at.startsWith("definitions.") }
            ?: schema.entities.singleOrNull()?.type?.takeIf { f.fields.any { c -> (schema.tree.resolve(c) ?: c).type == FieldType.ID } }
            ?: at

    private class Bound(val id: String?, val place: (DerivationSelector?) -> TemplatePlace?)

    /** 手を当てる文脈: 受け取った値（いまの場所）・受け手・束縛。 */
    private data class Scope(
        val params: Map<String, Any?>,
        val paramsNode: YNode.Map,
        val receiver: TemplatePlace?,
        val receiverName: String?,
        val bindings: Map<String, Bound>,
    ) {
        val here: TemplatePlace get() = TemplatePlace(paramsNode, null, emptyList())
        fun templateBindings(): Map<String, (DerivationSelector?) -> TemplatePlace?> = bindings.mapValues { (_, b) -> b.place }
    }

    private class Planning(val session: WorkspaceSession, val now: LocalDateTime) {
        val schemas = session.schemas
        private val semantic = session.derived.semantic
        private val ids = semantic.ids
        private val refs = semantic.refs
        private val graph = semantic.graph

        /** 変更を当てた木（文書 → 写し）。読み出しもここを優先する。 */
        private val working = LinkedHashMap<DocumentId, YNode>()
        private val ops = LinkedHashMap<DocumentId, MutableList<PatchOp>>()
        /** 新しく作る文書（スキーマと中身）。 */
        private val creates = LinkedHashMap<DocumentId, Pair<WorkspaceSchema, YNode.Map>>()
        private val newIds = LinkedHashSet<String>()
        private val summary = mutableListOf<String>()

        private fun fail(message: String): Nothing = throw ActionFailure(message)
        private fun rel(d: DocumentId): String = d.value

        private fun stored(d: DocumentId): YNode? = session.snapshot.documents[d]?.document?.documents?.firstOrNull()
        private fun tree(d: DocumentId): YNode? = working[d] ?: creates[d]?.second ?: stored(d)
        private fun editable(d: DocumentId): YNode = working.getOrPut(d) {
            creates[d]?.second ?: stored(d)?.deepCopy() ?: fail("文書がありません: ${d.value}")
        }
        private fun schemaOf(d: DocumentId): WorkspaceSchema? =
            creates[d]?.first ?: session.snapshot.documents[d]?.schemaId?.let { sid -> schemas.firstOrNull { it.id == sid } }
        private val fieldAt: FieldResolver = { d, p -> schemaOf(d)?.tree?.let { t -> tree(d)?.let { r -> t.fieldAt(r, p) } } }
        private val loader: (DocumentId) -> YNode? = { d -> tree(d) }

        /** 変更を 1 つ当てて記録する（新しい文書は中身を直接変える）。 */
        private fun record(d: DocumentId, op: PatchOp) {
            creates[d]?.let { (_, content) -> Patcher.apply(content, op); return }
            Patcher.apply(editable(d), op)
            ops.getOrPut(d) { mutableListOf() } += op
        }

        fun plan(request: ActionRequest): ActionPlan {
            val found = schemas.asSequence().flatMap { s -> s.actions.asSequence().map { s to it } }.firstOrNull { it.second.id == request.name }
            val title = when {
                found != null -> runAction(found.first, found.second, request)
                '.' in request.name -> runMethod(request.name.substringBeforeLast('.'), request.name.substringAfterLast('.'), request)
                else -> fail(
                    "操作「${request.name}」が見つかりません（使える操作: ${
                        catalog(schemas).filter { it.kind != "create" }.map { it.name }.joinToString(", ").ifEmpty { "なし" }
                    }）"
                )
            }
            return ActionPlan(title, changeSet(), summary.toList())
        }

        private fun changeSet(): ChangeSet {
            val edited = ops.entries.filter { it.value.isNotEmpty() }.map { (d, list) ->
                FileChange(d.value, session.storedText(d)?.let(ChangeSets::contentHash), list.toList())
            }
            val created = creates.map { (d, pair) -> FileChange(d.value, null, emptyList(), create = true, content = pair.second) }
            return ChangeSet(edited + created)
        }

        // ---- 入口 ----

        private fun runAction(schema: WorkspaceSchema, action: ActionDef, request: ActionRequest): String {
            val label = "操作「${action.displayName}」"
            val (values, node) = prepareParams(action.params, request.params, label, schema.tree)
            var startId: String? = null
            val on = action.target
            val scope = when {
                on?.entity != null -> {
                    val id = request.target ?: fail("$label には受け手（${on.entity} の要素の ID）が要ります")
                    val entity = graph.entities[id] ?: fail("要素「$id」が見つかりません")
                    val place = entityPlace(id, null) ?: fail("要素「$id」の場所を読めません")
                    val type = entity.entityType ?: place.document?.let { fieldAt(it, place.path)?.entity?.type }
                    if (type != on.entity) fail("要素「$id」は ${on.entity} ではありません（型: ${type ?: "不明"}）")
                    startId = id
                    Scope(values, node, place, on.bind, mapOf(on.bind to Bound(id) { sel -> entityPlace(id, sel) }))
                }
                on?.schema != null -> {
                    val sid = on.schema
                    val candidates = session.snapshot.documents.filter { it.value.schemaId == sid }.keys.map { it.value }.sorted()
                    val relPath = request.target?.replace('\\', '/') ?: candidates.singleOrNull()
                        ?: fail(if (candidates.isEmpty()) "スキーマ「$sid」の文書がありません" else "$label の受け手の文書を指定してください（候補: ${candidates.joinToString(", ")}）")
                    val docId = DocumentId(relPath)
                    if (session.snapshot.documents[docId]?.schemaId != sid) fail("文書「$relPath」はスキーマ「$sid」の文書ではありません")
                    val place = TemplatePlace(editable(docId), docId, emptyList())
                    Scope(values, node, place, on.bind, mapOf(on.bind to Bound(null) { _ -> TemplatePlace(editable(docId), docId, emptyList()) }))
                }
                else -> Scope(values, node, null, null, emptyMap())
            }
            checkRequire(action, scope, startId)
            run(action.steps, scope)
            return action.displayName + (request.target?.let { "（$it）" } ?: "")
        }

        private fun runMethod(typeName: String, methodName: String, request: ActionRequest): String {
            val id = request.target ?: fail("操作「$typeName.$methodName」には受け手（要素の ID。--on）が要ります")
            val entity = graph.entities[id] ?: fail("要素「$id」が見つかりません")
            val place = entityPlace(id, null) ?: fail("要素「$id」の場所を読めません")
            val doc = place.document!!
            val def = fieldAt(doc, place.path) ?: fail("要素「$id」の型が分かりません（${doc.value} にスキーマが無いか、要素が object ではありません）")
            if (entity.entityType != typeName && !typeMatches(def, typeName)) fail("要素「$id」は型「$typeName」ではありません（型: ${typeLabel(def, entity.entityType)}）")
            val method = def.methods.firstOrNull { it.name == methodName && !it.isCreate }
                ?: fail("型「$typeName」に操作「$methodName」はありません（ある操作: ${def.methods.filter { !it.isCreate }.map { it.name }.joinToString(", ").ifEmpty { "なし" }}）")
            invoke(method, place, request.params, schemaOf(doc)?.tree)
            return "${method.displayName}（$id）"
        }

        private fun typeMatches(def: FieldDef, name: String): Boolean =
            def.entity?.type == name || def.typeName == name || def.lineage.any { it == name || it.substringAfter('#') == name }

        private fun typeLabel(def: FieldDef, entityType: String?): String =
            entityType ?: def.entity?.type ?: def.lineage.firstOrNull() ?: def.label ?: def.key ?: "object"

        // ---- 受け取った値 ----

        private fun prepareParams(defs: List<FieldDef>, given: Map<String, Any?>, label: String, tree: SchemaTree?): Pair<Map<String, Any?>, YNode.Map> =
            prepareActionParams(defs, given, label, tree)

        // ---- 前提 ----

        private fun checkRequire(action: ActionDef, scope: Scope, startId: String?) {
            for (req in action.require) {
                val rule = try { req.rule(scope.params) } catch (e: IllegalStateException) { fail(e.message ?: req.origin) } catch (e: IllegalArgumentException) { fail(e.message ?: req.origin) }
                val name = rule.description ?: rule.id ?: req.origin
                if (startId != null) {
                    val r = SemanticRules.evaluate(ids, refs, graph, rule, startId, ::rel, loader = loader, fieldAt = fieldAt)
                    when (r.outcome) {
                        CheckOutcome.VIOLATED -> fail(message(rule, r.matches, startId))
                        CheckOutcome.WITHHELD -> fail("前提「$name」を確かめられません: ${r.reason?.text ?: "判定保留"}")
                        CheckOutcome.NOT_APPLICABLE ->
                            if (r.reason == CheckReason.NOT_IN_SCOPE) fail("前提「$name」: 「$startId」はこの前提の対象の要素ではありません")
                        CheckOutcome.SATISFIED -> Unit
                    }
                } else {
                    val result = SemanticQuery.run(ids, refs, graph, rule.spec, fieldAt, loader)
                    val count = rule.count
                    val below = count?.min?.let { result.total < it } ?: (rule.expect == ConstraintExpect.EXISTS && result.total == 0)
                    val above = count?.max?.let { result.total > it } ?: (rule.expect == ConstraintExpect.ABSENT && result.total > 0)
                    if (below && result.unknownDropped) fail("前提「$name」を確かめられません: ${CheckReason.UNRESOLVED_REFERENCE.text}")
                    if (below || above) fail(message(rule, result, null))
                }
            }
        }

        /** 前提の文面: message のひな型（{id} {count} {束縛.id} {束縛.title} {束縛.type}）か、既定の文。 */
        private fun message(rule: ConstraintRule, matches: SemanticQueryResult?, startId: String?): String {
            val name = rule.description ?: rule.id ?: "前提"
            var text = rule.message ?: "前提「$name」を満たしていません"
            text = text.replace("{id}", startId ?: "").replace("{count}", (matches?.total ?: 0).toString())
            matches?.rows?.firstOrNull()?.bindings?.forEach { (b, ref) ->
                text = text.replace("{$b.id}", ref.id).replace("{$b.title}", ref.title ?: ref.id).replace("{$b.type}", ref.entityType ?: "").replace("{$b}", ref.id)
            }
            return text
        }

        // ---- 手 ----

        private fun build(t: Template, scope: Scope): YNode? = SemanticTransform.build(t, scope.params, scope.here, scope.templateBindings()).term

        private fun holds(conditions: List<TemplateCondition>, scope: Scope): Boolean =
            SemanticTransform.holds(conditions, scope.params, scope.here, scope.templateBindings())

        private fun run(steps: List<ActionStep>, scope: Scope) {
            for (step in steps) {
                if (!holds(step.conditions, scope)) {
                    summary += "飛ばした: ${step.origin}（when の条件に合わない）"
                    continue
                }
                try {
                    when (step) {
                        is ActionStep.Set -> doSet(step, scope)
                        is ActionStep.Insert -> doInsert(step, scope)
                        is ActionStep.Delete -> doDelete(step, scope)
                        is ActionStep.Create -> doCreate(step, scope)
                        is ActionStep.Call -> doCall(step, scope)
                        is ActionStep.EachMatch -> doEachMatch(step, scope)
                        is ActionStep.EachPath -> doEachPath(step, scope)
                    }
                } catch (e: ActionFailure) {
                    throw e
                } catch (e: RuntimeException) {
                    fail("${step.origin}: ${e.message ?: e.toString()}")
                }
            }
        }

        /** 手の対象の土台: 束縛の要素（of）・文書の指定（in）・受け手の順。 */
        private fun base(site: StepSite, scope: Scope, origin: String): TemplatePlace {
            val of = site.of
            val inDoc = site.inDoc
            return when {
                of != null -> scope.bindings[of]?.place?.invoke(inDoc)
                    ?: fail("$origin: 束縛「$of」の要素${inDoc?.let { "（${describe(it)} に合う出現）" } ?: ""}が見つかりません")
                inDoc != null -> documentPlace(inDoc, origin)
                scope.receiver != null -> scope.receiver
                else -> fail("$origin: 対象を of（束縛名）か in（文書の指定）で指定してください")
            }
        }

        private fun describe(selector: DerivationSelector): String =
            listOfNotNull(selector.schemaId?.let { "schemaId: $it" }, selector.fileGlob?.let { "fileGlob: $it" }).joinToString(", ")

        /** 文書の指定に合う文書がちょうど 1 つのとき、そのルート。 */
        private fun documentPlace(selector: DerivationSelector, origin: String): TemplatePlace {
            val candidates = (
                session.snapshot.documents.values.filter { d -> d.inLedger && selector.matches(d.schemaId, d.document.id.value) }.map { it.document.id } +
                    creates.filter { (id, pair) -> selector.matches(pair.first.id, id.value) }.keys
                ).distinct().sortedBy { it.value }
            val doc = candidates.singleOrNull() ?: fail(
                if (candidates.isEmpty()) "$origin: 文書の指定（${describe(selector)}）に合う文書がありません"
                else "$origin: 文書を 1 つに決められません（${candidates.joinToString(", ") { it.value }}。fileGlob で絞るか、of で要素を指してください）"
            )
            return TemplatePlace(editable(doc), doc, emptyList())
        }

        /** 要素の場所（定義出現を優先。文書の指定に合う出現から）。書き換えの土台なので作業用の写しを返す。 */
        private fun entityPlace(id: String, selector: DerivationSelector?): TemplatePlace? {
            val node = graph.entities[id] ?: return null
            val occ = SemanticExpression.preferDefining(node.occurrences)
                .firstOrNull { selector == null || selector.matches(it.schemaId, rel(it.document)) } ?: return null
            if (stored(occ.document) == null && occ.document !in creates) return null
            return getAt(editable(occ.document), occ.path)?.let { TemplatePlace(it, occ.document, occ.path) }
        }

        private fun refBound(ref: SemanticQueryNodeRef): Bound = when (ref.kind) {
            QueryNodeKind.ENTITY -> Bound(ref.id) { sel -> entityPlace(ref.id, sel) }
            QueryNodeKind.DOCUMENT -> Bound(ref.id) { _ ->
                graph.documents[ref.id]?.let { d -> TemplatePlace(editable(d.document), d.document, emptyList()) }
            }
        }

        private fun doSet(s: ActionStep.Set, scope: Scope) {
            val at = base(s.site, scope, s.origin)
            val doc = at.document ?: fail("${s.origin}: 書き込む文書が決まりません")
            val value = build(s.value, scope) ?: run { summary += "飛ばした: ${s.origin}（値が得られない）"; return }
            val path = at.path + s.site.path
            record(doc, PatchOp.Set(path, value))
            summary += "設定: ${doc.value} ${path.pretty()} ← ${SemanticExpression.flowText(value)}"
        }

        private fun doDelete(s: ActionStep.Delete, scope: Scope) {
            val at = base(s.site, scope, s.origin)
            val doc = at.document ?: fail("${s.origin}: 書き込む文書が決まりません")
            val path = at.path + s.site.path
            if (getAt(editable(doc), path) == null) {
                summary += "飛ばした: ${s.origin}（${path.pretty()} は無い）"
                return
            }
            record(doc, PatchOp.Delete(path))
            summary += "削除: ${doc.value} ${path.pretty()}"
        }

        private fun doInsert(s: ActionStep.Insert, scope: Scope) {
            val at = base(s.site, scope, s.origin)
            val doc = at.document ?: fail("${s.origin}: 書き込む文書が決まりません")
            val arrayPath = at.path + s.site.path
            val existing = getAt(editable(doc), arrayPath)
            // 配列がまだ無ければ、要素 1 つの配列を置く（1 つの操作にまとめる）
            val array = (existing ?: YNode.Seq()) as? YNode.Seq ?: fail("${s.origin}: ${doc.value} の ${arrayPath.pretty()} は配列ではありません")
            val schema = schemaOf(doc)
            val arrayDef = schema?.tree?.let { t -> t.fieldAt(editable(doc), arrayPath) }
            val paramsTemplate = s.params
            val element: YNode = if (paramsTemplate != null) {
                val tree = schema?.tree ?: fail("${s.origin}: ${doc.value} にはスキーマが無く、要素の型が分かりません（value で要素そのものを書きます）")
                val itemDef = arrayDef?.item?.let { tree.resolve(it) ?: it } ?: fail("${s.origin}: ${arrayPath.pretty()} の要素の定義が見つかりません")
                val given = build(paramsTemplate, scope) as? YNode.Map ?: fail("${s.origin}: params はobjectで書きます")
                createElement(tree, itemDef, given, "${s.origin} の要素")
            } else {
                build(s.value!!, scope) ?: run { summary += "飛ばした: ${s.origin}（値が得られない）"; return }
            }
            val rule = arrayDef?.insertRule
            if (rule != null) {
                val skipped = applyInsertRuleTo(rule, element, array, graph.entities.keys + newIds)
                if (skipped != null) {
                    summary += "飛ばした: ${s.origin}（$skipped）"
                    return
                }
            }
            val index = array.items.size
            if (existing == null) record(doc, PatchOp.Set(arrayPath, YNode.Seq(mutableListOf(element))))
            else record(doc, PatchOp.Insert(arrayPath + index, element))
            (element as? YNode.Map)?.let { m -> idOf(m, arrayDef?.item?.let { schema?.tree?.resolve(it) })?.let { newIds += it } }
            summary += "追加: ${doc.value} ${(arrayPath + index).pretty()} ← ${SemanticExpression.flowText(element)}"
        }

        private fun createElement(tree: SchemaTree, def: FieldDef, given: YNode.Map, label: String): YNode = buildElement(tree, def, given, label, now)

        private fun doCreate(s: ActionStep.Create, scope: Scope) {
            val schema = schemas.firstOrNull { it.id == s.schemaId } ?: fail("${s.origin}: スキーマ「${s.schemaId}」がありません")
            if (schema.kind != SchemaKind.DOCUMENT || schema.isTemplate) fail("${s.origin}: 「${s.schemaId}」は文書のスキーマではありません")
            val given = build(s.params, scope) as? YNode.Map ?: fail("${s.origin}: params はobjectで書きます")
            val tree = schema.tree
            val rootDef = tree.resolve(tree.root) ?: tree.root
            val idKey = rootDef.fields.firstOrNull { (tree.resolve(it) ?: it).type == FieldType.ID }?.key ?: "id"
            val id = scalarText(given.entries[idKey])?.takeIf { it.isNotBlank() } ?: fail("${s.origin}: 作る文書の ID（$idKey）が params にありません")
            val pattern = DocumentScaffolds.filePatternOf(schema)
                ?: fail("${s.origin}: スキーマ「${schema.id}」の文書の置き場所が決められません（filePattern を書くか、match を単純な形にします）")
            val relPath = DocumentScaffolds.pathFor(pattern, ScaffoldSource.idOnly(id))
            val docId = DocumentId(relPath)
            if (session.hasDocument(docId) || docId in creates) fail("${s.origin}: 既に同名のファイルが存在します: $relPath")
            val content = createElement(tree, rootDef, given, "${s.origin} の文書") as? YNode.Map ?: fail("${s.origin}: 文書の中身がobjectになりません")
            content.entries[idKey] = YNode.Scalar(id)
            creates[docId] = schema to content
            newIds += id
            summary += "作成: $relPath"
        }

        private fun doCall(s: ActionStep.Call, scope: Scope) {
            val at = base(s.site, scope, s.origin)
            val doc = at.document ?: fail("${s.origin}: 要素の文書が決まりません")
            val def = fieldAt(doc, at.path) ?: fail("${s.origin}: 要素（${doc.value} ${at.path.pretty()}）の型が分かりません")
            val method = def.methods.firstOrNull { it.name == s.method && !it.isCreate }
                ?: fail("${s.origin}: 型「${typeLabel(def, null)}」に操作「${s.method}」はありません（ある操作: ${def.methods.filter { !it.isCreate }.map { it.name }.joinToString(", ").ifEmpty { "なし" }}）")
            val given = build(s.params, scope) as? YNode.Map ?: YNode.Map()
            @Suppress("UNCHECKED_CAST")
            invoke(method, at, (YamlIO.toAny(given) as? Map<String, Any?>).orEmpty(), schemaOf(doc)?.tree)
        }

        /** 型の操作を受け手に当てる。手の中で指せる要素は受け手（self）だけ。 */
        private fun invoke(method: MethodDef, receiver: TemplatePlace, given: Map<String, Any?>, tree: SchemaTree?) {
            val (values, node) = prepareParams(method.params, given, "操作「${method.displayName}」", tree)
            val self = Bound(receiver.document?.let { d -> (receiver.node as? YNode.Map)?.let { idOf(it, fieldAt(d, receiver.path)) } }) { _ -> receiver }
            run(method.steps, Scope(values, node, receiver, MethodDef.SELF, mapOf(MethodDef.SELF to self)))
        }

        private fun idOf(element: YNode.Map, def: FieldDef?): String? = elementId(element, def)

        private fun doEachMatch(s: ActionStep.EachMatch, scope: Scope) {
            val from = s.from ?: scope.receiverName ?: fail("${s.origin}: 起点の要素が決まりません（from に束縛名を書くか、target で受け手を宣言します）")
            val startId = scope.bindings[from]?.id ?: fail("${s.origin}: 束縛「$from」は要素ではありません")
            val rows = SemanticQuery.matchesFrom(ids, refs, graph, s.spec, startId, fieldAt, loader).rows
            summary += "照合: ${s.origin} は ${rows.size} 件"
            for (row in rows) {
                val bound = scope.bindings + row.bindings.mapValues { (_, ref) -> refBound(ref) }
                run(s.steps, scope.copy(bindings = bound))
            }
        }

        private fun doEachPath(s: ActionStep.EachPath, scope: Scope) {
            val at = base(s.site, scope, s.origin)
            val doc = at.document ?: fail("${s.origin}: 文書が決まりません")
            val arrayPath = at.path + s.site.path
            val seq = getAt(editable(doc), arrayPath) as? YNode.Seq ?: run { summary += "飛ばした: ${s.origin}（${arrayPath.pretty()} は配列ではない）"; return }
            for (item in seq.items.toList()) {
                val i = seq.items.indexOfFirst { it === item }
                if (i < 0) continue
                val place = TemplatePlace(item, doc, arrayPath + i)
                val self = Bound((item as? YNode.Map)?.let { idOf(it, fieldAt(doc, arrayPath + i)) }) { _ -> place }
                run(s.steps, scope.copy(receiver = place, receiverName = MethodDef.SELF, bindings = scope.bindings + (MethodDef.SELF to self)))
            }
        }
    }
}

// ---------------------------------------------------------------- 要素の作り方（計画と、追加ボタン・新規行が共有する）

private fun actionFail(message: String): Nothing = throw ActionFailure(message)

/**
 * 受け取った値をそろえる: 宣言に無い名前は誤り・省いた値は default・宣言があれば構造の検証（必須・型・選択肢・形式）。
 * 返すのは、ひな型へ渡す値と、「いまの場所」にする項。
 */
private fun prepareActionParams(defs: List<FieldDef>, given: Map<String, Any?>, label: String, tree: SchemaTree?): Pair<Map<String, Any?>, YNode.Map> {
    val declared = defs.mapNotNull { it.key }
    given.keys.firstOrNull { it !in declared }
        ?.let { actionFail("$label は「$it」を受け取りません（受け取る値: ${declared.joinToString(", ").ifEmpty { "なし" }}）") }
    val values = LinkedHashMap<String, Any?>()
    for (def in defs) {
        val key = def.key ?: continue
        if (given.containsKey(key)) values[key] = given[key] else def.default?.let { values[key] = it }
    }
    val node = YamlIO.fromAny(values) as YNode.Map
    if (defs.isNotEmpty()) {
        val root = FieldDef(type = FieldType.OBJECT, fields = defs, definitionScope = tree?.id ?: "params")
        val paramsTree = tree?.let { SchemaTree("params", root, it.definitions, definitionCatalog = it.definitionCatalog) } ?: SchemaTree("params", root)
        val issues = DerivedStructures(paramsTree).validate(node).filter { it.severity == Severity.ERROR }
        if (issues.isNotEmpty()) {
            actionFail("$label の受け取る値に誤りがあります: " + issues.joinToString("・") { "${it.path.pretty().ifEmpty { "（全体）" }}: ${it.message}" })
        }
    }
    return values to node
}

/**
 * 新しい要素の中身（型の create）: 必須の初期値（保存時に自動で入る値を含む）の上に、create の build（定義側から順）を重ねる。
 * build が無ければ、受け取った値のうち項目のキーに合うものを写す。create に params が宣言されていれば、受け取った値を
 * 構造で検証する（宣言が無ければ、項目のキーに合うことだけを見る）。
 */
private fun buildElement(tree: SchemaTree, def: FieldDef, given: YNode.Map, label: String, now: LocalDateTime): YNode {
    val resolved = tree.resolve(def) ?: def
    val create = resolved.methods.firstOrNull { it.isCreate }
    val fieldKeys = if (resolved.type == FieldType.OBJECT) tree.effectiveFields(resolved.fields, given).fields.mapNotNull { it.key }.toSet() else emptySet()
    @Suppress("UNCHECKED_CAST")
    val raw = YamlIO.toAny(given) as? Map<String, Any?> ?: emptyMap()
    val (values, node) = if (create != null && create.params.isNotEmpty()) prepareActionParams(create.params, raw, label, tree) else {
        raw.keys.firstOrNull { it !in fieldKeys }?.let { actionFail("$label: 「$it」は要素の項目ではありません（項目: ${fieldKeys.joinToString(", ")}）") }
        raw to given
    }
    var result = SchemaScaffold.defaultTree(tree, resolved, now = now)
    if (create != null && create.builds.isNotEmpty()) {
        val here = TemplatePlace(node, null, emptyList())
        for (b in create.builds) SemanticTransform.build(b, values, here).term?.let { result = overlayElement(result, it) }
    } else if (resolved.type == FieldType.OBJECT) {
        // object の要素だけ、受け取った値のうち項目のキーに合うものを写す（スカラーの要素は必須の初期値のまま）
        val copy = YNode.Map(LinkedHashMap(node.entries.filterKeys { it in fieldKeys }))
        result = overlayElement(result, copy)
    }
    return result
}

/** ひな型の出力を初期値の上に重ねる（マップどうしは項目ごとに重ね、それ以外は出力が勝つ）。 */
private fun overlayElement(base: YNode, over: YNode): YNode {
    if (base !is YNode.Map || over !is YNode.Map) return over
    val entries = LinkedHashMap(base.entries)
    for ((key, value) in over.entries) {
        val existing = entries[key]
        entries[key] = if (existing != null) overlayElement(existing, value) else value
    }
    return YNode.Map(entries)
}

/**
 * 配列の追加の決まり: 採番（ID の項目が空なら接頭辞＋連番。既存は existingIds と配列の中）と、同じ明細の見分け
 * （dedupe のとき、key が同じ要素があれば足さない）。足さない理由を返す（null＝足す）。要素の ID は書き換える。
 */
private fun applyInsertRuleTo(rule: InsertRule, element: YNode, array: YNode.Seq, existingIds: Collection<String>, dedupe: Boolean = true): String? {
    val map = element as? YNode.Map ?: return null
    rule.id?.let { numbering ->
        if (scalarText(getAt(map, numbering.path)).isNullOrBlank()) {
            val prefix = SemanticTransform.build(numbering.prefix, emptyMap(), TemplatePlace(map, null, emptyList())).term?.let(::scalarText)
                ?: actionFail("採番の接頭辞が得られません（${rule.origin}）")
            val existing = existingIds + array.items.mapNotNull { scalarText(getAt(it, numbering.path)) }
            Patcher.apply(map, PatchOp.Set(numbering.path, YNode.Scalar(IdSeries.next(existing, prefix, numbering.digits))))
        }
    }
    if (dedupe && rule.key.isNotEmpty()) {
        val values = rule.key.map { scalarText(getAt(map, it)) }
        if (array.items.any { e -> rule.key.map { scalarText(getAt(e, it)) } == values }) {
            return "同じ明細が既にあります: ${values.joinToString("・")}"
        }
    }
    return null
}

/** 要素の ID（ID の項目があれば）。 */
private fun elementId(element: YNode.Map, def: FieldDef?): String? {
    val key = def?.fields?.firstOrNull { it.type == FieldType.ID }?.key ?: "id"
    return scalarText(element.entries[key])
}
