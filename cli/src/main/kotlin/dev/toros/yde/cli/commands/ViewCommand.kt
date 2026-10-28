package dev.toros.yde.cli.commands

import dev.toros.yde.cli.asData
import dev.toros.yde.cli.CliResult
import dev.toros.yde.app.core.Dashboards
import dev.toros.yde.app.core.DocBacklinkRow
import dev.toros.yde.app.core.DocBacklinksDef
import dev.toros.yde.app.core.Views
import dev.toros.yde.cli.stringFlags
import dev.toros.yde.cli.workspaceOf
import dev.toros.yde.workspace.DocumentScaffolds
import dev.toros.yde.workspace.ScaffoldSource
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.pretty

/**
 * ビューの命令: _views の定義を解決して返す（横断グリッド・文書ビュー・フロー・ダッシュボード・チャート）。各セルに原本の座標が付く。view-id 省略時は定義の一覧。
 * 業務ロジックは置かない（引数を窓口の入力へ写し、結果を YAML へ写すだけ）。入口は AppCli.kt の runCli。
 */

internal fun viewCommand(rest: List<String>): CliResult = run {
    val ws = workspaceOf(rest)
    val loaded = Views.load(ws.root, ws.schemas)
    val viewId = rest.getOrNull(1)?.takeIf { !it.startsWith("--") }
        ?: return asData(
            linkedMapOf(
                "views" to (loaded.views.map { v ->
                    linkedMapOf(
                        "id" to v.id, "name" to v.name,
                        "kind" to if (v.matrix != null) "matrix" else null,
                        "idPattern" to v.rows?.idPattern,
                        "rowKey" to v.rowKey,
                        "columns" to if (v.matrix != null) "（列は cells.columns から広げる）" else v.columns.map { it.label },
                    ).filterValues { it != null }
                } + loaded.docViews.map { v ->
                    linkedMapOf(
                        "id" to v.id, "name" to v.name, "kind" to "document",
                        "match" to v.match, "schemaId" to v.schemaId,
                    ).filterValues { it != null }
                } + loaded.dashboards.map { v ->
                    linkedMapOf("id" to v.id, "name" to v.name, "kind" to "dashboard")
                } + loaded.lensViews.map { v ->
                    linkedMapOf(
                        "id" to v.id, "name" to v.name, "kind" to "lens",
                        "relations" to v.relations.map { "${it.name}: ${it.arrow.id}" },
                    )
                } + loaded.extensionViews.map { v -> v.listing() }),
                "errors" to loaded.errors,
            )
        )
    // 拡張の種類（チャート・フローなど。ADR-0014）: 解決して種類の値を返す（描画は画面だけ）。
    // 文書に縛られる種類（フロー）は view <workspace> <id> <file> で対象の文書を選ぶ（当たる文書が 1 つなら省ける）
    loaded.extensionView(viewId)?.let { def ->
        val document = if (!def.perDocument) null else {
            val fileArg = rest.getOrNull(2)?.takeIf { !it.startsWith("--") }
            val candidates = ws.files.map { ws.relPath(it) }.filter {
                def.appliesTo(DocumentId(it), ws.schemaFor(ws.resolve(it))?.id)
            }.sorted()
            val rel = fileArg?.let {
                val path = ws.resolve(it).toAbsolutePath().normalize()
                require(path.startsWith(ws.root.toAbsolutePath().normalize())) { "${def.kindLabel}の原本はワークスペース内で指定してください" }
                ws.relPath(path).replace('\\', '/')
            } ?: candidates.singleOrNull() ?: return asData(linkedMapOf(
                "view" to def.id, "kind" to def.viewKind, "files" to candidates,
                "hint" to "view <workspace> ${def.id} <file> で対象文書を指定してください",
            ))
            DocumentId(rel)
        }
        return asData(Views.resolveExtension(def, loaded, ws.index, ws::treeOf, ws::schemaFor, document).report(loaded.errors))
    }
    loaded.dashboard(viewId)?.let { dashboard ->
        val dateIndex = rest.indexOf("--date")
        val date = if (dateIndex < 0) java.time.LocalDate.now() else {
            val value = rest.getOrNull(dateIndex + 1) ?: error("--date に YYYY-MM-DD を指定してください")
            runCatching { java.time.LocalDate.parse(value) }.getOrElse { error("--date は YYYY-MM-DD で指定してください") }
        }
        // 共有の入力: --param 名前=値（空文字＝絞らない。渡さなければ定義の default）
        val inputs = stringFlags(rest, "--param").associate { raw ->
            val at = raw.indexOf('=').takeIf { it > 0 } ?: error("--param は 名前=値 で書きます: $raw")
            raw.substring(0, at) to raw.substring(at + 1)
        }
        inputs.keys.firstOrNull { k -> dashboard.inputs.none { it.key == k } }
            ?.let { error("ダッシュボード「${dashboard.id}」に入力「$it」はありません（inputs: ${dashboard.inputs.joinToString(", ") { i -> i.key }.ifEmpty { "なし" }}）") }
        val result = Dashboards.resolve(dashboard, date, loaded, ws.index, ws::schemaFor, ws::treeOf, inputs)
        return asData(Dashboards.report(dashboard, result, loaded.errors))
    }
    // 文書ビュー（R14-01）: view <workspace> <view-id> [file] で対象文書を解決する
    loaded.docView(viewId)?.let { dv ->
        val fileArg = rest.getOrNull(2)?.takeIf { !it.startsWith("--") }?.replace('\\', '/')
        val candidates = ws.files.map { ws.relPath(it) }
            .filter { dv.appliesTo(it, ws.schemaFor(ws.resolve(it))?.id) }
            .sorted()
        val rel = fileArg ?: candidates.singleOrNull() ?: return asData(
            linkedMapOf(
                "view" to dv.id,
                "kind" to "document",
                "files" to candidates,
                "hint" to "view <workspace> ${dv.id} <file> で対象文書を指定してください",
            )
        )
        val sections = Views.resolveDocument(dv, DocumentId(rel), ws.index, loader = ws::treeOf)
        val attachments = Views.resolveAttachments(dv, DocumentId(rel), ws.index, loader = ws::treeOf)
        // 被参照行 → YAML 構造（セクション・アタッチ共用）
        fun rowMaps(rows: List<DocBacklinkRow>?, def: DocBacklinksDef?): List<Map<String, Any?>>? =
            rows?.map { row ->
                linkedMapOf<String, Any?>(
                    "id" to row.id,
                    "title" to row.title,
                    "path" to row.path.pretty(),
                    "backlinks" to row.backlinks.map { b ->
                        linkedMapOf<String, Any?>(
                            "id" to b.sourceId,
                            "title" to b.sourceTitle,
                            "file" to b.document.value,
                            "path" to b.refPath.pretty(),
                            "values" to def?.columns
                                ?.mapIndexed { ci, col -> col.label to Views.cellText(b.values.getOrNull(ci)) }
                                ?.toMap()?.takeIf { it.isNotEmpty() },
                        ).filterValues { it != null }
                    },
                ).filterValues { it != null }
            }
        return asData(
            linkedMapOf(
                "view" to dv.id,
                "kind" to "document",
                "name" to dv.name,
                "file" to rel,
                "sections" to sections.map { resolved ->
                    val section = resolved.section
                    linkedMapOf<String, Any?>(
                        "label" to section.label,
                        "fields" to section.fieldsPath?.let { p -> if (p.isEmpty()) "(root)" else p.pretty() },
                        "include" to section.fieldsInclude.takeIf { it.isNotEmpty() },
                        "exclude" to section.fieldsExclude.takeIf { it.isNotEmpty() },
                        "rows" to rowMaps(resolved.rows, section.backlinks),
                    ).also { m ->
                        // 埋め込んだ拡張の種類（フロー・チャート）: 種類の ID を鍵に、この文書で解決した値
                        section.embed?.let { embed -> m[embed.kind] = Views.embeddedReport(embed, DocumentId(rel), loaded, ws.index, ws::treeOf, ws::schemaFor) }
                    }.filterValues { it != null }
                },
                "attachments" to attachments.map { resolved ->
                    linkedMapOf<String, Any?>(
                        "label" to resolved.attachment.label,
                        "at" to resolved.attachment.atText,
                        "rows" to rowMaps(resolved.rows, resolved.attachment.backlinks),
                    ).filterValues { it != null }
                }.takeIf { it.isNotEmpty() },
            ).filterValues { it != null }
        )
    }
    val view = loaded.view(viewId)
        ?: error("ビュー「$viewId」が見つかりません（view コマンドで一覧を確認できます）")
    val resolved = Views.resolveDetailed(view, ws.index, ws::treeOf, ws::schemaFor)
    val rows = resolved.rows
    asData(
        linkedMapOf<String, Any?>(
            "view" to view.id,
            "name" to view.name,
            // クエリ行の limit で切れたとき（書かなければ上限なし）
            "truncated" to resolved.takeIf { it.truncated }?.let { linkedMapOf("limit" to it.limit, "total" to it.total) },
            // 新規行の入口（配列モード: file+path へ要素追加／ファイルモード: filePattern の {id} 置換先へ文書作成）
            "newRow" to view.newRow?.let { nr ->
                if (nr.filePattern != null) linkedMapOf("filePattern" to nr.filePattern!!.text)
                else linkedMapOf("file" to nr.file, "path" to nr.path.pretty())
            },
            "rows" to rows.map { row ->
                linkedMapOf<String, Any?>(
                    "id" to row.id,
                    "title" to row.title,
                    "cells" to row.cells.map { cell ->
                        // 複合値は要約に留める（全文は read コマンドで座標を指定して取得する）
                        val value = when (cell.value) {
                            is YNode.Scalar -> dev.toros.yde.yaml.YamlIO.toAny(cell.value!!)
                            null -> null
                            else -> Views.cellText(cell.value)
                        }
                        linkedMapOf<String, Any?>(
                            "column" to cell.column.label,
                            "value" to value,
                            "file" to cell.document?.value,
                            "path" to cell.path?.pretty(),
                            "editable" to cell.column.editable.takeIf { it },
                            // 計算列（値の式の列）: 値は評価済み・座標は値の出どころ（ひな型・読み出した値・選ばれた要素）。
                            // derivation が来歴（元値と入力値の座標。値が得られなければ raw がその理由）
                            "computed" to cell.computed.takeIf { it },
                            "derivation" to cell.trace?.takeIf { it.inputs.isNotEmpty() || cell.value == null }?.let { t ->
                                linkedMapOf<String, Any?>(
                                    "raw" to t.raw,
                                    "inputs" to t.inputs.map { input ->
                                        linkedMapOf<String, Any?>(
                                            "placeholder" to input.placeholder,
                                            "value" to input.value,
                                            "file" to input.document?.value,
                                            "path" to input.path?.pretty(),
                                        ).filterValues { it != null }
                                    },
                                )
                            },
                            // バックリンク列: 参照元のIDと座標（参照元の要素へは read コマンドで辿る）
                            "backlinks" to cell.backlinks?.map { b ->
                                linkedMapOf(
                                    "id" to b.sourceId,
                                    "file" to b.document.value,
                                    "path" to b.path.pretty(),
                                ).filterValues { it != null }
                            },
                        ).filterValues { it != null }
                    },
                    "detail" to row.detailDocument?.value,
                    // 詳細が未作成の行の作り先（filePattern を行の要素で評価。決められなければ出さない）
                    "detailCreatePath" to if (row.detailDocument?.value == null)
                        view.detail?.filePattern?.let { p ->
                            runCatching {
                                DocumentScaffolds.pathFor(p, ScaffoldSource.of(row.id, ws.index.occurrences(row.id), ws::treeOf, ws.index.graph))
                            }.getOrNull()
                        } else null,
                ).filterValues { it != null }
            },
        ).filterValues { it != null }
    )
}
