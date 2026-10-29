package dev.toros.yde.app.core

import java.nio.file.Files
import kotlin.system.measureTimeMillis
import dev.toros.yde.desktop.AppState
import dev.toros.yde.desktop.main

/** 実ファイルを使う任意実行の計測。通常の全テストへ大量のファイル作成を持ち込まない。 */
object FileTreeBenchmark {
    @JvmStatic fun main(args: Array<String>) {
        for (count in listOf(1000, 100000)) {
            val root = Files.createTempDirectory("yde-tree-bench-")
            try {
                repeat(count) { index ->
                    val dir = root.resolve("sources/module${index / 100}")
                    Files.createDirectories(dir)
                    Files.writeString(dir.resolve("Class$index.kt"), "class Class$index {\n fun work() = $index\n}\n")
                }
                val options = FileCatalog.Options(true)
                val open = measureTimeMillis { check(FileCatalog.children(root, root, options).size == 1) }
                Files.createDirectories(root.resolve(".yde"))
                Files.writeString(root.resolve(".yde/workspace.yaml"), "ledger: [documents]\n")
                Files.createDirectories(root.resolve("documents"))
                Files.writeString(root.resolve("documents/a.yaml"), "title: example\n")
                val workspace = measureTimeMillis { check(Workspace.open(root).files.size == 2) }
                val search = measureTimeMillis {
                    val result = FileCatalog.search(root, "Class${count - 1}.kt", options)
                    check(result.entries.size == 1 && !result.truncated)
                }
                val app = measureTimeMillis { dev.toros.yde.desktop.AppState().openWorkspace(root) }
                var contentResult: ReferenceSearch.Result? = null
                val content = measureTimeMillis {
                    contentResult = ReferenceSearch.search(root, "work() = ${count - 1}", options)
                    check(contentResult!!.hits.size == 1)
                }
                println("files=$count rootListingMs=$open workspaceOpenMs=$workspace appOpenMs=$app filenameSearchMs=$search contentSearchMs=$content contentVisited=${contentResult!!.visited} contentIncomplete=${contentResult!!.incomplete}")
            } finally { root.toFile().deleteRecursively() }
        }
    }
}
