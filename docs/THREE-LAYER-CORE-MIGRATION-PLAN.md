# 3層コア移行計画

## 1. 本書の目的

本書は、`yaml-design-editor` を同一リポジトリ内でGradleマルチモジュール化し、次の3層を段階的に切り出すための作業計画である。

- `yaml-core`: 1つのYAML文書の構文、スキーマ適合、編集操作
- `semantic-core`: 文書集合の意味的解釈、意味グラフ、意味的検証、リファクタリング計画
- `workspace-core`: 文書集合の版、更新、永続化、履歴、トランザクション、派生状態の鮮度管理

UI、Compose、AI、agent、skillは今回の移行対象外とし、当面ルートのアプリモジュールに残す。移行中の動作確認にはJUnitと簡易CLIを使用する。

移行は**複製ではなく移動**である。コードとテストを機能単位でモジュールへ移し、各ステップでアプリを含む全体のビルド・テストが成功する状態を保つ（strangler方式）。別プロジェクトへの再実装は行わない——境界の再設計が実質リライトを超えるリアーキテクチャへ膨らむためである。挙動を変える場合は、旧経路を残したまま新APIを併設し、利用者を新APIへ移してから旧経路を撤去する。

## 2. 移行の基本方針

### 2.1 守る原則

1. YAMLファイルを正とし、索引や意味グラフは常に再構築可能な派生物とする。
2. `YNode`はYAMLの構文木に留め、entityやrefによる意味グラフを埋め込まない。
3. `semantic-core`はファイルI/Oを行わず、与えられた文書snapshotから結果を導出する。
4. `workspace-core`は意味規則を実装せず、`semantic-core`の実行と結果の版を管理する。
5. すべての書き換えを、型付けされた操作とトランザクションで表現する。
6. 保存、編集履歴、意味のある変更履歴を別の概念として扱う。
7. 移行時は既存テストをコードと同時に移し、移した先で通し続ける。
8. 1フェーズで移動と仕様変更を同時に行わない。仕様調整は次の独立した変更として行う。
9. 各フェーズの完了条件を満たすまで、次のフェーズへ進まない。
10. 移行は移動であり複製ではない。すべてのステップでアプリを含む `./gradlew test` が成功する。
11. YNodeは可変のままとするが、公開済みsnapshotの木は変更しない（§2.4のcopy-on-write規律）。
12. Undo/Redo・トランザクション・dirty管理はworkspace-coreだけが所有する。yaml-coreは操作と逆操作の機構のみ提供する。
13. 表記は意味に影響しない。意味が表記を処方することは許すが、styleHintが値・ID・ref・グラフの解釈を変えることはない（§4.4）。

### 2.2 今回移行しないもの

次は当面ルートのアプリモジュールに残す。

- `Main.kt`
- `app/`のUI状態とナビゲーション
- `ui/`
- `ai/`
- `agent/`の実行エンジン
- `skill/`
- Compose Desktop、Material、Apache POI、HTTPクライアント
- OS配布物作成

ただし、`agent/Staging.kt`の「原本を直接変更せず、overlay上で変更案を作る」という設計は、`workspace-core`のトランザクション設計の参考にする。コード自体は初期移行対象にしない。

### 2.3 既存挙動から意図的に変える点

次は移植ミスではなく、3層化のために最初から意図する変更である。Phase 0の「意図的差分一覧」に登録する。

in-repo移行では、これらの変更を既存経路へ直接加えない。新APIをモジュール側に併設し、利用者（まずCLI、後でUI・AI・agent）を新APIへ移してから旧経路を撤去する。旧経路と新APIの併存はフェーズ完了ごとに解消し、二重実装の期間を短く保つ。

| 現在 | 新API |
|---|---|
| `Workspace.apply`が変更セットを検証してファイルへ書く | `apply`はworking snapshotだけを更新し、`save`が別途ファイルへ書く |
| UIの文書キャッシュ、ディスク索引、agent stagingが別々のoverlayを持つ | すべて`WorkspaceSnapshot`を入力とする共通semantic engineを使う |
| `EditBuffer`が正規化text snapshotを履歴に持つ | 型付けされた`YamlOperation`とinverseを基本履歴にする |
| `AppState`や複数のcore機能が直接ファイルを書く | coreの書込を`DocumentStore`と`WorkspaceSession.save`へ集約する |
| `Schema.kt`と`Validator.kt`が構造規則と意味規則を同時に扱う | structuralとsemanticのcompile/validationを分ける |
| `Path`がschema、index、coreへ広く渡る | `Path`はworkspace-coreのI/O境界に閉じ、下位層には`DocumentId`を渡す |

既存挙動との比較テストでは、この表に該当する差分を単純な不一致として扱わない。新旧それぞれの期待値を明記する。

### 2.4 可変YNodeとsnapshotの規律（決定事項）

YNodeのimmutable化は行わない。既存実装との互換のため可変のまま移すが、snapshot意味論と両立させるために次の規律を全層で守る。可変な木をsnapshot間で共有すると、1回のin-place編集がworking・persisted・候補のすべてを同時に書き換えてしまうためである。

1. YNode木の所有者は常に1つのworking文書である。公開済みsnapshot（`WorkspaceSnapshot`として観測された木）は以後変更しない。
2. 変更は「対象文書のdeep copy → `YamlOperation`適用 → 成功時に参照交換」で行う（文書単位のcopy-on-write）。交換前の木は旧snapshotの内容としてそのまま有効に残る。
3. persisted状態はYNode木の共有で表現しない。保存済みテキスト（dump結果）で保持し、dirty判定はテキスト比較で行う。
4. semantic-coreは入力の木を変更せず、出力（`SemanticSnapshot`）にYNode参照を保持しない。抽出済みの値・`YPath`・座標だけを持つ（現行`IdIndex`と同じ流儀）。
5. UIを含む将来の利用者も木を直接変更せず、`YamlOperation`／`WorkspaceTransaction`を発行する（§16）。

deep copyは変更対象の文書に限られるため、全文書コピーのメモリ負荷は発生しない。この規律はADRへ登録し、レビュー時のチェック項目とする。

## 3. 目標プロジェクト構成

### 3.1 Gradleモジュール

```text
yaml-design-editor/
├─ settings.gradle.kts
├─ build.gradle.kts          ← 既存Composeアプリ（ルートモジュール）
├─ src/{main,test}/kotlin/   ← 既存コード。移動が完了した領域から縮んでいく
├─ yaml-core/
│  ├─ build.gradle.kts
│  └─ src/{main,test}/kotlin/
├─ semantic-core/
│  ├─ build.gradle.kts
│  └─ src/{main,test}/kotlin/
├─ workspace-core/
│  ├─ build.gradle.kts
│  └─ src/{main,test}/kotlin/
├─ cli/
│  ├─ build.gradle.kts
│  └─ src/{main,test}/kotlin/
└─ test-fixtures/
   ├─ yaml/
   ├─ schemas/
   └─ workspaces/
```

依存方向は次の一方向に固定する。

```text
yaml-core
    ▲
    │
semantic-core
    ▲
    │
workspace-core
    ▲
    ├──────────────┐
   cli      アプリ（ルート）
```

ルートのアプリは移行期間中、3つのcoreすべてへ直接依存してよい（移動済みコードのimportを満たすため）。移行完了後にアプリを`:app`モジュールへ切り出すかは、本計画の範囲外の後続判断とする。

禁止する依存は次のとおり。

- `yaml-core`から`semantic-core`または`workspace-core`への依存
- `semantic-core`から`workspace-core`への依存
- 3つのcoreから`cli`への依存
- 3つのcoreからルート（アプリ）への依存
- 3つのcoreからCompose、UI、AI、agent、skillへの依存
- `yaml-core`と`semantic-core`から`java.nio.file.Path`への依存

### 3.2 Gradleの最小構成

ルート`settings.gradle.kts`の例:

