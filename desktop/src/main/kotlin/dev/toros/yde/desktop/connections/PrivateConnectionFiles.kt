package dev.toros.yde.desktop.connections

import java.nio.file.*
import java.nio.file.attribute.*
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 設定内容や資格情報を含まない、画面へ表示できる保護の失敗理由。 */
internal class ConnectionProtectionException(message: String) : IOException(message)

/** 本文は現在の利用者だけに許可する。保存先への他者の書込も許さない。 */
internal object PrivateConnectionFiles {
    private fun owner(path: Path) = path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
    private fun posix(path: Path) = Files.getFileStore(path).supportsFileAttributeView(PosixFileAttributeView::class.java)
    private val directoryRead = setOf(AclEntryPermission.READ_DATA, AclEntryPermission.READ_NAMED_ATTRS,
        AclEntryPermission.READ_ATTRIBUTES, AclEntryPermission.READ_ACL, AclEntryPermission.EXECUTE, AclEntryPermission.SYNCHRONIZE)

    fun attribute(existing: Path, directory: Boolean): FileAttribute<*> {
        if (posix(existing)) return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
        requireProtected(Files.getFileStore(existing).supportsFileAttributeView(AclFileAttributeView::class.java)) { "接続設定を利用者専用に保護できません" }
        val entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner(existing))
            .setPermissions(AclEntryPermission.entries.toSet()).build()
        return object : FileAttribute<List<AclEntry>> {
            override fun name() = "acl:acl"
            override fun value() = listOf(entry)
        }
    }

    fun createDirectory(path: Path) {
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(path, attribute(path.parent, true))
            stopInheritance(path)
        } else {
            verify(path, directory = true) // 既存の広い権限を無断で信頼・修復しない。
            stopInheritance(path)
        }
        verify(path, directory = true)
    }

    fun protectNewFile(path: Path) { stopInheritance(path); verify(path) }

    private fun stopInheritance(path: Path) {
        if (posix(path)) return
        // NIO の初期 ACL には DACL の継承を停止する属性がない。空の保存先で停止してから本文を書く。
        // シェルを使わず OS 標準ツールへ別引数で渡し、リンクの先や子孫には作用させない。
        val windowsRoot = System.getenv("SystemRoot") ?: throw ConnectionProtectionException("この環境では接続設定の継承を保護できません")
        val executable = Path.of(windowsRoot, "System32", "icacls.exe")
        val process = ProcessBuilder(executable.toString(), path.toAbsolutePath().toString(), "/inheritance:r", "/l", "/q")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            requireProtected(process.waitFor(10, TimeUnit.SECONDS)) { "接続設定の権限保護が時間内に完了しませんでした" }
            requireProtected(process.exitValue() == 0) { "接続設定の権限の継承を停止できませんでした" }
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    private inline fun requireProtected(condition: Boolean, message: () -> String) {
        if (!condition) throw ConnectionProtectionException(message())
    }

    fun verify(path: Path, directory: Boolean = false) {
        requireProtected(if (directory) Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) else Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "接続設定の保存先が通常の${if (directory) "ディレクトリ" else "ファイル"}ではありません"
        }
        val expected = owner(path)
        requireProtected(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS) == expected) { "接続設定の所有者が異なります" }
        if (posix(path)) {
            val allowed = PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------")
            requireProtected(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).all { it in allowed }) { "接続設定が他の利用者に公開されています" }
        } else {
            val view = requireNotNull(Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)) { "接続設定を保護できません" }
            // フォルダの一覧参照は本文の読取を許可しない。本文は継承を止めた個別の ACL で守る。
            // DELETE_CHILD・WRITE_ACL 等は含めず、保存先からの置換や保護の解除も拒否する。
            requireProtected(view.acl.none {
                it.type() == AclEntryType.ALLOW && it.principal() != expected &&
                    (!directory || it.permissions().any { permission -> permission !in directoryRead })
            }) {
                if (directory) "接続設定のフォルダに他の利用者による変更の許可が含まれています。保存先の権限を修復してください。"
                else "接続設定のファイルに他の利用者への許可が含まれています。保存先の権限を修復してください。"
            }
        }
    }
}
