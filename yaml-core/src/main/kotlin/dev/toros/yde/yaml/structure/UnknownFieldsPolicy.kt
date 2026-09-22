package dev.toros.yde.yaml.structure

/**
 * `object` に含まれる未定義キーの検証方針。未指定時は WARNING。
 * 構造の決まりなので、yaml-core に置く（最小の構造のスキーマ structure.StructureField が使う。
 * 利用者が書くスキーマの項目の木 FieldDef は semantic-core にあり、同じ型を使う）。
 */
enum class UnknownFieldsPolicy(val id: String) {
    ALLOW("allow"),
    WARNING("warning"),
    ERROR("error");

    companion object {
        fun parse(value: String?): UnknownFieldsPolicy? =
            value?.let { raw -> entries.firstOrNull { it.id == raw } }
    }
}
