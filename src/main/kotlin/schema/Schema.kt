package schema

import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path
import model.YNode
import model.parseYPath
import model.scalarText

/**
 * このスキーマが定義する ID への充足要件（トレーサビリティのカバレッジルール）。
 * - 参照ベース（`requiredBy`）: 「idPattern に一致する ID は、条件を満たす参照を minCount 件以上受けること」
 * - 出現ベース（`detailIn`）: 「idPattern に一致する ID は、指定スキーマの文書に minCount 件以上出現すること」
 *   （段階的詳細化の詳細文書の存在検証。R7-02）
 */
data class CoverageRule(
    val id: String? = null,                       // ルールの識別子（表示用）
    val description: String? = null,              // 人間向けの説明
    val idPattern: String? = null,                // 対象 ID の glob（null = このスキーマの全 ID）
    val fromSchemaIds: List<String> = emptyList(),// 参照元文書のスキーマIDを限定（空 = 制限なし。参照ベースのみ）
    val relation: String? = null,                 // 参照の関係種別を限定（null = 制限なし。参照ベースのみ）
    val detailSchemaIds: List<String> = emptyList(),// 空でなければ出現ベース: このスキーマの文書への出現を数える
    val minCount: Int = 1,                        // 必要な参照数（出現ベースでは出現数）
    val level: CoverageLevel = CoverageLevel.WARNING,
    val activeFrom: String? = null,               // IDを定義する文書がこのフェーズに達してから判定（null = 常時）
) {
    /** true = 出現ベース（詳細文書の存在検証）、false = 参照ベース（被参照数）。 */
    val isOccurrence: Boolean get() = detailSchemaIds.isNotEmpty()
}

/**
 * 型を決める写像（R11-03）: 導出元要素の `path` の値を `map`（値辞書）で FieldType の id に変換する。
 * 語彙（path / map / default）は skill の列マッピング（SK-02）と揃える。
 */
data class DeriveTypeRule(
    val path: String,                        // 型を決める値のパス（導出元要素からのドット区切り）
    val map: Map<String, String> = emptyMap(), // 値辞書（例 varchar → string）
    val default: String = FieldType.STRING.id, // 辞書に無い・未記入のときの型
)

/**
 * 派生スキーマの宣言（R11-03）: 「テーブル定義書のような実質スキーマの文書」から検証スキーマを
 * ロード時にコンパイルする写像。宣言したスキーマ自身は文書にマッチせず、テンプレートに徹する。
 */
data class DeriveDef(
    val from: String,                 // 導出元文書の glob（予約ディレクトリは不可）
    val each: String? = null,         // 1文書内の複数要素から導出する場合の配列パス（null = 文書ルート）
    val idPattern: String,            // 導出スキーマのID（{パス} が導出元要素の値で置換される）
    val namePattern: String? = null,  // 導出スキーマの表示名（同上）
    val matchPattern: String,         // 導出スキーマの適用先 glob（同上）
    val fieldsPath: String,           // フィールド一覧にする配列のパス（導出元要素から相対）
    val keyPath: String,              // 各要素のこの値 → field.key
    val labelPath: String? = null,    // 任意 → field.label
    val descriptionPath: String? = null, // 任意 → field.description
    val requiredPath: String? = null, // 任意 → field.required（真偽値）
    val typeRule: DeriveTypeRule? = null, // 任意 → field.type（省略時は string）
) {
    private val fromRegex: Regex by lazy { globToRegex(from) }

    /** ワークスペース相対パスが導出元文書か（この文書の変更はスキーマ再構築の契機になる）。 */
    fun matchesSource(relPath: String): Boolean = fromRegex.matches(relPath)
}

/**
 * スキーマ 1 件。`match` の glob にマッチするファイルへ適用される。
 */
