package dev.toros.yde.desktop

import androidx.compose.runtime.*
import dev.toros.yde.workspace.WorkspacePaths
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.YamlIO
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 図の対象と選択条件。開閉・選択・文書の版・表示中のノード集合は含めない。 */
data class DiagramContext(val diagram: Map<String, String>, val selection: Map<String, List<String>>) {
    internal fun diagramValue() = diagram.toSortedMap()
    internal fun selectionValue() = selection.toSortedMap().mapValues { it.value.distinct().sorted() }
    internal fun canonical() = copy(diagram = diagramValue(), selection = selectionValue())
}

/** 配列の添字だけで別のノードへ固定位置を渡さない。出現は ID と文書・原本パスも照合する。 */
data class DiagramNodeId(val kind: String, val key: String, val document: String? = null, val path: String? = null) {
    internal fun value(): Map<String, String> = buildMap {
        put("kind", kind); put("key", key)
        document?.let { put("document", it) }; path?.let { put("path", it) }
    }
}

/** frame が無いときは図の原点、それ以外は親の内容領域の左上を基準にする。単位は dip。 */
data class DiagramPosition(val node: DiagramNodeId, val frame: DiagramNodeId?, val x: Float, val y: Float, val pinned: Boolean)

data class DiagramPositionRead(val positions: Map<DiagramNodeId, DiagramPosition>, val text: String?, val warning: String? = null)

/** 計算結果の保存で再配置を起動しない。密度変換の丸めだけを許容する。 */
internal fun sameDiagramPositions(actual: Map<DiagramNodeId, DiagramPosition>, saved: Map<DiagramNodeId, DiagramPosition>): Boolean =
    actual.all { (id, point) -> saved[id]?.let {
        point.frame == it.frame && kotlin.math.abs(point.x - it.x) < .001f && kotlin.math.abs(point.y - it.y) < .001f
    } == true }

/** 現在位置と手動固定を保存する現行形式。過去形式の読込・移行は持たない。 */
internal object DiagramPositionCodec {
    fun encode(context: DiagramContext, positions: Map<DiagramNodeId, DiagramPosition>): String {
        require(positions.size <= 10000) { "位置の件数が上限を超えています" }
        val values = positions.values.sortedBy { YamlIO.dumpPlain(YamlIO.fromAny(it.node.value())) }.map { pin ->
            require(pin.x.isFinite() && pin.y.isFinite() && kotlin.math.abs(pin.x) <= 1_000_000f && kotlin.math.abs(pin.y) <= 1_000_000f) { "固定位置の値が範囲外です" }
            linkedMapOf("node" to pin.node.value(), "frame" to (pin.frame?.value() ?: mapOf("root" to true)),
                "at" to mapOf("x" to pin.x.toDouble(), "y" to pin.y.toDouble()), "pinned" to pin.pinned)
        }
        return YamlIO.dumpPlain(YamlIO.fromAny(linkedMapOf("diagram" to context.diagramValue(),
            "selection" to context.selectionValue(), "unit" to "dip", "positions" to values)))
    }

    fun decode(text: String, context: DiagramContext): Map<DiagramNodeId, DiagramPosition> {
        fun map(value: Any?) = value as? Map<*, *> ?: error("配置記録の項目がマップではありません")
        fun keys(value: Map<*, *>, allowed: Set<String>) {
            require(value.keys.all { it in allowed }) { "配置記録に未対応の項目があります" }
        }
        fun node(value: Any?): DiagramNodeId {
            val m = map(value); keys(m, setOf("kind", "key", "document", "path"))
            fun string(key: String) = m[key]?.let { require(it is String && it.isNotBlank()) { "ノードの識別子が不正です" }; it }
            return DiagramNodeId(requireNotNull(string("kind")) { "ノードの種類がありません" },
                requireNotNull(string("key")) { "ノードの識別子がありません" }, string("document"), string("path"))
        }
        val root = map(YamlIO.parseToRaw(text)); keys(root, setOf("diagram", "selection", "unit", "positions"))
        require(root["unit"] == "dip") { "配置記録の座標単位を読み取れません" }
        require(root["diagram"] == context.diagramValue() && root["selection"] == context.selectionValue()) {
            "配置記録の対象・選択条件が一致しません"
        }
        val pins = root["positions"] as? List<*> ?: error("位置の一覧がありません")
        require(pins.size <= 10000) { "固定位置の件数が上限を超えています" }
        val result = linkedMapOf<DiagramNodeId, DiagramPosition>()
        for (value in pins) {
            val m = map(value); keys(m, setOf("node", "frame", "at", "pinned"))
            val id = node(m["node"])
            val frame = map(m["frame"]).let { if (it == mapOf("root" to true)) null else node(it) }
            val at = map(m["at"]); keys(at, setOf("x", "y"))
            fun coordinate(key: String): Float = (at[key] as? Number)?.toFloat()?.also {
                require(it.isFinite() && kotlin.math.abs(it) <= 1_000_000f) { "固定位置の値が範囲外です" }
            } ?: error("固定位置は数で指定してください")
            require(id !in result && id != frame) { "固定位置の識別子が重複しているか、自分を親にしています" }
            val pinned = m["pinned"] as? Boolean ?: error("固定の有無は真偽値で指定してください")
            result[id] = DiagramPosition(id, frame, coordinate("x"), coordinate("y"), pinned)
        }
        return result
    }
}

