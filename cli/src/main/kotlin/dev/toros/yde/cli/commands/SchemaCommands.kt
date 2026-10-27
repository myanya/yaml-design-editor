package dev.toros.yde.cli.commands

import dev.toros.yde.app.core.Workspace
import dev.toros.yde.app.schema.SchemaInference
import dev.toros.yde.app.schema.validateDefinition
import dev.toros.yde.cli.dumpYaml
import dev.toros.yde.cli.stringFlag
import dev.toros.yde.cli.workspaceOf
import dev.toros.yde.semantic.schema.SchemaKind
import dev.toros.yde.semantic.schema.globToRegex
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path

/**
 * スキーマの命令: infer-schema（既存文書からの推測）・infer-relations（参照と保有の実績から関係レジストリのドラフト）・schema-graph（スキーマの図の材料＝レンズの「型」の段）。
 * 業務ロジックは置かない（引数を窓口の入力へ写し、結果を YAML へ写すだけ）。入口は AppCli.kt の runCli。
 */

internal fun inferSchemaCommand(rest: List<String>): String = run {
    // スキーマ推測（R11-01）: 既存文書からドラフトを推測し、表示または _schemas へ保存する
    val ws = workspaceOf(rest)
    val glob = rest.getOrNull(1)?.takeIf { !it.startsWith("--") }
        ?: error("対象文書の glob を指定してください（例 tables/**/*.yaml）")
    val regex = globToRegex(glob)
    val targets = ws.files
        .map { ws.relPath(it) to it }
        .filter { (rel, _) -> ws.layout.inLedger(rel) && regex.matches(rel) }
    val samples = targets.map { (rel, file) ->
        SchemaInference.Sample(rel, runCatching { YamlIO.parseFirst(Files.readString(file)) }.getOrNull())
    }
    val id = stringFlag(rest, "--id") ?: defaultInferredSchemaId(glob)
    val draft = SchemaInference.infer(samples, schemaId = id, match = glob)
    val yamlText = YamlIO.dump(draft)
    if ("--apply" !in rest) {
        yamlText
    } else {
        val rel = "${ws.layout.schemasDir}/$id.yaml"
        val file = ws.resolve(rel)
        require(!Files.exists(file)) { "既にスキーマファイルが存在します: $rel" }
        Files.createDirectories(file.parent)
        Files.writeString(file, yamlText)
        ws.refresh()
        dumpYaml(
            linkedMapOf(
                "file" to rel,
                "documents" to targets.size,
                "schemaErrors" to ws.schemaErrors.filter { it.startsWith("$id.yaml") },
            )
        )
    }
}

internal fun inferRelationsCommand(rest: List<String>): String = run {
    // 関係レジストリの自動推測（R20-02）: 参照実績からドラフトを生成し、表示または _schemas へ保存する
    val ws = workspaceOf(rest)
    val id = stringFlag(rest, "--id") ?: "workspace-relations"
    val yamlText = inferRelationsDraft(ws, id)
    if ("--apply" !in rest) {
        yamlText
    } else {
        val rel = "${ws.layout.schemasDir}/$id.yaml"
        val file = ws.resolve(rel)
        require(!Files.exists(file)) { "既にレジストリファイルが存在します: $rel" }
        Files.createDirectories(file.parent)
        Files.writeString(file, yamlText)
        ws.refresh()
        dumpYaml(
            linkedMapOf(
                "file" to rel,
                "relations" to ws.relations.keys.toList(),
                "schemaErrors" to ws.schemaErrors.filter { it.startsWith("$id.yaml") },
            )
        )
    }
}

internal fun schemaGraphCommand(rest: List<String>): String = run {
    // スキーマの図（レンズの「型」の段と同じ材料。P42。architecture/graph-query.md「スキーマレンズ」）: 文書を読まず、
    // スキーマと関係レジストリから型・関係・保有の宣言を導く。空の値は出さない
    val g = workspaceOf(rest).schemaGraph
    fun site(s: dev.toros.yde.semantic.SchemaGraphSite) = "${s.schemaId}:${s.path}"
    fun compact(vararg pairs: Pair<String, Any?>): LinkedHashMap<String, Any?> =
        linkedMapOf(*pairs.filter { (_, v) -> v != null && (v !is Collection<*> || v.isNotEmpty()) }.toTypedArray())
    dumpYaml(
        linkedMapOf(
            "types" to g.types.map { t ->
                compact(
                    "type" to t.type, "label" to t.label,
                    "schemas" to t.appearances.map { a ->
                        compact(
                            "schema" to a.schemaId, "path" to a.path, "role" to (if (a.defines) "defines" else "detail"),
                            "aspect" to a.aspect, "idPattern" to a.idPattern, "inferred" to a.inferred.takeIf { it },
                        )
                    },
                )
            },
            "relations" to g.edges.map { e ->
                compact("from" to e.from, "to" to e.to, "relation" to e.relation, "source" to e.source.label, "sites" to e.sites.map(::site))
            },
            "holdings" to g.holdings.map { h ->
                compact("parent" to h.parent, "child" to h.child, "relation" to h.relation, "defines" to h.childDefines, "sites" to h.sites.map(::site))
            },
            "unresolved" to g.unresolved.map { u ->
                compact("schema" to u.site.schemaId, "path" to u.site.path, "relation" to u.relation, "reason" to u.reason)
            },
            // 宣言より広い導出（両端を宣言した関係で、スキーマの scope がレジストリより緩いぶん。線にはしない）
            "beyondDeclaration" to g.beyondDeclaration.map { e ->
                compact("from" to e.from, "to" to e.to, "relation" to e.relation, "sites" to e.sites.map(::site))
            },
        )
    )
}

