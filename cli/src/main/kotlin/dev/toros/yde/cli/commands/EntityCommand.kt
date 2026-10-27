package dev.toros.yde.cli.commands

import dev.toros.yde.app.core.Aspects
import dev.toros.yde.cli.dumpYaml
import dev.toros.yde.cli.workspaceOf
import dev.toros.yde.semantic.schema.EntityRole
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.pretty

/**
 * エンティティの命令: entity show（全体像＝エンティティページ・read_entity と同じ合成）。変更系（rename / merge / delete・move・refactor）はカーネルの命令 workspace refactor にある。
 * 業務ロジックは置かない（引数を窓口の入力へ写し、結果を YAML へ写すだけ）。入口は AppCli.kt の runCli。
 */

internal fun entityCommand(rest: List<String>): String = run {
    val ws = workspaceOf(rest)
    val op = rest.getOrNull(1) ?: error("操作（show / rename / merge / delete）を指定してください")
    // 全体像の表示（R23-04。architecture/graph-query.md「エンティティページ」）: エンティティページ・read_entity と同じ合成を返す
    if (op == "show") {
        val id = rest.getOrNull(2)?.takeIf { !it.startsWith("--") } ?: error("表示するIDを指定してください")
        val composite = Aspects.compose(id, ws.index, ws.schemas, ws.entityTypes, ws::treeOf)
            ?: error("ID「$id」の出現が見つかりません")
        val idx = ws.index
        return dumpYaml(
            linkedMapOf(
                "id" to composite.id,
                "type" to composite.entityType,
                "typeLabel" to composite.typeLabel?.takeIf { it != composite.entityType },
                "title" to composite.title,
                "aspects" to composite.sections.map { s ->
                    linkedMapOf(
                        "aspect" to s.aspect,
                        "label" to s.label.takeIf { it != s.aspect },
                        "defining" to (s.entry.role == EntityRole.DEFINING).takeIf { it },
                        "file" to s.entry.document.value,
                        "path" to s.entry.path.pretty(),
                        "fields" to s.fields.map { f ->
                            linkedMapOf(
                                "key" to f.key,
                                "value" to (f.value as? YNode.Scalar)?.value,
                                "path" to f.path.pretty(),
                                "duplicateOf" to f.duplicateOf?.let { "${it.first.value} :: ${it.second.pretty()}" },
                            ).filterValues { it != null }
                        },
                    ).filterValues { it != null }
                },
                "unassigned" to composite.unassigned.map { "${it.document.value} :: ${it.path.pretty()}" }.ifEmpty { null },
                // 保有と所属（入れ子で書かれた要素。P40）: 関係パネル・read_entity と同じ 2 節
                "holdings" to idx.graph.holdingsOf(id).map { h ->
                    linkedMapOf(
                        "child" to h.child.id,
                        "relation" to h.relation,
                        "defining" to (h.child.role == EntityRole.DEFINING).takeIf { it },
                        "file" to h.child.document.value,
                        "path" to h.child.path.pretty(),
                    ).filterValues { it != null }
                }.ifEmpty { null },
                "holders" to idx.graph.holdersOf(id).map { h ->
                    linkedMapOf(
                        "parent" to h.parent.id,
                        "relation" to h.relation,
                        "file" to h.child.document.value,
                        "path" to h.child.path.pretty(),
                    )
                }.ifEmpty { null },
                "refs" to idx.outgoingRefs(id).map { r ->
                    linkedMapOf(
                        "to" to r.targetId,
                        "via" to r.viaKey,
                        "relation" to r.relation,
                        "attributes" to r.attributeSummary(),
                    ).filterValues { it != null }
                }.ifEmpty { null },
                "backlinks" to idx.backlinksTo(setOf(id)).map { b ->
                    linkedMapOf(
                        "from" to (idx.sourceEntryOf(b)?.id ?: b.document.value),
                        "file" to b.document.value,
                        "path" to b.path.pretty(),
                        "relation" to b.relation,
                        "attributes" to b.attributeSummary(),
                    ).filterValues { it != null }
                }.ifEmpty { null },
            ).filterValues { it != null }
        )
    }
    // 変更系（rename / merge / delete）はカーネルの命令（Commands.kt）にある（B-1・RB-01: 旧生成器 EntityOps の削除）
    error(
        "entity の変更操作はカーネルの命令 workspace refactor で行います: " +
            "./gradlew runCli --args=\"workspace refactor <workspace> rename-entity <old> <new> --apply\"" +
            "（merge-entity / delete-entity も同様。ここで使えるのは entity show のみ）"
    )
}

internal fun refactorCommand(rest: List<String>): String = run {
    error(
    // スキーマ・リファクタリング（P25）はカーネルの命令（Commands.kt）にある（B-1・RB-01: 旧生成器 SchemaRefactor の削除）
    "refactor はカーネルの命令 workspace refactor で行います: ./gradlew runCli --args=\"workspace refactor <workspace> <op> … --apply\"" +
        "（op: rename-field / move-field / align-order / rename-enum-value / delete-field / require-field / " +
        "convert-type / rename-definition / extract-definition / inline-definition / rename-relation / rename-schema-id）"
)
}

internal fun moveCommand(rest: List<String>): String = run {
    error(
    // ファイル移動（R21-03）はカーネルの命令（Commands.kt）にある（B-1・RB-01: 旧 DocMove の削除）
    "move はカーネルの命令 workspace refactor move-document で行います: ./gradlew runCli --args=\"workspace refactor <workspace> move-document <old> <new> --apply\""
)
}