/** ワークスペース内の表示状態。原本文書の索引・保存・Undo と独立した記録で、ビュー定義へは書かない。 */
internal class DiagramPositionStore(private val root: Path, private val layout: WorkspaceLayout) {
    private fun hash(value: Any) = MessageDigest.getInstance("SHA-256")
        .digest(YamlIO.dumpPlain(YamlIO.fromAny(value)).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun relative(context: DiagramContext): String =
        "${layout.diagramStateDir}/${hash(context.diagramValue())}/${hash(context.selectionValue())}.yaml"

    private fun readText(context: DiagramContext): String? {
        val file = WorkspacePaths.resolve(root, relative(context))
        val bytes = try { Files.newInputStream(file).use { it.readNBytes(2 * 1024 * 1024 + 1) } }
            catch (_: NoSuchFileException) { return null }
        require(bytes.size <= 2 * 1024 * 1024) { "配置記録のサイズが上限を超えています" }
        return bytes.toString(Charsets.UTF_8)
    }

    fun load(context: DiagramContext): DiagramPositionRead {
        val text = readText(context) ?: return DiagramPositionRead(emptyMap(), null)
        return runCatching { DiagramPositionRead(DiagramPositionCodec.decode(text, context), text) }
            .getOrElse { DiagramPositionRead(emptyMap(), text, "配置記録を読み込めないため自動配置に戻しました: ${it.message}") }
    }

    fun save(context: DiagramContext, positions: Map<DiagramNodeId, DiagramPosition>, expected: String?): String {
        require(readText(context) == expected) { "配置記録が外部で変更されています。「配置を再読込」してから操作してください" }
        val text = DiagramPositionCodec.encode(context, positions)
        require(text.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "配置記録のサイズが上限を超えています" }
        val file = WorkspacePaths.resolve(root, relative(context))
        Files.createDirectories(file.parent)
        WorkspacePaths.resolve(root, relative(context))
        val temporary = Files.createTempFile(file.parent, ".positions-", ".tmp")
        try {
            Files.writeString(temporary, text)
            require(readText(context) == expected) { "配置記録が外部で変更されています" }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        return text
    }
}

class DiagramPositionMemory {
    var positions by mutableStateOf<Map<DiagramNodeId, DiagramPosition>>(emptyMap())
        internal set
    var ready by mutableStateOf(false)
        internal set
    var saving by mutableStateOf(false)
        internal set
    var writable by mutableStateOf(false)
        internal set
    var warning by mutableStateOf<String?>(null)
        internal set
    internal var text: String? = null
}

/** 読込・保存は画面を離れても継続する。確定した記録だけ画面へ公開する。 */
fun AppState.diagramPositions(context: DiagramContext): DiagramPositionMemory {
    val key = context.canonical()
    memory.diagramPositions[key]?.let { return it }
    val position = DiagramPositionMemory()
    memory.diagramPositions[key] = position
    reloadDiagramPositions(key)
    return position
}

fun AppState.reloadDiagramPositions(context: DiagramContext) {
    val key = context.canonical()
    val position = memory.diagramPositions[key] ?: return
    if (position.saving) return
    val root = workspace ?: return
    position.ready = false
    position.writable = false
    val store = DiagramPositionStore(root, layout)
    operationScope.launch {
        runCatching { withContext(Dispatchers.IO) { store.load(key) } }
            .onSuccess { position.positions = it.positions; position.text = it.text; position.warning = it.warning; position.writable = true }
            .onFailure { position.warning = "配置記録を読み込めません: ${it.message}" }
        position.ready = true
    }
}

fun AppState.saveDiagramPositions(context: DiagramContext, positions: Map<DiagramNodeId, DiagramPosition>) {
    val key = context.canonical()
    val position = memory.diagramPositions[key] ?: return
    val root = workspace ?: return
    if (!position.ready || !position.writable || position.saving) return
    val savedLayout = layout
    val store = DiagramPositionStore(root, savedLayout)
    position.saving = true
    operationScope.launch {
        try {
            host.exclusive("図の配置を保存") {
                check(workspace == root && layout == savedLayout && memory.diagramPositions[key] === position) { "ワークスペースが切り替わりました" }
                val expected = position.text
                val text = withContext(Dispatchers.IO) { store.save(key, positions, expected) }
                position.text = text; position.positions = positions.toMap(); position.warning = null
            }
        } catch (e: Exception) { position.warning = "配置を保存できません: ${e.message}" }
        finally { position.saving = false }
    }
}
