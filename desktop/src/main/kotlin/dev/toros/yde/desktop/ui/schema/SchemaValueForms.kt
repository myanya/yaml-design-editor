package dev.toros.yde.desktop.ui.schema

import dev.toros.yde.semantic.schema.CheckOp
import dev.toros.yde.yaml.YamlIO
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.YPath

/** 編集開始用の形。評価・検証は既存の Query / Predicate / Construct に任せる。 */
internal data class SchemaValuePreset(val group: String, val label: String, val value: Any?) {
    fun node(): YNode = YamlIO.fromAny(value)
}

internal object SchemaValueForms {
    private val labels = mapOf(
        "path" to "項目のパス", "of" to "対象の束縛名", "in" to "値を読む文書", "value" to "値",
        "params" to "渡す入力値", "when" to "実行条件", "where" to "絞り込み", "check" to "判定",
        "match" to "対象の照合", "message" to "メッセージ", "expect" to "期待", "count" to "件数",
        "entity" to "エンティティ型", "schema" to "スキーマID", "schemaId" to "スキーマID",
        "fileGlob" to "ファイルのパターン", "bind" to "束縛名", "relation" to "関係名", "dir" to "方向",
        "set" to "値を設定", "insert" to "要素を追加", "delete" to "項目を削除", "create" to "文書を作成",
        "call" to "操作を呼ぶ", "each" to "繰り返す", "do" to "処理手順", "method" to "操作名",
        "equals" to "等しい値", "notEquals" to "等しくない値", "exists" to "存在する", "empty" to "空である",
        "or" to "いずれか", "not" to "否定", "\$copy" to "コピーする項目", "\$of" to "コピー元の束縛名",
        "\$in" to "コピー元の文書", "\$format" to "文字列のひな型", "\$when" to "使用条件")
    fun label(key: String): String = labels[key]?.let { "$it（$key）" } ?: key
    private fun obj(vararg pairs: Pair<String, Any?>) = linkedMapOf(*pairs)
    private fun preset(group: String, label: String, vararg pairs: Pair<String, Any?>) = SchemaValuePreset(group, label, obj(*pairs))
    private val comparisonLabels = mapOf("eq" to "等しい", "ne" to "等しくない", "lt" to "より小さい", "lte" to "以下", "gt" to "より大きい", "gte" to "以上",
        "oneOf" to "選択肢のいずれか", "matches" to "正規表現に一致", "startsWith" to "で始まる", "endsWith" to "で終わる", "contains" to "を含む")
    private val leaf = obj("path" to "status", "equals" to "OPEN")
    private val check = obj("eq" to listOf(obj("path" to "status"), "OPEN"))
    private val step = obj("set" to obj("path" to "status", "value" to "OPEN"))