```kotlin
// 既存のsettings.gradle.ktsへ追記する（rootProject.nameは変えない）
include(
    ":yaml-core",
    ":semantic-core",
    ":workspace-core",
    ":cli"
)
```

依存関係の例:

```kotlin
// semantic-core/build.gradle.kts
dependencies {
    api(project(":yaml-core"))
    testImplementation(kotlin("test-junit5"))
}

// workspace-core/build.gradle.kts
dependencies {
    api(project(":yaml-core"))
    api(project(":semantic-core"))
    testImplementation(kotlin("test-junit5"))
}

// cli/build.gradle.kts
dependencies {
    implementation(project(":workspace-core"))
    testImplementation(kotlin("test-junit5"))
}

// ルート build.gradle.kts（既存アプリ）へ追加
dependencies {
    implementation(project(":workspace-core"))
    implementation(project(":semantic-core"))
    implementation(project(":yaml-core"))
}
```

全モジュールでJUnit Platformを使う。

```kotlin
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
```

SnakeYAMLへの依存は`yaml-core`だけに置く。`semantic-core`と`workspace-core`がSnakeYAMLの型を公開APIで受け渡さないことを、コンパイル依存でも確認する。

移行中はパッケージ改名を行わない。既存パッケージ名（`model`・`yaml`・`schema`・`index`・`core`・`validate`）のままファイルをモジュールへ移動し、アプリ側のimport変更をなくして1回の移動を小さく保つ。§4の推奨パッケージへの改名は、移行完了後の独立した機械的変更とする（分割で新設するコードは最初から新パッケージでよい）。

## 4. 各層の責務と公開契約

### 4.1 yaml-core

#### 担当するもの

- `YDocument`、`YNode`、`YPath`
- YAMLのparse/dump
- コメント、順序、scalar表記スタイルの保持
- 複数ドキュメントYAML
- スキーマ定義の構文モデル
- `$ref`を解決した文書スキーマの構築
- 単一文書に閉じた構造検証
- 単一文書に対する編集操作
- 操作の逆操作の生成
- `EditBatch`による一括適用と途中失敗時の巻き戻し

#### 担当しないもの

- ファイル探索、保存、外部変更検出
- Undo/Redoスタック、dirty判定、保存状態（workspace-coreが所有。§5）
- IDのワークスペース全体での一意性
- 文書をまたぐref解決
- entity graph、relation、query
- 複数文書トランザクション
- 人間・AIの権限判定

#### 推奨パッケージ

```text
dev.toros.yde.yaml.document
dev.toros.yde.yaml.io
dev.toros.yde.yaml.schema
dev.toros.yde.yaml.validation
dev.toros.yde.yaml.edit
dev.toros.yde.yaml.diagnostic
```

#### 最小公開API案

```kotlin
data class DocumentId(val value: String)

data class YDocument(
    val id: DocumentId,
    val documents: List<YNode>
)

interface YamlCodec {
    fun parse(id: DocumentId, text: String): YDocument
    fun dump(document: YDocument): String
}

interface DocumentSchemaCompiler {
    fun compile(
        schema: SchemaDefinition,
        registry: SchemaRegistry
    ): CompiledDocumentSchema
}

interface StructuralValidator {
    fun validate(
        document: YDocument,
        schema: CompiledDocumentSchema
    ): List<StructuralIssue>
}
```

`DocumentId`はファイルパスそのものではなく、上位層が与える不透明な論理識別子とする。これにより、テストやメモリ上の文書にも同じAPIを使える。

#### 編集API案

```kotlin
sealed interface YamlOperation {
    /** valueの値域はスカラーの閉集合（String/Boolean/Int/Long/Double/null）。Kotlinの型が正（「スカラー値と表記の契約」参照） */
    data class SetScalar(val path: YPath, val value: Any?) : YamlOperation
    data class Insert(val parent: YPath, val position: Int, val value: YNode) : YamlOperation
    data class Remove(val path: YPath) : YamlOperation
    data class Move(val from: YPath, val toParent: YPath, val position: Int) : YamlOperation
    data class RenameKey(val parent: YPath, val from: String, val to: String) : YamlOperation
    data class Replace(val path: YPath, val value: YNode) : YamlOperation
}

data class OperationResult(
    val document: YDocument,
    val inverse: YamlOperation
)

interface YamlOperationExecutor {
    fun apply(document: YDocument, operation: YamlOperation): OperationResult
}
```

Undo/Redoスタック・dirty判定・保存状態はyaml-coreに置かない（§5）。yaml-coreが提供するのは「操作を適用すると逆操作が得られる」という機構までとし、履歴の所有はworkspace-coreへ一元化する。単文書だけを扱いたい利用者は、文書1件のworkspaceを`InMemoryDocumentStore`上に作ればよい。

`EditBatch`は複数操作の一括適用単位で、結果として逆順のinverse列を返す。途中の操作が失敗した場合は適用済み分を巻き戻し、文書を部分変更のまま残さない。

#### スカラー値と表記の契約（決定事項）

yaml-coreの構文層はYAML仕様の範囲だけを扱い、値の中身を解釈も推測もしない。

- スカラーの値域は閉集合 `{String, Boolean, Int/Long, Double, null}` とする（現行`PlainDateResolver`と同一。タイムスタンプ型は持たず、日付らしい文字列は素の`String`のまま扱う）。
- `SetScalar`の契約は「Kotlinの型が正」。渡した値の型がそのままYAML上の型になる。日付・ID等の解釈は値を作る側（構造スキーマ層・上位層）の仕事とする。
- styleHintは操作ペイロードに載せない。置換時は既存ノードのstyleHint・コメントを`inheritMetaFrom`相当で継承する。
- dumpは往復型安全を保証する。素のまま出すと再パースで型が変わる文字列（`"true"`・`"2026-08-16"`等）は引用符付きで出力する。これはYAML仕様上の義務であり、スキーマ知識を要しない。
- スタイルを明示制御したい呼び出し側は`Replace(path, YNode.Scalar(styleHint = ...))`を使う。表記の決定は常に上位からデータとして流れ、yaml-coreは忠実に実行するだけとする（§4.4）。

### 4.2 semantic-core

#### 担当するもの

- 文書とスキーマからID、ref、entityを抽出する
- `IdIndex`、`ReferenceGraph`、`EntityGraph`
- relation、aspect、entity typeの解釈
- 文書横断の意味的検証
- query、traverse、derivation、projection
- 表示に依存しないview解決
- 意味的な影響解析
- リファクタリング計画
- snapshot間の意味差分

#### 担当しないもの

- YAML textのparse/dump
- styleHintから意味を導くこと（表記→意味は禁止。§4.4）
- ファイルの読み書き
- workspaceの保存状態や競合状態
- Compose表示
- 変更計画の原本への適用

#### 推奨パッケージ

```text
dev.toros.yde.semantic.model
dev.toros.yde.semantic.schema
dev.toros.yde.semantic.index
dev.toros.yde.semantic.graph
dev.toros.yde.semantic.validation
dev.toros.yde.semantic.query
dev.toros.yde.semantic.derivation
dev.toros.yde.semantic.view
dev.toros.yde.semantic.refactor
```

#### snapshot API案

```kotlin
data class SemanticDocument(
    val id: DocumentId,
    val root: YNode,
    val schema: CompiledDocumentSchema?
)

data class SemanticInput(
    val workspaceVersion: Long,
    val documents: List<SemanticDocument>,
    val declarations: SemanticDeclarations
)

data class SemanticSnapshot(
    val sourceVersion: Long,
    val idIndex: IdIndex,
    val referenceGraph: ReferenceGraph,
    val entityGraph: EntityGraph,
    val issues: List<SemanticIssue>
)

interface SemanticEngine {
    fun build(input: SemanticInput): SemanticSnapshot
}
```

versionは`Long`の単一連番（§4.3「バージョンの方針」）。semantic-coreは受け取った値を結果へ転記するだけで、自身では採番しない。

