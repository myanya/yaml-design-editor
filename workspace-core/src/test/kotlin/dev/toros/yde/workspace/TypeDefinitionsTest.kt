package dev.toros.yde.workspace

import dev.toros.yde.semantic.schema.BuiltinTypes
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.SchemaCatalog
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.model.DocumentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ユーザ定義の型（P33 第一弾）のテスト。
 * - `type: 名前` で definitions のスカラーの定義（型）を使い、決まりが検証に効く（型から型への連鎖も）
 * - 狭める規則: 緩める書き方は定義の誤り。合成は緩めない向きに倒す
 * - 組込みの型 text（定義体）: 型の連鎖で見分け、基底の種類は string。メタスキーマは型の名前を受け付ける
 * - 参照の絞り込みの重ね合わせ（idPatterns の層）
 */
class TypeDefinitionsTest {

    private val common = """
        id: common
        kind: library
        definitions:
          画面ID: { type: id, pattern: "^SCR-\\d{3}$" }
          メールアドレス: { type: string, maxLength: 100, pattern: "^[^@]+@[^@]+$" }
          社員メール: { type: メールアドレス, pattern: "^[^@]+@example\\.com$", maxLength: 50 }
          金額: { type: int, min: 0, display: grouped }
          画面参照: { type: ref, idPattern: "SCR-*", relation: next, scope: { schemaIds: [screen] } }
          状態: { type: enum, values: [open, closed, archived] }
    """.trimIndent()

    private fun screenSchema(extraFields: String = "") = """
        id: screen
        match: "screens/*.yaml"
        entity: { type: screen, idPattern: "SCR-*", defines: true }
        root:
          type: object
          fields:
            - { key: id, label: 画面ID, required: true, type: common#画面ID }
            - { key: mail, label: 連絡先, type: common#メールアドレス }
            - { key: mail3, label: 社員の連絡先, type: common#社員メール }
            - { key: budget, label: 予算, type: common#金額 }
            - { key: next, label: 次の画面, type: common#画面参照 }
            - { key: note, label: 備考, type: text }
        $extraFields
    """.trimIndent()

    private fun load(files: Map<String, String>) = WorkspaceReader.load(
        InMemoryDocumentStore(files),
        builtins = BuiltinSchemaResources.kernelSchemas(WorkspaceLayout.LEGACY),
    )

    private fun structural(ws: LoadedWorkspace, rel: String) = ws.derived.structuralIssues[DocumentId(rel)].orEmpty().map { it.message }

    @Test
    fun `型の名前で definitions の決まりが検証に効き 型から型への連鎖も解決される`() {
        val ws = load(
            mapOf(
                "_schemas/common.yaml" to common,
                "_schemas/screen.yaml" to screenSchema(),
                "screens/a.yaml" to "id: SCR-001\nmail: a@example.com\nmail3: b@example.com\nbudget: 100\nnext: SCR-002\nnote: |\n  複数行の\n  備考\n",
                "screens/b.yaml" to "id: SCR-2\nmail: invalid\nmail3: ${"a".repeat(60)}@example.com\nbudget: -5\n",
                "screens/c.yaml" to "id: SCR-002\nmail3: c@other.com\n",
            )
        )
        assertEquals(emptyList(), ws.schemaErrors)
        assertEquals(emptyList(), structural(ws, "screens/a.yaml"))
        val b = structural(ws, "screens/b.yaml")
        assertTrue(b.any { "「画面ID」は形式（^SCR-\\d{3}$）に一致していません" in it }, b.toString())
        assertTrue(b.any { "「連絡先」は形式（^[^@]+@[^@]+$）に一致していません" in it }, b.toString())
        assertTrue(b.any { "「社員の連絡先」は 50 文字以内で入力してください" in it }, b.toString())
        assertTrue(b.any { "「予算」は 0 以上で入力してください" in it }, b.toString())
        // 型から型へ: 社員メール はメールアドレスの形式と、自分の形式の両方に一致すること
        val c = structural(ws, "screens/c.yaml")
        assertTrue(c.any { "「社員の連絡先」は形式（^[^@]+@example\\.com$）に一致していません" in it }, c.toString())

        val screen = ws.schemas.first { it.id == "screen" }
        val mail3 = screen.tree.resolve(screen.tree.root.fields.first { it.key == "mail3" })!!
        assertEquals(FieldType.STRING, mail3.type)
        assertEquals(listOf("common#社員メール", "common#メールアドレス"), mail3.lineage)
        assertEquals(listOf("^[^@]+@[^@]+$", "^[^@]+@example\\.com$"), mail3.patterns)
        assertEquals(50, mail3.maxLength)
        val budget = screen.tree.resolve(screen.tree.root.fields.first { it.key == "budget" })!!
        assertEquals(FieldType.INT, budget.type)
        assertEquals("grouped", budget.hints.numberDisplay?.id)
        // 参照の型: idPattern・scope・relation を引き継ぐ
        val next = screen.tree.resolve(screen.tree.root.fields.first { it.key == "next" })!!
        assertEquals(FieldType.REF, next.type)
        assertEquals(listOf("SCR-*"), next.idPatterns)
        assertEquals("next", next.relation)
        assertEquals(listOf("screen"), next.scope.schemaIds)
    }