data class SchemaDef(
    val id: String,
    val name: String,
    val description: String? = null,
    val match: String,
    val root: FieldDef,
    val definitions: Map<String, FieldDef> = emptyMap(),
    val coverage: List<CoverageRule> = emptyList(),
    /** 整合ルール（R16-03a）: このスキーマの文書のID要素の値と、導出値との一致要件。 */
    val consistency: List<ConsistencyRule> = emptyList(),
    /** パターン制約（R22-03。ARCHITECTURE §4.32）: 起点エンティティごとのパターンの存在／不在要件。 */
    val constraints: List<ConstraintRule> = emptyList(),
    val phases: PhasesDef? = null,
    /** エンティティ型の宣言（R20-01。ARCHITECTURE §4.30）。空 = 宣言なし（従来どおり）。 */
    val entities: List<EntityDecl> = emptyList(),
    /** 関係レジストリの内容（kind: relations のみ。R20-02）。 */
    val relations: List<RelationDef> = emptyList(),
    /** エンティティ型のレジストリ宣言（kind: relations のみ。表示名・アスペクト順序。R23-01。§4.33）。 */
    val entityTypes: List<EntityTypeDef> = emptyList(),
    /** 強制スイッチ（kind: relations のみ。R21-01。ARCHITECTURE §4.31）。 */
    val enforcement: EnforcementDef? = null,
    val source: Path,
    val kind: SchemaKind = SchemaKind.DOCUMENT,
    /** 派生スキーマの宣言（R11-03）。非 null のスキーマはテンプレートであり、自身は文書にマッチしない。 */
    val derive: DeriveDef? = null,
    /** SchemaLoader が全モジュールを読み込んだ後にリンクする。単体生成時はローカル定義だけで動作する。 */
    internal val definitionCatalog: SchemaCatalog = SchemaCatalog.local(id, definitions),
) {
    private val regex: Regex by lazy { globToRegex(match) }

    /** 構造コア（yaml-core）。$ref解決・cases合成・フェーズ必須・パス解決はここへ委譲する（YM-004b）。 */
    val structural: StructuralSchema by lazy { StructuralSchema(id, root, definitions, phases, definitionCatalog) }

    /** ワークスペース相対パス（区切りは '/'）がこのスキーマの対象か。derive 宣言スキーマはマッチしない。 */
    fun matches(relPath: String): Boolean =
        kind == SchemaKind.DOCUMENT && derive == null && match.isNotBlank() && regex.matches(relPath)

    /** `$ref` の参照先。非修飾名はフィールドの定義元、修飾名は `module#name` から引く。 */
    internal fun definitionTarget(field: FieldDef): Pair<DefinitionKey, FieldDef>? = structural.definitionTarget(field)

    internal fun withDefinitionCatalog(catalog: SchemaCatalog): SchemaDef = copy(definitionCatalog = catalog)

    /**
     * フィールドが参照する named definition の修飾名（`module#name`）。`$ref` でなければ null。
     * ビュー定義の一級参照・リント（R14-02）が「このフィールドはどの定義か」を外部から判定するのに使う。
     */
    fun definitionRefOf(field: FieldDef?): String? = field?.let { f -> definitionTarget(f)?.first?.toString() }

    /** $ref を1段ずつ解決する。循環はデータ走査側が有限なので許容する。実装は yaml-core（YM-004b）。 */
    fun resolve(field: FieldDef?): FieldDef? = structural.resolve(field)
}

/** 文書ルートから現在フェーズの申告値を読む（phases 未宣言なら null）。 */
fun SchemaDef.currentPhaseValue(root: YNode?): String? = structural.currentPhaseValue(root)

/** 現在フェーズの順序位置。未申告・未知の値は先頭フェーズ（最も緩い判定）。phases 未宣言なら null。 */
fun SchemaDef.currentPhaseIndex(root: YNode?): Int? = structural.currentPhaseIndex(root)

/**
 * フェーズを考慮した実効 required。requiredFrom は「そのフェーズ以降で必須」（累積的）。
 * 検証・進捗計算・フォーム表示はこの判定を共有する（ARCHITECTURE §4.15）。
 */
fun SchemaDef.requiredAt(field: FieldDef, phaseIndex: Int?): Boolean = structural.requiredAt(field, phaseIndex)

/**
 * object の子フィールド一覧に、データノードの enum 値に応じた case フィールドを合成して返す。
 * object のフィールドを列挙する側（検証・フォーム・索引・AI変換・初期値生成）は必ずこの関数を通すこと。
 * 実装は yaml-core の StructuralSchema（YM-004b）。
 */
fun SchemaDef.effectiveFields(fields: List<FieldDef>, node: YNode.Map?): EffectiveFields =
    structural.effectiveFields(fields, node)

/**
 * データパスに対応するフィールド定義をスキーマを辿って解決する（object の子は effectiveFields＝
 * enum の cases 合成、$ref は resolve を通す）。旧 core/Views.fieldAt の移設先（R23-01。ARCHITECTURE §4.33）。
 * 実装は yaml-core の StructuralSchema（YM-004b）。`Views.fieldAt` は互換のためこの関数へ委譲する。
 */
fun SchemaDef.fieldAt(root: YNode, path: model.YPath): FieldDef? = structural.fieldAt(root, path)

data class SchemaIssue(val message: String, val path: String)

