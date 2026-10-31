package dev.toros.yde.app.service

import dev.toros.yde.workspace.FileSystemDocumentStore
import dev.toros.yde.workspace.PreparedDocumentChange
import dev.toros.yde.workspace.WorkspacePaths
import dev.toros.yde.workspace.changeset.ChangeSets
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.model.DocumentId
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import kotlinx.serialization.json.*

enum class WorkState { WORKING, VALIDATED, AWAITING_APPROVAL, APPLYING, APPLIED, REJECTED, DISCARDED, FAILED, PARTIAL, UNKNOWN, RECONCILED }

/** 不変の提出物。資格情報・会話・実行プロンプトは持たない。変更内容は作業者の権限内の本文を含む。 */
data class WorkRecord(
    val id: String, val caller: OperationCaller, val title: String, val state: WorkState,
    val createdAt: String, val submittedAt: String? = null,
    val changes: String? = null, val documents: List<PreparedDocumentChange> = emptyList(),
    val requestId: String? = null, val planId: String? = null, val message: String? = null,
    val persistence: Map<String, String> = emptyMap(),
)

interface WorkJournal {
    fun readAll(): List<WorkRecord>
    fun write(record: WorkRecord)
}

/** RunLog は内蔵 AI の会話・会計、こちらは外部作業の提出・採否。配置モードは WorkspaceLayout に従う。 */
class FileWorkJournal(root: Path, layout: WorkspaceLayout, private val maxRecords: Int = 10_000) : WorkJournal {
    private val root = root.toAbsolutePath().normalize()
    private val directory = "${layout.runsDir}/local-work"
    private val store = FileSystemDocumentStore(this.root)
    private fun name(record: WorkRecord) = ChangeSets.contentHash(buildJsonArray {
        add(record.caller.workspaceId); add(record.caller.callerId); add(record.id)
    }.toString()) + ".json"

    private fun files(): List<Path> = try {
        Files.list(WorkspacePaths.resolve(root, directory)).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".json") }.toList()
        }
    } catch (_: NoSuchFileException) { emptyList() }

    override fun readAll(): List<WorkRecord> = files().also {
        require(it.size <= maxRecords) { "提出記録の保管上限を超えています" }
    }.map { file ->
        require(Files.size(file) <= MAX_BYTES) { "提出記録が大きすぎます" }
        val o = Json.parseToJsonElement(requireNotNull(store.read(DocumentId("$directory/${file.fileName}"))).text).jsonObject
        fun field(key: String) = o.getValue(key).jsonPrimitive.content
        fun optional(key: String) = o[key]?.jsonPrimitive?.contentOrNull
        require(field("version") == "1") { "未対応の提出記録です" }
        WorkRecord(field("id"), OperationCaller(field("workspace"), field("caller")), field("title"), WorkState.valueOf(field("state")),
            field("created"), optional("submitted"), optional("changes"), o.getValue("documents").jsonArray.map {
                val d = it.jsonObject
                PreparedDocumentChange(DocumentId(d.getValue("file").jsonPrimitive.content),
                    d.getValue("before").jsonPrimitive.contentOrNull, d.getValue("after").jsonPrimitive.content)
            }, optional("request"), optional("plan"), optional("message"),
            o.getValue("persistence").jsonObject.mapValues { it.value.jsonPrimitive.content }).also {
            require(file.fileName.toString() == name(it)) { "提出記録の識別子が一致しません" }
        }
    }

    override fun write(record: WorkRecord) {
        val id = DocumentId("$directory/${name(record)}")
        require(store.read(id) != null || files().size < maxRecords) { "提出記録の保管上限に達しました" }
        val text = buildJsonObject {
            fun optional(key: String, value: String?) = put(key, value?.let(::JsonPrimitive) ?: JsonNull)
            put("version", 1); put("id", record.id); put("workspace", record.caller.workspaceId); put("caller", record.caller.callerId)
            put("title", record.title); put("state", record.state.name); put("created", record.createdAt)
            optional("submitted", record.submittedAt); optional("changes", record.changes)
            optional("request", record.requestId); optional("plan", record.planId); optional("message", record.message)
            put("persistence", JsonObject(record.persistence.mapValues { JsonPrimitive(it.value) }))
            put("documents", JsonArray(record.documents.map { d -> buildJsonObject {
                put("file", d.id.value); put("before", d.before?.let(::JsonPrimitive) ?: JsonNull); put("after", d.after)
            } }))
        }.toString()
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "提出内容が大きすぎます（上限 4 MiB）" }
        store.write(id, text)
        FileChannel.open(WorkspacePaths.resolve(root, id.value), WRITE).use { it.force(true) }
    }

    private companion object { const val MAX_BYTES = 4 * 1024 * 1024 }
}
