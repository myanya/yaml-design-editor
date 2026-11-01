package dev.toros.yde.server.mcp

import dev.toros.yde.app.service.*
import dev.toros.yde.server.protocol.externalJson
import dev.toros.yde.server.protocol.invalidWrite
import dev.toros.yde.server.protocol.jsonValue
import dev.toros.yde.server.auth.LocalAccessFailure
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*

internal data class McpToolSpec(val tool: Tool, val resource: ReadResource? = null) {
    fun check(args: JsonObject) {
        if (args.toString().toByteArray(Charsets.UTF_8).size > 2 * 1024 * 1024) throw LocalAccessFailure(413, "引数が大きすぎます。作業を分割してください")
        val schema = tool.inputSchema
        if (args.keys.any { it !in schema.properties.orEmpty() } || schema.required.orEmpty().any { it !in args }) invalidWrite()
        jsonValue(args)
    }
}

/** 権限は一覧の絞り込みだけでなく各呼出でも確認する。スキーマは通信形式であり業務の型定義ではない。 */
internal fun mcpToolSpecs(grant: ConnectionGrant): List<McpToolSpec> = buildList {
    val revision = mapOf("revision" to stringField("保存済みの版。前の応答と同じ版を要求するときに指定", 64))
    val page = mapOf("offset" to integerField(0, 10_000), "limit" to integerField(1, 200))
    fun read(name: String, description: String, resource: ReadResource, fields: Map<String, Any?> = emptyMap(), required: List<String> = emptyList(), paged: Boolean = false) {
        add(spec(name, description, revision + (if (paged) page else emptyMap()) + fields, required, readOnly = true, resource = resource))
    }
    read("get_workspace", "接続したワークスペースの保存済み状態を取得する", ReadResource.WORKSPACE)
    read("list_documents", "閲覧可能な保存済み文書を一覧する。nextOffset があれば revision とともに次のページへ渡す", ReadResource.DOCUMENTS, paged = true)
    read("read_document", "保存済み文書と変更セット用のハッシュを読む。未保存編集や作業コピーは含まない", ReadResource.DOCUMENT,
        mapOf("document" to stringField("ワークスペース相対の文書パス", 1024)), listOf("document"))
    read("search", "閲覧可能な保存済みの内容を検索する", ReadResource.SEARCH, mapOf("q" to stringField("検索語", 500)), listOf("q"), true)
    read("read_entity", "保存済みのエンティティと原本座標を取得する", ReadResource.ENTITY, mapOf("id" to stringField("エンティティ ID")), listOf("id"))
    read("list_views", "利用できるビューを一覧する", ReadResource.VIEWS, paged = true)
    read("read_view", "保存済みのビューを解決する。列・値・原本座標と保存版を返す", ReadResource.VIEW,
        mapOf("id" to stringField("ビュー ID"), "date" to (stringField("基準日 yyyy-MM-dd", 10) + ("format" to "date"))), listOf("id"), true)
    read("validate_workspace", "保存済みの検証結果を取得する。返したページの指摘・保留を確認し、残りのページも取得する", ReadResource.VALIDATION,
        mapOf("document" to stringField("文書を限定するときの相対パス", 1024)), paged = true)
    read("list_actions", "定義済み操作のうち、この接続者に許可された操作名・入力・対象を一覧する", ReadResource.ACTIONS, paged = true)

    val proposal = grant.allows(ConnectionCapability.PROPOSE)
    val direct = grant.allows(ConnectionCapability.COMMIT)
    val workId = mapOf("workId" to stringField("begin_work が返した作業 ID", 180))
    val planId = mapOf("planId" to stringField("直前の計画が返した ID"))
    if (proposal || direct) {
        val modes = buildList { if (proposal) add("proposal"); if (direct) add("direct") }
        add(spec("begin_work", "作業コピーを開始する。proposal は提出後にデスクトップで承認、direct は計画の確認後に直接確定。方式は開始後に変更できない",
            mapOf("title" to stringField("作業の件名"), "mode" to mapOf("type" to "string", "enum" to modes)), listOf("title", "mode")))
        add(spec("edit_work", "作業コピーへ変更セットを適用する。原本は保存しない。set / insert / delete / move / rename / reorder と業務文書の新規作成に対応。編集後は validate_work で差分と検証結果を確認する",
            workId + ("changes" to patchSchemas.getValue("ChangeSet").jsonObject.getValue("properties").jsonObject.getValue("changes")), listOf("workId", "changes"), destructive = true, defs = patchSchemas))
        add(spec("validate_work", "作業コピーと影響先を検証し、変更前後の本文と指摘を返す。以前の確定計画は無効になる", workId, listOf("workId"), destructive = true))
        add(spec("discard_work", "未確定の作業を破棄する。保存済みの変更を取り消す操作ではない", workId, listOf("workId"), destructive = true))
        add(spec("work_result", "自分の作業の状態と結果を照会する。通信断後は新規作業にせず同じ ID を照会する", workId, listOf("workId"), readOnly = true))
        if (proposal) add(spec("submit_work", "proposal の作業をデスクトップの受信箱へ提出する。提出時点では保存されない。提出後は編集できない", workId, listOf("workId"), idempotent = true))
        if (direct) {
            add(spec("plan_work", "direct の作業の確定計画を作る。差分・検証結果・canCommit を確認し、返った計画 ID で commit_work を呼ぶ", workId, listOf("workId"), destructive = true))
            add(spec("commit_work", "direct の作業を計画 ID で確定して保存する。検証エラー・未保存編集・古い計画は拒否する。再送は同じ作業 ID と計画 ID を使う", workId + planId,
                listOf("workId", "planId"), destructive = true, idempotent = true))
        }
    }
    if (grant.allows(ConnectionCapability.APPLY)) {
        add(spec("plan_action", "list_actions の操作を計画する。差分と検証結果を確認して commit_action へ進む",
            mapOf("name" to stringField("許可された操作名"), "target" to stringField("操作の対象 ID または文書", 1024), "params" to mapOf("type" to "object")), listOf("name")))
        val requestId = mapOf("requestId" to (stringField("1 回の処理に一意な要求 ID。再送時は同じ値", 180) + ("pattern" to "^[A-Za-z0-9_-]+$")))
        add(spec("commit_action", "定義済み操作の計画を確定して保存する。同じ処理の再送は同じ計画 ID と要求 ID を使う", planId + requestId,
            listOf("planId", "requestId"), destructive = true, idempotent = true))
        add(spec("action_result", "自分の定義済み操作の結果を要求 ID で照会する", requestId, listOf("requestId"), readOnly = true))
    }
}