初期実装は毎回全再構築でよい。正しさが確認できてから、次のAPIを追加する。

```kotlin
fun update(
    previous: SemanticSnapshot,
    input: SemanticInput,
    changedDocuments: Set<DocumentId>
): SemanticSnapshot
```

増分更新の結果が全再構築と一致することをproperty testまたは比較テストで保証する。

#### リファクタリングAPI案

```kotlin
sealed interface RefactoringRequest {
    data class RenameEntity(val oldId: String, val newId: String) : RefactoringRequest
    data class DeleteEntity(val id: String) : RefactoringRequest
    data class MergeEntity(val sourceId: String, val targetId: String) : RefactoringRequest
    data class RenameSchemaField(
        val schemaId: String,
        val oldKey: String,
        val newKey: String
    ) : RefactoringRequest
}

data class RefactoringPlan(
    val sourceVersion: Long,
    val intent: RefactoringRequest,
    val changes: List<PlannedDocumentChange>,
    val impacts: List<Impact>,
    val warnings: List<RefactoringWarning>
)

interface RefactoringPlanner {
    fun plan(
        snapshot: SemanticSnapshot,
        documents: Map<DocumentId, YDocument>,
        request: RefactoringRequest
    ): RefactoringPlan
}
```

semantic-coreは`RefactoringPlan`を生成するだけで適用しない。

### 4.3 workspace-core

#### 担当するもの

- workspace layoutと対象文書の探索
- 文書のロードと保存
- working/persisted snapshot
- workspace全体の版（単一連番）と文書ごとのdirty判定
- 複数文書トランザクション
- 作成、削除、移動、内容変更
- 意味のある変更履歴
- workspace単位のUndo/Redo
- structural/semantic validationの実行と集約
- semantic snapshotの鮮度管理
- 外部変更の取り込み

#### 担当しないもの

- YAML構文操作の詳細
- entity/refの意味規則
- UI状態、画面遷移、ダイアログ
- AIの承認ポリシー

#### 推奨パッケージ

```text
dev.toros.yde.workspace.model
dev.toros.yde.workspace.layout
dev.toros.yde.workspace.store
dev.toros.yde.workspace.session
dev.toros.yde.workspace.transaction
dev.toros.yde.workspace.history
dev.toros.yde.workspace.validation
dev.toros.yde.workspace.watch
```

#### バージョンの方針（決定事項）

エージェント基盤向けに「意味のある変更のまとまり」をバージョン体系で表現する構想は複雑になりすぎたため、移行では現行実装以上のことを行わない。バージョン概念の本格的な再設計は、移行後に各層の役割を整理してから改めて行う（§11）。

- バージョンは単一の連番だけにする: `typealias WorkspaceVersion = Long`。working snapshotが変わるたび（apply・undo・redo・reload）に+1する。undoで内容が過去と一致してもversionは巻き戻さない。
- semantic-coreへは`Long`のまま渡す（`WorkspaceVersionToken`は導入しない）。semantic-coreは受け取った値を結果へ転記するだけ。
- 文書ごとのrevision型（`DocumentRevision`）は持たない。dirty判定は保存済みテキストと現在のdump結果の比較で行う（現行`EditBuffer`と同水準）。
- 楽観ロック（`ContentVersion`）は導入しない。外部変更はタイムスタンプ基準の検出・通知・reloadまでとし、保存は最終書込み優先（現行`FileWatcher`水準）。
- トランザクションの`baseVersion`確認とリファクタリングplanの`sourceVersion`確認は、この単一連番の比較だけで行う。

#### 永続化ポート

```kotlin
interface DocumentStore {
    fun list(): List<StoredDocument>
    fun read(id: DocumentId): StoredContent
    fun write(id: DocumentId, text: String)
    fun delete(id: DocumentId)
    fun move(from: DocumentId, to: DocumentId)
}
```

`FileSystemDocumentStore`はworkspace-coreに置く。テスト用に`InMemoryDocumentStore`を用意する。

#### workspace状態

```kotlin
data class WorkspaceSnapshot(
    val version: WorkspaceVersion,
    val documents: Map<DocumentId, WorkspaceDocument>
)

data class WorkspaceDocument(
    val document: YDocument,
    /** 保存済みテキスト（未保存の新規文書はnull）。dirty判定は現在のdump結果との比較で行う */
    val persistedText: String?
)

data class WorkspaceDerivedState(
    val sourceVersion: WorkspaceVersion,
    val structuralIssues: List<StructuralIssue>,
    val semantic: SemanticSnapshot
)
```

意味結果の鮮度は必ずversionで判断する。

```kotlin
val semanticIsCurrent =
    derivedState.semantic.sourceVersion == currentSnapshot.version
```

#### トランザクション

```kotlin
sealed interface WorkspaceOperation {
    data class EditDocument(
        val documentId: DocumentId,
        val operations: List<YamlOperation>
    ) : WorkspaceOperation

    data class CreateDocument(val document: YDocument) : WorkspaceOperation
    data class DeleteDocument(val documentId: DocumentId) : WorkspaceOperation
    data class MoveDocument(val from: DocumentId, val to: DocumentId) : WorkspaceOperation
}

data class WorkspaceTransaction(
    val baseVersion: WorkspaceVersion,
    val intent: ChangeIntent,
    val operations: List<WorkspaceOperation>
)
```

適用順序を固定する。

```text
1. baseVersion確認
2. 全操作をメモリ上の候補snapshotへ適用
3. structural validation
4. semantic snapshot再構築
5. semantic validation
6. 適用条件を満たす場合だけworking snapshotを交換
7. ChangeRecordを履歴へ追加
8. 必要に応じて明示的にsave
```

`apply`と`save`は別メソッドにする。

```kotlin
interface WorkspaceSession {
    val current: WorkspaceSnapshot
    val persisted: WorkspaceSnapshot
    val derived: WorkspaceDerivedState

    fun apply(transaction: WorkspaceTransaction): TransactionResult
    fun undo(): TransactionResult
    fun redo(): TransactionResult
    fun save(selection: SaveSelection = SaveSelection.All): SaveResult
    fun reload(): ReloadResult
}
```

### 4.4 表記と意味の方向規則（決定事項）

最終的には、ユーザ定義スキーマから構造スキーマと意味スキーマの2層を導出して各層で検証し、意味スキーマ側が表記ポリシー（「dateフィールドは引用符なし」「長文はブロックスタイル」等）まで担う構想がある。この拡張を可能にするため、次の方向規則を最初から固定する。

- **意味 → 表記: 可**。意味スキーマが表記を処方してよい。表記ポリシーのlintが現在のstyleHintを読んで指摘するのもよい（presentation issueとして報告するだけで、意味内容には影響させない）。
- **表記 → 意味: 不可**。styleHintが値・ID・ref・グラフ・検証の意味内容を変えることは永遠にない。引用符の有無で意味が変わる世界にしない。
- **往復型安全はyaml-coreの床**。表記ポリシーが型を壊すスタイルを指定しても、yaml-coreのdumpが仕様の範囲で上書き（または拒否）する。ポリシーは提案であり、構文層が強制する。

表記の決定は常にデータとして下へ流れる。意味スキーマ側の判断は「(YPath, 望ましいstyle)」の指定になり、styleを明示した`Replace`や正規化パスなど**通常の操作**として適用される。これによりスタイル変更もplan/apply・staging・undo・diffへそのまま乗り、専用の書込経路を作らない。

表記ポリシー自体の実装は今回の移行対象外とする（§11）。導入時は`SemanticDeclarations`へ新しい宣言族として追加し、最終的な再設計では構造／意味／表記を独立した宣言カテゴリに分ける。

## 5. 変更履歴、Undo/Redo、保存の分離

3種類の履歴単位を混同しない。

| 単位 | 例 | 定義・適用する層 | 履歴として積む層 |
|---|---|---|---|
| `YamlOperation` | scalarを1回変更 | yaml-core | （単独では積まない） |
| `EditBatch` | 1回のフォーム操作で3ノード変更 | yaml-core | workspace-core |
| `ChangeRecord` | entity改名で5文書更新 | workspace-core | workspace-core |

