package index

import schema.SchemaLoader
import yaml.YamlIO
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocScannerTest {

    private val schema = SchemaLoader.parseSchemaText(
        """
        id: screen
        name: 画面
        match: "screens/**/*.yaml"
        root:
          type: object
          fields:
            - key: type
              type: enum
              values: [radio, text]
              cases:
                radio:
                  - key: options
                    type: array
                    item:
                      type: object
                      fields:
                        - key: id
                          type: id
                        - key: rel
                          type: ref
        """.trimIndent(),
        Path.of("_schemas/screen.yaml"),
    )

    @Test
    fun `アクティブなcase配下のIDと参照が索引化される`() {
        val root = YamlIO.parse(
            """
            type: radio
            options:
              - id: OPT-1
                rel: TARGET-1
            """.trimIndent()
        )
        val (ids, refs) = DocScanner.scan(root, Path.of("screens/a.yaml"), schema)
        assertEquals(listOf("OPT-1"), ids.map { it.id })
        assertEquals(listOf("TARGET-1"), refs.map { it.targetId })
    }

    @Test
    fun `非アクティブなcase配下はスキーマ境界として索引化されない`() {
        val root = YamlIO.parse(
            """
            type: text
            options:
              - id: OPT-1
                rel: TARGET-1
            """.trimIndent()
        )
        val (ids, refs) = DocScanner.scan(root, Path.of("screens/a.yaml"), schema)
        assertTrue(ids.isEmpty())
        assertTrue(refs.isEmpty())
    }

    // ---------------------------------------------------------------- 属性付き参照（R20-03）

    private val attributedSchema = SchemaLoader.parseSchemaText(
        """
        id: attributed
        name: 属性付き参照
        match: "screens/**/*.yaml"
        root:
          type: object
          fields:
            - key: id
              type: id
            - key: actors
              type: array
              item:
                type: ref
                relation: uses
                fields:
                  - key: role
                    type: string
            - key: owner
              type: ref
        """.trimIndent(),
        Path.of("_schemas/attributed.yaml"),
    )

    @Test
    fun `属性付き参照はtoの座標で索引化され属性が写しとして載る`() {
        val root = YamlIO.parse(
            """
            id: SCR-1
            actors:
              - to: ACT-ADMIN
                role: 承認者
              - ACT-USER
            owner: ACT-OWNER
            """.trimIndent()
        )
        val (_, refs) = DocScanner.scan(root, Path.of("screens/a.yaml"), attributedSchema)
        val attributed = refs.first { it.targetId == "ACT-ADMIN" }
        // path は to 値の座標（ジャンプ・パッチ・バックリンクの既存セマンティクス。§4.30）
        assertEquals(listOf("actors", 0, "to"), attributed.path)
        assertEquals("uses", attributed.relation)
        assertEquals(mapOf("role" to "承認者"), attributed.attributes)
        // 同じフィールドのスカラー形式・宣言なしの通常 ref も従来どおり
        val scalarForm = refs.first { it.targetId == "ACT-USER" }
        assertEquals(listOf("actors", 1), scalarForm.path)
        assertTrue(scalarForm.attributes.isEmpty())
        assertEquals(listOf("owner"), refs.first { it.targetId == "ACT-OWNER" }.path)
    }

    @Test
    fun `fields宣言のないrefにオブジェクトが来ても参照として索引化しない`() {
        val root = YamlIO.parse(
            """
            id: SCR-1
            owner:
              to: ACT-OWNER
              role: 管理者
            """.trimIndent()
        )
        val (_, refs) = DocScanner.scan(root, Path.of("screens/a.yaml"), attributedSchema)
        assertTrue(refs.none { it.targetId == "ACT-OWNER" }, refs.toString())
    }

    @Test
    fun `ネストしたcase配下のIDと参照も索引化される`() {
        val nestedSchema = SchemaLoader.parseSchemaText(
            """
            id: nested-cases
            name: ネストしたcases
            match: "screens/**/*.yaml"
            root:
              type: object
              fields:
                - key: value-type
                  type: enum
                  values: [direct, reference]
                  cases:
                    direct:
                      - key: type
                        type: enum
                        values: [select, text]
                        cases:
                          select:
                            - key: options
                              type: array
                              item:
                                type: object
                                fields:
                                  - key: id
                                    type: id
                                  - key: rel
                                    type: ref
            """.trimIndent(),
            Path.of("_schemas/nested-cases.yaml"),
        )
        val root = YamlIO.parse(
            """
            value-type: direct
            type: select
            options:
              - id: OPT-1
                rel: TARGET-1
            """.trimIndent(),
        )

        val (ids, refs) = DocScanner.scan(root, Path.of("screens/a.yaml"), nestedSchema)

        assertEquals(listOf("OPT-1"), ids.map { it.id })
        assertEquals(listOf("TARGET-1"), refs.map { it.targetId })
    }
}
