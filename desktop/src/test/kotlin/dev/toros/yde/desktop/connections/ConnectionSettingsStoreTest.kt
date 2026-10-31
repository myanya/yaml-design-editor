package dev.toros.yde.desktop.connections

import dev.toros.yde.app.service.*
import dev.toros.yde.server.*
import dev.toros.yde.server.auth.*
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.desktop.lifecycle.ResidentSettings
import java.nio.file.*
import java.nio.file.attribute.*
import java.util.UUID
import kotlin.test.*

class ConnectionSettingsStoreTest {
    private val root = Files.createTempDirectory("connection-settings")
    private val directory = root.resolve("private")
    private val store = FileConnectionSettingsStore(directory)
    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }

    @Test fun `Windowsの保存先と置換後の設定ファイルはACLの継承を無効にする`() {
        if (!Files.getFileStore(root).supportsFileAttributeView(AclFileAttributeView::class.java)) return
        store.save(SavedConnections(resident = ResidentSettings(true)))
        assertWindowsInheritanceDisabled()
        assertEquals(ResidentSettings(true), store.load().resident)
        // 原子的な置換後の新しいファイルでも、保護を引き継ぐ。
        store.save(SavedConnections(resident = ResidentSettings(true, true)))
        assertWindowsInheritanceDisabled()
        assertEquals(ResidentSettings(true, true), store.load().resident)
    }

    private fun assertWindowsInheritanceDisabled() {
        // Java の AclFileAttributeView が公開しない保護フラグを、Windows の読み取り API で確かめる。
        val powershell = Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
        val command = "[System.IO.Directory]::GetAccessControl(\$env:YDE_ACL_TEST_DIRECTORY).AreAccessRulesProtected; " +
            "[System.IO.File]::GetAccessControl([System.IO.Path]::Combine(\$env:YDE_ACL_TEST_DIRECTORY, 'settings.json')).AreAccessRulesProtected"
        val builder = ProcessBuilder(powershell.toString(), "-NoProfile", "-NonInteractive", "-Command", command).redirectErrorStream(true)
        builder.environment()["YDE_ACL_TEST_DIRECTORY"] = directory.toString()
        val process = builder.start()
        try {
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
            val lines = process.inputStream.bufferedReader().use { it.readLines() }.filter(String::isNotBlank)
            assertEquals(0, process.exitValue(), lines.joinToString("\n"))
            assertEquals(listOf("True", "True"), lines)
        } finally { if (process.isAlive) process.destroyForcibly().waitFor() }
    }

    @Test fun `Windowsのフォルダの参照許可は本文へ継承せず他者の変更と本文参照は拒否する`() {
        if (!Files.getFileStore(root).supportsFileAttributeView(AclFileAttributeView::class.java)) return
        store.save(SavedConnections())
        val directoryAcl = Files.getFileAttributeView(directory, AclFileAttributeView::class.java)
        val file = directory.resolve("settings.json")
        val fileAcl = Files.getFileAttributeView(file, AclFileAttributeView::class.java)
        val owner = Files.getOwner(directory)
        val other = Files.getFileAttributeView(root, AclFileAttributeView::class.java).acl.first { it.principal() != owner }.principal()
        val original = directoryAcl.acl
        val read = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(other)
            .setPermissions(AclEntryPermission.READ_DATA, AclEntryPermission.READ_NAMED_ATTRS, AclEntryPermission.READ_ATTRIBUTES,
                AclEntryPermission.READ_ACL, AclEntryPermission.EXECUTE, AclEntryPermission.SYNCHRONIZE)
            .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT).build()
        directoryAcl.acl = original + read
        assertEquals(SavedConnections(), store.load())
        store.save(SavedConnections(resident = ResidentSettings(true)))
        assertEquals(ResidentSettings(true), store.load().resident)
        assertWindowsInheritanceDisabled()
        // 本文への読取許可は、フォルダと異なり拒否する。
        fileAcl.acl = fileAcl.acl + AclEntry.newBuilder(read).setFlags(emptySet()).build()
        assertFailsWith<ConnectionProtectionException> { store.load() }
        fileAcl.acl = fileAcl.acl.filter { it.principal() == owner }
        for (permission in listOf(AclEntryPermission.WRITE_DATA, AclEntryPermission.DELETE_CHILD,
            AclEntryPermission.DELETE, AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER)) {
            directoryAcl.acl = original + AclEntry.newBuilder(read).setPermissions(read.permissions() + permission).build()
            assertFailsWith<ConnectionProtectionException>(permission.toString()) { store.load() }
            assertFailsWith<ConnectionProtectionException>(permission.toString()) { store.save(SavedConnections()) }
        }
    }

    @Test fun `旧版で設定がなければファイルを作らず全機能を停止する`() {
        assertEquals(SavedConnections(), store.load())
        assertFalse(Files.exists(directory))
        assertEquals(LocalConnectionSettings(), ConnectionSettingsCodec.decode("{\"version\":1}").settings)
        assertEquals(ResidentSettings(), ConnectionSettingsCodec.decode("{\"version\":1,\"api\":true}").resident)
    }

    @Test fun `利用者だけが読める領域へ三つの設定と権限を保存して復元する`() {
        val id = UUID.randomUUID().toString()
        val client = LocalClient(id, "自作ツール", LocalFeature.API, OperationCaller("workspace", id))
        val document = DocumentId("orders/a.yaml")
        val value = SavedConnections(LocalConnectionSettings(true, false, true, 23456), listOf(ClientRegistration(client,
            ConnectionPermissions(setOf(document), setOf(document), setOf(document), setOf("approve")))), ResidentSettings(true, true))
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
            "{\"version\":1,\"api\":null}", "{\"version\":1,\"port\":0}",
            "{\"version\":1,\"startHidden\":true}", "{\"version\":1,\"tray\":\"true\"}").forEach { invalid ->
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