Undo/Redoスタックの所有者はworkspace-coreだけである（決定事項）。yaml-coreは操作と逆操作の「語彙と機構」を提供し、スタック・dirty判定・保存状態を持たない。単文書の編集も文書1件の`ChangeRecord`としてworkspace履歴で扱い、第二のUndoスタックを作らない。

なお、パース不能なテキスト状態（現行`EditBuffer`の`rawText`／`parseError`）は型付き操作で表現できない。ソース編集中のテキストレベルUndoは将来のUI層の責務とし、coreの履歴には含めない（既知の境界として意図的差分一覧へ登録する）。

保存は履歴項目ではない。

```text
Working:   W0 → W1 → W2 → W3
Persisted:           ↑
Current:                  ↑
```

W2を保存した後にW3へ進み、UndoでW2へ戻った場合、内容は保存済み状態と一致するためdirtyはfalseになる。さらにUndoでW1へ戻るとdirtyはtrueになる。この振る舞いをJUnitで固定する。

意味のある変更履歴は次を保持する。

```kotlin
data class ChangeRecord(
    val id: ChangeId,
    val intent: ChangeIntent,
    val origin: ChangeOrigin,
    val beforeVersion: WorkspaceVersion,
    val afterVersion: WorkspaceVersion,
    val forward: WorkspaceTransaction,
    val inverse: WorkspaceTransaction,
    val occurredAt: Instant
)
```

時刻は注入し、テストで固定する。人間・AIの区別は`ChangeOrigin`として上位から渡せるが、coreは権限判断をしない。

## 6. 現行ファイルの移行先

### 6.1 yaml-coreへ移すもの

| 現行ファイル | 新しい配置 | 作業内容 |
|---|---|---|
| `model/YNode.kt` | `yaml.document` | `YNode`と`YPath`を分離。必要なら`NodeId`を追加 |
| `yaml/YamlIO.kt` | `yaml.io` | `YamlCodec`実装化。SnakeYAMLの型を外へ出さない |
| `core/Patch.kt` | `yaml.edit` | パッチ構文と操作実行を分離。各操作から逆操作を生成 |
| `core/Documents.kt`の`EditBuffer` | 分割 | 操作適用・逆操作生成は`yaml.edit`へ。履歴・dirty管理はworkspace-coreの`ChangeRecord`履歴へ統合し、テキストsnapshot履歴は廃止（§5） |
| `schema/Schema.kt`の`FieldType`、`FieldDef`、構造schema | `yaml.schema` | 意味的宣言とI/Oを切り離す |
| `schema/Schema.kt`の`validateDefinition` | `yaml.schema.validation` | schema定義自身の構造検証として移す |
| `validate/Validator.kt`の型・required・range検証 | `yaml.validation` | `IdIndex`を必要としない検査だけ抽出 |
| `schema/BuiltinSchemas.kt`の構造部分 | `yaml-core`のresources | Kotlin巨大文字列ではなくresource化を検討 |

`Schema.kt`と`Validator.kt`は丸ごとコピーしない。移植対象のテストケースを1つ選び、そのケースに必要な型と関数だけを移す。

### 6.2 semantic-coreへ移すもの

| 現行ファイル | 新しい配置 | 作業内容 |
|---|---|---|
| `index/IdIndex.kt` | `semantic.index` | `Files`や`Path`から切り離し、`SemanticDocument`を入力にする |
| `index/EntityGraph.kt` | `semantic.graph` | snapshot由来の不変グラフとして構築 |
| `index/Query.kt` | `semantic.query` | 宣言モデルと評価器を同じ機能領域に寄せる |
| `index/Derivation.kt` | `semantic.derivation` | 文書loaderをsnapshot参照へ置換 |
| `schema/Relations.kt` | `semantic.schema` | relation/entity/aspect宣言として移す |
| `schema/Query.kt` | `semantic.query` | `index/Query.kt`と統合 |
| `schema/Derivation.kt` | `semantic.derivation` | `index/Derivation.kt`と統合 |
| `schema/Projection.kt` | `semantic.derivation` | 入力値と来歴を明示する純粋評価へ整理 |
| `schema/Display.kt` | `semantic.model` | タイトル解決（`titleOf`）は`IdEntry.title`が依存するためPhase 5でIdIndexと同時に移す。UI装飾は移さない |
| `schema/IdSuggest.kt` | `semantic.index` | ID候補計算の純粋関数として移す |
| `schema/SchemaInference.kt` | 後半フェーズ | schema推測は基本機能安定後に移す |
| `schema/DerivedSchemas.kt` | `semantic.schema` | 導出規則とworkspaceの保存処理を分割 |
| `core/Aspects.kt` | `semantic.model` | entity合成を純粋関数化 |
| `core/EntityOps.kt` | `semantic.refactor` | 影響解析と変更計画を抽出 |
| `core/Search.kt` | `semantic.query` | 文書集合への検索として移す |
| `core/Disclosure.kt` | `semantic.query` | カタログ・骨子・全文の段階的開示（R1-03）をsnapshot入力の純粋関数として移す。ファイル読込は上位から渡す |
| `core/Traverse.kt` | `semantic.graph` | グラフ走査として移す |
| `core/Views.kt` | `semantic.view` | 定義、parse、lint、resolve、renderを分割。renderはCLI側でもよい |
| `core/SchemaRefactor.kt` | `semantic.refactor` | 計画生成だけを移し、適用はworkspaceへ委譲 |
| `core/DocMove.kt`の参照影響解析 | `semantic.refactor` | ファイル操作自体はworkspaceへ分ける |
| `validate/GraphIntegrity.kt` | `semantic.validation` | 意味グラフ検証として移す |
| `validate/Validator.kt`のref、entity、query検証 | `semantic.validation` | structural validatorから分離 |

### 6.3 workspace-coreへ移すもの

| 現行ファイル | 新しい配置 | 作業内容 |
|---|---|---|
| `core/Workspace.kt` | `workspace.session` | 中心実装として再構成。mutableな個別状態をsnapshotへまとめる |
| `schema/WorkspaceLayout.kt` | `workspace.layout` | schemaではなくworkspace配置規則として移す |
| `core/Documents.kt`のworkspace文書操作 | `workspace.session` | 文書集合と保存状態を担当 |
| `core/FileWatcher.kt` | `workspace.watch` | 最終段階で移す。最初は明示的reloadだけにする |
| `core/DocMove.kt`のファイル移動 | `workspace.transaction` | semantic plannerの計画を実行 |
| `core/Bench.kt` | `cli`または後回し | 性能基準が必要になってから移す |
| `agent/Staging.kt` | 直接移さない | `WorkspaceTransaction`と候補snapshotの設計参考にする |
| `app/AppState.kt`のsave/create/delete/rename | `workspace.session` | UI状態を持ち込まずユースケースだけ再実装 |
| `app/AppState.kt`のoverlayIndex | `workspace.session` | working snapshotからsemantic snapshotを作る共通経路へ置換 |

### 6.4 CLIへ移すもの

`core/Cli.kt`は丸ごと移さない。パースと表示だけを`cli`へ置き、処理は3つのcore APIを呼び出す。

初期CLIコマンドは次に限定する。

```text
yaml parse <file>
yaml roundtrip <file>
yaml validate <file> --schema <schema-file>

workspace catalog <root>
workspace validate <root>
workspace inspect-entity <root> <id>
workspace plan-rename-entity <root> <old-id> <new-id>
workspace apply-rename-entity <root> <old-id> <new-id> --dry-run

shell <root>
```

`shell`は同一プロセスでworking stateを維持し、Undo/Redoと保存分離を確認するために用意する。

```text
open requirements/a.yaml
set requirements/a.yaml title "変更後"
status
undo
redo
save
history
validate
quit
```

