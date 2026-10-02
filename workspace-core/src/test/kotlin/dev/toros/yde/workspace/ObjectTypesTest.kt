package dev.toros.yde.workspace

import dev.toros.yde.semantic.schema.EntityRole
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.parseYPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * オブジェクト型（P33 第三弾）のテスト。
 * - object の定義とその場の object に同一性（entity）を書け、走査は要素の型からエンティティ型を決める（ID の形に頼らない）
 * - `type: 名前` で object を使い、定義に無いキーは末尾に足し、定義にあるキーは同じ項目を狭める（必須にする・決まりを狭める・型を狭めた型に）
 * - 緩める・基底の種類を変える・狭めていない型・同一性の重ね書きは定義の誤り
 * - 追加のある使い方は構造の検証へ展開して写る（足した必須・狭めた長さ・狭めた形式が効く）
 */
class ObjectTypesTest {

    private val types = """
        id: types
        kind: library
        definitions:
          部品ID: { type: id, pattern: "^PT-\\d+$" }
          メール: { type: string, maxLength: 100, pattern: "^[^@]+@[^@]+$" }
          社員メール: { type: メール, pattern: "^[^@]+@example\\.com$" }
          部品:
            type: object
            label: 部品マスタ
            entity: { defines: true }
            fields:
              - { key: id, type: 部品ID, required: true }
              - { key: name, label: 名前, type: line, role: title, required: true, maxLength: 50 }
              - { key: summary, label: 概要, type: string }
              - { key: contact, label: 連絡先, type: メール }
          明細行:
            type: object
            fields:
              - { key: price, label: 単価, type: int, min: 0, required: true }
              - { key: qty, label: 数量, type: int, min: 1, required: true }
    """.trimIndent()

    /** 文書スキーマ。parts は配列の要素の定義（インデント無しで書き、ここで item の下へ入れる）。 */
    private fun assembly(parts: String): String {
        val head = """
            id: assembly
            match: "asm/*.yaml"
            entity: { type: assembly, idPattern: "ASM-*", defines: true }
            root:
              type: object
              fields:
                - { key: id, type: id, required: true }
                - key: parts
                  type: array
                  item:
        """.trimIndent()
        val tail = """
                - key: events
                  type: array
                  item:
                    type: object
                    entity: { type: event, defines: true }
                    fields:
                      - { key: id, type: id, required: true }
                      - { key: name, type: string, role: title }
                - key: lines
                  type: array
                  item: { type: types#明細行 }
        """.trimIndent().lines().joinToString("\n") { "    $it" }
        val item = parts.trimIndent().lines().joinToString("\n") { "        $it" }
        return "$head\n$item\n$tail\n"
    }

    private val extendedParts = """
        type: types#部品
        fields:
          - { key: summary, required: true, label: 概要（必須） }
          - { key: name, maxLength: 20 }
          - { key: contact, type: types#社員メール }
          - { key: owner, label: 担当, type: string }
    """.trimIndent()

    private fun load(files: Map<String, String>) = WorkspaceReader.load(
        InMemoryDocumentStore(files),
        builtins = BuiltinSchemaResources.kernelSchemas(WorkspaceLayout.LEGACY),
    )

    private fun structural(ws: LoadedWorkspace, rel: String) = ws.derived.structuralIssues[DocumentId(rel)].orEmpty().map { it.message }

