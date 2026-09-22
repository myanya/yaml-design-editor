package dev.toros.yde.workspace

import dev.toros.yde.semantic.schema.DefinitionKey
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldRole
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.RelationDef
import dev.toros.yde.semantic.schema.SchemaKind
import dev.toros.yde.semantic.schema.globToRegex

/** スキーマの定義の点検で見つかった誤り1件（path は、スキーマ文書の中の場所の表示）。 */
data class SchemaIssue(val message: String, val path: String)

/**
 * スキーマの定義そのものの点検（P27 段D の D-a で、root の `SchemaDef.validateDefinition` と `SchemaLoader` から移した。意図的差分 D-33）。
 * - スキーマの定義の点検（issuesOf）: 種別ごとに書けるもの・型と制約の食い違い・`$ref` の参照先・cases・フェーズの宣言など
 * - 宣言のない関係名の点検（undeclaredRelations）: 関係レジストリがあるのに、参照の項目がレジストリに無い関係名を使っている
 * スキーマ置き場に書かれたスキーマについて WorkspaceReader.buildSchemas が行い、スキーマの読み込みエラーへ足す（loadErrors）。
 * 組込みスキーマと、derive から導出されたスキーマには行わない。
 */
object SchemaDefinitionChecks {

    /** スキーマ置き場に書かれたスキーマの点検の結果を、読み込みエラーの文面にする（文面は、root にあったときと同じ）。 */
    fun loadErrors(written: List<WorkspaceSchema>, registry: Map<String, RelationDef>): List<String> {
        val errors = mutableListOf<String>()
        fun fileName(schema: WorkspaceSchema) = schema.source.value.substringAfterLast('/')
        written.forEach { schema ->
            issuesOf(schema).forEach { issue -> errors += "${fileName(schema)} [${issue.path}]: ${issue.message}" }
        }
        // 未宣言 relation の lint（R20-02）: レジストリが1つでもあるときだけ判定する（非破壊。§4.30）
        if (registry.isNotEmpty()) {
            written.filter { it.kind == SchemaKind.DOCUMENT }.forEach { schema ->
                undeclaredRelations(schema, registry).forEach { name ->
                    errors += "${fileName(schema)}: 関係「$name」は関係レジストリに宣言されていません"
                }
            }
        }
        return errors
    }

    /** スキーマの参照の項目が使う関係名のうち、関係レジストリに宣言のないもの（名前順）。 */
    fun undeclaredRelations(schema: WorkspaceSchema, registry: Map<String, RelationDef>): List<String> =
        relationNames(schema).filter { it !in registry }.sorted()

    /** スキーマの ref フィールドが宣言する関係種別（relation）を収集する。 */
    private fun relationNames(schema: WorkspaceSchema): Set<String> {
        val tree = schema.tree
        val names = mutableSetOf<String>()
        val seen = mutableSetOf<DefinitionKey>()
        fun walk(f0: FieldDef) {
            tree.definitionTarget(f0)?.let { (key, _) -> if (!seen.add(key)) return }
            val f = tree.resolve(f0) ?: f0
            if (f.type == FieldType.REF) f.relation?.let(names::add)
            f.fields.forEach(::walk)
            f.item?.let(::walk)
            f.value?.let(::walk)
            f.cases.values.flatten().forEach(::walk)
        }
        walk(tree.root)
        tree.definitions.values.forEach(::walk)
        return names
    }

