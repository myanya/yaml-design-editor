package schema

/**
 * スキーマ文書の意味宣言パース（3層コア移行 WS-002 で SchemaLoader から移動）。
 * entity 型宣言（R20-01 / R23-01）・関係レジストリ（R20-02）・エンティティ型レジストリ（R23-01）・
 * 強制スイッチ（R21-01）を raw Map から組み立てる純粋関数。
 * ルート側 SchemaLoader と workspace-core の読取パイプライン（WorkspaceReader）が共有する。
 */
object SemanticDeclParser {

    /** エンティティ型宣言（R20-01 / R23-01）のパース。単一 object または複数型のリスト（idPattern で判別）。 */
    fun parseEntities(raw: Any?): List<EntityDecl> {
        if (raw == null) return emptyList()
        fun one(m: Any?, where: String): EntityDecl {
            val map = m as? Map<*, *> ?: error("$where は object（type / idPattern / defines / aspect / owns）である必要があります")
            val owns = when (val o = map["owns"]) {
                null -> null
                is List<*> -> o.map { it.toString() }
                else -> error("$where.owns は所有フィールドキーのリストである必要があります")
            }
            return EntityDecl(
                type = map["type"]?.toString()?.takeIf { it.isNotBlank() } ?: error("$where.type（エンティティ型名）が必要です"),
                idPattern = map["idPattern"]?.toString()?.takeIf { it.isNotBlank() },
                defines = (map["defines"] as? Boolean) ?: false,
                aspect = map["aspect"]?.toString()?.takeIf { it.isNotBlank() },
                owns = owns,
            )
        }
        return when (raw) {
            is List<*> -> raw.mapIndexed { i, e -> one(e, "entity[$i]") }
            else -> listOf(one(raw, "entity"))
        }
    }

    /** エンティティ型のレジストリ宣言（R23-01。ARCHITECTURE §4.33）のパース。`entities:` 節。 */
    fun parseEntityTypes(raw: Any?): List<EntityTypeDef> {
        if (raw == null) return emptyList()
        val list = raw as? List<*> ?: error("entities はリストである必要があります")
        return list.mapIndexed { i, e ->
            val m = e as? Map<*, *> ?: error("entities[$i] は object（type / label / description / aspects）である必要があります")
            val aspects = when (val a = m["aspects"]) {
                null -> emptyList()
                is List<*> -> a.mapIndexed { j, entry ->
                    val am = entry as? Map<*, *> ?: error("entities[$i].aspects[$j] は object（name / label）である必要があります")
                    AspectDecl(
                        name = am["name"]?.toString()?.takeIf { it.isNotBlank() }
                            ?: error("entities[$i].aspects[$j].name（アスペクト名）が必要です"),
                        label = am["label"]?.toString(),
                    )
                }
                else -> error("entities[$i].aspects はリストである必要があります")
            }
            EntityTypeDef(
                type = m["type"]?.toString()?.takeIf { it.isNotBlank() } ?: error("entities[$i].type（エンティティ型名）が必要です"),
                label = m["label"]?.toString(),
                description = m["description"]?.toString(),
                aspects = aspects,
            )
        }
    }

    /** 関係レジストリ（R20-02）のパース。cardinality / acyclic は宣言（強制は R21-01 の検証側）。 */
    fun parseRelations(raw: Any?): List<RelationDef> {
        if (raw == null) return emptyList()
        val list = raw as? List<*> ?: error("relations はリストである必要があります")
        fun strings(v: Any?): List<String> = when (v) {
            is List<*> -> v.map { it.toString() }
            null -> emptyList()
            else -> listOf(v.toString())
        }
        return list.mapIndexed { i, e ->
            val m = e as? Map<*, *> ?: error("relations[$i] は object である必要があります")
            RelationDef(
                name = m["name"]?.toString()?.takeIf { it.isNotBlank() } ?: error("relations[$i].name（関係名）が必要です"),
                label = m["label"]?.toString(),
                inverseLabel = m["inverseLabel"]?.toString(),
                source = strings(m["source"]),
                target = strings(m["target"]),
                cardinality = (m["cardinality"] as? Map<*, *>)?.let { c ->
                    CardinalityDef(
                        inMin = (c["inMin"] as? Number)?.toInt(),
                        inMax = (c["inMax"] as? Number)?.toInt(),
                        outMin = (c["outMin"] as? Number)?.toInt(),
                        outMax = (c["outMax"] as? Number)?.toInt(),
                    )
                },
                acyclic = (m["acyclic"] as? Boolean) ?: false,
                level = m["level"]?.toString()?.let { v ->
                    CoverageLevel.entries.firstOrNull { it.id == v }
                        ?: error("relations[$i].level は warning / error のいずれかである必要があります: $v")
                } ?: CoverageLevel.WARNING,
                description = m["description"]?.toString(),
            )
        }
    }

    /** 強制スイッチ（R21-01。ARCHITECTURE §4.31）のパース。各キーは3値（未宣言＝null / warning / error）。 */
    fun parseEnforcement(raw: Any?): EnforcementDef? {
        if (raw == null) return null
        val m = raw as? Map<*, *> ?: error("enforcement は object である必要があります")
        val known = setOf("undeclaredRelation", "definingUniqueness")
        m.keys.map { it.toString() }.firstOrNull { it !in known }?.let {
            error("enforcement の未知のキーです: $it（使用可能: ${known.joinToString(" / ")}）")
        }
        fun level(key: String): CoverageLevel? = m[key]?.toString()?.let { v ->
            CoverageLevel.entries.firstOrNull { it.id == v }
                ?: error("enforcement.$key は warning / error のいずれかである必要があります: $v")
        }
        return EnforcementDef(
            undeclaredRelation = level("undeclaredRelation"),
            definingUniqueness = level("definingUniqueness"),
        )
    }
}