    @Test
    fun `要素の型の同一性で型が決まり 使う側は項目を足し同じ項目を狭める`() {
        val ws = load(
            mapOf(
                "_schemas/types.yaml" to types,
                "_schemas/assembly.yaml" to assembly(extendedParts),
                "asm/a.yaml" to """
                    id: ASM-1
                    parts:
                      - { id: PT-1, name: ボルト, summary: 六角, contact: a@example.com, owner: 佐藤 }
                      - { id: PT-2, name: ${"x".repeat(25)}, contact: b@other.com }
                    events:
                      - { id: EV-1, name: 組立 }
                    lines:
                      - { price: 100, qty: 2 }
                """.trimIndent(),
            )
        )
        assertEquals(emptyList(), ws.schemaErrors)
        // 走査: 部品は定義名がエンティティ型（entity.type 省略）、その場の object は書いた型、ルートはスキーマの entity（ID の形）
        val graph = ws.derived.semantic.graph
        assertEquals("部品", graph.entities.getValue("PT-1").entityType)
        assertEquals(EntityRole.DEFINING, graph.entities.getValue("PT-1").defining?.role)
        assertEquals("event", graph.entities.getValue("EV-1").entityType)
        assertEquals("assembly", graph.entities.getValue("ASM-1").entityType)
        // 表示名: レジストリに宣言の無いエンティティ型は、型（object の定義）の label が表示名。label の無いその場の object は型名のまま
        assertEquals("部品マスタ", ws.declarations.entityTypes.first { it.type == "部品" }.displayLabel)
        assertEquals("event", ws.declarations.entityTypes.first { it.type == "event" }.displayLabel)
        // 合成: 定義の項目の順のまま、同じキーは狭まり、新しいキーは末尾
        val schema = ws.schemas.first { it.id == "assembly" }
        val item = schema.tree.resolve(schema.tree.root.fields.first { it.key == "parts" }.item)!!
        assertEquals(listOf("id", "name", "summary", "contact", "owner"), item.fields.map { it.key })
        assertEquals("部品", item.entity?.type)
        assertEquals(listOf("types#部品"), item.lineage)
        val summary = item.fields.first { it.key == "summary" }
        assertTrue(summary.required)
        assertEquals("概要（必須）", summary.label)
        assertEquals(FieldType.STRING, summary.type)
        assertEquals(20, item.fields.first { it.key == "name" }.maxLength)
        val contact = schema.tree.resolve(item.fields.first { it.key == "contact" })!!
        assertEquals("types#社員メール", contact.lineage.first())
        assertEquals(listOf("^[^@]+@[^@]+$", "^[^@]+@example\\.com$"), contact.patterns)
        assertEquals(100, contact.maxLength)
        // データのパスからも同じ合成が引ける（ビュー・エンティティ合成・所有の判定が使う）
        val root = ws.snapshot.documents.getValue(DocumentId("asm/a.yaml")).document.documents.first()
        assertEquals("部品", schema.tree.fieldAt(root, parseYPath("parts[0]"))?.entity?.type)
        // 構造の検証: 足した必須・狭めた長さ・狭めた形式が効く（追加のある使い方は展開して写る）
        val issues = structural(ws, "asm/a.yaml")
        assertTrue(issues.any { it == "必須項目「概要（必須）」が未設定です" }, issues.toString())
        assertTrue(issues.any { it.startsWith("「名前」は 20 文字以内で入力してください") }, issues.toString())
        assertTrue(issues.any { "「連絡先」は形式（^[^@]+@example\\.com$）に一致していません" in it }, issues.toString())
        assertEquals(3, issues.size, issues.toString())
        // ID の形の宣言（ASM-*）に合わなくても、要素の型で型が決まった要素は「一致しません」と指摘されない
        val delivered = WorkspaceCheckRunner.deliverAll(ws)[DocumentId("asm/a.yaml")]?.issues.orEmpty().map { it.message }
        assertTrue(delivered.none { "一致しません" in it }, delivered.toString())
    }

    @Test
    fun `追加の無い使い方は定義への参照のまま写り ルートを型で書けばスキーマの entity は省略できる`() {
        val ws = load(
            mapOf(
                "_schemas/types.yaml" to types,
                "_schemas/widget.yaml" to "id: widget\nmatch: \"w/*.yaml\"\nroot: { type: types#部品 }\n",
                "_schemas/registry.yaml" to "id: registry\nkind: relations\nentities:\n  - { type: 部品, label: レジストリの部品 }\nrelations:\n  - { name: uses }\n",
                "w/p.yaml" to "id: PT-9\nname: ナット\n",
                "w/q.yaml" to "id: PT-10\nname: ${"x".repeat(60)}\ncontact: bad\n",
            )
        )
        assertEquals(emptyList(), ws.schemaErrors)
        val graph = ws.derived.semantic.graph
        assertEquals("部品", graph.entities.getValue("PT-9").entityType)
        assertEquals(EntityRole.DEFINING, graph.entities.getValue("PT-9").defining?.role)
        // レジストリの宣言があれば、型の label より勝つ
        assertEquals("レジストリの部品", ws.declarations.entityTypes.first { it.type == "部品" }.displayLabel)
        assertEquals(emptyList(), structural(ws, "w/p.yaml"))
        val q = structural(ws, "w/q.yaml")
        assertTrue(q.any { it.startsWith("「名前」は 50 文字以内で入力してください") }, q.toString())
        assertTrue(q.any { "「連絡先」は形式（^[^@]+@[^@]+$）に一致していません" in it }, q.toString())
        val delivered = WorkspaceCheckRunner.deliverAll(ws)[DocumentId("w/p.yaml")]?.issues.orEmpty()
        assertTrue(delivered.isEmpty(), delivered.toString())
    }