初期CLIは外部ライブラリを使わず、引数配列の小さなdispatcherでよい。CLIの高機能化をcore移行より先行させない。

### 6.5 今回移動しないもの

| 現行領域 | 理由 |
|---|---|
| `ui/` | UI移行は後続フェーズ |
| `Main.kt` | Composeアプリの入口 |
| `ai/` | core境界の確定後に接続する利用者 |
| `agent/` | 権限、実行、承認は今回のcore外 |
| `skill/` | 入出力アダプターであり、core確定後に再接続する |
| `app/AppState.kt`全体 | UI状態とcoreユースケースが混在しているため丸ごと移さない |
| `core/Cli.kt`全体 | agent、skillを含む多数のコマンドが混在しているため丸ごと移さない |

## 7. 既存テストの移行先

### 7.1 yaml-core

- `model/YPathTest.kt`
- `yaml/YamlIOTest.kt`
- `core/PatchTest.kt`
- `schema/SchemaLoaderTest.kt`の構文・`$ref`部分
- `schema/MetaSchemaTest.kt`の構造部分
- `validate/ConstraintTest.kt`の単文書制約
- `validate/ValidatorTest.kt`（casesの実効フィールド・属性付き参照の形状検証。単文書で完結する）
- `validate/PhaseTest.kt`のrequired判定部分

### 7.2 semantic-core

- `index/DocScannerTest.kt`
- `index/IdIndexIncrementalTest.kt`
- `index/EntityGraphTest.kt`
- `index/QueryTest.kt`
- `schema/RelationRegistryTest.kt`
- `schema/EntityDeclTest.kt`
- `schema/DisplayTest.kt`
- `schema/IdSuggestTest.kt`
- `schema/DerivedSchemasTest.kt`
- `schema/SchemaInferenceTest.kt`（Phase 6F）
- `core/AspectTest.kt`
- `core/EntityOpsTest.kt`
- `core/TraverseTest.kt`
- `core/ViewsTest.kt`
- `validate/ConsistencyTest.kt`
- `validate/CoverageTest.kt`
- `validate/GraphIntegrityTest.kt`
- `validate/QueryConstraintTest.kt`
- `core/SchemaRefactorTest.kt`の計画生成部分

### 7.3 workspace-core

- `core/WorkspaceLayoutTest.kt`
- `core/OverlayIndexTest.kt`
- `core/DocumentsTest.kt`の履歴部分（テキストsnapshot履歴の契約を`ChangeRecord`履歴として再設計）
- `core/FileWatcherTest.kt`
- `core/DocRefTest.kt`のファイル移動部分
- `core/SchemaRefactorTest.kt`の適用部分
- `app/OpenDocumentTest.kt`の保存・複数文書部分
- `agent/StagingTest.kt`の候補snapshotと原本非変更に関するケース

### 7.4 CLI

- `core/WorkspaceCliTest.kt`を縮小して移す

既存テストをファイル単位で丸ごと移さない。各テストケースを次の3種類に分類する。

1. そのまま契約として維持する
2. 新しい境界に合わせてセットアップだけ変更する
3. UI・agent・旧構造固有のため移行対象外とする

分類理由をテスト移行台帳へ記録する。

## 8. 段階的な実施計画

### Phase 0: 移行基準を固定する

#### 目的

移行中に「既存挙動の再現」と「新しい仕様への変更」が混ざることを防ぐ。

#### 作業

1. 本リポジトリに`docs/decisions/`を作る。
2. 次の決定をADRとして記録する。
   - in-repoマルチモジュール方式（別プロジェクトへの再実装は行わない）
   - 3層の依存方向
   - YNodeは構文木とする
   - YNodeは可変のまま、copy-on-write規律で守る（§2.4）
   - semantic graphは派生物とする
   - 保存と履歴を分離する
   - すべての変更をoperation/transactionで表す
   - Undo/Redo・履歴はworkspace-coreが単一所有する（§5）
3. 現時点のテスト実行結果を基準として記録する。
4. 代表的なYAMLとworkspaceを`test-fixtures`へ複製する（`sample-workspace/`は変更しない）。
5. 既存挙動を変える予定の項目を「意図的差分一覧」に記録する。

#### 必須fixture

- コメント、引用符、日付、null、空文字を含むYAML
- map、sequence、入れ子object
- 複数ドキュメントYAML
- `$ref`と`cases`を含むschema
- ID、ref、未解決ref、ID重複を含む複数文書workspace
- `.yde`構成とlegacy構成を1つずつ
- entity renameで複数文書が更新されるworkspace

#### 完了条件

- アプリを含む`./gradlew test`が引き続き成功する。
- fixtureの由来と期待結果が文書化されている。
- 意図的差分と移植ミスを区別する場所がある。

### Phase 1: モジュール骨格を作る

#### 作業

1. `yaml-core`、`semantic-core`、`workspace-core`、`cli`を作る。
2. 依存方向をGradleで設定する。
3. 各モジュールに1つずつsmoke testを置く。
4. JDK、Kotlin、SnakeYAMLのversionを固定する。
5. CIを用意できる場合は、この時点で`./gradlew test`だけを登録する。

#### 完了条件

- 4モジュールが個別にコンパイルできる。
- 既存アプリのビルド・テストが引き続き成功する。
- `yaml-core`から`workspace-core`の型を参照できない。
- 3つのcoreモジュールにCompose、POI、HTTP関連依存が存在しない。

### Phase 2: YAML文書モデルとI/Oを移す

#### 移植元

- `model/YNode.kt`
- `yaml/YamlIO.kt`
- `model/YPathTest.kt`
- `yaml/YamlIOTest.kt`

#### 作業順

1. `model/YNode.kt`（`YNode`と`YPath`）とそのテストをモジュールへ移動する。
2. アプリを含む全体テストで移動の無害を確認する。
3. `YDocument`と`DocumentId`を追加する。
4. `YamlCodec.parse`を実装する。
5. `YamlCodec.dump`を実装する。
6. 複数ドキュメントを`YDocument.documents`として扱う。
7. コメント・style継承のテストを移す。
8. `dumpPlain`相当が必要なら、正規化用途として明示的に別APIにする。

#### 判断ポイント

- 決定済み: YNodeはmutableのまま移す。snapshot公開後の木を変更しないcopy-on-write規律（§2.4）で整合を守る。
- YAML anchor/aliasをサポート対象に含めるか。
- 複数ドキュメントの編集対象を先頭だけに限定するか、すべて扱うか。

#### JUnit確認

- parse → dump → parseでplain treeが一致する。
- コメントとキー順が保持される。
- 引用符付きscalarのstyleが保持される。
- 日付らしい文字列が意図せずDateへ変換されない。
- 複数ドキュメントの後続文書が失われない。

#### 完了条件

- `yaml-core`単独で全fixtureをround-tripできる。
- `YamlCodec`公開APIにSnakeYAMLの型がない。
- ファイルI/Oなしでテストできる。

### Phase 3: 構造schemaと単文書検証を移す

#### 移植元

- `schema/Schema.kt`
- `schema/BuiltinSchemas.kt`の必要部分
- `validate/Validator.kt`の構造検証
- 関連するschema/validateテスト

#### 作業順

1. `FieldType`、`FieldDef`、`SchemaDef`の最小構造を移す。
2. schema YAMLから`SchemaDefinition`をparseする。
3. `$ref`をI/Oなしの`SchemaRegistry`経由で解決する。
4. schema定義自身の検証を移す。
5. string、number、boolean、enumから構造検証を開始する。
6. array、map、object、anyを追加する。
7. required、cases、range、length、patternを追加する。
8. id/refは構文上認識するが、存在確認はまだ実装しない。
9. structural issueとsemantic issueを別の型にする。

#### 重要な境界

同じschema YAMLに構造制約と意味的注釈が共存してもよい。ただしyaml-coreは意味的注釈を解釈しない。

```text
type: ref
idPattern: REQ-*
```

