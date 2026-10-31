package dev.toros.yde.app.service

import dev.toros.yde.workspace.FileSystemDocumentStore
import dev.toros.yde.workspace.WorkspacePaths
import dev.toros.yde.workspace.changeset.ChangeSets
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.model.DocumentId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.NoSuchFileException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.WRITE
import kotlinx.serialization.json.*

enum class OperationState { RECEIVED, APPLYING, COMMITTED, REJECTED, PARTIAL, UNKNOWN, RECONCILED }

data class OperationRecord(
    val workspaceId: String, val callerId: String, val requestId: String, val planId: String,
    val requestHash: String, val state: OperationState,
    val before: Map<String, String?>, val after: Map<String, String?>,
    val persistence: Map<String, String> = emptyMap(), val message: String? = null,
)

/** 要求記録の永続化ポート。テストは保存の各段階で失敗を注入する。 */
interface OperationJournal {
    fun readAll(): List<OperationRecord>
    fun write(record: OperationRecord)
}

/** 本文と資格情報を含めない。記録の削除で要求 ID を忘れることはしない。 */
class FileOperationJournal(root: Path, layout: WorkspaceLayout, private val maxRecords: Int = 10_000) : OperationJournal {
    private val root = root.toAbsolutePath().normalize()
    private val directory = "${layout.runsDir}/local-operations"
    private val store = FileSystemDocumentStore(this.root)

    private fun name(r: OperationRecord): String = ChangeSets.contentHash(buildJsonArray {
        add(r.workspaceId); add(r.callerId); add(r.requestId)
    }.toString()) + ".json"

    private fun files(): List<Path> {
        val path = WorkspacePaths.resolve(root, directory)
        return try {
            Files.list(path).use { stream -> stream.filter { it.fileName.toString().endsWith(".json") }.toList() }
        } catch (_: NoSuchFileException) { emptyList() }
    }

    override fun readAll(): List<OperationRecord> = files().also {
        require(it.size <= maxRecords) { "操作記録の保管上限を超えています" }
    }.map { file ->
        val id = DocumentId("$directory/${file.fileName}")
        require(Files.size(file) <= 1_048_576) { "操作記録が大きすぎます" }
        val obj = Json.parseToJsonElement(requireNotNull(store.read(id)).text).jsonObject
        fun field(key: String) = obj.getValue(key).jsonPrimitive.content
        fun values(key: String) = obj.getValue(key).jsonObject.mapValues { it.value.jsonPrimitive.contentOrNull }
        require(field("version") == "1") { "未対応の操作記録です" }
        OperationRecord(field("workspace"), field("caller"), field("request"), field("plan"), field("hash"),
            OperationState.valueOf(field("state")), values("before"), values("after"),
            values("persistence").mapValues { requireNotNull(it.value) }, obj["message"]?.jsonPrimitive?.contentOrNull)
            .also { require(file.fileName.toString() == name(it)) { "操作記録の識別子が一致しません" } }
    }

    override fun write(record: OperationRecord) {
        val id = DocumentId("$directory/${name(record)}")
        require(store.read(id) != null || files().size < maxRecords) { "操作記録の保管上限に達しました。新規の確定を停止します" }
        fun values(map: Map<String, String?>) = JsonObject(map.mapValues { (_, value) -> value?.let(::JsonPrimitive) ?: JsonNull })
        val text = buildJsonObject {
            put("version", 1); put("workspace", record.workspaceId); put("caller", record.callerId)
            put("request", record.requestId); put("plan", record.planId); put("hash", record.requestHash)
            put("state", record.state.name); put("before", values(record.before)); put("after", values(record.after))
            put("persistence", values(record.persistence)); put("message", record.message?.let(::JsonPrimitive) ?: JsonNull)
        }.toString()
        require(text.toByteArray(Charsets.UTF_8).size <= 1_048_576) { "操作記録が大きすぎます" }
        store.write(id, text)
        FileChannel.open(WorkspacePaths.resolve(root, id.value), WRITE).use { it.force(true) }
    }
}