    @Test
    fun `組込みの型 text は型の連鎖で見分け 基底の種類は string`() {
        val ws = load(mapOf("_schemas/common.yaml" to common, "_schemas/screen.yaml" to screenSchema()))
        assertEquals(emptyList(), ws.schemaErrors)
        val screen = ws.schemas.first { it.id == "screen" }
        val note = screen.tree.resolve(screen.tree.root.fields.first { it.key == "note" })!!
        assertEquals(FieldType.STRING, note.type)
        assertTrue(note.isA(BuiltinTypes.TEXT))
        assertEquals(listOf(BuiltinTypes.TEXT), note.lineage)
        // メタスキーマ: 型の名前（組込みの型・利用者の型）が type の選択肢に足されている
        val meta = ws.schemas.first { it.id == WorkspaceReader.META_SCHEMA_ID }
        val typeField = meta.tree.definitions.getValue("field").fields.first { it.key == "type" }
        assertTrue("text" in typeField.values && "common#金額" in typeField.values && "金額" in typeField.values, typeField.values.toString())
        // 型の名前で書いた項目の決まり（maxLength）は基底の種類と同じ扱いで、メタスキーマの警告にならない
        assertEquals(emptyList(), structural(ws, "_schemas/screen.yaml").filter { "maxLength" in it })
    }

