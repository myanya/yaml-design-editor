package dev.toros.yde.workspace

import dev.toros.yde.semantic.schema.CheckLibrary
import dev.toros.yde.semantic.schema.DeriveDef
import dev.toros.yde.semantic.schema.EnforcementDef
import dev.toros.yde.semantic.schema.EntityDecl
import dev.toros.yde.semantic.schema.EntityTypeDef
import dev.toros.yde.semantic.schema.ExpressionFields
import dev.toros.yde.semantic.schema.ExpressionValue
import dev.toros.yde.semantic.schema.RelationDef
import dev.toros.yde.semantic.schema.SchemaDerivation
import dev.toros.yde.semantic.schema.SchemaKind
import dev.toros.yde.semantic.schema.SemanticDeclParser
import dev.toros.yde.semantic.schema.SchemaTree
import dev.toros.yde.semantic.schema.SchemaTreeParser
import dev.toros.yde.semantic.schema.TransformDef
import dev.toros.yde.semantic.schema.VocabularySource
import dev.toros.yde.semantic.SemanticDeclarations
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YPath

/**
 * スキーマ文書1件を読んだ結果（P27 段C の C-3a。意図的差分 D-31）。アプリ側（root の SchemaLoader → SchemaDef）と
 * カーネル側（WorkspaceReader → WorkspaceSchema）が、同じ読み取りの結果から自分の型を作る。
 */
class ParsedSchemaDocument(
    val id: String,
    val name: String,
    val description: String?,
    val kind: SchemaKind,
    /** 適用先のパターン。書かれていなければ、文書のスキーマ（derive 宣言を除く）は全 YAML・それ以外は空。 */
    val match: String,
    val derive: DeriveDef?,
    /** 項目の木（root・definitions・phases）。 */
    val tree: SchemaTree,
    val entities: List<EntityDecl>,
    /** 関係レジストリの内容（kind: relations の文書だけ）。 */
    val relations: List<RelationDef>,
    val entityTypes: List<EntityTypeDef>,
    val enforcement: EnforcementDef?,
    /** 検査（constraints と語彙を、基本の検査へ展開してパースしたもの）。 */
    val checks: CheckCatalog.SchemaChecks,
    /** この文書が宣言する検査の定義と語彙の定義（functions / vocabulary）。 */
    val ownLibrary: CheckLibrary,
    /** 語彙の再展開に使う原文と定義の一式（派生スキーマが、導出元の語彙を自分の ID で展開し直すために使う）。 */
    val vocabularySource: VocabularySource,
    /** 式を受け取る項目（メタスキーマの expression: true の印）の値（場所 → 式。P31）。例: filePattern＝新しい文書の置き場所。 */
    val expressions: Map<YPath, ExpressionValue> = emptyMap(),
    /** 新しい文書のひな型（変換のひな型。いまの場所＝元の要素。P31・R31-03）。 */
    val scaffold: TransformDef? = null,
)

/**
 * スキーマ文書の読み取りの1実装（P27 段C の C-3a）。以前は root の SchemaLoader.parseSchemaText と、カーネルの
 * WorkspaceReader.parseSchemas が、同じ読み取りを別々に持っていた（書ける場所の決まりの点検が、カーネル側には無かった）。
 */
object SchemaDocuments {

