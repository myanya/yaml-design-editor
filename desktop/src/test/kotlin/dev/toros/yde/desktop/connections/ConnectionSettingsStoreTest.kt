package dev.toros.yde.desktop.connections

import dev.toros.yde.app.service.*
import dev.toros.yde.server.*
import dev.toros.yde.server.auth.*
import dev.toros.yde.yaml.model.DocumentId
import java.nio.file.*
import java.nio.file.attribute.*
import java.util.UUID
import kotlin.test.*

class ConnectionSettingsStoreTest {
    private val root = Files.createTempDirectory("connection-settings")
    private val directory = root.resolve("private")
    private val store = FileConnectionSettingsStore(directory)
    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }

    @Test fun `旧版で設定がなければファイルを作らず全機能を停止する`() {
        assertEquals(SavedConnections(), store.load())
        assertFalse(Files.exists(directory))
        assertEquals(LocalConnectionSettings(), ConnectionSettingsCodec.decode("{\"version\":1}").settings)
    }

    @Test fun `利用者だけが読める領域へ三つの設定と権限を保存して復元する`() {
        val id = UUID.randomUUID().toString()
        val client = LocalClient(id, "自作ツール", LocalFeature.API, OperationCaller("workspace", id))
        val document = DocumentId("orders/a.yaml")
        val value = SavedConnections(LocalConnectionSettings(true, false, true, 23456), listOf(ClientRegistration(client,
            ConnectionPermissions(setOf(document), setOf(document), setOf(document), setOf("approve")))))
        store.save(value)
        assertEquals(value, store.load())
        PrivateConnectionFiles.verify(directory, directory = true)
        PrivateConnectionFiles.verify(directory.resolve("settings.json"))
        store.save(value.copy(settings = value.settings.copy(apiEnabled = false)))
        assertFalse(store.load().settings.apiEnabled)
        Files.list(directory).use { assertEquals(listOf("settings.json"), it.map { p -> p.fileName.toString() }.toList()) }
        assertFalse(Files.readString(directory.resolve("settings.json")).contains("token", ignoreCase = true))
    }

    @Test fun `不正な設定や権限を部分復元しない`() {
        listOf("{", "{\"version\":2,\"api\":true}", "{\"version\":1,\"api\":\"true\"}",
            "{\"version\":1,\"api\":null}", "{\"version\":1,\"port\":0}").forEach { invalid ->
            assertFails { ConnectionSettingsCodec.decode(invalid) }
        }
        val id = UUID.randomUUID().toString()
        val client = LocalClient(id, "読取", LocalFeature.MCP, OperationCaller("workspace", id))
        val invalid = SavedConnections(registrations = listOf(ClientRegistration(client, ConnectionPermissions(readable = setOf(DocumentId("../outside.yaml"))))))
        assertFails { ConnectionSettingsCodec.decode(ConnectionSettingsCodec.encode(invalid)) }
        val duplicate = invalid.copy(registrations = List(2) { ClientRegistration(client, ConnectionPermissions()) })
        assertFails { ConnectionSettingsCodec.decode(ConnectionSettingsCodec.encode(duplicate)) }
    }

    @Test fun `保存ファイルの破損は既定値で上書きせず利用者の修復を待つ`() {
        store.save(SavedConnections())
        val file = directory.resolve("settings.json")
        Files.writeString(file, "broken")
        assertFails { store.load() }
        assertEquals("broken", Files.readString(file))
    }

    @Test fun `他の利用者へ公開された保存先を使わない`() {
        store.save(SavedConnections())
        if (Files.getFileStore(directory).supportsFileAttributeView(PosixFileAttributeView::class.java)) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"))
        } else {
            val view = Files.getFileAttributeView(directory, AclFileAttributeView::class.java)
            // 親の継承 ACL を戻して利用者専用ではなくする。
            view.acl = Files.getFileAttributeView(root, AclFileAttributeView::class.java).acl
        }
        assertFails { store.load() }
        assertFails { store.save(SavedConnections(LocalConnectionSettings(apiEnabled = true))) }
    }
}