    /** スキーマ定義自体を検証するメタスキーマ検証。 */
    fun issuesOf(schema: WorkspaceSchema): List<SchemaIssue> = with(schema) {
        val root = tree.root
        val definitions = tree.definitions
        val phases = tree.phases
        val issues = mutableListOf<SchemaIssue>()
        if (name.isBlank()) issues += SchemaIssue("スキーマ名が空です", "name")
        if (id.isBlank()) issues += SchemaIssue("スキーマIDが空です", "id")
        val deriveDef = derive
        if (kind == SchemaKind.DOCUMENT && deriveDef != null) {
            // 派生スキーマの宣言（R11-03）: 自身は文書にマッチしないため match は持たず、root は共通フィールドの object
            runCatching { globToRegex(deriveDef.from) }
                .onFailure { issues += SchemaIssue("derive.from が不正です: ${it.message}", "derive.from") }
            if (tree.resolve(root)?.type != FieldType.OBJECT) {
                issues += SchemaIssue("derive 宣言スキーマの root は object である必要があります（共通フィールドとして合成されます）", "root")
            }
        } else if (kind == SchemaKind.DOCUMENT) {
            if (match.isBlank()) issues += SchemaIssue("適用パターンが空です", "match")
            runCatching { globToRegex(match) }
                .onFailure { issues += SchemaIssue("適用パターンが不正です: ${it.message}", "match") }
        } else if (kind == SchemaKind.RELATIONS) {
            // 関係レジストリ（R20-02）: 文書には適用されない宣言専用の文書。ARCHITECTURE §4.30
            if (match.isNotBlank()) issues += SchemaIssue("relations 文書に match は定義できません", "match")
            if (relations.isEmpty()) issues += SchemaIssue("kind: relations には1つ以上の relations が必要です", "relations")
            if (definitions.isNotEmpty()) issues += SchemaIssue("relations 文書に definitions は定義できません", "definitions")
            if (expandedChecks.any { it.origin.startsWith("coverage[") }) {
                issues += SchemaIssue("relations 文書に coverage は定義できません", "coverage")
            }
            // 関係の宣言（relations[]）に書かれた語彙は検査へ展開される（多重度・対象型など。P29 Phase 6 第2段）。
            // 検査を直接書くこと（constraints）はできない——関係レジストリは宣言の文書で、検査は語彙を通して作る
            if (expandedChecks.any { it.origin.startsWith("constraints[") }) {
                issues += SchemaIssue("relations 文書に検査（constraints）は直接定義できません（関係の宣言に書く語彙から展開されます）", "constraints")
            }
            if (phases != null) issues += SchemaIssue("relations 文書に phases は定義できません", "phases")
            relations.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach {
                issues += SchemaIssue("関係「$it」が重複しています", "relations")
            }
            // エンティティ型のレジストリ宣言（R23-01）: 型・アスペクト名の文書内重複を検出する
            entityTypes.groupBy { it.type }.filterValues { it.size > 1 }.keys.forEach {
                issues += SchemaIssue("エンティティ型「$it」の宣言が重複しています", "entities")
            }
            entityTypes.forEachIndexed { i, t ->
                t.aspects.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach {
                    issues += SchemaIssue("アスペクト「$it」が重複しています", "entities[$i].aspects")
                }
            }
            relations.forEachIndexed { i, r ->
                r.cardinality?.let { c ->
                    listOfNotNull(c.inMin, c.inMax, c.outMin, c.outMax).forEach { v ->
                        if (v < 0) issues += SchemaIssue("cardinality は 0 以上が必要です", "relations[$i].cardinality")
                    }
                    val inMin = c.inMin
                    val inMax = c.inMax
                    if (inMin != null && inMax != null && inMin > inMax) {
                        issues += SchemaIssue("inMin が inMax を超えています", "relations[$i].cardinality")
                    }
                    val outMin = c.outMin
                    val outMax = c.outMax
                    if (outMin != null && outMax != null && outMin > outMax) {
                        issues += SchemaIssue("outMin が outMax を超えています", "relations[$i].cardinality")
                    }
                }
            }
        } else {
            if (match.isNotBlank()) issues += SchemaIssue("library スキーマに match は定義できません", "match")
            // library は共有する定義の置き場——フィールドの定義（definitions）か、検査の定義・語彙の定義（functions / vocabulary）を持つ
            if (definitions.isEmpty() && checkLibrary.functions.isEmpty() && checkLibrary.words.isEmpty() && constraints.isEmpty() && checkers.isEmpty()) {
                issues += SchemaIssue("library スキーマには definitions・functions・vocabulary・constraints のいずれかが必要です", "definitions")
            }
            // library に直接書いた検査（constraints）は、特定のスキーマに属さない検査——ワークスペースのどの文書に
            // 定義された要素にも適用される（組込み定義体のエンティティの整合と同じ扱い。P29 Phase 6 第3段）
            if (phases != null) issues += SchemaIssue("library スキーマに phases は定義できません", "phases")
        }

        // エンティティ型宣言（R20-01）の妥当性: 型の重複・catch-all（idPattern なし）の多重宣言
        entities.groupBy { it.type }.filterValues { it.size > 1 }.keys.forEach {
            issues += SchemaIssue("エンティティ型「$it」が重複しています", "entity")
        }
        if (entities.count { it.idPattern == null } > 1) {
            issues += SchemaIssue("idPattern のないエンティティ型宣言は1つまでです（複数型は idPattern で判別します）", "entity")
        }

        // 制約語彙（R16-01）の妥当性: 型との適合・範囲の整合・正規表現の可否
        fun checkConstraints(f: FieldDef, path: String) {
            val stringy = f.type == FieldType.STRING || f.type == FieldType.TEXT
            if ((f.minLength != null || f.maxLength != null) && !stringy) {
                issues += SchemaIssue("minLength / maxLength は string / text 型でのみ使用できます", path)
            }
            // pattern は ID のフォーマット強制（UX-79）にも使う
            if (f.pattern != null && !stringy && f.type != FieldType.ID) {
                issues += SchemaIssue("pattern は string / text / id 型でのみ使用できます", path)
            }
            if ((f.min != null || f.max != null) && f.type != FieldType.INT && f.type != FieldType.NUMBER) {
                issues += SchemaIssue("min / max は int / number 型でのみ使用できます", path)
            }
            val minLength = f.minLength
            val maxLength = f.maxLength
            if (minLength != null && minLength < 0) issues += SchemaIssue("minLength は 0 以上が必要です", path)
            if (minLength != null && maxLength != null && minLength > maxLength) {
                issues += SchemaIssue("minLength が maxLength を超えています", path)
            }
            val min = f.min
            val max = f.max
            if (min != null && max != null && min > max) issues += SchemaIssue("min が max を超えています", path)
            f.pattern?.let { p ->
                runCatching { Regex(p) }.onFailure { issues += SchemaIssue("pattern が正規表現として不正です: $p", path) }
            }
        }

        fun check(field: FieldDef, path: String, stack: Set<DefinitionKey>) {
            field.requiredFrom?.let { from ->
                if (phases == null) issues += SchemaIssue("requiredFrom を使うには phases の宣言が必要です", path)
                else if (from !in phases.order) issues += SchemaIssue("requiredFrom「$from」は phases に含まれていません", path)
                if (field.required) issues += SchemaIssue("required と requiredFrom は併用できません", path)
            }
            field.ref?.let { ref ->
                val located = tree.definitionTarget(field)
                if (located == null) issues += SchemaIssue("未定義の参照です: \$$ref", path)
                else {
                    val (key, target) = located
                    if (key !in stack) check(target, "definitions.$key", stack + key)
                    val resolved = tree.resolve(field) ?: field
                    if (resolved.cases.isNotEmpty() && resolved.type != FieldType.ENUM) {
                        issues += SchemaIssue("casesはenum型でのみ使用できます", path)
                    }
                    if (resolved.display != null && resolved.type != FieldType.ARRAY && resolved.type != FieldType.MAP) {
                        issues += SchemaIssue("displayはarray / map型でのみ使用できます", path)
                    }
                    checkConstraints(resolved, path)
                }
                return
            }
            checkConstraints(field, path)
            if (field.cases.isNotEmpty() && field.type != FieldType.ENUM) {
                issues += SchemaIssue("casesはenum型でのみ使用できます", path)
            }
            if (field.display != null && field.type != FieldType.ARRAY && field.type != FieldType.MAP) {
                issues += SchemaIssue("displayはarray / map型でのみ使用できます", path)
            }
            when (field.type) {
                FieldType.OBJECT -> {
                    val keys = field.fields.mapNotNull { it.key }
                    keys.groupBy { it }.filterValues { it.size > 1 }.keys.forEach {
                        issues += SchemaIssue("キー「$it」が重複しています", path)
                    }
                    // 表示役割（R10-01）は object 内で各1つまで（複数あると表示解決が先頭のみを使い、意図が曖昧になる）
                    FieldRole.entries.forEach { role ->
                        val holders = field.fields.mapNotNull { ch -> ch.key?.takeIf { (tree.resolve(ch) ?: ch).role == role } }
                        if (holders.size > 1) {
                            issues += SchemaIssue("role「${role.id}」が複数のフィールド（${holders.joinToString(", ")}）に指定されています", path)
                        }
                    }
                    field.fields.forEachIndexed { index, child ->
                        if (child.key.isNullOrBlank()) issues += SchemaIssue("フィールドのキーが空です", "$path.fields[$index]")
                        check(child, "$path.${child.key ?: index}", stack)
                    }
                    // case 由来キーの衝突検査（共通フィールド・別の判別フィールドの cases と重複させない）
                    val commonKeys = keys.toSet()
                    val caseKeyOwner = mutableMapOf<String, String>()
                    field.fields.forEach { child ->
                        val eff = tree.resolve(child) ?: child
                        if (eff.type != FieldType.ENUM || eff.cases.isEmpty()) return@forEach
                        val discriminator = child.key ?: return@forEach
                        eff.cases.values.flatten().mapNotNull { it.key }.distinct().forEach { caseKey ->
                            if (caseKey in commonKeys) {
                                issues += SchemaIssue("caseのキー「$caseKey」が既存のフィールドと重複しています", "$path.$discriminator.cases")
                            } else {
                                val owner = caseKeyOwner.putIfAbsent(caseKey, discriminator)
                                if (owner != null && owner != discriminator) {
                                    issues += SchemaIssue("caseのキー「$caseKey」が「$owner」と「$discriminator」の両方のcasesに現れています", path)
                                }
                            }
                        }
                    }
                }
                FieldType.ARRAY -> {
                    val item = field.item
                    if (item == null) issues += SchemaIssue("arrayには要素定義が必要です", "$path.item")
                    else check(item, "$path.item", stack)
                }
                FieldType.MAP -> {
                    val value = field.value
                    if (value == null) issues += SchemaIssue("mapには値定義が必要です", "$path.value")
                    else check(value, "$path.value", stack)
                }
                FieldType.ENUM -> {
                    if (field.values.isEmpty()) issues += SchemaIssue("enumには選択肢が必要です", "$path.values")
                    field.values.groupBy { it }.filterValues { it.size > 1 }.keys.forEach {
                        issues += SchemaIssue("選択肢の値「$it」が重複しています", "$path.values")
                    }
                    field.cases.forEach { (value, caseFields) ->
                        if (value !in field.values) {
                            issues += SchemaIssue("casesの値「$value」は選択肢に含まれていません", "$path.cases")
                        }
                        caseFields.mapNotNull { it.key }.groupBy { it }.filterValues { it.size > 1 }.keys.forEach {
                            issues += SchemaIssue("キー「$it」が重複しています", "$path.cases.$value")
                        }
                        caseFields.forEachIndexed { index, caseField ->
                            if (caseField.key.isNullOrBlank()) issues += SchemaIssue("フィールドのキーが空です", "$path.cases.$value[$index]")
                            check(caseField, "$path.cases.$value.${caseField.key ?: index}", stack)
                        }
                    }
                }
                FieldType.REF -> {
                    // 属性付き参照（R20-03）: 属性は「IDを持たない注記」。入れ子の ref / id は不許可（走査境界。§4.30）
                    if (field.fields.isNotEmpty()) {
                        field.fields.mapNotNull { it.key }.groupBy { it }.filterValues { it.size > 1 }.keys.forEach {
                            issues += SchemaIssue("キー「$it」が重複しています", path)
                        }
                        fun checkNoRefIn(f0: FieldDef, p: String, seen: Set<DefinitionKey>) {
                            val located = tree.definitionTarget(f0)
                            if (located != null && located.first in seen) return
                            val nextSeen = if (located != null) seen + located.first else seen
                            val f = tree.resolve(f0) ?: f0
                            if (f.type == FieldType.REF || f.type == FieldType.ID) {
                                issues += SchemaIssue("属性付き参照の属性に ref / id 型は使えません（関係の詳細化は 1:N 具体化を使う）", p)
                                return
                            }
                            f.fields.forEach { checkNoRefIn(it, "$p.${it.key}", nextSeen) }
                            f.item?.let { checkNoRefIn(it, "$p.item", nextSeen) }
                            f.value?.let { checkNoRefIn(it, "$p.value", nextSeen) }
                            f.cases.values.flatten().forEach { checkNoRefIn(it, "$p.cases", nextSeen) }
                        }
                        field.fields.forEachIndexed { index, attr ->
                            if (attr.key.isNullOrBlank()) issues += SchemaIssue("属性フィールドのキーが空です", "$path.fields[$index]")
                            if (attr.key == "to") issues += SchemaIssue("「to」は参照値の予約キーのため属性名に使えません", "$path.fields[$index]")
                            checkNoRefIn(attr, "$path.${attr.key ?: index}", stack)
                            check(attr, "$path.${attr.key ?: index}", stack)
                        }
                    }
                }
                else -> Unit
            }
        }
        if (kind == SchemaKind.DOCUMENT) check(root, "root", emptySet())
        definitions.forEach { (name, field) ->
            check(field, "definitions.$name", setOf(DefinitionKey(id, name)))
        }
        // activeFrom の静的検査は全検証語彙で共通（R29-03。従来は coverage / consistency のみだった）
        fun checkActiveFrom(activeFrom: String?, where: String) {
            activeFrom?.let { from ->
                if (phases == null) issues += SchemaIssue("activeFrom を使うには phases の宣言が必要です", where)
                else if (from !in phases.order) issues += SchemaIssue("activeFrom「$from」は phases に含まれていません", where)
            }
        }
        // constraints と checkers の各検査の由来（それぞれ、同じ並び）
        val constraintOrigins = expandedChecks.filter { !it.usesChecker }.map { it.origin }
        val checkerOrigins = expandedChecks.filter { it.usesChecker }.map { it.origin }
        constraints.forEachIndexed { i, rule -> checkActiveFrom(rule.activeFrom, constraintOrigins.getOrNull(i) ?: "constraints[$i]") }
        checkers.forEachIndexed { i, rule -> checkActiveFrom(rule.activeFrom, checkerOrigins.getOrNull(i) ?: "constraints[$i]") }
        phases?.let { p ->
            if (p.order.isEmpty()) issues += SchemaIssue("phases には1つ以上のフェーズが必要です", "phases")
            if (p.key.isBlank()) issues += SchemaIssue("phases のフェーズ申告キーが空です", "phases.key")
            p.order.groupBy { it }.filterValues { it.size > 1 }.keys.forEach {
                issues += SchemaIssue("フェーズ「$it」が重複しています", "phases.order")
            }
        }
        issues.distinct()
    }
}