    val presets: List<SchemaValuePreset> = listOf(
        SchemaValuePreset("値", "文字列", ""), SchemaValuePreset("値", "整数", 0),
        SchemaValuePreset("値", "小数", 0.0), SchemaValuePreset("値", "真偽値", false),
        SchemaValuePreset("値", "項目の集まり", emptyMap<String, Any?>()), SchemaValuePreset("値", "配列", emptyList<Any?>()), SchemaValuePreset("値", "null", null),
        preset("手順", "値を設定（set）", "set" to obj("path" to "status", "value" to "OPEN")),
        preset("手順", "要素を追加（insert）", "insert" to obj("path" to "comments", "value" to obj("body" to ""))),
        preset("手順", "項目を削除（delete）", "delete" to obj("path" to "result")),
        preset("手順", "文書を作成（create）", "create" to obj("schema" to "detail", "params" to obj())),
        preset("手順", "型の操作を呼ぶ（call）", "call" to obj("of" to "self", "method" to "update", "params" to obj())),
        preset("手順", "配列を繰り返す（each）", "each" to obj("path" to "items", "do" to listOf(step))),
        preset("手順", "照合結果を繰り返す（each）", "each" to obj("from" to "self", "match" to listOf(obj("node" to obj("entity" to "todo", "bind" to "item"))), "do" to listOf(obj("set" to obj("of" to "item", "path" to "status", "value" to "OPEN"))))),
        preset("条件", "値が等しい", "path" to "status", "equals" to "OPEN"),
        preset("条件", "値が等しくない", "path" to "status", "notEquals" to "CLOSED"),
        preset("条件", "項目が存在する", "path" to "result", "exists" to true),
        preset("条件", "項目が空", "path" to "result", "empty" to true),
        preset("条件", "いずれかの条件（or）", "or" to listOf(leaf, obj("path" to "status", "equals" to "IN_PROGRESS"))),
        preset("条件", "条件を否定（not）", "not" to leaf),
        preset("判定", "いずれかの判定（or）", "or" to listOf(check, obj("eq" to listOf(obj("path" to "status"), "IN_PROGRESS")))),
        preset("判定", "判定を否定（not）", "not" to check),
        preset("型の検査", "値を比較する", "check" to listOf(check), "message" to "条件を満たしてください"),
        preset("型の検査", "条件に合う要素を禁止する", "where" to listOf(leaf), "expect" to "absent", "message" to "この状態は許されません"),
        preset("検査", "値を比較する", "match" to listOf(obj("node" to obj("entity" to "todo", "bind" to "self"))),
            "check" to listOf(obj("eq" to listOf(obj("of" to "self", "path" to "status"), "OPEN"))), "message" to "条件を満たしてください"),
        preset("検査", "対象の存在を要求", "match" to listOf(obj("node" to obj("entity" to "todo"))), "expect" to "exists", "message" to "対象が必要です"),
        preset("検査", "対象の件数を検査", "match" to listOf(obj("node" to obj("entity" to "todo"))), "count" to obj("min" to 1), "message" to "必要な件数を満たしてください"),
        preset("照合", "要素を選ぶ（node）", "node" to obj("entity" to "todo", "bind" to "item")),
        preset("照合", "参照を辿る（edge）", "edge" to obj("relation" to "category", "dir" to "out")),
        preset("照合", "保有する要素を辿る", "edge" to obj("contains" to true)),
        preset("ひな型", "値をコピー", "\$copy" to "title"),
        preset("ひな型", "対象の現在値（初期設定）", "\$copy" to "title", "\$of" to "self"),
        preset("ひな型", "操作開始日（初期設定）", "\$copy" to "today", "\$of" to "context"),
        SchemaValuePreset("ひな型", "入力値を使う", "\$value"),
        preset("ひな型", "文字列を組み立てる", "\$format" to "{title}"),
        preset("ひな型", "辞書で置き換える", "\$map" to "OPEN", "\$using" to obj("OPEN" to "未着手")),
        preset("ひな型", "最初に得られる値", "\$first" to listOf(obj("\$copy" to "title"), "未設定")),
        preset("ひな型", "配列の各要素を組み立てる", "\$each" to "items", "\$build" to obj("\$copy" to "title")),
        preset("ひな型", "マップの各項目を組み立てる", "\$eachEntry" to "items", "\$build" to obj("\$copy" to ".")),
        preset("ひな型", "規則を適用する", "\$apply" to "rule"),
        preset("ひな型", "配列を連結する", "\$concat" to listOf(listOf("a"), listOf("b"))),
        preset("ひな型", "日付の表記を揃える", "\$date" to "2026-01-01", "\$as" to "yyyy-MM-dd"),
        preset("ひな型", "数値の表記を揃える", "\$number" to "1"),
        preset("ひな型", "前後の空白を除く", "\$trim" to " 値 "),
        preset("ひな型", "条件を満たすときだけ使う", "\$when" to leaf, "\$then" to "値"),
        preset("値の式", "項目の値を読む", "path" to "title"),
        preset("値の式", "文字列を組み立てる", "format" to "{title}"),
        preset("値の式", "最初に得られる値", "first" to listOf(obj("path" to "title"), "未設定")),
        preset("値の式", "文字数", "length" to obj("path" to "title")),
        preset("値の式", "合計", "sum" to obj("path" to "items[*].amount")),
        preset("値の式", "加算", "add" to listOf(1, 2))
    ) + CheckOp.entries.map { op ->
        preset("判定", comparisonLabels.getValue(op.id), op.id to listOf(obj("path" to "status"), if (op == CheckOp.ONE_OF) listOf("OPEN", "IN_PROGRESS") else "OPEN"))
    } + CheckOp.entries.filter { it !in setOf(CheckOp.EQ, CheckOp.NE) }.map { op ->
        preset("条件", comparisonLabels.getValue(op.id), "path" to "status", op.id to if (op == CheckOp.ONE_OF) listOf("OPEN", "IN_PROGRESS") else "OPEN")
    }

