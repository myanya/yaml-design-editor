package dev.toros.yde.desktop.connections

import dev.toros.yde.app.service.ConnectionGrants
import dev.toros.yde.app.service.ConnectionPermissions
import dev.toros.yde.app.service.OperationCaller
import dev.toros.yde.server.LocalConnectionSettings
import dev.toros.yde.server.LocalFeature
import dev.toros.yde.server.auth.ClientRegistration
import dev.toros.yde.server.auth.LocalClient
import dev.toros.yde.yaml.model.DocumentId
import java.nio.file.*
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import kotlinx.serialization.json.*

internal data class SavedConnections(
    val settings: LocalConnectionSettings = LocalConnectionSettings(),
    val registrations: List<ClientRegistration> = emptyList(),
)

internal interface ConnectionSettingsStore {
    fun load(): SavedConnections
    fun save(value: SavedConnections)
}

/** 利用者専用領域の設定。ワークスペース・Preferences・通常ログへ資格情報を書かない。 */
internal class FileConnectionSettingsStore(private val directory: Path = Path.of(System.getProperty("user.home"), ".yaml-design-editor-connections")) : ConnectionSettingsStore {
    private val file = directory.resolve("settings.json")
    override fun load(): SavedConnections {
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return SavedConnections()
        PrivateConnectionFiles.verify(directory, directory = true)
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return SavedConnections()
        PrivateConnectionFiles.verify(file)
        val bytes = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAX_BYTES + 1) }
        require(bytes.size <= MAX_BYTES) { "接続設定のサイズが上限を超えています" }
        return ConnectionSettingsCodec.decode(bytes.toString(Charsets.UTF_8))
    }

    override fun save(value: SavedConnections) {
        val bytes = ConnectionSettingsCodec.encode(value).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "接続設定のサイズが上限を超えています" }
        PrivateConnectionFiles.createDirectory(directory)
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) PrivateConnectionFiles.verify(file)
        val temporary = Files.createTempFile(directory, ".settings-", ".tmp", PrivateConnectionFiles.attribute(directory, false))
        try {
            PrivateConnectionFiles.verify(temporary)
            FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    companion object { private const val MAX_BYTES = 8 * 1024 * 1024 }
}

/** 不明な版・不正な真偽値・権限は部分復元せず、起動側で全 OFF として扱う。 */
internal object ConnectionSettingsCodec {
    fun encode(value: SavedConnections): String = buildJsonObject {
        put("version", 1)
        put("api", value.settings.apiEnabled); put("mcp", value.settings.mcpEnabled); put("web", value.settings.webEnabled)
        put("port", value.settings.port)
        putJsonArray("clients") { value.registrations.forEach { (client, p) -> add(buildJsonObject {
            put("id", client.id); put("name", client.name); put("feature", client.feature.name); put("workspace", client.caller.workspaceId)
            fun documents(key: String, values: Set<DocumentId>) { putJsonArray(key) { values.sortedBy { it.value }.forEach { add(it.value) } } }
            documents("read", p.readable); documents("propose", p.proposable); documents("apply", p.actionable)
            putJsonArray("actions") { p.actions.sorted().forEach { add(it) } }
        }) } }
    }.toString()

    fun decode(text: String): SavedConnections {
        val obj = Json.parseToJsonElement(text).jsonObject
        require(obj["version"]?.jsonPrimitive?.int == 1) { "未対応の接続設定です" }
        fun enabled(key: String): Boolean = obj[key]?.jsonPrimitive?.let {
            require(!it.isString) { "接続設定の真偽値が不正です" }; requireNotNull(it.booleanOrNull)
        } ?: false
        val settings = LocalConnectionSettings(enabled("api"), enabled("mcp"), enabled("web"), obj["port"]?.jsonPrimitive?.int ?: 18765)
        val values = obj["clients"]?.jsonArray.orEmpty()
        require(values.size <= 256) { "接続登録の上限を超えています" }
        val grants = ConnectionGrants()
        val registrations = values.map { entry ->
            val c = entry.jsonObject
            fun string(key: String) = c.getValue(key).jsonPrimitive.let { require(it.isString); it.content }
            fun strings(key: String) = c[key]?.jsonArray.orEmpty().map { it.jsonPrimitive.let { p -> require(p.isString); p.content } }.toSet()
            val id = string("id")
            require(java.util.UUID.fromString(id).toString() == id) { "接続者の識別子が不正です" }
            val name = string("name")
            require(name.isNotBlank() && name.length <= 100) { "接続名が不正です" }
            val client = LocalClient(id, name, LocalFeature.valueOf(string("feature")), OperationCaller(string("workspace"), id))
            val p = ConnectionPermissions(strings("read").map(::DocumentId).toSet(), strings("propose").map(::DocumentId).toSet(),
                strings("apply").map(::DocumentId).toSet(), strings("actions"))
            ClientRegistration(client, grants.put(client.caller, p).permissions)
        }
        require(registrations.map { it.client.id }.toSet().size == registrations.size) { "接続者が重複しています" }
        return SavedConnections(settings, registrations)
    }
}
