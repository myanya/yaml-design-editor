package ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.AppState
import java.nio.file.Files
import java.nio.file.Path

/**
 * 設計スペース（R12-01）。
 * 予約ディレクトリの定義文書（スキーマ・ビュー・エージェント・アクティビティ・評価・連携skill）を
 * 種類別に見渡し、作成・編集の入口を1箇所に集める。「定義もYAML文書」（セルフホスティング）の入口であり、
 * 編集は既存のエディタ（編集スペース）へジャンプする——定義専用の編集・書込経路は作らない（ARCHITECTURE §4.24）。
 */

private data class DefKind(
    val zone: schema.WorkspaceLayout.Zone,
    val label: String,
    val description: String,
    /** 空状態で表示する始め方の説明。 */
    val emptyHint: String,
)

private val defKinds = listOf(
    DefKind(
        schema.WorkspaceLayout.Zone.SCHEMAS, "スキーマ",
        "文書の形と検証ルール。match（glob）で適用先の文書を決めます。",
        "スキーマを置くと、一致する文書がフォーム編集・検証・AI生成の対象になります。既存のYAMLがあるなら「既存文書から推測」が近道です。",
    ),
    DefKind(
        schema.WorkspaceLayout.Zone.VIEWS, "ビュー",
        "複数文書を1つの編集可能な表に射影する宣言（行=論理ID × 列=相対パス）。台帳スペースの「ビューグリッド」に表示されます。",
        "ビュー定義を置くと、一覧と詳細文書の往復なしに横断編集できます（README §5）。",
    ),
    DefKind(
        schema.WorkspaceLayout.Zone.AGENTS, "エージェント",
        "実行者の定義（persona・観点・モデル・権限）。同じアクティビティでも担当エージェントで観点が変わります。",
        "エージェント定義を置くと、実行スペースからアクティビティを委託できるようになります（README §12）。",
    ),
    DefKind(
        schema.WorkspaceLayout.Zone.ACTIVITIES, "アクティビティ",
        "仕事の単位（対象・指示・書込許可・上限・提出ゲート・トリガ）。エージェントに委託する業務の定義です。",
        "アクティビティ定義を置くと、実行スペースの「手動実行」や自動化トリガから起動できます（README §12）。",
    ),
    DefKind(
        schema.WorkspaceLayout.Zone.EVALS, "評価セット",
        "アクティビティの回帰評価（ゴールデン入力＋宣言的な期待結果）。実行スペースの「計測・評価」から実行します。",
        "評価セットを置くと、定義の変更やモデルの差し替えをA/B比較できます（README §13）。",
    ),
    DefKind(
        schema.WorkspaceLayout.Zone.SKILLS, "連携（skill）",
        "Excel・CSV・Word 等との入出力変換と、承認制のコード実行。YAMLが原本、他形式は派生成果物です。",
        "skill定義を置くと、外部フォーマットとの取り込み・帳票再生成をこの画面から実行できます（README §14）。",
    ),
)