yaml-coreは「refとして許されるscalar/object形状」を確認できるが、「REQ-001が存在するか」は確認しない。

#### 完了条件

- 単一文書の検証に`IdIndex`やworkspaceが不要である。
- schema parse、resolve、validateがメモリ上の文字列だけで実行できる。
- 代表的な既存constraintテストが通る。

### Phase 4: 編集操作と逆操作を移す

#### 移植元

- `core/Patch.kt`
- `core/PatchTest.kt`

`core/Documents.kt`のテキストsnapshot履歴はここでは移さない。履歴はPhase 8でworkspace-coreの`ChangeRecord`として再設計する（§5）。

#### 作業順

1. `YamlOperation`を定義する。
2. 各操作の事前条件と失敗型を決める。
3. 操作適用と同時にinverseを生成する。
4. `EditBatch`（一括適用と途中失敗時の巻き戻し）を追加する。
5. コメント・スタイルを含めて「適用→inverse適用」で元へ戻ることをテストする。

#### 必須テストシナリオ

```text
各操作について:
適用              -> 期待する木になる
inverse適用       -> plain treeとdump結果が元と一致する
失敗する操作      -> 文書が変化しない
EditBatch途中失敗 -> 文書が変化しない
曖昧な文字列のSetScalar -> dump→再parseで型が変わらない（"true"・"2026-08-16"等）
```

#### 完了条件

- すべての編集操作に逆操作がある。
- 操作失敗時に文書が部分変更されない。
- `SetScalar`がスカラー値域と往復型安全の契約（§4.1）を満たす。
- Undo/Redoスタックはこのフェーズでは作らない（Phase 8で実装する）。

### Phase 5: semantic snapshot、ID、refを移す

#### 移植元

- `index/IdIndex.kt`
- `index/EntityGraph.kt`
- `schema/Display.kt`のタイトル解決（`IdEntry.title`が`Display.titleOf`へ依存する。R10-01）
- `index/DocScannerTest.kt`
- `index/IdIndexIncrementalTest.kt`
- `index/EntityGraphTest.kt`
- `validate/Validator.kt`のID/ref検証

#### 作業順

1. `SemanticDocument`と`SemanticInput`を定義する。
2. 文書1件からIDとrefを抽出する`DocumentSemanticScanner`を作る。
3. 複数文書から`IdIndex`を構築する。
4. 重複IDをsemantic issueとして報告する。
5. 未解決refを報告する。
6. 逆参照を構築する。
7. `ReferenceGraph`を構築する。
8. 最小の`EntityGraph`を構築する。
9. `SemanticSnapshot.sourceVersion`を必須にする。
10. 最初は全再構築だけを実装する。

`IdEntry.title`は`Display.titleOf`（R10-01）に依存するため、Displayのタイトル解決コアをこのフェーズで同時に移す。サブタイトルや畳み込みヘッダ等のUI寄り表示はPhase 6E以降でよい。

#### 完了条件

- ファイルシステムなしで複数文書の意味解析ができる。
- scalarのID、配列内ID、入れ子IDを既存ルールどおり認識する。
- 重複ID、未解決ref、逆参照がテストできる。
- semantic-coreがworkspace-coreへ依存していない。

### Phase 6: 高度な意味機能を小分けに移す

一括して移さない。次の順序で、各サブフェーズを独立して完了させる。

#### Phase 6A: relationとentity type

- `schema/Relations.kt`
- entity type registry
- relation label/inverse label
- relation宣言の重複検証

完了条件: 同じ文書集合から宣言とグラフが決定的に構築される。

#### Phase 6B: aspectとentity合成

- `core/Aspects.kt`
- aspect ownership
- entityの複数出現合成
- 重複所有検出

完了条件: 合成結果の各値に原本文書と`YPath`の来歴がある。

#### Phase 6C: queryとtraverse

- `schema/Query.kt`
- `index/Query.kt`
- `core/Traverse.kt`
- query constraint

完了条件: query宣言、評価、制約検証がsemantic-core内で追跡できる。

#### Phase 6D: derivationとprojection

- `schema/Derivation.kt`
- `index/Derivation.kt`
- `schema/Projection.kt`

完了条件: 導出値に入力座標と規則座標の来歴があり、未解決理由を構造化して返す。

#### Phase 6E: viewの非UI部分

- `core/Views.kt`のモデル、parse、lint、resolve
- text renderはCLI側へ分離可能
- Compose向け編集処理は移さない

完了条件: view解決結果が原本座標を保持し、独自の書込経路を持たない。

#### Phase 6F: schema inferenceとderived schema

- `schema/SchemaInference.kt`
- `schema/DerivedSchemas.kt`

この段階は初期コア完成後でもよい。推測結果は必ずPhase 3のschema compilerとdefinition validatorを通す。

### Phase 7: 読取専用workspaceを作る

#### 移植元

- `schema/WorkspaceLayout.kt`
- `core/Workspace.kt`のscan/load部分
- `core/WorkspaceLayoutTest.kt`

#### 作業順

1. `DocumentStore`と`InMemoryDocumentStore`を定義する。
2. `FileSystemDocumentStore`を実装する。
3. path正規化とworkspace外参照拒否を実装する。
4. legacy構成のlayoutを移す。
5. `.yde`構成のlayoutを移す。
6. YAML文書とschema文書をロードする。
7. `WorkspaceSnapshot`を生成する。
8. structural validationを実行する。
9. `SemanticInput`を構築してsemantic engineを実行する。
10. `WorkspaceDerivedState`を返す。

#### 完了条件

- `workspace validate <root>`でfixtureを検証できる。
- coreモジュール内では、workspace-core以外に`Path`や`Files`が漏れない。
- 読取時点の全結果が同じ`WorkspaceVersion`に紐付く。
- legacyと`.yde`の対象文書が既存挙動と一致する。

### Phase 8: workspace更新、保存、履歴を作る

#### 移植元

- `core/Workspace.kt`の`ChangeSet`適用
- `agent/Staging.kt`の候補状態
- `app/AppState.kt`のsave/create/delete/rename
- `core/Documents.kt`のworkspace操作
- `core/Documents.kt`の履歴部分（テキストsnapshot履歴を`ChangeRecord`履歴として再設計）

#### 作業順

1. `WorkspaceOperation`と`WorkspaceTransaction`を定義する。
2. working snapshotだけを更新する`apply`を作る。
3. 候補snapshot上でstructural/semantic検証する。
4. 検証失敗時の適用ポリシーを決める。
5. inverse transactionを生成する。
6. `ChangeRecord`とworkspace Undo/Redoを実装する。
7. `save`を独立して実装する。
8. 外部変更の検出（タイムスタンプ基準の通知とreload。現行`FileWatcher`水準）を実装する。
9. 複数ファイル保存の失敗時ポリシーを決める。
10. create/delete/moveを追加する。

#### 保存の安全性

ファイルシステム実装では次を満たす。

- workspaceルート外へ書かない。
- 一時ファイルへ書き、可能ならatomic moveする。
- 一部保存に失敗した場合、成功済みファイルを結果として明示する。
- 完全なatomicityを保証できない場合、API名や結果型で誤解させない。

#### 必須テスト

- applyしてもDocumentStoreは変化しない。
- saveしたときだけDocumentStoreが変化する。
- saveしても履歴が消えない。
- 複数文書transactionが途中状態を公開しない。
- 古いbaseVersionのtransactionを拒否する。
- 外部変更が検出でき、reloadで取り込める。
- undo/redoのたびにsemantic snapshotが対応versionへ更新される。

保存とUndo/Redoの独立は次のシナリオで固定する。

```text
初期A
apply B
apply C
undo      -> B
redo      -> C
save
apply D   -> dirty
undo      -> C、dirty=false
undo      -> B、dirty=true
apply E   -> redo不可
```

#### 完了条件

- UIなしで、編集→検証→Undo→Redo→保存をJUnitとCLI shellから確認できる。
- すべての書込が`WorkspaceSession.save`へ集約される。