    /**
     * スキーマ文書1件を読む。fallbackId は `id:` が無いときの ID（ふつうはファイル名）、sourceName は定義の出どころの表示名。
     * library は検査の展開に使う定義の一式（ワークスペース全体で集めたもの）。null のとき（単体の読み取り）は、
     * 組込み定義体とこの文書自身の定義で展開する。
     */
    fun parse(
        text: String,
        fallbackId: String,
        sourceName: String,
        library: CheckLibrary?,
        /** メタスキーマの木（どの項目が値の式を受け取るか。null＝式の項目を読まない＝組込みスキーマ自身の読み取り）。 */
        metaschema: SchemaTree? = null,
    ): ParsedSchemaDocument {
        val raw = YamlIO.parseToRaw(text) as? Map<*, *> ?: error("スキーマのルートはobjectである必要があります")
        val id = raw["id"]?.toString() ?: fallbackId
        val kind = SchemaKind.parse(raw["kind"]?.toString())
        val derive = SchemaDerivation.parse(raw["derive"])
        if (derive != null) {
            require(kind == SchemaKind.DOCUMENT) { "derive は ${kind.id} スキーマには定義できません" }
            require(raw["match"] == null) { "derive 宣言スキーマに match は定義できません（適用先は derive.match で決まります）" }
        }
        // エンティティ型の宣言と関係レジストリ: 書ける文書の種類が決まっている（architecture/graph-query.md「エンティティ型と関係レジストリ」）
        val entities = SemanticDeclParser.parseEntities(raw["entity"])
        require(entities.isEmpty() || kind == SchemaKind.DOCUMENT) { "entity は kind: ${kind.id} には定義できません" }
        require(kind == SchemaKind.RELATIONS || raw["relations"] == null) { "relations は kind: relations の文書でのみ定義できます" }
        require(kind == SchemaKind.RELATIONS || raw["entities"] == null) { "entities は kind: relations の文書でのみ定義できます" }
        require(kind == SchemaKind.RELATIONS || raw["enforcement"] == null) { "enforcement は kind: relations の文書でのみ定義できます" }
        val isRegistry = kind == SchemaKind.RELATIONS
        val match = raw["match"]?.toString() ?: if (kind == SchemaKind.DOCUMENT && derive == null) "**/*.{yaml,yml}" else ""
        // 値の式を受け取る項目（P31）: どの項目かはメタスキーマの印（expression: true）だけが知り、読み取りは共通（ExpressionFields）
        val expressions = metaschema?.let { ExpressionFields.read(YamlIO.fromAny(raw), it) }.orEmpty()
        // 新しい文書のひな型（変換のひな型。値の式ではないので、キーで読む）: 文書のスキーマ（derive 宣言でないもの）にだけ書ける
        val scaffold = raw["scaffold"]?.let { TransformDef.ofTemplate("$id-scaffold", it, "scaffold") }
        require(scaffold == null || (kind == SchemaKind.DOCUMENT && derive == null)) { "scaffold は、文書のスキーマ（derive 宣言でないもの）にだけ書けます" }
        val tree = SchemaTree(
            id = id,
            root = SchemaTreeParser.parseRoot(raw["root"], kind, id, allowMissingRoot = derive != null),
            definitions = SchemaTreeParser.parseDefinitions(raw["definitions"], id),
            phases = SchemaTreeParser.parsePhases(raw["phases"]),
        )
        // 検査: constraints に書いた検査と語彙を、基本の検査へ展開してからパースする（CheckCatalog）
        val ownLibrary = CheckLibrary.parse(raw, sourceName)
        val effectiveLibrary = library ?: (CheckCatalog.builtinLibrary + ownLibrary)
        return ParsedSchemaDocument(
            id = id,
            name = raw["name"]?.toString() ?: id,
            description = raw["description"]?.toString(),
            kind = kind,
            match = match,
            derive = derive,
            tree = tree,
            entities = entities,
            relations = if (isRegistry) SemanticDeclParser.parseRelations(raw["relations"]) else emptyList(),
            entityTypes = if (isRegistry) SemanticDeclParser.parseEntityTypes(raw["entities"]) else emptyList(),
            enforcement = if (isRegistry) SemanticDeclParser.parseEnforcement(raw["enforcement"]) else null,
            checks = CheckCatalog.checksOf(effectiveLibrary, raw, id),
            ownLibrary = ownLibrary,
            vocabularySource = VocabularySource(raw, effectiveLibrary),
            expressions = expressions,
            scaffold = scaffold,
        )
    }

    /** 関係レジストリの文書1件分の宣言（集約の入力）。sourceName は、重複の誤りに出す文書の表示名。 */
    class RegistryPart(
        val sourceName: String,
        val relations: List<RelationDef>,
        val entityTypes: List<EntityTypeDef>,
        val enforcement: EnforcementDef?,
    )

    /**
     * 関係レジストリ（kind: relations の文書）の集約。関係・エンティティ型・強制スイッチが複数のレジストリ文書で重ねて
     * 宣言されていたら誤りにして、先に読んだほうを採る（1つの文書の中の重複は、スキーマの定義の点検が報告する）。
     */
    fun declarationsOf(parts: List<RegistryPart>, errors: MutableList<String>): SemanticDeclarations {
        val relations = LinkedHashMap<String, RelationDef>()
        val entityTypes = LinkedHashMap<String, EntityTypeDef>()
        var enforcement = EnforcementDef()
        // 関係 → エンティティ型 → 強制スイッチの順に集める（誤りの並びを、読んだ文書の順に保つ）
        for (part in parts) {
            part.relations.distinctBy { it.name }.forEach { r ->
                if (relations.putIfAbsent(r.name, r) != null) errors += "${part.sourceName}: 関係「${r.name}」が複数のレジストリで宣言されています"
            }
        }
        for (part in parts) {
            part.entityTypes.distinctBy { it.type }.forEach { t ->
                if (entityTypes.putIfAbsent(t.type, t) != null) errors += "${part.sourceName}: エンティティ型「${t.type}」が複数のレジストリで宣言されています"
            }
        }
        for (part in parts) {
            val e = part.enforcement ?: continue
            e.undeclaredRelation?.let { v ->
                if (enforcement.undeclaredRelation != null) errors += "${part.sourceName}: enforcement.undeclaredRelation が複数のレジストリで宣言されています"
                else enforcement = enforcement.copy(undeclaredRelation = v)
            }
            e.definingUniqueness?.let { v ->
                if (enforcement.definingUniqueness != null) errors += "${part.sourceName}: enforcement.definingUniqueness が複数のレジストリで宣言されています"
                else enforcement = enforcement.copy(definingUniqueness = v)
            }
        }
        return SemanticDeclarations(relations, entityTypes.values.toList(), enforcement)
    }
}
