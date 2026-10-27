package dev.toros.yde.app.tools

import dev.toros.yde.yaml.model.YNode
import kotlinx.serialization.json.*

/** JSON の値（ツールの引数・LLM の出力）を YAML の木へ。数は Long → Double の順に、文字はそのまま。 */
fun jsonToYNode(element: JsonElement): YNode = when (element) {
    is JsonObject -> YNode.Map(LinkedHashMap<String, YNode>().apply {
        element.forEach { (key, value) -> put(key, jsonToYNode(value)) }
    })
    is JsonArray -> YNode.Seq(element.map(::jsonToYNode).toMutableList())
    is JsonNull -> YNode.Scalar(null)
    is JsonPrimitive -> YNode.Scalar(
        if (element.isString) element.content
        else element.booleanOrNull ?: element.longOrNull ?: element.doubleOrNull ?: element.content
    )
}
