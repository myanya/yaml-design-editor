package dev.toros.yde.yaml.edit

import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.YamlIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 型付き編集操作（YamlOperation / YamlEdit）の契約テスト（Phase 4・YM-007b）。
 * - すべての操作に逆操作があり、順に適用すると dump（コメント・スタイル・キー順込み）が元に戻る
 * - 失敗する操作・EditBatch の途中失敗は文書を変更しない
 * - SetScalar は値域の閉集合と往復型安全（§4.1）を守る
 */
class YamlEditTest {

    private fun tree(text: String): YNode = YamlIO.parse(text.trimIndent())
    private fun dump(node: YNode): String = YamlIO.dump(node)

    /** 適用 → 逆適用で dump が完全に元へ戻ることを確認する共通ヘルパ。 */
    private fun roundTrip(before: String, op: YamlOperation): String {
        val root = tree(before)
        val original = dump(root)
        val inverse = YamlEdit.apply(root, op)
        val applied = dump(root)
        inverse.forEach { YamlEdit.apply(root, it) }
        assertEquals(original, dump(root), "逆適用で元に戻る（コメント・スタイル・キー順込み）")
        return applied
    }

    @Test
    fun `SetScalarは既存値を置換し逆適用でスタイルとコメントごと元に戻る`() {
        val applied = roundTrip(
            "# 先頭コメント\ntitle: \"old\"  # 行内コメント\nowner: x",
            YamlOperation.SetScalar(parseYPath("title"), "new"),
        )
        assertTrue("\"new\"" in applied, "引用符スタイルが継承される: $applied")
        assertTrue("行内コメント" in applied, "コメントが継承される: $applied")
    }

    @Test
    fun `SetScalarは新規キーを作成し逆操作はRemoveになる`() {
        val root = tree("a: 1")
        val inverse = YamlEdit.apply(root, YamlOperation.SetScalar(parseYPath("b"), true))
        assertEquals(listOf<YamlOperation>(YamlOperation.Remove(parseYPath("b"))), inverse)
        inverse.forEach { YamlEdit.apply(root, it) }
        assertEquals(dump(tree("a: 1")), dump(root))
    }

    @Test
    fun `SetScalarは閉集合外の値を拒否し文書を変更しない`() {
        val root = tree("a: 1")
        val before = dump(root)
        assertFailsWith<IllegalArgumentException> {
            YamlEdit.apply(root, YamlOperation.SetScalar(parseYPath("a"), java.util.Date()))
        }
        assertEquals(before, dump(root))
    }

    @Test
    fun `曖昧な文字列のSetScalarはdump後の再parseで型が変わらない`() {
        val root = tree("a: 1")
        YamlEdit.apply(root, YamlOperation.SetScalar(parseYPath("b"), "true"))
        YamlEdit.apply(root, YamlOperation.SetScalar(parseYPath("c"), "2026-08-16"))
        YamlEdit.apply(root, YamlOperation.SetScalar(parseYPath("d"), true))
        val out = dump(root)
        val reparsed = YamlIO.parse(out) as YNode.Map
        assertEquals("true", (reparsed.entries["b"] as YNode.Scalar).value, "文字列 \"true\" は文字列のまま: $out")
        assertEquals("2026-08-16", (reparsed.entries["c"] as YNode.Scalar).value, "日付らしい文字列は文字列のまま: $out")
        assertEquals(true, (reparsed.entries["d"] as YNode.Scalar).value, "Boolean は Boolean のまま: $out")
    }

    @Test
    fun `Insertの逆操作はRemoveで配列が元に戻る`() {
        roundTrip("items:\n- a\n- c", YamlOperation.Insert(parseYPath("items[1]"), YNode.Scalar("b")))
    }

    @Test
    fun `マップ中間キーのRemoveは逆適用でキー順まで戻る`() {
        val applied = roundTrip("a: 1\nb: 2  # bのコメント\nc: 3", YamlOperation.Remove(parseYPath("b")))
        assertTrue("b:" !in applied, "削除が効いている: $applied")
    }

    @Test
    fun `配列要素のRemoveも逆適用で元に戻る`() {
        roundTrip("items:\n- a\n- b\n- c", YamlOperation.Remove(parseYPath("items[1]")))
    }

    @Test
    fun `Moveの逆適用で元に戻る（配列内とコンテナ間）`() {
        roundTrip("items:\n- a\n- b\n- c", YamlOperation.Move(parseYPath("items[2]"), parseYPath("items[0]")))
        roundTrip("draft:\n- a\ndone: []", YamlOperation.Move(parseYPath("draft[0]"), parseYPath("done[0]")))
    }

    @Test
    fun `マップ中間キーのMoveは逆適用でキー順まで戻る`() {
        roundTrip("a: 1\nb: 2\nc: 3\nnest: {}", YamlOperation.Move(parseYPath("b"), parseYPath("nest.b")))
    }

    @Test
    fun `Move先が不正なら文書は変わらない`() {
        val root = tree("a: 1\nb: 2\nc: 3")
        val before = dump(root)
        assertFailsWith<IllegalStateException> {
            YamlEdit.apply(root, YamlOperation.Move(parseYPath("b"), parseYPath("missing.z")))
        }
        assertEquals(before, dump(root), "削除だけ適用された状態を残さない（キー順含む）")
    }

    @Test
    fun `RenameKeyの逆適用で元に戻る`() {
        val applied = roundTrip(
            "details:\n  priority: high  # コメント\n  memo: m",
            YamlOperation.RenameKey(parseYPath("details.priority"), "urgency"),
        )
        assertTrue("urgency:" in applied && "priority" !in applied, applied)
    }

    @Test
    fun `ReorderKeysの逆適用で元の順に戻る`() {
        val applied = roundTrip("c: 3\na: 1\nz: 26\nb: 2", YamlOperation.ReorderKeys(emptyList(), listOf("a", "b")))
        assertTrue(applied.indexOf("a:") < applied.indexOf("b:") && applied.indexOf("b:") < applied.indexOf("c:"), applied)
    }

    @Test
    fun `EditBatchの途中失敗は適用済み分を巻き戻す`() {
        val root = tree("title: x\nitems:\n- a")
        val before = dump(root)
        assertFailsWith<IllegalStateException> {
            YamlEdit.applyBatch(
                root,
                EditBatch(
                    listOf(
                        YamlOperation.SetScalar(parseYPath("title"), "変更後"),
                        YamlOperation.Remove(parseYPath("items[0]")),
                        YamlOperation.Insert(parseYPath("title"), YNode.Scalar("既存キーで失敗")),
                    )
                ),
            )
        }
        assertEquals(before, dump(root), "部分変更を残さない")
    }

    @Test
    fun `EditBatchの逆操作列で全変更が元に戻る`() {
        val root = tree("# ヘッダ\ntitle: x\nitems:\n- a\n- b")
        val original = dump(root)
        val inverse = YamlEdit.applyBatch(
            root,
            EditBatch(
                listOf(
                    YamlOperation.SetScalar(parseYPath("title"), "変更後"),
                    YamlOperation.Remove(parseYPath("items[0]")),
                    YamlOperation.SetScalar(parseYPath("owner"), "yamada"),
                    YamlOperation.RenameKey(parseYPath("title"), "name"),
                )
            ),
        )
        assertTrue("name:" in dump(root) && "yamada" in dump(root))
        inverse.forEach { YamlEdit.apply(root, it) }
        assertEquals(original, dump(root))
    }
}