### Phase 9: 意味的リファクタリングを移す

#### 移植元

- `core/EntityOps.kt`
- `core/SchemaRefactor.kt`
- `core/DocMove.kt`
- 対応テスト

#### 移植順

1. entity ID rename
2. entity delete
3. entity merge
4. schema field rename
5. schema field type migration
6. document moveと文書参照更新

各機能を次の2段階に分ける。

```text
semantic-core: plan(snapshot, request) -> RefactoringPlan
workspace-core: apply(plan) -> TransactionResult
```

#### 必須テスト

- plan作成ではworkspaceが変化しない。
- planに全影響文書と原本座標が含まれる。
- plan作成後にworkspace versionが変化した場合は適用を拒否する。
- dry-runと実適用の候補結果が一致する。
- 適用後に参照切れが発生しない。
- 1回のworkspace Undoで全変更が戻る。

#### 完了条件

- CLIでplanを表示してから明示的に適用できる。
- リファクタリングコードがファイルを直接書かない。

### Phase 10: 簡易CLIを完成させる

#### 表示形式

CLI出力は、テストしやすいように次を分ける。

- 人間向けtext
- `--format yaml`または`--format json`による機械可読出力

CLIのexit codeを固定する。

| code | 意味 |
|---:|---|
| 0 | 成功、検証問題なし |
| 1 | コマンドまたは入力エラー |
| 2 | structural validation error |
| 3 | semantic validation error |
| 4 | 古いbaseVersionの拒否 |
| 5 | 保存失敗 |

#### CLIテスト

CLIのテストは、標準出力文字列だけでなく、exit codeと構造化結果を確認する。CLI dispatcherからcore API呼出しを分離し、処理本体は直接JUnitテストできるようにする。

#### 完了条件

- parse、validate、catalog、entity inspect、refactor planが実行できる。
- shellでapply、undo、redo、save、reloadを確認できる。
- CLIに業務ロジックが存在しない。

### Phase 11: 増分更新と性能を戻す

正しさの確認前に増分更新を移さない。

#### 作業

1. 全再構築の結果をoracleとする。
2. 文書1件変更時のsemantic差分入力を定義する。
3. ID indexの増分更新を移す。
4. entity graph、query cacheの順に増分化する。
5. schema変更時は影響範囲が不明なら全再構築する。
6. 増分結果と全再構築結果を毎回比較するテストを用意する。
7. `core/Bench.kt`から必要な計測だけをCLIへ移す。

#### 完了条件

- 増分更新と全再構築が同一結果を返す。
- staleなsemantic snapshotをcurrentとして公開しない。
- 性能最適化を無効化しても正しく動く。

### Phase 12: 移行完了判定

#### yaml-core Definition of Done

- 単独でビルド・テストできる。
- SnakeYAML以外のアプリ依存がない。
- parse/dump、schema構造検証、編集操作と逆操作が動く。
- ファイルシステム、entity、workspaceを知らない。
- 公開APIに実装ライブラリの型が漏れていない。

#### semantic-core Definition of Done

- メモリ上のsnapshotだけで動く。
- ID/ref/entity/relationの意味モデルを構築できる。
- 意味的検証とリファクタリング計画を生成できる。
- workspaceやファイルI/Oへ依存しない。
- すべての結果が入力versionと原本座標を持つ。

#### workspace-core Definition of Done

- working/persisted/derivedの状態を区別できる。
- apply、Undo/Redo、save、reload、外部変更検出が動く。
- structural/semantic結果のsource versionを追跡できる。
- 書込経路がDocumentStoreへ集約されている。
- `InMemoryDocumentStore`で全主要動作をテストできる。

#### 全体 Definition of Done

- `./gradlew test`が全モジュールで成功する。
- 簡易CLIだけで主要シナリオを再現できる。
- 3つのcoreモジュールがルート（アプリ）のコードへ依存していない。
- 旧テストの移行・非移行理由が台帳化されている。
- 既存挙動との差分が意図的差分一覧に記録されている。

### 推奨マイルストーン

| マイルストーン | 到達フェーズ | 利用可能になるもの |
|---|---:|---|
| M1: YAMLライブラリ | Phase 4 | parse/dump、構造schema検証、編集操作と逆操作 |
| M2: 意味解析ライブラリ | Phase 6 | ID/ref/entity graph、意味検証、query等の主要な純粋機能 |
| M3: ヘッドレスworkspace | Phase 8 | load、working state、transaction、Undo/Redo、save、外部変更検出 |
| M4: リファクタリング基盤 | Phase 10 | plan/apply分離とCLIによる手動確認 |
| M5: 実用性能 | Phase 11 | 増分索引と性能計測 |

M1、M2、M3の各時点でいったん設計レビューを行う。既存UIは旧経路のまま動かし、M3までは新APIへ接続しない。

## 9. 必須の統合シナリオ

### Scenario A: 単文書編集

1. YAMLをparseする。
2. schemaを適用する。
3. scalarを変更する。
4. structural validationする。
5. inverse operationで元へ戻す。
6. もう一度適用する。
7. dumpしてround-tripを確認する。

### Scenario B: 保存と履歴の分離

1. workspaceをロードする。
2. 文書を2回変更する。
3. 保存する。
4. Undoする。
5. dirtyになることを確認する。
6. Redoする。
7. 保存済み内容と一致しdirty=falseになることを確認する。

### Scenario C: 未保存snapshotの意味解析

1. 原本には存在しないIDをworking snapshotへ追加する。
2. 別文書からそのIDへrefを追加する。
3. 保存前にsemantic validationする。
4. refが解決されることを確認する。
5. DocumentStoreが未変更であることを確認する。

### Scenario D: entity rename

1. entityと複数refを含むworkspaceをロードする。
2. rename planを作る。
3. 影響を表示する。
4. workspace versionが変わっていないことを確認する。
5. planを適用する。
6. semantic validationが成功することを確認する。
7. 1回のUndoですべて戻す。

### Scenario E: 外部変更の検出

1. workspaceをロードする。
2. DocumentStore上の文書を外部変更する。
3. 外部変更が検出・通知されることを確認する。
4. reloadで新しい内容が取り込まれることを確認する。

楽観ロックは行わない（保存は最終書込み優先。現行水準）。競合の拒否は移行後のバージョン再設計で扱う（§11）。

### Scenario F: stale derived state

1. Workspace v1からSemanticSnapshot v1を構築する。
2. 文書を編集してWorkspace v2にする。
3. v1のsemantic結果をcurrentとして取得できないことを確認する。
4. 再構築後にSemanticSnapshot v2が得られることを確認する。

## 10. 移行時の主要リスク

### 10.1 Schema.ktをそのまま移して循環依存する

`Schema.kt`には構造schema、意味宣言、loaderが同居している。丸ごとyaml-coreへ置くと、workspace探索やentity概念までyaml-coreへ入り込む。

対策:

- テストケース単位で必要部分だけ移す。
- parse、compile、semantic interpretation、workspace discoveryを別APIにする。
- yaml-coreはschema文書の内容を受け取るが、schemaファイルを探さない。

### 10.2 Validatorの分割で検証漏れが起きる

既存Validatorは1回の走査で多数の検査を行う。分割時にどちらにも移されない分岐が発生しやすい。

対策:

- 既存の各`Issue`生成箇所を台帳化する。
- 各項目へ`structural`、`semantic`、`対象外`を付ける。
- 旧fixtureと新fixtureの問題一覧を正規化して比較する。

### 10.3 コメント・styleが操作時に失われる

ノード置換時のmetadata継承は既存実装の重要な暗黙条件である。

対策:

- `YamlOperationExecutor`がmetadata継承を一元化する。
- すべての置換操作でround-tripテストを行う。
- `dumpPlain`は比較専用で、保存には使用しない。

### 10.4 可変YNodeとsnapshotの共有で状態が壊れる