@Composable
fun DesignSpace(state: AppState) {
    val ws = state.workspace ?: return
    state.indexVersion  // 再読込・保存での再コンポーズ購読
    var selectedZone by remember { mutableStateOf(defKinds.first().zone) }
    var createFor by remember { mutableStateOf<DefKind?>(null) }
    var inferOpen by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    fun filesOf(kind: DefKind): List<Path> =
        state.yamlFiles.filter { state.layout.zoneOf(state.relPath(it)) == kind.zone }

    Row(Modifier.fillMaxSize().background(Palette.EditorBg)) {
        // ---------------- 左: 定義の種類 ----------------
        Column(Modifier.width(230.dp).fillMaxHeight().background(Palette.PanelBg)) {
            Text("ワークスペースの設計", fontWeight = FontWeight.Bold, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
            defKinds.forEach { kind ->
                val selected = kind.zone == selectedZone
                val count = filesOf(kind).size
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (selected) Palette.Selected else Color.Transparent)
                        .clickable { selectedZone = kind.zone; message = "" }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(kind.label, fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.weight(1f))
                    Text(if (count > 0) "$count" else "—", fontSize = 11.sp, color = Palette.SubText)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                "定義はどれも通常のYAML文書です。組込みスキーマ付きで、フォーム編集・検証・差分がそのまま使えます。",
                fontSize = 10.sp, color = Palette.SubText,
                modifier = Modifier.padding(12.dp),
            )
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.Border))

        // ---------------- 右: 選択した種類の一覧と操作 ----------------
        val kind = defKinds.first { it.zone == selectedZone }
        val files = filesOf(kind)
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val scroll = rememberScrollState()
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(kind.label, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(kind.description, fontSize = 12.sp, color = Palette.SubText)
                    }
                    if (kind.zone == schema.WorkspaceLayout.Zone.SCHEMAS) {
                        OutlinedButton(onClick = { inferOpen = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) {
                            Text("既存文書から推測…", fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    if (!state.readOnlyMode) {
                        Button(onClick = { createFor = kind },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) {
                            Icon(Icons.Default.Add, contentDescription = null, Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("新規作成", fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))

                if (kind.zone == schema.WorkspaceLayout.Zone.SCHEMAS && state.schemaErrors.isNotEmpty()) {
                    state.schemaErrors.forEach { Text("⚠ $it", fontSize = 11.sp, color = Palette.ErrorRed) }
                    Spacer(Modifier.height(6.dp))
                }

                if (files.isEmpty()) {
                    Text("まだ ${kind.label} はありません", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(kind.emptyHint, fontSize = 12.sp, color = Palette.SubText)
                } else {
                    files.forEach { file ->
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable { state.openFile(file) }
                                .padding(vertical = 5.dp),
                        ) {
                            Text(file.fileName.toString(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(state.relPath(file), fontSize = 10.sp, color = Palette.SubText)
                        }
                    }
                }

                // 連携（skill）は定義の一覧に加えて、取り込み・出力の実行もここで行う
                if (kind.zone == schema.WorkspaceLayout.Zone.SKILLS) {
                    Spacer(Modifier.height(14.dp))
                    Text("実行", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Palette.SubText)
                    Spacer(Modifier.height(4.dp))
                    SkillsSection(state)
                }

                if (message.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(message, fontSize = 12.sp, color = Palette.ErrorRed)
                }
                Spacer(Modifier.height(24.dp))
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }

    // ---------------- 新規作成ダイアログ ----------------
    createFor?.let { kind ->
        var name by remember(kind) { mutableStateOf("") }
        val create: () -> Unit = {
            val dir = ws.resolve(state.layout.dir(kind.zone))
            runCatching { Files.createDirectories(dir) }
                .mapCatching { state.createYaml(dir, name).getOrThrow() }
                .onSuccess { createFor = null; message = "" }
                .onFailure { message = "作成できません: ${it.message}" ; createFor = null }
        }
        AlertDialog(
            onDismissRequest = { createFor = null },
            title = { Text("${kind.label} を新規作成") },
            text = {
                Column(Modifier.width(420.dp)) {
                    Text("${state.layout.dir(kind.zone)}/ に作成します。作成後はフォーム（組込みスキーマ）で編集できます。",
                        fontSize = 12.sp, color = Palette.SubText)
                    Spacer(Modifier.height(8.dp))
                    CompactTextField(name, { name = it }, singleLine = true,
                        placeholder = { Text("ファイル名（例: review.yaml）") },
                        modifier = Modifier.fillMaxWidth().confirmOnEnter(enabled = { name.isNotBlank() }, onConfirm = create))
                }
            },
            confirmButton = {
                Button(enabled = name.isNotBlank(), onClick = create) { Text("作成") }
            },
            dismissButton = { TextButton(onClick = { createFor = null }) { Text("キャンセル") } },
        )
    }

    // ---------------- スキーマ推測ダイアログ（S-16） ----------------
    if (inferOpen) {
        var glob by remember { mutableStateOf("") }
        var schemaId by remember { mutableStateOf("") }
        var inferError by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { inferOpen = false },
            title = { Text("既存文書からスキーマを推測") },
            text = {
                Column(Modifier.width(480.dp)) {
                    Text(
                        "glob に一致する既存YAMLから型・参照・enum候補を推測し、_schemas/<ID>.yaml のドラフトを作成します。仕上げはスキーマ設計画面で行います。",
                        fontSize = 12.sp, color = Palette.SubText,
                    )
                    Spacer(Modifier.height(8.dp))
                    CompactTextField(glob, { glob = it }, singleLine = true,
                        placeholder = { Text("対象の glob（例: requirements/**/*.yaml）") },
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    CompactTextField(schemaId, { schemaId = it }, singleLine = true,
                        placeholder = { Text("スキーマID（例: requirement）") },
                        modifier = Modifier.fillMaxWidth())
                    if (inferError.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(inferError, fontSize = 12.sp, color = Palette.ErrorRed)
                    }
                }
            },
            confirmButton = {
                Button(enabled = glob.isNotBlank() && schemaId.isNotBlank(), onClick = {
                    state.inferSchema(glob.trim(), schemaId.trim())
                        .onSuccess { inferOpen = false }
                        .onFailure { inferError = it.message ?: "推測に失敗しました" }
                }) { Text("推測して作成") }
            },
            dismissButton = { TextButton(onClick = { inferOpen = false }) { Text("キャンセル") } },
        )
    }
}
