package dev.toros.yde.app.core

import dev.toros.yde.workspace.layout.FilePolicy
import dev.toros.yde.workspace.layout.FilePolicyLevel
import dev.toros.yde.yaml.model.DocumentId
import java.nio.file.Path
import kotlin.test.*

class FileLocationsTest {
    @Test fun `画像は原本文書から解決し外部ファイルの許可を検証と共有する`() {
        val workspace = Path.of("").toAbsolutePath().resolve("example")
        val document = DocumentId("people/a.yaml")
        val policy = FilePolicy(outside = FilePolicyLevel.ERROR)
        assertEquals(workspace.resolve("people/face.png"), FileLocations.readable("./face.png", document, workspace, policy))
        assertEquals(workspace.resolve("images/face.png"), FileLocations.readable("images/face.png", document, workspace, policy))
        assertNull(FileLocations.readable("../../face.png", document, workspace, policy))
        val outside = workspace.parent.resolve("allowed/face.png")
        assertNull(FileLocations.readable(outside.toString(), document, workspace, policy))
        val allowed = policy.copy(allowedRoots = listOf(outside.parent.toString()))
        assertEquals(outside, FileLocations.readable(outside.toString(), document, workspace, allowed))
        assertEquals(FilePolicyLevel.ALLOW, allowed.outsideLevel(outside.toString(), document))
    }
}