YNodeは可変のまま維持する（決定事項）。このままsnapshot間で木を共有すると、1回のin-place編集がworking・persisted・候補のすべてを同時に書き換えてしまう。

対策（§2.4の規律の徹底）:

- 公開済みsnapshotへ入った木は以後変更しない。編集は「対象文書だけをdeep copy → 操作適用 → 参照交換」で行う。
- persisted状態はYNode木を共有せず、保存済みテキストで保持する。
- `SemanticSnapshot`にYNode参照を保持しない。抽出済みの値・`YPath`・座標だけを持つ。
- deep copyは変更対象の文書に限るため全文書コピーは発生しない。さらなる構造共有はPhase 11で検討する。

### 10.5 workspace transactionとファイル保存を同一視する

`apply`時に直接書き込む設計に戻ると、未保存確認、Undo、AI提案、dry-runを共通化できない。

対策:

- InMemoryDocumentStoreの書込回数をテストする。
- `apply`のテストでは書込回数が0であることを必須にする。
- 保存は`save`メソッドだけに限定する。

### 10.6 増分索引を早く移しすぎる

旧実装にはディスク索引、UI overlay、agent stagingという複数経路がある。最初からすべて再現すると新しいsnapshot設計が不明瞭になる。

対策:

- 最初はsnapshot全再構築に統一する。
- correctness testが揃ってから増分化する。
- 増分結果を常に全再構築と比較する。

### 10.7 手動移行中に機能追加が混ざる

理解を深めながら調整する作業では、移植と再設計の境界が曖昧になりやすい。

対策:

- 変更ごとに`port`、`refactor`、`behavior-change`のラベルを付ける。
- 1コミットに複数ラベルを混ぜない。
- behavior changeには変更前後のJUnitを用意する。

## 11. 判断を保留してよい項目

次は初期移行を止めず、後から決められる。

- バージョン概念の再設計（意味のある変更まとまりの管理・楽観ロック・競合検出。各層の役割整理後に改めて行う）
- 意味スキーマによる表記ポリシー（§4.4。`SemanticDeclarations`への宣言族追加として導入する）
- 永続的な変更履歴ファイル
- 分散workspaceや共同編集
- YAML anchor/aliasの完全サポート
- 増分semantic engine
- view機能の全移植
- schema inference
- derived schemaの自動適用
- FileWatcher
- AI由来の`ChangeOrigin`と権限モデル
- UI向けobservable state

一方、次はPhase 1から固定する。

- モジュール依存方向
- YNodeとsemantic graphの分離
- 可変YNodeのcopy-on-write規律（§2.4）
- Undo/Redo・履歴のworkspace-core単一所有（§5）
- 表記と意味の方向規則（§4.4）
- semantic-coreからI/Oを排除すること
- applyとsaveの分離
- 結果にsource versionを持たせること
- リファクタリングのplanとapplyの分離

## 12. 手動移行用の作業台帳

本リポジトリに次の表を置き、テストまたは機能単位で更新する。

```markdown
| ID | 旧テスト/機能 | 新モジュール | 状態 | 差分 | 判断 |
|---|---|---|---|---|---|
| YM-001 | YPathTest | yaml-core | done | なし | 既存契約維持 |
| YM-002 | YamlIOTest/comment | yaml-core | doing | 未確認 | - |
| SM-001 | DocScannerTest/id | semantic-core | todo | - | - |
| WS-001 | WorkspaceLayoutTest | workspace-core | todo | - | - |
```

状態は`todo`、`doing`、`blocked`、`done`だけを使う。`blocked`には必ず再開条件を書く。

各作業項目では次を記録する。

```text
旧実装:
旧テスト:
新しい責務:
新しい公開API:
維持する挙動:
意図的に変える挙動:
未解決事項:
完了確認コマンド:
```

## 13. 最初の具体的な20手

1. `settings.gradle.kts`へ4モジュールを追加する。
2. 各モジュールへKotlin・JUnit 5を設定し、`yaml-core`だけにSnakeYAMLを置く。
3. 各モジュールにsmoke testを1件置き、アプリを含む`./gradlew test`が通ることを確認する。
4. in-repo方式・依存方向・可変YNode規律・Undo単一所有のADRを書く。
5. `model/YNode.kt`（`YNode`・`YPath`）と`YPathTest`を`yaml-core`へ移動する。
6. アプリのimportを変えずに全体テストが通ることを確認する。
7. `yaml/YamlIO.kt`と`YamlIOTest`を`yaml-core`へ移動する。
8. ルートのSnakeYAML依存を外し、`yaml-core`経由へ一本化する。
9. `YDocument`と`DocumentId`を`yaml-core`へ新設する。
10. `YamlCodec`を`YamlIO`のラッパーとして導入する。
11. コメント・style保持とround-tripのテストが`yaml-core`側で通ることを確認する。
12. 複数ドキュメントfixtureのテストを追加する。
13. `Schema.kt`から`FieldType`・`FieldDef`・`$ref`解決の構造部分を`yaml-core`へ分離する。
14. `SchemaLoaderTest`の構文・`$ref`ケースを移す。
15. `Validator.kt`から型・required・range検証を`yaml.validation`へ分離する。
16. `ConstraintTest`の単文書ケースと`ValidatorTest`を移す。
17. `core/Patch.kt`を`yaml.edit`へ移動する。
18. `YamlOperation`として再構成し、`PatchTest`を移す。
19. `SetScalar`と`Remove`のinverse生成テストを書く。
20. `EditBatch`の一括適用と途中失敗の巻き戻しを確認する。

ここまで完了するまで、IdIndex、Workspace、CLIには着手しない。この時点でyaml-coreの境界が使いにくい場合は、上位層を作る前に修正する。

## 14. フェーズごとの確認コマンド

```bash
./gradlew :yaml-core:test
./gradlew :semantic-core:test
./gradlew :workspace-core:test
./gradlew :cli:test
./gradlew test
```

CLI確認例:

```bash
./gradlew :cli:run --args="yaml roundtrip test-fixtures/yaml/comments.yaml"
./gradlew :cli:run --args="workspace validate test-fixtures/workspaces/basic"
./gradlew :cli:run --args="workspace inspect-entity test-fixtures/workspaces/basic REQ-001"
./gradlew :cli:run --args="workspace plan-rename-entity test-fixtures/workspaces/basic REQ-001 REQ-101"
./gradlew :cli:run --args="shell test-fixtures/workspaces/basic"
```

## 15. 推奨コミット単位

手動移行では、コミットを小さく保つ。

良い例:

```text
YPathと座標テストをyaml-coreへ移植
コメントを保持するYamlCodecの契約を追加
stringの構造検証を移植
YamlOperationの逆操作を追加
ID抽出をsemantic snapshot入力へ移植
workspaceのworking/persisted状態を分離
entity改名をplanとapplyへ分割
```

避ける例:

```text
coreを全部移行
SchemaとValidatorをリファクタリング
Workspaceを新設計へ変更
```

各コミットは、少なくとも対象モジュールのJUnitが成功する状態にする。

## 16. 移行後にUIを接続するときの入口

今回は実装しないが、core側では将来UIが次だけを利用すればよい形を目標にする。

```kotlin
interface WorkspaceFacade {
    fun snapshot(): WorkspaceSnapshot
    fun derivedState(): WorkspaceDerivedState
    fun apply(transaction: WorkspaceTransaction): TransactionResult
    fun planRefactoring(request: RefactoringRequest): RefactoringPlan
    fun applyRefactoring(plan: RefactoringPlan): TransactionResult
    fun undo(): TransactionResult
    fun redo(): TransactionResult
    fun save(selection: SaveSelection): SaveResult
    fun reload(): ReloadResult
}
```

UIは`YNode`を直接変更せず、`YamlOperation`または`WorkspaceTransaction`を作る。AIも将来同じ入口を使い、違いは提案・承認ポリシーだけにする。

この入口をCLIで十分に検証できれば、UI接続時にcore設計を再び変更する必要が小さくなる。