/** スキーマ定義自体を検証するメタスキーマ検証。 */
fun SchemaDef.validateDefinition(): List<SchemaIssue> {
    val issues = mutableListOf<SchemaIssue>()
    if (name.isBlank()) issues += SchemaIssue("スキーマ名が空です", "name")
    if (id.isBlank()) issues += SchemaIssue("スキーマIDが空です", "id")
    val deriveDef = derive
    if (kind == SchemaKind.DOCUMENT && deriveDef != null) {
        // 派生スキーマの宣言（R11-03）: 自身は文書にマッチしないため match は持たず、root は共通フィールドの object
        runCatching { globToRegex(deriveDef.from) }
            .onFailure { issues += SchemaIssue("derive.from が不正です: ${it.message}", "derive.from") }
        if (resolve(root)?.type != FieldType.OBJECT) {
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
        if (coverage.isNotEmpty()) issues += SchemaIssue("relations 文書に coverage は定義できません", "coverage")
        if (consistency.isNotEmpty()) issues += SchemaIssue("relations 文書に consistency は定義できません", "consistency")
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
        if (definitions.isEmpty()) issues += SchemaIssue("library スキーマには1つ以上の definitions が必要です", "definitions")
        if (coverage.isNotEmpty()) issues += SchemaIssue("library スキーマに coverage は定義できません", "coverage")
        if (consistency.isNotEmpty()) issues += SchemaIssue("library スキーマに consistency は定義できません", "consistency")
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
            val located = definitionTarget(field)
            if (located == null) issues += SchemaIssue("未定義の参照です: \$$ref", path)
            else {
                val (key, target) = located
                if (key !in stack) check(target, "definitions.$key", stack + key)
                val resolved = resolve(field) ?: field
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
                    val holders = field.fields.mapNotNull { ch -> ch.key?.takeIf { (resolve(ch) ?: ch).role == role } }
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
                    val eff = resolve(child) ?: child
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
                        val located = definitionTarget(f0)
                        if (located != null && located.first in seen) return
                        val nextSeen = if (located != null) seen + located.first else seen
                        val f = resolve(f0) ?: f0
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
    coverage.forEachIndexed { i, rule ->
        if (rule.minCount < 1) issues += SchemaIssue("minCountは1以上が必要です", "coverage[$i]")
        if (rule.idPattern != null && rule.idPattern.isBlank()) issues += SchemaIssue("idPatternが空です", "coverage[$i]")
        rule.activeFrom?.let { from ->
            if (phases == null) issues += SchemaIssue("activeFrom を使うには phases の宣言が必要です", "coverage[$i]")
            else if (from !in phases.order) issues += SchemaIssue("activeFrom「$from」は phases に含まれていません", "coverage[$i]")
        }
    }
    consistency.forEachIndexed { i, rule ->
        if (rule.path.isEmpty()) issues += SchemaIssue("consistency[$i].path が空です", "consistency[$i]")
        if (rule.idPattern?.isBlank() == true) issues += SchemaIssue("idPatternが空です", "consistency[$i]")
        rule.activeFrom?.let { from ->
            if (phases == null) issues += SchemaIssue("activeFrom を使うには phases の宣言が必要です", "consistency[$i]")
            else if (from !in phases.order) issues += SchemaIssue("activeFrom「$from」は phases に含まれていません", "consistency[$i]")
        }
    }
    phases?.let { p ->
        if (p.order.isEmpty()) issues += SchemaIssue("phases には1つ以上のフェーズが必要です", "phases")
        if (p.key.isBlank()) issues += SchemaIssue("phases のフェーズ申告キーが空です", "phases.key")
        p.order.groupBy { it }.filterValues { it.size > 1 }.keys.forEach {
            issues += SchemaIssue("フェーズ「$it」が重複しています", "phases.order")
        }
    }
    return issues.distinct()
}

/** プレビューや新規作成に使うサンプルYAMLツリーを生成する。 */
fun SchemaDef.generateSample(): YNode {
    fun create(original: FieldDef, depth: Int, stack: Set<DefinitionKey>): YNode {
        if (depth > 16) return YNode.Map()
        val reference = definitionTarget(original)?.first
        if (reference != null && reference in stack) return YNode.Map()
        val nextStack = if (reference == null) stack else stack + reference
        val field = resolve(original) ?: original
        if (field.default != null) return YamlIO.fromAny(field.default)
        return when (field.type) {
            FieldType.OBJECT -> {
                val entries = LinkedHashMap<String, YNode>().apply {
                    field.fields.forEach { child -> child.key?.let { put(it, create(child, depth + 1, nextStack)) } }
                }
                // enum の生成値（default または先頭の選択肢）に応じた case フィールドを実効順で合成する
                val ordered = effectiveFields(field.fields, YNode.Map(entries)).fields
                YNode.Map(LinkedHashMap<String, YNode>().apply {
                    ordered.forEach { child ->
                        val key = child.key ?: return@forEach
                        put(key, entries[key] ?: create(child, depth + 1, nextStack))
                    }
                })
            }
            FieldType.ARRAY -> YNode.Seq(
                if (field.item?.let { item -> definitionTarget(item)?.first?.let { it in nextStack } } == true) mutableListOf()
                else field.item?.let { mutableListOf(create(it, depth + 1, nextStack)) } ?: mutableListOf()
            )
            FieldType.MAP -> YNode.Map()
            FieldType.BOOLEAN -> YNode.Scalar(false)
            FieldType.INT, FieldType.NUMBER -> YNode.Scalar(0)
            FieldType.ENUM -> YNode.Scalar(field.values.firstOrNull() ?: "")
            FieldType.DATE -> YNode.Scalar("2026-01-01")
            FieldType.ID -> YNode.Scalar("SAMPLE-001")
            // 必須スカラーは空文字にせず型別の例値を入れる（UX-28。空文字は必須エラーになりプレビューが赤くなる）
            FieldType.REF -> YNode.Scalar(if (field.required || field.requiredFrom != null) "SAMPLE-001" else "")
            FieldType.TEXT, FieldType.STRING ->
                YNode.Scalar(if (field.required || field.requiredFrom != null) "サンプル" else "")
            FieldType.ANY -> YNode.Scalar("")
        }
    }
    return create(root, 0, emptySet())
}

/**
 * ワークスペース直下の `_schemas/` からスキーマ定義（*.yaml / *.yml）を読み込む。
 */
object SchemaLoader {

    const val SCHEMA_DIR = "_schemas"

    /** エージェント実行の予約ディレクトリ（セルフホスティング。ROADMAP §4.2）。 */
    const val AGENTS_DIR = "_agents"
    const val ACTIVITIES_DIR = "_activities"
    const val RUNS_DIR = "_runs"
    const val EVALS_DIR = "_evals"
    const val SKILLS_DIR = "_skills"
    const val VIEWS_DIR = "_views"

    data class LoadResult(
        val schemas: List<SchemaDef>,
        val errors: List<String>,
        /** 関係レジストリ（R20-02）: `kind: relations` 文書から集約した name → RelationDef。 */
        val relations: Map<String, RelationDef> = emptyMap(),
        /** 強制スイッチ（R21-01）: レジストリ文書の enforcement 節から集約（未宣言キーは null）。 */
        val enforcement: EnforcementDef = EnforcementDef(),
        /** エンティティ型レジストリ（R23-01。§4.33）: `entities:` 節から集約した type → EntityTypeDef。 */
        val entityTypes: Map<String, EntityTypeDef> = emptyMap(),
    )

    fun loadAll(workspace: Path): List<SchemaDef> = loadAllWithErrors(workspace).schemas

    fun loadAllWithErrors(workspace: Path, layout: WorkspaceLayout = WorkspaceLayout.of(workspace)): LoadResult {
        val dir = workspace.resolve(layout.schemasDir)
        if (!Files.isDirectory(dir)) return LoadResult(emptyList(), emptyList())
        val files = Files.list(dir).use { stream ->
            stream.filter { p ->
                val n = p.fileName.toString()
                Files.isRegularFile(p) && (n.endsWith(".yaml") || n.endsWith(".yml"))
            }.toList()
        }.sortedBy { it.fileName.toString() }
        val schemas = mutableListOf<SchemaDef>()
        val errors = mutableListOf<String>()
        files.forEach { f ->
            runCatching { parseSchemaFile(f) }.onSuccess { schemas += it }
                .onFailure { errors += "${f.fileName}: ${it.message ?: it::class.simpleName}" }
        }
        schemas.groupBy { it.id }.filterValues { it.size > 1 }.forEach { (id, duplicates) ->
            errors += "スキーマID「$id」が重複しています（${duplicates.joinToString(", ") { it.source.fileName.toString() }}）"
        }
        // 組込みスキーマ（agents / activities 等の定義ゾーン用）は利用者スキーマの後ろに置く（先勝ちのため利用者定義が優先）
        val all = schemas + BuiltinSchemas.forLayout(layout).filter { builtin -> schemas.none { it.id == builtin.id } }
        val catalog = SchemaCatalog.linked(all.map { it.id to it.definitions })
        val linked = all.map { it.withDefinitionCatalog(catalog) }
        linked.take(schemas.size).forEach { schema ->
            schema.validateDefinition().forEach { issue ->
                errors += "${schema.source.fileName} [${issue.path}]: ${issue.message}"
            }
        }
        // 第3段階「導出」（R11-03）: derive 宣言を導出元文書から検証スキーマへコンパイルし、
        // 宣言スキーマの直後に挿入する（match の先勝ち規則を宣言位置で制御できるようにする）
        val derived = DerivedSchemas.compile(linked, workspace, layout)
        errors += derived.errors
        val result = if (derived.byDeclaring.isEmpty()) linked
        else linked.flatMap { s -> listOf(s) + derived.byDeclaring[s.id].orEmpty() }
        // 関係レジストリ（R20-02）: kind: relations の文書から集約する。文書内の重複は
        // validateDefinition が報告するため、ここではレジストリ間の name 重複だけをエラーにする
        val registry = LinkedHashMap<String, RelationDef>()
        linked.filter { it.kind == SchemaKind.RELATIONS }.forEach { doc ->
            doc.relations.distinctBy { it.name }.forEach { r ->
                if (registry.putIfAbsent(r.name, r) != null) {
                    errors += "${doc.source.fileName}: 関係「${r.name}」が複数のレジストリで宣言されています"
                }
            }
        }
        // 未宣言 relation の lint（R20-02）: レジストリが1つでもあるときだけ判定する（非破壊。§4.30）
        if (registry.isNotEmpty()) {
            linked.take(schemas.size).filter { it.kind == SchemaKind.DOCUMENT }.forEach { s ->
                collectRelationNames(s).filter { it !in registry }.sorted().forEach { name ->
                    errors += "${s.source.fileName}: 関係「$name」は関係レジストリに宣言されていません"
                }
            }
        }
        // エンティティ型レジストリ（R23-01。§4.33）: entities 節を集約する。文書内の重複は
        // validateDefinition が報告するため、ここではレジストリ間の type 重複だけをエラーにする
        val typeRegistry = LinkedHashMap<String, EntityTypeDef>()
        linked.filter { it.kind == SchemaKind.RELATIONS }.forEach { doc ->
            doc.entityTypes.distinctBy { it.type }.forEach { t ->
                if (typeRegistry.putIfAbsent(t.type, t) != null) {
                    errors += "${doc.source.fileName}: エンティティ型「${t.type}」が複数のレジストリで宣言されています"
                }
            }
        }
        // 強制スイッチ（R21-01。§4.31）: レジストリ文書の enforcement 節を集約する。
        // 同一キーの重複宣言は relation name 重複と同じ流儀でロードエラー
        var enforcement = EnforcementDef()
        linked.filter { it.kind == SchemaKind.RELATIONS }.forEach { doc ->
            val e = doc.enforcement ?: return@forEach
            e.undeclaredRelation?.let { v ->
                if (enforcement.undeclaredRelation != null) {
                    errors += "${doc.source.fileName}: enforcement.undeclaredRelation が複数のレジストリで宣言されています"
                } else enforcement = enforcement.copy(undeclaredRelation = v)
            }
            e.definingUniqueness?.let { v ->
                if (enforcement.definingUniqueness != null) {
                    errors += "${doc.source.fileName}: enforcement.definingUniqueness が複数のレジストリで宣言されています"
                } else enforcement = enforcement.copy(definingUniqueness = v)
            }
        }
        return LoadResult(result, errors, registry, enforcement, typeRegistry)
    }

    /** スキーマの ref フィールドが宣言する関係種別（relation）を収集する（未宣言 relation の lint 用）。 */
    private fun collectRelationNames(schema: SchemaDef): Set<String> {
        val names = mutableSetOf<String>()
        val seen = mutableSetOf<DefinitionKey>()
        fun walk(f0: FieldDef) {
            schema.definitionTarget(f0)?.let { (key, _) -> if (!seen.add(key)) return }
            val f = schema.resolve(f0) ?: f0
            if (f.type == FieldType.REF) f.relation?.let(names::add)
            f.fields.forEach(::walk)
            f.item?.let(::walk)
            f.value?.let(::walk)
            f.cases.values.flatten().forEach(::walk)
        }
        walk(schema.root)
        schema.definitions.values.forEach(::walk)
        return names
    }

    fun parseSchemaText(text: String, source: Path, availableSchemas: List<SchemaDef> = emptyList()): SchemaDef {
        val raw = YamlIO.parseToRaw(text) as? Map<*, *> ?: error("スキーマのルートはobjectである必要があります")
        val fallbackId = source.fileName.toString().substringBeforeLast('.')
        val id = raw["id"]?.toString() ?: fallbackId
        val name = raw["name"]?.toString() ?: id
        val description = raw["description"]?.toString()
        val kind = SchemaKind.parse(raw["kind"]?.toString())
        val derive = parseDerive(raw["derive"])
        if (derive != null) {
            require(kind == SchemaKind.DOCUMENT) { "derive は ${kind.id} スキーマには定義できません" }
            require(raw["match"] == null) { "derive 宣言スキーマに match は定義できません（適用先は derive.match で決まります）" }
        }
        // エンティティ型宣言（R20-01）と関係レジストリ（R20-02）。ARCHITECTURE §4.30
        val entities = parseEntities(raw["entity"])
        require(entities.isEmpty() || kind == SchemaKind.DOCUMENT) { "entity は kind: ${kind.id} には定義できません" }
        require(kind == SchemaKind.RELATIONS || raw["relations"] == null) { "relations は kind: relations の文書でのみ定義できます" }
        val relations = if (kind == SchemaKind.RELATIONS) parseRelations(raw["relations"]) else emptyList()
        require(kind == SchemaKind.RELATIONS || raw["entities"] == null) { "entities は kind: relations の文書でのみ定義できます" }
        val entityTypes = if (kind == SchemaKind.RELATIONS) parseEntityTypes(raw["entities"]) else emptyList()
        require(kind == SchemaKind.RELATIONS || raw["enforcement"] == null) { "enforcement は kind: relations の文書でのみ定義できます" }
        val enforcement = if (kind == SchemaKind.RELATIONS) parseEnforcement(raw["enforcement"]) else null
        val match = raw["match"]?.toString()
            ?: if (kind == SchemaKind.DOCUMENT && derive == null) "**/*.{yaml,yml}" else ""
        // 構造部分（definitions・root・フィールド木）のパースは yaml-core の StructuralSchemaParser（YM-004c）
        val definitions = StructuralSchemaParser.parseDefinitions(raw["definitions"], id)
        val root = StructuralSchemaParser.parseRoot(raw["root"], kind, id, allowMissingRoot = derive != null)
        val coverage = parseCoverage(raw["coverage"])
        val consistency = parseConsistency(raw["consistency"])
        // パターン制約（R22-03。ARCHITECTURE §4.32）: パースはクエリコア（schema/Query.kt）と共用
        val constraints = (raw["constraints"])?.let { rawList ->
            val list = rawList as? List<*> ?: error("constraintsはリストである必要があります")
            list.mapIndexed { i, entry -> ConstraintRule.parse(entry, "constraints[$i]") }
        } ?: emptyList()
        val phases = StructuralSchemaParser.parsePhases(raw["phases"])
        val parsed = SchemaDef(
            id = id,
            name = name,
            description = description,
            match = match,
            root = root,
            definitions = definitions,
            coverage = coverage,
            consistency = consistency,
            constraints = constraints,
            phases = phases,
            entities = entities,
            relations = relations,
            entityTypes = entityTypes,
            enforcement = enforcement,
            source = source,
            kind = kind,
            derive = derive,
        )
        if (availableSchemas.isEmpty()) return parsed
        val modules = availableSchemas.filter { it.id != parsed.id }.map { it.id to it.definitions } +
            (parsed.id to parsed.definitions)
        val catalog = SchemaCatalog.linked(modules)
        return parsed.withDefinitionCatalog(catalog)
    }

    /** 派生スキーマ宣言（R11-03）のパース。写像の語彙（path / map / default）は SK-02 と揃える。 */
    private fun parseDerive(raw: Any?): DeriveDef? {
        if (raw == null) return null
        val m = raw as? Map<*, *> ?: error("derive は object である必要があります")
        val from = m["from"]?.toString()?.takeIf { it.isNotBlank() } ?: error("derive.from（導出元文書の glob）が必要です")
        val fromNorm = from.replace('\\', '/').trimStart('/')
        require(!fromNorm.startsWith("_") && !fromNorm.startsWith(".")) { "derive.from に予約ディレクトリ・隠しディレクトリは指定できません: $from" }
        val fieldsMap = m["fields"] as? Map<*, *> ?: error("derive.fields（フィールドの写像）が必要です")
        val typeRule = when (val rawType = fieldsMap["type"]) {
            null -> null
            is Map<*, *> -> {
                val path = rawType["path"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: error("derive.fields.type には path（型を決める値のパス）が必要です")
                val dict = (rawType["map"] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v.toString() } ?: emptyMap()
                val default = rawType["default"]?.toString() ?: FieldType.STRING.id
                (dict.values + default).forEach { typeId ->
                    require(FieldType.entries.any { it.id == typeId }) { "derive.fields.type の型「$typeId」は未知の型です" }
                }
                DeriveTypeRule(path = path, map = dict, default = default)
            }
            else -> error("derive.fields.type は object（path / map / default）である必要があります")
        }
        return DeriveDef(
            from = from,
            each = m["each"]?.toString()?.takeIf { it.isNotBlank() },
            idPattern = m["id"]?.toString()?.takeIf { it.isNotBlank() } ?: error("derive.id（導出スキーマのIDパターン）が必要です"),
            namePattern = m["name"]?.toString(),
            matchPattern = m["match"]?.toString()?.takeIf { it.isNotBlank() } ?: error("derive.match（導出スキーマの適用先 glob）が必要です"),
            fieldsPath = fieldsMap["source"]?.toString()?.takeIf { it.isNotBlank() }
                ?: error("derive.fields.source（フィールドにする配列のパス）が必要です"),
            keyPath = fieldsMap["key"]?.toString()?.takeIf { it.isNotBlank() } ?: error("derive.fields.key（キーの値パス）が必要です"),
            labelPath = fieldsMap["label"]?.toString(),
            descriptionPath = fieldsMap["description"]?.toString(),
            requiredPath = fieldsMap["required"]?.toString(),
            typeRule = typeRule,
        )
    }

    /** エンティティ型宣言（R20-01 / R23-01）のパース。単一 object または複数型のリスト（idPattern で判別）。 */
    private fun parseEntities(raw: Any?): List<EntityDecl> {
        if (raw == null) return emptyList()
        fun one(m: Any?, where: String): EntityDecl {
            val map = m as? Map<*, *> ?: error("$where は object（type / idPattern / defines / aspect / owns）である必要があります")
            val owns = when (val o = map["owns"]) {
                null -> null
                is List<*> -> o.map { it.toString() }
                else -> error("$where.owns は所有フィールドキーのリストである必要があります")
            }
            return EntityDecl(
                type = map["type"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$where.type（エンティティ型名）が必要です"),
                idPattern = map["idPattern"]?.toString()?.takeIf { it.isNotBlank() },
                defines = (map["defines"] as? Boolean) ?: false,
                aspect = map["aspect"]?.toString()?.takeIf { it.isNotBlank() },
                owns = owns,
            )
        }
        return when (raw) {
            is List<*> -> raw.mapIndexed { i, e -> one(e, "entity[$i]") }
            else -> listOf(one(raw, "entity"))
        }
    }

    /** エンティティ型のレジストリ宣言（R23-01。ARCHITECTURE §4.33）のパース。`entities:` 節。 */
    private fun parseEntityTypes(raw: Any?): List<EntityTypeDef> {
        if (raw == null) return emptyList()
        val list = raw as? List<*> ?: error("entities はリストである必要があります")
        return list.mapIndexed { i, e ->
            val m = e as? Map<*, *> ?: error("entities[$i] は object（type / label / description / aspects）である必要があります")
            val aspects = when (val a = m["aspects"]) {
                null -> emptyList()
                is List<*> -> a.mapIndexed { j, entry ->
                    val am = entry as? Map<*, *> ?: error("entities[$i].aspects[$j] は object（name / label）である必要があります")
                    AspectDecl(
                        name = am["name"]?.toString()?.takeIf { it.isNotBlank() }
                            ?: error("entities[$i].aspects[$j].name（アスペクト名）が必要です"),
                        label = am["label"]?.toString(),
                    )
                }
                else -> error("entities[$i].aspects はリストである必要があります")
            }
            EntityTypeDef(
                type = m["type"]?.toString()?.takeIf { it.isNotBlank() } ?: error("entities[$i].type（エンティティ型名）が必要です"),
                label = m["label"]?.toString(),
                description = m["description"]?.toString(),
                aspects = aspects,
            )
        }
    }

    /** 関係レジストリ（R20-02）のパース。cardinality / acyclic は P20 では宣言のみ（強制は P21）。 */
    private fun parseRelations(raw: Any?): List<RelationDef> {
        if (raw == null) return emptyList()
        val list = raw as? List<*> ?: error("relations はリストである必要があります")
        fun strings(v: Any?): List<String> = when (v) {
            is List<*> -> v.map { it.toString() }
            null -> emptyList()
            else -> listOf(v.toString())
        }
        return list.mapIndexed { i, e ->
            val m = e as? Map<*, *> ?: error("relations[$i] は object である必要があります")
            RelationDef(
                name = m["name"]?.toString()?.takeIf { it.isNotBlank() } ?: error("relations[$i].name（関係名）が必要です"),
                label = m["label"]?.toString(),
                inverseLabel = m["inverseLabel"]?.toString(),
                source = strings(m["source"]),
                target = strings(m["target"]),
                cardinality = (m["cardinality"] as? Map<*, *>)?.let { c ->
                    CardinalityDef(
                        inMin = (c["inMin"] as? Number)?.toInt(),
                        inMax = (c["inMax"] as? Number)?.toInt(),
                        outMin = (c["outMin"] as? Number)?.toInt(),
                        outMax = (c["outMax"] as? Number)?.toInt(),
                    )
                },
                acyclic = (m["acyclic"] as? Boolean) ?: false,
                level = m["level"]?.toString()?.let { v ->
                    CoverageLevel.entries.firstOrNull { it.id == v }
                        ?: error("relations[$i].level は warning / error のいずれかである必要があります: $v")
                } ?: CoverageLevel.WARNING,
                description = m["description"]?.toString(),
            )
        }
    }

    /** 強制スイッチ（R21-01。ARCHITECTURE §4.31）のパース。各キーは3値（未宣言＝null / warning / error）。 */
    private fun parseEnforcement(raw: Any?): EnforcementDef? {
        if (raw == null) return null
        val m = raw as? Map<*, *> ?: error("enforcement は object である必要があります")
        val known = setOf("undeclaredRelation", "definingUniqueness")
        m.keys.map { it.toString() }.firstOrNull { it !in known }?.let {
            error("enforcement の未知のキーです: $it（使用可能: ${known.joinToString(" / ")}）")
        }
        fun level(key: String): CoverageLevel? = m[key]?.toString()?.let { v ->
            CoverageLevel.entries.firstOrNull { it.id == v }
                ?: error("enforcement.$key は warning / error のいずれかである必要があります: $v")
        }
        return EnforcementDef(
            undeclaredRelation = level("undeclaredRelation"),
            definingUniqueness = level("definingUniqueness"),
        )
    }

    private fun parseCoverage(raw: Any?): List<CoverageRule> {
        if (raw == null) return emptyList()
        val list = raw as? List<*> ?: error("coverageはリストである必要があります")
        fun schemaIds(raw: Any?): List<String> = when (raw) {
            is List<*> -> raw.map { it.toString() }
            null -> emptyList()
            else -> listOf(raw.toString())
        }
        return list.mapIndexed { i, entry ->
            val m = entry as? Map<*, *> ?: error("coverage[$i]はobjectである必要があります")
            val requiredBy = m["requiredBy"] as? Map<*, *>
            val detailIn = m["detailIn"] as? Map<*, *>
            if (requiredBy != null && detailIn != null) {
                error("coverage[$i]: requiredBy（参照ベース）と detailIn（出現ベース）は併用できません")
            }
            val detailSchemaIds = schemaIds(detailIn?.get("schemaIds"))
            if (detailIn != null && detailSchemaIds.isEmpty()) {
                error("coverage[$i].detailIn には schemaIds（出現先スキーマ）が必要です")
            }
            CoverageRule(
                id = m["id"]?.toString(),
                description = m["description"]?.toString(),
                idPattern = m["idPattern"]?.toString(),
                fromSchemaIds = schemaIds(requiredBy?.get("schemaIds")),
                relation = requiredBy?.get("relation")?.toString(),
                detailSchemaIds = detailSchemaIds,
                minCount = ((detailIn ?: requiredBy)?.get("minCount") as? Number)?.toInt() ?: 1,
                level = m["level"]?.toString()?.let { value ->
                    CoverageLevel.entries.firstOrNull { it.id == value }
                        ?: error("coverage[$i].level は warning / error のいずれかである必要があります: $value")
                } ?: CoverageLevel.WARNING,
                activeFrom = m["activeFrom"]?.toString(),
            )
        }
    }

    /** 導出宣言（R16-02/03a 共通語彙）のパース。ビューの computed（Views.parseView）と共有する。 */
    internal fun parseDerivationSelector(raw: Any?, where: String): DerivationSelector {
        val m = raw as? Map<*, *> ?: error("$where はobjectである必要があります")
        return DerivationSelector(
            schemaId = m["schemaId"]?.toString(),
            fileGlob = m["fileGlob"]?.toString(),
        )
    }

    internal fun parseDerivation(raw: Any?, where: String): DerivationDef {
        val m = raw as? Map<*, *> ?: error("$where はobjectである必要があります")
        val source = parseDerivationSelector(m["source"] ?: error("$where.source が必要です"), "$where.source")
        require(!source.isEmpty) { "$where.source には schemaId か fileGlob が必要です" }
        val lookup = (m["lookup"] as? Map<*, *>)?.let { lm ->
            DerivationLookup(
                from = DerivationSelector(lm["schemaId"]?.toString(), lm["fileGlob"]?.toString()),
                via = parseYPath(lm["via"]?.toString() ?: error("$where.lookup.via が必要です")),
            )
        }
        return DerivationDef(
            lookup = lookup,
            source = source,
            path = parseYPath(m["path"]?.toString() ?: error("$where.path が必要です")),
            values = (m["values"] as? List<*>)?.mapIndexed { i, v ->
                parseDerivationSelector(v, "$where.values[$i]")
            } ?: emptyList(),
            map = (m["map"] as? Map<*, *>)?.entries
                ?.associate { it.key.toString() to (it.value?.toString() ?: "") } ?: emptyMap(),
            default = m["default"]?.toString(),
        )
    }

    /** 整合ルール（R16-03a）のパース。level / activeFrom の語彙は coverage と揃える。 */
    private fun parseConsistency(raw: Any?): List<ConsistencyRule> {
        if (raw == null) return emptyList()
        val list = raw as? List<*> ?: error("consistencyはリストである必要があります")
        return list.mapIndexed { i, entry ->
            val m = entry as? Map<*, *> ?: error("consistency[$i]はobjectである必要があります")
            ConsistencyRule(
                id = m["id"]?.toString(),
                description = m["description"]?.toString(),
                idPattern = m["idPattern"]?.toString(),
                path = parseYPath(m["path"]?.toString() ?: error("consistency[$i].path が必要です")),
                expected = parseDerivation(m["expected"] ?: error("consistency[$i].expected が必要です"), "consistency[$i].expected"),
                assert = m["assert"]?.toString()?.let { value ->
                    ConsistencyAssert.entries.firstOrNull { it.id == value }
                        ?: error("consistency[$i].assert は equals / lte / gte のいずれかである必要があります: $value")
                } ?: ConsistencyAssert.EQUALS,
                level = m["level"]?.toString()?.let { value ->
                    CoverageLevel.entries.firstOrNull { it.id == value }
                        ?: error("consistency[$i].level は warning / error のいずれかである必要があります: $value")
                } ?: CoverageLevel.WARNING,
                activeFrom = m["activeFrom"]?.toString(),
            )
        }
    }

    private fun parseSchemaFile(file: Path): SchemaDef = parseSchemaText(Files.readString(file), file)
}
