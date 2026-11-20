package dev.toros.yde.app.core

import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path

/** 設計サンプルのテスト入力。生成アプリやそのビルド成果物を設計の検証に取り込まない。 */
internal object TodosSysdevFiles {
    fun definition(root: Path, zone: String, name: String): String =
        Files.walk(root.resolve(".yde/$zone/design")).use { paths ->
            val found = paths.filter { Files.isRegularFile(it) && it.fileName.toString() == "$name.yaml" }.toList().single()
            root.relativize(found).toString().replace('\\', '/')
        }

    fun inputs(root: Path): List<Path> = buildList {
        val definitions = root.resolve(".yde")
        Files.list(definitions).use { entries -> entries.forEach { entry ->
            if (Files.isRegularFile(entry)) add(entry)
            else if (entry.fileName.toString() in setOf("schemas", "transforms", "views", "reports", "stages")) {
                Files.walk(entry).use { paths -> addAll(paths.filter { Files.isRegularFile(it) }.toList()) }
            }
        } }
        Files.walk(root.resolve("design")).use { paths -> addAll(paths.filter { Files.isRegularFile(it) }.toList()) }
    }

    fun copy(root: Path, destination: Path) {
        inputs(root).forEach { source ->
            val target = destination.resolve(root.relativize(source))
            Files.createDirectories(target.parent)
            Files.copy(source, target)
        }
    }

    fun documents(root: Path): List<Path> = listOf("basic", "detail").flatMap { phase ->
        Files.walk(root.resolve("design/$phase/final")).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".yaml") }.toList()
                .filter { (YamlIO.parseToRaw(Files.readString(it)) as? Map<*, *>)?.containsKey("designId") == true }
        }
    }
}
