package dev.toros.yde.app.core

import dev.toros.yde.semantic.schema.FilePath
import dev.toros.yde.semantic.schema.FilePathKind
import dev.toros.yde.workspace.layout.FilePolicy
import dev.toros.yde.workspace.layout.FilePolicyLevel
import dev.toros.yde.yaml.model.DocumentId
import java.nio.file.Path

/** file型と図の画像が共有する、表記からOSの場所への境界。 */
object FileLocations {
    fun resolve(text: String, documentFile: Path, workspace: Path): Path? {
        val value = FilePath.normalize(text)
        if (!FilePath.isWellFormed(value)) return null
        return runCatching {
            when (FilePath.kindOf(value)) {
                FilePathKind.ABSOLUTE -> Path.of(value.removePrefix("file:///").removePrefix("file:")).normalize()
                FilePathKind.DOCUMENT -> (documentFile.parent ?: workspace).resolve(value).normalize()
                FilePathKind.WORKSPACE -> workspace.resolve(value).normalize()
            }
        }.getOrNull()
    }

    fun readable(text: String, document: DocumentId, workspace: Path, policy: FilePolicy): Path? {
        if (policy.outsideLevel(text, document) == FilePolicyLevel.ERROR) return null
        return resolve(text, workspace.resolve(document.value), workspace)
    }
}