    fun groupFor(path: YPath): String {
        if ("initial" in path && path.lastOrNull() !in setOf("\$when", "where", "when")) return "ひな型"
        val key = path.lastOrNull { it is String }
        return when (key) {
            "do" -> "手順"
            "constraints" -> if (path.firstOrNull() in setOf("root", "definitions")) "型の検査" else "検査"
            "require", "checks" -> "検査"
            "when", "where", "\$when" -> "条件"
            "check" -> "判定"
            "match", "steps" -> "照合"
            "build", "scaffold", "prefix", "\$build", "value" -> "ひな型"
            "filePattern", "left", "right" -> "値の式"
            else -> if (key in CheckOp.entries.map { it.id }) "値の式" else "値"
        }
    }

    fun choices(group: String, path: YPath): List<SchemaValuePreset> = presets.filter { preset ->
        preset.group == group && (!preset.label.endsWith("（初期設定）") || "initial" in path) && ("methods" !in path || group != "手順" ||
            (preset.value as? Map<*, *>)?.let { m -> "create" !in m && "call" !in m && ((m["each"] as? Map<*, *>)?.containsKey("match") != true) } == true)
    }

    /** 欄の追加候補。保存するキーは既存の語彙だけで、入力済みの未知キーもそのまま編集する。 */
    fun suggestedKeys(path: YPath, node: YNode.Map): List<String> {
        val last = path.lastOrNull()
        val keys = when (last) {
            "set" -> listOf("of", "in", "path", "value")
            "insert" -> listOf("of", "in", "path", "params", "value")
            "delete" -> listOf("of", "in", "path")
            "create" -> listOf("schema", "params")
            "call" -> listOf("of", "in", "method", "params")
            "each" -> listOf("of", "in", "path", "from", "match", "where", "also", "limit", "do")
            "node" -> listOf("entity", "schema", "id", "bind", "same", "not", "typed")
            "edge" -> listOf("relation", "dir", "via", "in", "bind", "repeat", "row", "contains")
            "in", "\$in" -> listOf("schemaId", "fileGlob")
            else -> when {
                node.entries.keys.any { it in setOf("set", "insert", "delete", "create", "call", "each") } -> listOf("when")
                "\$copy" in node.entries -> listOf("\$of", "\$in", "\$when")
                "\$format" in node.entries -> listOf("\$of", "\$in", "\$at", "\$with", "\$when")
                "path" in node.entries && groupFor(path) == "値の式" -> listOf("of", "in", "via")
                groupFor(path) == "条件" -> listOf("of", "in", "path")
                groupFor(path) == "型の検査" -> listOf("id", "description", "where", "check", "expect", "level", "message")
                groupFor(path) == "検査" -> listOf("id", "description", "match", "where", "check", "expect", "count", "level", "message", "responses")
                else -> emptyList()
            }
        }
        return keys.filter { it !in node.entries && !("methods" in path && it in setOf("of", "in", "from", "match")) &&
            !("require" in path && it in setOf("level", "responses")) }
    }

    fun propertyNode(key: String): YNode = YamlIO.fromAny(when (key) {
        "when", "where", "\$when", "do", "match", "check", "responses" -> emptyList<Any?>()
        "params", "in", "\$in", "\$with", "count", "not", "row" -> emptyMap<String, Any?>()
        "exists", "empty", "typed", "contains", "repeat" -> true
        "limit" -> 100
        else -> ""
    })
}
