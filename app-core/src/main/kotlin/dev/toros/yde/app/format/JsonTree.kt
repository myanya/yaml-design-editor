package dev.toros.yde.app.format

import dev.toros.yde.yaml.model.YNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.BigInteger

/**
 * JSON と木（`YNode`）の読み書き（skill の木のままの取り込みと、木を組み立てる書き出し）。
 * - 読み: 入れ子・キーの順・数の型（整数は YAML を読んだときと同じ Int / Long、小数は Double）・null をそのまま木にする
 * - 書き: 数と文字は JavaScript の `JSON.stringify` と同じ書き方（小数部の無い数は整数・指数表記の境目・日本語はそのまま・
 *   制御文字だけ \u で書く）。他のツールが書いた JSON を読んで書き戻しても、値が同じなら同じ文字になるようにするため
 */
object JsonTree {

    fun parse(text: String): YNode = nodeOf(Json.parseToJsonElement(text.removePrefix("﻿")))

    fun write(node: YNode, style: TreeStyle, finalNewline: Boolean): String {
        val text = when (style) {
            TreeStyle.COMPACT -> compact(node)
            TreeStyle.PRETTY -> StringBuilder().also { pretty(node, it, "") }.toString()
            TreeStyle.LINES -> lines(node)
        }
        return if (finalNewline) text + "\n" else text
    }

    // ---------------------------------------------------------------- 読み

    private val INTEGER = Regex("""-?(0|[1-9][0-9]*)""")

    private fun nodeOf(e: JsonElement): YNode = when (e) {
        is JsonObject -> YNode.Map(LinkedHashMap<String, YNode>().also { m -> e.forEach { (k, v) -> m[k] = nodeOf(v) } })
        is JsonArray -> YNode.Seq(e.mapTo(mutableListOf()) { nodeOf(it) })
        is JsonNull -> YNode.Scalar(null)
        is JsonPrimitive -> YNode.Scalar(
            when {
                e.isString -> e.content
                e.content == "true" -> true
                e.content == "false" -> false
                INTEGER.matches(e.content) -> e.content.toIntOrNull() ?: e.content.toLongOrNull() ?: BigInteger(e.content)
                else -> e.content.toDouble()
            }
        )
    }

    // ---------------------------------------------------------------- 書き

    private fun compact(node: YNode): String = when (node) {
        is YNode.Scalar -> scalar(node.value)
        is YNode.Seq -> node.items.joinToString(",", "[", "]") { compact(it) }
        is YNode.Map -> node.entries.entries.joinToString(",", "{", "}") { (k, v) -> quote(k) + ":" + compact(v) }
    }

    private fun pretty(node: YNode, out: StringBuilder, indent: String) {
        val inner = "$indent  "
        when (node) {
            is YNode.Scalar -> out.append(scalar(node.value))
            is YNode.Seq -> if (node.items.isEmpty()) out.append("[]") else {
                out.append("[\n")
                node.items.forEachIndexed { i, item ->
                    out.append(inner)
                    pretty(item, out, inner)
                    out.append(if (i < node.items.size - 1) ",\n" else "\n")
                }
                out.append(indent).append(']')
            }
            is YNode.Map -> if (node.entries.isEmpty()) out.append("{}") else {
                out.append("{\n")
                val last = node.entries.size - 1
                node.entries.entries.forEachIndexed { i, (k, v) ->
                    out.append(inner).append(quote(k)).append(": ")
                    pretty(v, out, inner)
                    out.append(if (i < last) ",\n" else "\n")
                }
                out.append(indent).append('}')
            }
        }
    }

    private fun lines(node: YNode): String = when (node) {
        is YNode.Seq -> if (node.items.isEmpty()) "[]" else node.items.joinToString(",\n", "[\n", "\n]") { compact(it) }
        is YNode.Map -> if (node.entries.isEmpty()) "{}"
        else node.entries.entries.joinToString(",\n", "{\n", "\n}") { (k, v) -> quote(k) + ":" + compact(v) }
        is YNode.Scalar -> compact(node)
    }

    private fun scalar(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Int, is Long, is Short, is Byte, is BigInteger -> value.toString()
        is Double -> number(value)
        is Float -> number(value.toDouble())
        is BigDecimal -> if (value.signum() == 0 || value.stripTrailingZeros().scale() <= 0) value.toBigInteger().toString() else number(value.toDouble())
        is Number -> number(value.toDouble())
        else -> quote(value.toString())
    }

    /**
     * 数を JavaScript の Number の文字（`Number.prototype.toString`）と同じ形にする。桁は値を区別できる最短の並び
     * （Java の Double.toString と同じ並び）で、21 桁未満は指数を使わず、小さい数は 0.000001 までを小数で書く。
     */
    internal fun number(d: Double): String {
        require(d.isFinite()) { "JSON に書けない数です: $d" }
        if (d == 0.0) return "0"
        val exact = BigDecimal(java.lang.Double.toString(Math.abs(d))).stripTrailingZeros()
        val digits = exact.unscaledValue().toString()
        val k = digits.length
        val n = k - exact.scale()  // 値 = 0.digits × 10^n
        val body = when {
            n in k..21 -> digits + "0".repeat(n - k)
            n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + digits
            else -> {
                val e = n - 1
                val mantissa = if (k == 1) digits else digits[0] + "." + digits.substring(1)
                mantissa + "e" + (if (e >= 0) "+" else "-") + Math.abs(e)
            }
        }
        return if (d < 0) "-$body" else body
    }

    /** 文字を JSON の文字列に（JavaScript の JSON.stringify と同じ: " と \ と制御文字だけを書き換え、対になっていないサロゲートは \u で）。 */
    internal fun quote(text: String): String {
        val out = StringBuilder(text.length + 2).append('"')
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u").append(String.format("%04x", c.code))
                c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> { out.append(c).append(text[i + 1]); i++ }
                c.isSurrogate() -> out.append("\\u").append(String.format("%04x", c.code))
                else -> out.append(c)
            }
            i++
        }
        return out.append('"').toString()
    }
}
