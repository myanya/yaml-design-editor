package dev.toros.yde.workspace

import dev.toros.yde.yaml.YamlIO

/**
 * ビューのメタスキーマ（builtin-schemas/view.yaml）に、拡張の種類の断片（ViewKind.schemaFragment）を合成する（ADR-0014）。
 * 断片が足せるのは 3 つだけ: `kind` の選択肢 1 つ・ルートの項目（`fields`）・文書ビューの区画の項目（`section`）。
 * 項目の名前が中核や他の種類とぶつかれば誤り（登録の誤りなので、開く前に気付かせる）。
 */
object ViewSchemaFragments {

    private val fragmentKeys = setOf("kind", "fields", "section")

    /** base（view.yaml の原文）に kinds の断片を足した原文。kinds が空なら base のまま。 */
    fun compose(base: String, kinds: List<ViewKind>): String {
        if (kinds.isEmpty()) return base
        @Suppress("UNCHECKED_CAST")
        val schema = copy(YamlIO.parseToRaw(base)) as MutableMap<String, Any?>
        val rootFields = fieldsOf(schema["root"], "root")
        val kindValues = listAt(field(rootFields, "kind"), "values", "root.kind")
        val sectionFields = fieldsOf(field(rootFields, "sections")["item"], "root.sections.item")
        for (kind in kinds) {
            val where = "ビューの種類「${kind.id}」の断片"
            val fragment = YamlIO.parseToRaw(kind.schemaFragment) as? Map<*, *> ?: error("$where はobjectで書きます")
            fragment.keys.firstOrNull { it !in fragmentKeys }?.let { error("$where に書けないキーです: $it（書けるのは kind / fields / section）") }
            val choice = fragment["kind"] as? Map<*, *> ?: error("$where に kind（選択肢 1 つ）が必要です")
            require(choice["value"] == kind.id) { "$where の kind.value「${choice["value"]}」が種類の ID「${kind.id}」と違います" }
            require(kindValues.none { (it as? Map<*, *>)?.get("value") == kind.id }) { "ビューの種類「${kind.id}」は kind の選択肢にすでにあります" }
            kindValues += copy(choice)
            (fragment["fields"] as? List<*> ?: emptyList<Any?>()).forEach { add(rootFields, it, where, "ルート") }
            val section = fragment["section"]
            if (section != null) {
                require(kind.sectionKey != null && (section as? Map<*, *>)?.get("key") == kind.sectionKey) {
                    "$where の section.key は種類の sectionKey（${kind.sectionKey ?: "なし"}）と同じにします"
                }
                add(sectionFields, section, where, "文書ビューの区画")
            }
        }
        return YamlIO.dump(YamlIO.fromAny(schema))
    }

    private fun add(list: MutableList<Any?>, field: Any?, where: String, place: String) {
        val key = (field as? Map<*, *>)?.get("key") ?: error("$where の項目に key がありません")
        require(list.none { (it as? Map<*, *>)?.get("key") == key }) { "$where の項目「$key」は${place}にすでにあります" }
        list += copy(field)
    }

    @Suppress("UNCHECKED_CAST")
    private fun field(fields: MutableList<Any?>, key: String): MutableMap<String, Any?> =
        fields.firstOrNull { (it as? Map<*, *>)?.get("key") == key } as? MutableMap<String, Any?>
            ?: error("ビューのメタスキーマに項目「$key」がありません")

    @Suppress("UNCHECKED_CAST")
    private fun fieldsOf(obj: Any?, where: String): MutableList<Any?> =
        (obj as? MutableMap<String, Any?>)?.get("fields") as? MutableList<Any?> ?: error("ビューのメタスキーマの $where に fields がありません")

    @Suppress("UNCHECKED_CAST")
    private fun listAt(obj: MutableMap<String, Any?>, key: String, where: String): MutableList<Any?> =
        obj[key] as? MutableList<Any?> ?: error("ビューのメタスキーマの $where に $key がありません")

    /** 書き換えられる写し（Map は挿入順を保つ）。 */
    private fun copy(value: Any?): Any? = when (value) {
        is Map<*, *> -> LinkedHashMap<String, Any?>().also { m -> value.forEach { (k, v) -> m[k.toString()] = copy(v) } }
        is List<*> -> value.mapTo(ArrayList()) { copy(it) }
        else -> value
    }
}