    @Test
    fun `緩める 基底の種類を変える 狭めていない型 同一性の重ね書きは定義の誤り`() {
        val bad = """
            type: types#部品
            entity: { type: part }
            fields:
              - { key: name, maxLength: 80 }
              - { key: summary, type: int }
              - { key: contact, type: line }
        """.trimIndent()
        val ws = load(mapOf("_schemas/types.yaml" to types, "_schemas/assembly.yaml" to assembly(bad)))
        val errors = ws.schemaErrors
        assertTrue(errors.any { "maxLength 80 は型「types#部品.name」の 50 より緩いため書けません" in it }, errors.toString())
        assertTrue(errors.any { "項目「summary」の型 int（int）は型「types#部品」の項目の型（string）と違うため書けません" in it }, errors.toString())
        assertTrue(errors.any { "項目「contact」の型 line は型「types#部品」の項目の型「types#メール」を狭めていないため書けません" in it }, errors.toString())
        assertTrue(errors.any { "entity（同一性）は型「types#部品」に既にあるため書けません" in it }, errors.toString())
        // その場の object の entity は type が必要。ID の形は書けない。object 以外には書けない
        fun error(schema: String) = load(mapOf("_schemas/types.yaml" to types, "_schemas/x.yaml" to schema)).schemaErrors.joinToString("\n")
        assertTrue("entity.type（エンティティ型名）が必要です" in error("id: x\nmatch: \"x/*.yaml\"\nroot:\n  type: object\n  entity: { defines: true }\n  fields: [ { key: id, type: id } ]\n"))
        assertTrue("entity に idPattern は書けません" in error("id: x\nmatch: \"x/*.yaml\"\nroot:\n  type: object\n  entity: { type: a, idPattern: \"A-*\" }\n  fields: [ { key: id, type: id } ]\n"))
        assertTrue("entity（同一性）は type: object のフィールドでのみ定義できます" in error("id: x\nmatch: \"x/*.yaml\"\nroot:\n  type: object\n  fields: [ { key: id, type: id, entity: { type: a } } ]\n"))
        assertTrue("entity（同一性）は object でのみ使用できます" in error("id: x\nmatch: \"x/*.yaml\"\nroot:\n  type: object\n  fields: [ { key: m, type: types#メール, entity: { type: a } } ]\n"))
    }

