package dev.toros.yde.desktop.connections

import java.nio.file.*
import java.nio.file.attribute.*

/** 新規作成の時点で現在の利用者だけに許可する。保護できない保存先へは書かない。 */
internal object PrivateConnectionFiles {
    private fun owner(path: Path) = path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
    private fun posix(path: Path) = Files.getFileStore(path).supportsFileAttributeView(PosixFileAttributeView::class.java)

    fun attribute(existing: Path, directory: Boolean): FileAttribute<*> {
        if (posix(existing)) return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
        require(Files.getFileStore(existing).supportsFileAttributeView(AclFileAttributeView::class.java)) { "接続設定を利用者専用に保護できません" }
        val entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner(existing))
            .setPermissions(AclEntryPermission.entries.toSet()).build()
        return object : FileAttribute<List<AclEntry>> {
            override fun name() = "acl:acl"
            override fun value() = listOf(entry)
        }
    }

    fun createDirectory(path: Path) {
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(path, attribute(path.parent, true))
        verify(path, directory = true)
    }

    fun verify(path: Path, directory: Boolean = false) {
        require(if (directory) Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) else Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "接続設定の保存先が通常の${if (directory) "ディレクトリ" else "ファイル"}ではありません"
        }
        val expected = owner(path)
        require(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS) == expected) { "接続設定の所有者が異なります" }
        if (posix(path)) {
            val allowed = PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------")
            require(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).all { it in allowed }) { "接続設定が他の利用者に公開されています" }
        } else {
            val view = requireNotNull(Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)) { "接続設定を保護できません" }
            require(view.acl.none { it.type() == AclEntryType.ALLOW && it.principal() != expected }) { "接続設定が他の利用者に公開されています" }
        }
    }
}
