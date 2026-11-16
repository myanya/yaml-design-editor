package dev.toros.yde.semantic

import dev.toros.yde.semantic.TermComposition.Change
import dev.toros.yde.semantic.TermComposition.KeySpace
import dev.toros.yde.semantic.TermComposition.Priority
import dev.toros.yde.semantic.TermComposition.Record
import dev.toros.yde.semantic.TermComposition.Source
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.pretty

/**
 * Construct の `$compose` と Rewrite の候補合成をつなぐ、項の入出力形式。
 * 設計項目の ID・属性名・補正の保存形式は知らない。それらからの対応付けは変換定義が行う。
 * ここで読むのは合成の命令の形だけで、value の型と制約は後段の既存の検査に任せる。
 */
internal object TermCompositionFormat {
    fun compose(input: YNode?, where: String): YNode {
        val root = objectAt(input, where, setOf("derived", "additions", "changes"))
        val derived = listAt(root["derived"], "$where.derived").mapIndexed { i, n -> record(n, "$where.derived[$i]") }
        val additions = listAt(root["additions"], "$where.additions").mapIndexed { i, n -> record(n, "$where.additions[$i]") }
        val changes = listAt(root["changes"], "$where.changes").flatMapIndexed { i, n -> changes(n, "$where.changes[$i]") }
        val result = TermComposition.compose(derived, additions, changes)
        require(result.issues.isEmpty()) {
            result.issues.joinToString("\n") { issue ->
                val sources = issue.sources.joinToString("、") { s ->
                    s.id + (s.document?.let { "（${it.value}:${s.path.pretty()}）" } ?: "")
                }
                "$where: ${issue.message}（根拠: $sources）"
            }
        }
        return YamlIO.fromAny(linkedMapOf(
            "records" to requireNotNull(result.records).map { r ->
                linkedMapOf<String, Any?>("id" to r.id, "value" to YamlIO.toAny(r.value), "source" to sourceValue(r.source)).apply {
                    r.keySpace?.let { put("keySpace", mapOf("scope" to it.scope, "path" to it.path)) }
                }
            },
            "decisions" to result.decisions.map { d ->
                linkedMapOf("target" to d.target, "path" to d.path,
                    "candidates" to d.candidates.map(::changeValue), "selected" to d.selected.map(::changeValue))
            },
        ))
    }

    private fun record(node: YNode, where: String): Record {
        val m = objectAt(node, where, setOf("id", "value", "source", "keySpace"))
        val value = m["value"] as? YNode.Map ?: error("$where.value は object で書きます")
        val keySpace = m["keySpace"]?.let { n ->
            val k = objectAt(n, "$where.keySpace", setOf("scope", "path"))
            KeySpace(textAt(k["scope"], "$where.keySpace.scope"), pathAt(k["path"], "$where.keySpace.path", nonEmpty = true))
        }
        return Record(textAt(m["id"], "$where.id"), value, source(m["source"], "$where.source"), keySpace)
    }

    private fun changes(node: YNode, where: String): List<Change> {
        val m = objectAt(node, where, setOf("target", "path", "value", "set", "priority", "source"))
        val priority = when (textAt(m["priority"], "$where.priority")) {
            "rule" -> Priority.RULE
            "individual" -> Priority.INDIVIDUAL
            else -> error("$where.priority は rule か individual で書きます")
        }
        val target = textAt(m["target"], "$where.target")
        val source = source(m["source"], "$where.source")
        if ("set" in m) {
            require("path" !in m && "value" !in m) { "$where: set と path・value は併記できません" }
            val attributes = (m["set"] as? YNode.Map)?.entries ?: error("$where.set は object で書きます")
            require(attributes.isNotEmpty()) { "$where.set には変更する属性を書きます" }
            return attributes.map { (key, value) -> Change(target, listOf(key), value, priority, source) }
        }
        // null・false・0・空文字・空配列と、キーが無いことを区別する。
        val value = m["value"] ?: error("$where.value がありません")
        return listOf(Change(target, pathAt(m["path"], "$where.path", nonEmpty = true), value, priority, source))
    }

    private fun source(node: YNode?, where: String): Source {
        val m = objectAt(node, where, setOf("id", "document", "path"))
        return Source(textAt(m["id"], "$where.id"), m["document"]?.let { DocumentId(textAt(it, "$where.document")) },
            m["path"]?.let { pathAt(it, "$where.path") }.orEmpty())
    }

    private fun sourceValue(s: Source): Map<String, Any?> = linkedMapOf<String, Any?>("id" to s.id).apply {
        s.document?.let { put("document", it.value) }
        put("path", s.path)
    }

    private fun changeValue(c: Change): Map<String, Any?> = linkedMapOf(
        "target" to c.target, "path" to c.path, "value" to YamlIO.toAny(c.value),
        "priority" to if (c.priority == Priority.RULE) "rule" else "individual", "source" to sourceValue(c.source),
    )

    private fun objectAt(node: YNode?, where: String, keys: Set<String>): Map<String, YNode> {
        val m = (node as? YNode.Map)?.entries ?: error("$where は object で書きます")
        require(m.keys.all { it in keys }) { "$where: 未知のキーです: ${(m.keys - keys).joinToString("、")}" }
        return m
    }

    private fun listAt(node: YNode?, where: String): List<YNode> =
        (node as? YNode.Seq)?.items ?: error("$where は配列で書きます（候補が無いときは []）")

    private fun textAt(node: YNode?, where: String): String =
        ((node as? YNode.Scalar)?.value as? String)?.takeIf { it.isNotBlank() } ?: error("$where は空でない文字で書きます")

    private fun pathAt(node: YNode?, where: String, nonEmpty: Boolean = false): YPath {
        val path = listAt(node, where).map { (it as? YNode.Scalar)?.value }
        require((!nonEmpty || path.isNotEmpty()) && path.all { it is String || (it is Int && it >= 0) }) {
            "$where はキーの文字と 0 以上の要素番号の配列で書きます${if (nonEmpty) "（空は不可）" else ""}"
        }
        return path.filterNotNull()
    }
}