    @Test
    fun `組込みの型 date time datetime は string と形式の定義体で ヒントを型から引き継ぐ`() {
        val ws = load(
            mapOf(
                "_schemas/common.yaml" to """
                    id: common
                    kind: library
                    definitions:
                      予約日: { type: date, description: 会議室を使う日 }
                      予約時刻: { type: time, placeholder: "HH:MM（30 分刻み）" }
                """.trimIndent(),
                "_schemas/doc.yaml" to """
                    id: doc
                    match: "d/*.yaml"
                    root:
                      type: object
                      fields:
                        - { key: day, type: common#予約日 }
                        - { key: start, type: common#予約時刻 }
                        - { key: at, type: datetime }
                        - { key: note, type: text }
                        - { key: memo, type: string, display: multiline }
                """.trimIndent(),
                "d/a.yaml" to "day: 2026-10-01\nstart: 09:30\nat: 2026-10-01 09:30\n",
                "d/b.yaml" to "day: 2026/10/1\nstart: 9:30\nat: 20261001\n",
            )
        )
        assertEquals(emptyList(), ws.schemaErrors)
        assertEquals(emptyList(), structural(ws, "d/a.yaml"))
        val b = structural(ws, "d/b.yaml")
        assertTrue(b.any { "「day」は形式（^\\d{4}-\\d{2}-\\d{2}$）に一致していません" in it }, b.toString())
        assertTrue(b.any { "「start」は形式" in it }, b.toString())
        assertTrue(b.any { "「at」は形式" in it }, b.toString())

        val doc = ws.schemas.first { it.id == "doc" }
        fun field(key: String) = doc.tree.resolve(doc.tree.root.fields.first { it.key == key })!!
        val day = field("day")
        assertEquals(FieldType.STRING, day.type)
        assertTrue(day.isA(BuiltinTypes.DATE))
        assertEquals(listOf("common#予約日", BuiltinTypes.DATE), day.lineage)
        // ヒントは型から引き継ぎ、使う側（型から型へ）に書いたものが優先
        assertEquals("YYYY-MM-DD", day.placeholder)
        assertEquals("2026-01-01", day.sample)
        assertEquals("会議室を使う日", day.description)
        assertEquals("HH:MM（30 分刻み）", field("start").placeholder)
        assertEquals("09:00", field("start").sample)
        assertTrue(field("note").multiline)
        assertTrue(field("memo").multiline)
        assertTrue(!day.multiline)
        // サンプル YAML はヒントのサンプル値を使う
        val sample = dev.toros.yde.semantic.schema.SchemaScaffold.sample(doc.tree) as dev.toros.yde.yaml.model.YNode.Map
        assertEquals("2026-01-01", (sample.entries["day"] as dev.toros.yde.yaml.model.YNode.Scalar).value)
        assertEquals("2026-01-01 09:00", (sample.entries["at"] as dev.toros.yde.yaml.model.YNode.Scalar).value)
    }

    @Test
    fun `display multiline は基底の種類が string の型にだけ書ける`() {
        val ws = load(mapOf("_schemas/bad.yaml" to "id: bad\nmatch: \"x/*.yaml\"\nroot:\n  type: object\n  fields:\n    - { key: n, type: int, display: multiline }\n"))
        assertTrue(ws.schemaErrors.any { "multiline" in it }, ws.schemaErrors.toString())
    }

    @Test
    fun `未知の型の名前は定義の誤りで メタスキーマも選択肢の誤りにする`() {
        val ws = load(mapOf("_schemas/bad.yaml" to "id: bad\nmatch: \"x/*.yaml\"\nroot:\n  type: object\n  fields:\n    - { key: v, type: nonsense }\n"))
        assertTrue(ws.schemaErrors.any { "未知の型です: nonsense" in it }, ws.schemaErrors.toString())
        assertTrue(structural(ws, "_schemas/bad.yaml").any { "nonsense" in it }, structural(ws, "_schemas/bad.yaml").toString())
    }

    @Test
    fun `狭める規則 緩める書き方は定義の誤りになり 合成は緩めない`() {
        val ws = load(
            mapOf(
                "_schemas/common.yaml" to common,
                "_schemas/screen.yaml" to """
                    id: screen
                    match: "screens/*.yaml"
                    root:
                      type: object
                      fields:
                        - { key: mail, type: common#メールアドレス, maxLength: 200 }
                        - { key: budget, type: common#金額, min: -1, max: 10 }
                        - { key: status, type: common#状態, values: [open, deleted] }
                        - { key: status2, type: common#状態, values: [closed] }
                """.trimIndent(),
                "screens/a.yaml" to "mail: ${"a".repeat(150)}@example.com\nbudget: 5\nstatus: open\nstatus2: open\n",
            )
        )
        val errors = ws.schemaErrors.joinToString("\n")
        assertTrue("maxLength 200 は型「common#メールアドレス」の 100 より緩いため書けません" in errors, errors)
        assertTrue("min -1 は型「common#金額」の 0 より緩いため書けません" in errors, errors)
        assertTrue("選択肢 deleted は型「common#状態」に無いため書けません" in errors, errors)
        assertTrue("max 10" !in errors, errors)
        // 合成は緩めない: 150 文字は型の 100 文字を超えるので誤り。選択肢の部分集合は効く
        val a = structural(ws, "screens/a.yaml")
        assertTrue(a.any { "100 文字以内" in it }, a.toString())
        assertTrue(a.any { "「status2」の値「open」は選択肢 [closed]" in it }, a.toString())
    }

