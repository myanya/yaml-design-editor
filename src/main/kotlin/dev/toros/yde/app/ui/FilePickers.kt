package dev.toros.yde.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitDialogParent
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import java.nio.file.Files
import java.nio.file.Path
import java.util.prefs.Preferences

// ================================================================ ファイル・フォルダ選択（FileKit を包む）

/** ネイティブダイアログの親にするウィンドウ。Main.kt の Window が提供する（無ければ親なしで開く）。 */
val LocalDialogParentWindow = compositionLocalOf<java.awt.Window?> { null }

/**
 * OS ネイティブのファイル・フォルダ選択。ライブラリ（FileKit）の型はこのファイルの外へ出さない
 * （差し替えるときはここだけを書き換える）。最後に選んだ場所を用途ごとに覚え、次回はそこから開く。
 */
object FilePickers {
    /** 用途。覚える場所はこの単位で分ける（ワークスペースの選択と取り込みファイルは行き先が違う）。 */
    enum class Purpose(val key: String) { WORKSPACE("workspace"), IMPORT("import"), FILE_FIELD("file") }

    private val prefs = Preferences.userRoot().node("yaml-design-editor/pickers")
    // 覚えた場所。Compose の状態にして、選んだ直後の再コンポーズで次回の開始位置が更新されるようにする
    private val last = mutableStateMapOf<Purpose, Path>()

    /** 用途ごとに覚えた最後の場所（無い・消えていれば null）。 */
    fun lastDirectory(purpose: Purpose): Path? {
        val cached = last[purpose]
        if (cached != null) return cached.takeIf { Files.isDirectory(it) }
        return prefs.get(purpose.key, null)
            ?.let { runCatching { Path.of(it) }.getOrNull() }
            ?.takeIf { Files.isDirectory(it) }
    }

    /** 選んだ結果を覚える（ファイルならその親）。 */
    fun rememberChoice(purpose: Purpose, chosen: Path) {
        val dir = (if (Files.isDirectory(chosen)) chosen else chosen.parent) ?: return
        last[purpose] = dir
        prefs.put(purpose.key, dir.toString())
    }

    /** 最初に開く場所: 覚えた場所 → 呼び出し側の候補 → ホーム。存在するディレクトリだけを採る。 */
    internal fun initialDirectory(remembered: Path?, fallback: Path?, home: Path?): Path? =
        listOfNotNull(remembered, fallback, home).firstOrNull { runCatching { Files.isDirectory(it) }.getOrDefault(false) }

    internal fun userHome(): Path? = System.getProperty("user.home")?.let { runCatching { Path.of(it) }.getOrNull() }
}

/**
 * フォルダ選択ダイアログを開く関数を返す。呼び出し側はボタンの onClick から呼ぶ。
 * `fallback` は覚えた場所が無いときの開始位置（最近のワークスペースの親など）。
 */
@Composable
fun rememberDirectoryChooser(
    purpose: FilePickers.Purpose,
    title: String,
    fallback: Path? = null,
    onError: (String) -> Unit = {},
    onChosen: (Path) -> Unit,
): () -> Unit {
    val start = FilePickers.initialDirectory(FilePickers.lastDirectory(purpose), fallback, FilePickers.userHome())
    val parent = LocalDialogParentWindow.current
    val launcher = rememberDirectoryPickerLauncher(
        directory = start?.let { PlatformFile(it.toFile()) },
        dialogSettings = dialogSettings(title, parent),
        onError = { e -> onError("フォルダ選択を開けません: ${e.message ?: e::class.simpleName}") },
    ) { picked ->
        picked?.file?.toPath()?.let { FilePickers.rememberChoice(purpose, it); onChosen(it) }
    }
    return { launcher.launch() }
}

/**
 * ファイル選択ダイアログを開く関数を返す。`extensions` は拡張子の絞り込み（null なら全て）。
 */
@Composable
fun rememberFileChooser(
    purpose: FilePickers.Purpose,
    title: String,
    extensions: Set<String>? = null,
    fallback: Path? = null,
    onError: (String) -> Unit = {},
    onChosen: (Path) -> Unit,
): () -> Unit {
    val start = FilePickers.initialDirectory(FilePickers.lastDirectory(purpose), fallback, FilePickers.userHome())
    val parent = LocalDialogParentWindow.current
    val launcher = rememberFilePickerLauncher(
        type = FileKitType.File(extensions),
        directory = start?.let { PlatformFile(it.toFile()) },
        dialogSettings = dialogSettings(title, parent),
        onError = { e -> onError("ファイル選択を開けません: ${e.message ?: e::class.simpleName}") },
    ) { picked ->
        picked?.file?.toPath()?.let { FilePickers.rememberChoice(purpose, it); onChosen(it) }
    }
    return { launcher.launch() }
}

private fun dialogSettings(title: String, parent: java.awt.Window?): FileKitDialogSettings =
    FileKitDialogSettings(title = title, parent = parent?.let { FileKitDialogParent.awt(it) })