    @Test
    fun `型の検査は要素ごとに評価され ID の無い型でも書け 使う側の検査を足せる`() {
        val typesWithChecks = """
            id: types
            kind: library
            definitions:
              明細行:
                type: object
                fields:
                  - { key: price, label: 単価, type: int, min: 0, required: true }
                  - { key: qty, label: 数量, type: int, min: 1, required: true }
                  - { key: amount, label: 金額, type: int }
                  - { key: from, label: 開始, type: date }
                  - { key: to, label: 終了, type: date }
                constraints:
                  - id: amount-is-price-times-qty
                    description: 金額は単価 × 数量であること
                    check: [ { eq: [ { path: amount }, { multiply: [ { path: price }, { path: qty } ] } ] } ]
                    level: error
                  - id: period
                    description: 開始は終了以前であること
                    check: [ { lte: [ { path: from }, { path: to } ] } ]
                  - id: no-free
                    description: 単価 0 の明細は無いこと
                    where: [ { path: price, equals: "0" } ]
                    expect: absent
                    message: "単価 0 の明細があります（{path}）"
              部品ID: { type: id, pattern: "^PT-\\d+$" }
              メール: { type: string, maxLength: 100, pattern: "^[^@]+@[^@]+$" }
              社員メール: { type: メール, pattern: "^[^@]+@example\\.com$" }
              部品:
                type: object
                entity: { defines: true }
                fields:
                  - { key: id, type: 部品ID, required: true }
                  - { key: name, label: 名前, type: line, role: title, required: true, maxLength: 50 }
                  - { key: summary, label: 概要, type: string }
                  - { key: contact, label: 連絡先, type: メール }
        """.trimIndent()
        val lines = """
            type: types#明細行
            constraints:
              - id: qty-limit
                description: 数量は 10 以下であること
                check: [ { lte: [ { path: qty }, 10 ] } ]
        """.trimIndent()
        val ws = load(
            mapOf(
                "_schemas/types.yaml" to typesWithChecks,
                "_schemas/assembly.yaml" to assembly(extendedParts).replace("item: { type: types#明細行 }", "item:\n" + lines.lines().joinToString("\n") { "        $it" }),
                "asm/a.yaml" to """
                    id: ASM-1
                    parts: []
                    events: []
                    lines:
                      - { price: 100, qty: 2, amount: 200, from: 2026-01-01, to: 2026-01-31 }
                      - { price: 100, qty: 3, amount: 250, from: 2026-02-01, to: 2026-01-15 }
                      - { price: 0, qty: 20, amount: 0 }
                      - { price: 50, qty: 1 }
                """.trimIndent(),
            )
        )
        assertEquals(emptyList(), ws.schemaErrors)
        val delivered = WorkspaceCheckRunner.deliverAll(ws).getValue(DocumentId("asm/a.yaml"))
        val byPath = delivered.issues.groupBy { it.path.joinToString("/") }
        // 1 行目: すべて満たす
        assertNull(byPath["lines/0"])
        // 2 行目: 金額の違反（反例に式）と、日付の違反（日付として比べる）。指摘は要素の場所
        val second = byPath.getValue("lines/1").map { it.message }
        assertTrue(second.any { it == "types#明細行 の要素（lines[1]）が検査を満たしません。反例: amount: 250 ≠ 「300」（= 100 × 3） — 金額は単価 × 数量であること" }, second.toString())
        assertTrue(second.any { it == "types#明細行 の要素（lines[1]）が検査を満たしません。反例: from: 2026-02-01 > to: 2026-01-15 — 開始は終了以前であること" }, second.toString())
        assertEquals(dev.toros.yde.yaml.validate.Severity.ERROR, delivered.issues.first { it.message.startsWith("types#明細行 の要素（lines[1]）が検査を満たしません。反例: amount") }.severity)
        // 3 行目: where に合う要素があってはならない（文面のひな型）。数量の上限は使う側で足した検査
        val third = byPath.getValue("lines/2").map { it.message }
        assertTrue(third.any { it == "単価 0 の明細があります（lines[2]） — 単価 0 の明細は無いこと" }, third.toString())
        assertTrue(third.any { it.contains("反例: qty: 20 > 「10」 — 数量は 10 以下であること") }, third.toString())
        // 4 行目: 金額が無い → 判定保留（指摘ではない）
        assertNull(byPath["lines/3"])
        assertTrue(delivered.withheld.any { it.path == listOf("lines", 3) && it.ruleId == "amount-is-price-times-qty" }, delivered.withheld.toString())
        // 他の要素へ線を辿る検査は型には書けない
        val bad = load(mapOf("_schemas/x.yaml" to "id: x\nmatch: \"x/*.yaml\"\nroot:\n  type: object\n  entity: { type: a }\n  constraints:\n    - { id: r, match: [ { edge: { relation: uses } }, { node: { entity: b } } ], expect: exists }\n  fields: [ { key: id, type: id } ]\n"))
        assertTrue(bad.schemaErrors.any { "型の検査に match は書けません" in it }, bad.schemaErrors.toString())
    }

    @Test
    fun `型を省いた書き直しは定義の型のままで 必須は外せず 表示のヒントは使う側が勝つ`() {
        val parts = """
            type: types#部品
            fields:
              - { key: id, required: false, label: 部品番号 }
              - { key: contact, placeholder: 社員のメール }
        """.trimIndent()
        val ws = load(mapOf("_schemas/types.yaml" to types, "_schemas/assembly.yaml" to assembly(parts)))
        assertEquals(emptyList(), ws.schemaErrors)
        val schema = ws.schemas.first { it.id == "assembly" }
        val item = schema.tree.resolve(schema.tree.root.fields.first { it.key == "parts" }.item)!!
        val id = schema.tree.resolve(item.fields.first { it.key == "id" })!!
        assertTrue(id.required, "必須は外せない（required: false は効かない）")
        assertEquals("部品番号", id.label)
        assertEquals(FieldType.ID, id.type)
        assertEquals(listOf("^PT-\\d+$"), id.patterns)
        val contact = schema.tree.resolve(item.fields.first { it.key == "contact" })!!
        assertEquals("社員のメール", contact.placeholder)
        assertEquals(FieldType.STRING, contact.type)
        assertEquals("types#メール", contact.lineage.first())
        assertNull(item.fields.firstOrNull { it.key == "nonsense" })
        assertNotNull(item.entity)
    }
}