/**
 * 関係レジストリのドラフト生成（R20-02。architecture/graph-query.md「エンティティ型と関係レジストリ」）。
 * 索引の全参照から relation ごとに viaKey・参照元／先のエンティティ型の実績を集計する。
 * 生成物は必ず `SchemaLoader.parseSchemaText` → `validateDefinition` を通ること（R11-01 と同じ入口保証）。
 */
fun inferRelationsDraft(ws: Workspace, registryId: String = "workspace-relations"): String {
    val byRelation = ws.index.refs.filter { it.relation != null }.groupBy { it.relation!! }
    // 保有の線（入れ子で書かれた要素。P40）も名前ごとに集計する（名前は項目の relation か、書いている項目のキー名）
    val byHolding = ws.index.graph.holdings.groupBy { it.relation }
    require(byRelation.isNotEmpty() || byHolding.isNotEmpty()) {
        "relation を宣言した参照も、入れ子で書かれた要素（保有）も索引にありません（スキーマの ref フィールドに relation を宣言してから実行してください）"
    }
    fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    val text = buildString {
        appendLine("# 関係レジストリのドラフト（infer-relations が参照と保有の実績から生成。R20-02）")
        appendLine("# inverseLabel（被参照方向の表示ラベル）は TODO を実際の語彙に書き換えてください。")
        appendLine("# 保有（入れ子で書かれた要素）の名前は、スキーマの項目（array / object / map）に relation を書くと変えられます（既定はキー名）。")
        appendLine("id: $registryId")
        appendLine("kind: relations")
        appendLine("relations:")
        (byRelation.keys + byHolding.keys).toSortedSet().forEach { name ->
            val refs = byRelation[name].orEmpty()
            val held = byHolding[name].orEmpty()
            val viaKeys = (refs.map { it.viaKey } + held.map { it.viaKey }).distinct().sorted()
            val sources = (refs.mapNotNull { ws.index.sourceEntryOf(it)?.entityType } + held.mapNotNull { it.parent.entityType })
                .distinct().sorted()
            val targets = (
                refs.flatMap { r -> ws.index.occurrences(r.targetId).mapNotNull { it.entityType } } +
                    held.mapNotNull { it.child.entityType }
                ).distinct().sorted()
            appendLine("  - name: ${quote(name)}")
            appendLine("    label: ${quote(name)}")
            appendLine("    # TODO: inverseLabel を被参照方向の語彙に書き換える（例: 〜される）")
            appendLine("    inverseLabel: ${quote("$name（被参照）")}")
            if (sources.isNotEmpty()) appendLine("    source: [${sources.joinToString(", ") { quote(it) }}]")
            if (targets.isNotEmpty()) appendLine("    target: [${targets.joinToString(", ") { quote(it) }}]")
            val counts = listOfNotNull(
                refs.takeIf { it.isNotEmpty() }?.let { "参照 ${it.size} 件" },
                held.takeIf { it.isNotEmpty() }?.let { "保有 ${it.size} 件" },
            ).joinToString("・")
            appendLine("    description: ${quote("実績: $counts・キー: ${viaKeys.joinToString(", ")}")}")
        }
    }
    // 入口保証: 生成物がメタスキーマ検証（レジストリの定義検証）を通ることを生成段階で確認する
    val parsed = dev.toros.yde.app.schema.SchemaLoader.parseSchemaText(text, Path.of("_schemas/$registryId.yaml"))
    val issues = parsed.validateDefinition()
    require(parsed.kind == SchemaKind.RELATIONS && issues.isEmpty()) {
        "生成したドラフトが定義検証を通りません: ${issues.joinToString("; ") { it.message }}"
    }
    return text
}

/** glob からスキーマIDの既定値を作る（最初のワイルドカードより前の最後のパス要素。R11-01）。 */
internal fun defaultInferredSchemaId(glob: String): String {
    val prefix = glob.takeWhile { it !in "*?{[" }
    val segment = prefix.trimEnd('/').substringAfterLast('/').substringBeforeLast('.')
    return segment.ifBlank { "inferred-schema" }
}
