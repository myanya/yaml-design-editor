import org.gradle.api.Project
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register

/** 全件実行と単独実行で同じ Test タスクの結果を使う。分類はクラスファイルで固定する。 */
fun Project.testPartition(name: String, patterns: List<String>): TaskProvider<Test> {
    val source = extensions.getByType<SourceSetContainer>().getByName("test")
    val partition = tasks.register<Test>(name) {
        group = "verification"
        testClassesDirs = source.output.classesDirs
        classpath = source.runtimeClasspath
        include(patterns)
    }
    val all = tasks.named<Test>("test") {
        exclude(patterns)
        dependsOn(partition)
    }
    // --tests は指定されたタスクだけに渡るため、依存する区分にも同じ条件を渡す。
    // CLI のオプションが設定された後、実行・入力の指紋の計算より前にそろえる。
    gradle.taskGraph.whenReady {
        if (hasTask(all.get())) {
            // Gradle 9.3 の公開 TestFilter には CLI 条件の getter が無い。
            val patterns = (all.get().filter as DefaultTestFilter).commandLineIncludePatterns
            partition.get().setTestNameIncludePatterns(patterns.toList())
        }
    }
    return partition
}
