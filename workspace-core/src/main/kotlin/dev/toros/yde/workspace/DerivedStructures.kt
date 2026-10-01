package dev.toros.yde.workspace

import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.semantic.schema.BuiltinTypes
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.FieldYaml
import dev.toros.yde.semantic.schema.SchemaTree
import dev.toros.yde.semantic.schema.TransformDef
import dev.toros.yde.semantic.SemanticTransform
import dev.toros.yde.yaml.structure.StructureField
import dev.toros.yde.yaml.structure.StructureParser
import dev.toros.yde.yaml.structure.StructureSchema
import dev.toros.yde.yaml.structure.StructureValidator
import dev.toros.yde.yaml.validate.Severity
import dev.toros.yde.yaml.validate.StructuralIssue
import dev.toros.yde.yaml.validate.StructuralIssueKind
import dev.toros.yde.yaml.YamlIO
import java.util.concurrent.ConcurrentHashMap

/** 組込みの変換定義（同梱の YAML。資源 builtin-transforms）。 */
object BuiltinTransforms {
    /** 資源の原文。 */
    fun text(name: String): String =
        requireNotNull(BuiltinTransforms::class.java.getResourceAsStream("/builtin-transforms/$name.yaml")) {
            "組込みの変換定義が見つかりません: $name"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }

    fun parse(name: String): TransformDef = TransformDef.parse(YamlIO.parseToRaw(text(name)), "builtin-transforms/$name")

    /** 構造のスキーマの導出（意味のスキーマ → 最小の構造のスキーマ）。 */
    val structuralSchema: TransformDef by lazy { parse("structural-schema") }
}

/**
 * 利用者が書いたスキーマ1件から導出した、最小の構造のスキーマ（P27 段C の C-1。architecture/schema.md「構造スキーマの導出」・意図的差分 D-30）。
 * 構造の検証の入口——アプリ（root の Validator）とカーネル（WorkspaceReader・セッション）が、同じここを通る。
 * - 導出は、組込みの変換定義（builtin-transforms/structural-schema.yaml）を SemanticTransform で実行する。入力は、スキーマの項目の木を
 *   利用者が書く形の YAML へ書き出したもの（FieldYaml）——ファイルのスキーマも、派生スキーマも、語彙のキーを足したメタスキーマも同じ経路
 * - フェーズに達したら必須（requiredFrom）は、ここで文書のフェーズを読み、到達したフェーズの一覧を変換へ渡して、素の必須へ畳む。
 *   導出した構造のスキーマは、文書のフェーズごとに1つ（覚えておく）。yaml-core はフェーズを知らない
 * - 他のモジュールの定義（`モジュール#名前`）も、同じ変換で導出してつなぐ
 * - 定義されていないフェーズの申告の警告は、フェーズの解釈なので、ここ（意味の側）が出す
 */
class DerivedStructures(private val schema: SchemaTree) {

    /** フェーズの順位（フェーズの宣言が無ければ -1）→ 導出した構造のスキーマ。 */
    private val byPhase = ConcurrentHashMap<Int, StructureSchema>()

    /** この文書を検証するための、構造のスキーマ（文書の現在のフェーズに応じたもの）。 */
    fun at(root: YNode?): StructureSchema {
        val phaseIndex = schema.currentPhaseIndex(root) ?: -1
        return byPhase.computeIfAbsent(phaseIndex) { derive(it) }
    }

    /** 構造の検証: 定義されていないフェーズの申告の警告と、導出した構造のスキーマによる検証。 */
    fun validate(root: YNode): List<StructuralIssue> {
        val issues = mutableListOf<StructuralIssue>()
        schema.phases?.let { phases ->
            val declared = schema.currentPhaseValue(root)
            if (declared != null && declared !in phases.order) {
                issues += StructuralIssue(
                    Severity.WARNING,
                    "フェーズ「$declared」は phases（${phases.order.joinToString(" → ")}）に定義されていません（先頭フェーズとして扱います）",
                    listOf(phases.key),
                    StructuralIssueKind.PHASE,
                )
            }
        }
        issues += StructureValidator.validate(root, at(root))
        return issues
    }

    private fun derive(phaseIndex: Int): StructureSchema {
        val reached = if (phaseIndex < 0) emptyList() else schema.phases?.order.orEmpty().take(phaseIndex + 1)
        val own = transform(schema.id, schema.root, schema.definitions, reached)
        val parsed = StructureParser.parse(own, schema.id)
        // 他のモジュールの定義も、同じ変換で導出してつなぐ（必須の畳み込みは、検証する文書のフェーズで行う）
        val catalog = LinkedHashMap<String, Map<String, StructureField>>()
        catalog[schema.id] = parsed.definitions
        for ((moduleId, definitions) in schema.definitionCatalog.modules()) {
            if (moduleId == schema.id || definitions.isEmpty()) continue
            val raw = transform(moduleId, null, definitions, reached) as? Map<*, *>
            catalog[moduleId] = StructureParser.parseDefinitions(raw?.get("definitions"), moduleId)
        }
        return parsed.copy(catalog = catalog)
    }

    private fun transform(id: String, root: FieldDef?, definitions: Map<String, FieldDef>, reached: List<String>): Any? {
        // 型の連鎖（P33）は導出の前に解決して中身を写す。構造のスキーマは型の名前を知らない
        val term = FieldYaml.schemaTerm(id, root?.let(::inlineScalarTypes), definitions.mapValues { (_, d) -> inlineScalarTypes(d) })
        val result = SemanticTransform.run(BuiltinTransforms.structuralSchema, term, params = mapOf("reachedPhases" to reached))
        return result.term?.let { YamlIO.toAny(it) }
    }

    /**
     * スカラーの型の連鎖（`type: 名前` や、スカラーの定義への `$ref`）を解決し、基底の種類と合成した決まり（狭めた形式の一覧など）を
     * 項目に写す（P33）。定義への参照のまま残すのは、入れ物（object / array / map）の定義だけ（再帰のため。中身は定義の側で導出する）。
     */
    private fun inlineScalarTypes(field: FieldDef): FieldDef {
        val resolvedOrSelf = if (field.ref != null) {
            val resolved = schema.resolve(field) ?: field
            if (resolved.type.isContainer) field else resolved.copy(ref = null, typeName = null, lineage = emptyList())
        } else field
        // id / ref の直列化は word（空白なし）: 意味の基底型の内部は ID と参照で、構造の上では空白なしの文字列（P33）
        val inlined = if (resolvedOrSelf.type == FieldType.ID || resolvedOrSelf.type == FieldType.REF) {
            resolvedOrSelf.copy(inheritedPatterns = (resolvedOrSelf.inheritedPatterns + BuiltinTypes.WORD_PATTERN).distinct())
        } else resolvedOrSelf
        return inlined.copy(
            fields = inlined.fields.map(::inlineScalarTypes),
            item = inlined.item?.let(::inlineScalarTypes),
            value = inlined.value?.let(::inlineScalarTypes),
            cases = inlined.cases.mapValues { (_, list) -> list.map(::inlineScalarTypes) },
        )
    }
}