    @Test
    fun `型の連鎖の循環と cases の重ね書きは定義の誤り`() {
        val ws = load(
            mapOf(
                "_schemas/lib.yaml" to """
                    id: lib
                    kind: library
                    definitions:
                      a: { type: b }
                      b: { type: a }
                      種別:
                        type: enum
                        values: [x, y]
                        cases:
                          x:
                            - { key: extra, type: string }
                """.trimIndent(),
                "_schemas/doc.yaml" to """
                    id: doc
                    match: "d/*.yaml"
                    root:
                      type: object
                      fields:
                        - key: kind
                          type: lib#種別
                          cases:
                            y:
                              - { key: other, type: string }
                """.trimIndent(),
            )
        )
        val errors = ws.schemaErrors.joinToString("\n")
        assertTrue("型の定義が循環しています: lib#a → lib#b → lib#a" in errors, errors)
        assertTrue("cases は型「lib#種別」に既にあるため書けません" in errors, errors)
    }

    @Test
    fun `参照の型の絞り込みは使う側で重ね合わせて狭められる`() {
        val ws = load(
            mapOf(
                "_schemas/common.yaml" to common,
                "_schemas/screen.yaml" to """
                    id: screen
                    match: "screens/*.yaml"
                    entity: { type: screen, idPattern: "SCR-*", defines: true }
                    root:
                      type: object
                      fields:
                        - { key: id, type: common#画面ID, required: true }
                        - { key: next, type: common#画面参照, idPattern: "SCR-1*" }
                """.trimIndent(),
                "screens/a.yaml" to "id: SCR-101\nnext: SCR-201\n",
                "screens/b.yaml" to "id: SCR-201\nnext: SCR-101\n",
            )
        )
        assertEquals(emptyList(), ws.schemaErrors)
        val screen = ws.schemas.first { it.id == "screen" }
        val next = screen.tree.resolve(screen.tree.root.fields.first { it.key == "next" })!!
        assertEquals(listOf(listOf("SCR-*"), listOf("SCR-1*")), next.idPatternLayers)
        val messages = ws.derived.semantic.issues.map { it.message }
        // SCR-201 は型の層（SCR-*）には合うが、使う側の層（SCR-1*）に合わないので解決できない。SCR-101 は両方に合う
        assertTrue(messages.any { "SCR-201" in it }, messages.toString())
        assertTrue(messages.none { "SCR-101" in it }, messages.toString())
    }

    @Test
    fun `メタスキーマへの型の名前の合成は 組込みの型を含み 既にある名前は足さない`() {
        val names = MetaSchemaTypes.typeNames(SchemaCatalog.linked(emptyList()))
        assertEquals(FieldType.STRING, names["text"])
        assertEquals(FieldType.STRING, names[BuiltinTypes.TEXT])
        val meta = BuiltinSchemaResources.metaSchemaTree
        val composed = MetaSchemaTypes.withTypeNames(meta, names)
        val typeField = composed.definitions.getValue("field").fields.first { it.key == "type" }
        assertTrue("text" in typeField.values)
        assertEquals(typeField.cases.getValue("string"), typeField.cases.getValue("text"))
        assertEquals(composed, MetaSchemaTypes.withTypeNames(composed, names))
        // 組込みスキーマの一式のメタスキーマにも組込みの型が足されている（builtin-schema 自身が type: text を使う）
        val kernelMeta = BuiltinSchemaResources.kernelSchemas(WorkspaceLayout.LEGACY).first { it.id == WorkspaceReader.META_SCHEMA_ID }
        assertTrue("text" in kernelMeta.tree.definitions.getValue("field").fields.first { it.key == "type" }.values)
    }
}