private fun stringField(description: String, max: Int = 200) = mapOf("type" to "string", "minLength" to 1, "maxLength" to max, "description" to description)
private fun integerField(min: Int, max: Int) = mapOf("type" to "integer", "minimum" to min, "maximum" to max)
private fun spec(name: String, description: String, properties: Map<String, Any?>, required: List<String> = emptyList(),
    readOnly: Boolean = false, destructive: Boolean = false, idempotent: Boolean = false,
    resource: ReadResource? = null, defs: JsonObject? = null,
) = McpToolSpec(Tool(name, ToolSchema(properties = JsonObject(properties.mapValues { (_, value) -> if (value is JsonElement) value else externalJson(value) }), required = required, defs = defs),
    description = description + "。未定義の引数は拒否します。",
    annotations = ToolAnnotations(readOnlyHint = readOnly, destructiveHint = destructive, idempotentHint = readOnly || idempotent, openWorldHint = false)), resource)

/** パッチの通信形式の原本は既存 OpenAPI。MCP 用に参照先だけを $defs へ写す。 */
private val patchSchemas: JsonObject by lazy {
    val text = requireNotNull(McpToolSpec::class.java.getResourceAsStream("/api/openapi.json")).bufferedReader().use { it.readText() }
    val all = Json.parseToJsonElement(text).jsonObject.getValue("components").jsonObject.getValue("schemas").jsonObject
    fun references(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (key, item) -> if (key == "\$ref") JsonPrimitive(item.jsonPrimitive.content.replace("#/components/schemas/", "#/\$defs/")) else references(item) })
        is JsonArray -> JsonArray(value.map(::references))
        else -> value
    }
    JsonObject(listOf("JsonValue", "PatchOperation", "FileChange", "ChangeSet").associateWith { references(all.getValue(it)) })
}
