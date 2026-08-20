# 3層コア移行台帳

[THREE-LAYER-CORE-MIGRATION-PLAN.md](THREE-LAYER-CORE-MIGRATION-PLAN.md) §12の作業台帳。テストまたは機能単位で更新する。
状態は `todo` / `doing` / `blocked` / `done` のみ。`blocked` には必ず再開条件を書く。

## ベースライン（Phase 0記録）

- 記録日: 2026-08-16（コミット 677fc43 ＋ 計画書・ADR等の未コミット差分）
- `./gradlew test`: **561件 全成功**（単一モジュール構成・kotlin("test") / JUnit4ランナー）
- 環境: Gradle 9.3.0 / Kotlin 2.0.21 / JVM toolchain 17 / SnakeYAML 2.3
- 備考: SnakeYAMLの直接利用は `yaml/YamlIO.kt` と `ui/SourcePane.kt` の2箇所。SourcePaneはUI層のため移行対象外であり、ルートのSnakeYAML依存はUI移行まで残る（yaml-coreへの「一本化」はcore経路のみ）

## フェーズ進捗

| ID | 作業 | 新モジュール | 状態 | 差分 | 判断 |
|---|---|---|---|---|---|
| P0-01 | ADR 0001〜0008 作成 | - | done | - | 計画書§8 Phase 0 |
| P0-02 | 意図的差分一覧（D-01〜D-08） | - | done | - | 計画書§2.3 |
| P0-03 | test-fixtures 作成と期待結果の記録 | - | done | - | 由来と期待結果は test-fixtures/README.md |
| P0-04 | ベースラインテスト結果の記録 | - | done | - | 上記561件 |
| P1-01 | 4モジュール骨格（settings・build・smoke test） | 全モジュール | done | - | ルートは3coreへ依存追加 |
| YM-001 | model/YNode.kt（YNode・YPath）と YPathTest の移動 | yaml-core | done | なし | 既存契約維持（portのみ。2026-08-16） |
| YM-002 | yaml/YamlIO.kt と YamlIOTest の移動 | yaml-core | done | なし | 既存契約維持＋fixture round-tripテスト追加（2026-08-16） |
| YM-003 | YDocument / DocumentId / YamlCodec 新設 | yaml-core | done | なし（新API） | Phase 2完了。完了条件3点を確認（2026-08-16） |
| YM-004 | Schema.kt: FieldDefクラスタ（純構造型12種）の分離 | yaml-core | done | 可視性3箇所・smart cast修正7箇所 | Phase 3前半（2026-08-16） |
| YM-004b | SchemaDef の構造分割（StructuralSchema 新設・resolve／effectiveFields 等の移動） | yaml-core | done | なし（委譲化） | Phase 3（2026-08-17） |
| YM-004c | スキーマ構文パースの構造部分を yaml-core へ（StructuralSchemaParser） | yaml-core | done | なし（委譲化） | Phase 3（2026-08-17） |
| YM-005 | Validator の構造検証分離（StructuralValidator・StructuralIssue・Severity 移動） | yaml-core | done | 構造指摘の別型化 | Phase 3（2026-08-17） |
| YM-006 | ConstraintTest 単文書ケース・ValidatorTest 構造ケースの移動 | yaml-core | done | 属性付き参照の ok 判定から INFO フィルタ除去（構造検証は参照解決をしないため） | Phase 3（2026-08-17） |
| YM-006b | 残テストの移動（SchemaLoaderTest 構文・$ref／MetaSchemaTest 構造部分／PhaseTest required 部分）と BuiltinSchemas 資源化の検討 | yaml-core | todo | - | Phase 3 の残整理。急がない |
| YM-007a | Patch.kt の分割移動（PatchOp・Patcher → yaml-core、ChangeSet はルート ChangeSets.kt へ） | yaml-core | done | なし | Phase 4前半（2026-08-17） |
| YM-007b | YamlOperation・逆操作・EditBatch の新設（§4.1契約） | yaml-core | done | 逆操作は列（キー順復元の ReorderKeys 随伴） | Phase 4完了＝M1到達（2026-08-17） |
| SM-000 | 二層モデル宣言語彙（Relations.kt）と CoverageLevel の移動 | semantic-core | done | entityFor / ownedFieldKeys の再パラメータ化 | Phase 5準備（2026-08-18） |
| SM-001 | 走査カーネル（SemanticScanner）・表示解決（DisplayCore）・RefNaming の移動 | semantic-core | done | なし（委譲シェル化） | Phase 5（2026-08-18） |
| SM-002 | SemanticInput / SemanticSnapshot / SemanticEngine（全再構築・重複/未解決ref検出・逆参照） | semantic-core | done | 新API（重複メッセージは「文書内」表現） | Phase 5後半（2026-08-18） |
| SM-003 | 最小 EntityGraph の DocumentId 座標版カーネル化（SemanticEntityGraph） | semantic-core | done | ルート EntityGraph と一時併存 | Phase 5完了（2026-08-18） |
| SM-004 | グラフ整合検証（GraphIntegrity）のカーネル化とエンジン接続 | semantic-core | done | ルートはIdIndex→カーネル座標の変換シェル | Phase 6A完了（2026-08-19） |
| SM-005 | エンティティ合成（Aspects）のカーネル化（SemanticAspects・SchemaSemantics） | semantic-core | done | 変換ブリッジを index/SemanticBridge.kt へ一元化 | Phase 6B完了（2026-08-19） |
| SM-006 | query宣言の移動と照合エンジンのカーネル化（SemanticQuery・DocumentMeta） | semantic-core | done | ルート Query はシェル。Traverse の emit レンダラはルート残置 | Phase 6C完了（2026-08-19） |
| SM-007 | derivation/projection の移動（宣言・評価器・解決器カーネル・globToRegex） | semantic-core | done | ルート index/Derivation はシェル | Phase 6D完了（2026-08-19） |
| SM-008 | Phase 6E（view非UI部）→ 仮想文書設計で再実装（§17-6 方向決定 2026-08-19） | semantic-core | blocked | - | 再開条件: M3（Phase 8）完了。仮想文書（写像＋来歴＋書込翻訳）として6Eを再開し、現行UIへは ViewRow/ViewCell 互換シムで接続 |
| WS-001 | WorkspaceLayout の移動と DocumentStore（InMemory / FileSystem）新設 | workspace-core | done | 予約ディレクトリ名の正を Zone のリテラルへ | Phase 7前半（2026-08-19） |
| WS-002 | 読取専用ワークスペース（WorkspaceReader）と意味宣言パーサの移動 | workspace-core / semantic-core | done | v1未対応: derive・メタスキーマ適用 | Phase 7後半（2026-08-19） |
| WS-003 | 簡易CLIの最小配線（yaml parse / roundtrip・workspace validate・exit code契約） | cli | done | - | Phase 7完了（2026-08-19） |
| WS-004 | WorkspaceSession（apply / undo / redo / save・ChangeRecord履歴・CoW適用） | workspace-core | done | - | Phase 8主要部（2026-08-19） |
| WS-005 | Phase 8残の実装（MoveDocument・外部変更検出・atomic書込） | workspace-core | done | - | Phase 8完了＝M3到達（2026-08-20） |
| RF-001 | エンティティ操作の plan/apply 分離（rename / merge / delete）と CLI 配線 | semantic-core / workspace-core / cli | done | - | Phase 9第1弾（2026-08-20） |
| RF-002a | スキーマ文書を編集可能な文書として含める＋dirty基準の正規化修正 | workspace-core | done | dirty基準＝正規化dump（現行EditBufferと同基準）・外部変更検出は生テキスト別追跡 | RF-002の前提（2026-08-20） |
| RF-002b | スキーマ・リファクタリング第1弾（renameField の plan/apply 化・Site機構の移植） | semantic-core / workspace-core | done | ビュー追随は警告降格（6E設計待ち） | Phase 9続き（2026-08-20） |
| RF-002c | SchemaRefactor R25-03 の移植（renameEnumValue / deleteField / requireField） | semantic-core / workspace-core | done | ビュー等の言及追随は警告降格 | 2026-08-20 |
| RF-002d | SchemaRefactor R25-02 の移植（moveField / alignOrder・Crossing 機構の復元） | semantic-core / workspace-core | done | ビュー等の言及追随は警告降格 | 2026-08-20 |
| RF-002e | SchemaRefactor 残り操作の移植（convertFieldType / definitions系 / renameRelation / renameSchemaId） | semantic-core / workspace-core | done | ビュー内の definition / relation / schemaId 参照の追随は警告降格 | Phase 9主要部完了（2026-08-20） |
| WS-006 | スキーマ変更のセッション内 rebuild（スキーマ束再構築・全文書の割当再評価・undo対称） | workspace-core | done | schemasゾーン文書へ一般スキーマを割り当てない規則も是正 | 2026-08-20 |
| RF-003 | document move の参照更新（DocMove）の plan/apply 化 | semantic-core / workspace-core | done | 移動＋参照書き換えが1トランザクションに（旧経路の非原子的継ぎ目を解消） | Phase 9第2弾（2026-08-20） |

## 作業項目の記録

### YM-001 model/YNode.kt（YNode・YPath）の移動（done 2026-08-16）

```text
旧実装: src/main/kotlin/model/YNode.kt
旧テスト: src/test/kotlin/model/YPathTest.kt
新しい責務: 変更なし（git mv で yaml-core へ移動。パッケージ名 model のまま）
新しい公開API: 変更なし
維持する挙動: すべて（port のみ）
意図的に変える挙動: なし
未解決事項: なし
完了確認コマンド: ./gradlew test
```

### YM-002 yaml/YamlIO.kt の移動（done 2026-08-16）

```text
旧実装: src/main/kotlin/yaml/YamlIO.kt
旧テスト: src/test/kotlin/yaml/YamlIOTest.kt
新しい責務: 変更なし（git mv で yaml-core へ移動）
新しい公開API: 変更なし
維持する挙動: すべて（port のみ）。加えて test-fixtures/yaml のround-trip検証を
  FixtureRoundTripTest として新設（コメント・引用符スタイル・日付らしい文字列の
  非Date化・フロー表記・複数ドキュメント。fixtures.root システムプロパティ経由）
意図的に変える挙動: なし
未解決事項: ルートの SnakeYAML 依存は ui/SourcePane.kt が直接利用しているため
  UI移行まで残る（ベースライン欄参照）
完了確認コマンド: ./gradlew test / ./gradlew :yaml-core:test
```

### YM-003 YDocument / DocumentId / YamlCodec の新設（done 2026-08-16）

```text
旧実装: なし（新API。計画書§4.1のスケッチどおり）
旧テスト: なし（YamlCodecTest を新設。テストメソッド名は日本語＝CONVENTIONS §4）
新しい責務: テキスト ⇔ YDocument（`---` 区切りの全ドキュメント）の相互変換。
  DocumentId は上位層が与える不透明な論理識別子（空白のみは拒否）
新しい公開API: model.DocumentId / model.YDocument / yaml.YamlCodec / yaml.DefaultYamlCodec
維持する挙動: コメント・スタイル保持は YamlIO へ委譲（挙動変更なし）。
  正規化出力は YamlIO.dumpPlain のまま別API（計画書 Phase 2 作業順8）
意図的に変える挙動: なし
未解決事項: パッケージは移行方針どおり既存名（model / yaml）へ同居。最終改名時に dev.toros.yde.yaml.* へ
完了確認コマンド: ./gradlew :yaml-core:test / ./gradlew test
```

### YM-004 Schema.kt: FieldDefクラスタの分離（done 2026-08-16）

```text
旧実装: src/main/kotlin/schema/Schema.kt 冒頭部（FieldType〜PhasesDef）
旧テスト: なし（型定義のみ。挙動は SchemaLoaderTest 等が間接カバー）
新しい責務: yaml-core/src/main/kotlin/schema/Fields.kt へ純構造型12種を移動
  （FieldType / UnknownFieldsPolicy / FieldRole / CollectionDisplay / EnumOption /
   SchemaKind / DefinitionKey / SchemaCatalog / FieldDef / RefTarget / RefScope / PhasesDef。
   パッケージ名 schema のまま＝ルートとの分割パッケージ）
新しい公開API: 従来と同名・同形（moveのみ）
維持する挙動: すべて
意図的に変える挙動: なし。付随変更（refactor扱い）:
  - 可視性 internal→public 3箇所（DefinitionKey / SchemaCatalog の find・local・linked /
    FieldDef.definitionScope）。SchemaLoader がyaml-coreへ移った後に internal へ戻す
  - モジュール跨ぎで smart cast 不可となった7箇所をローカル変数化
    （Schema.kt の validateDefinition 2箇所・ui/FormView.kt 1箇所）
未解決事項: SchemaDef 本体は意味宣言（EntityDecl / RelationDef / CoverageRule 等）と
  source: Path を抱えるため未移動 → YM-004b。$ref の resolve は SchemaDef 側に残存
完了確認コマンド: ./gradlew test
```

### YM-004b SchemaDef の構造分割（done 2026-08-17）

```text
旧実装: schema/Schema.kt の SchemaDef.resolve / definitionTarget / definitionRefOf、
  拡張関数 currentPhaseValue / currentPhaseIndex / requiredAt / effectiveFields / fieldAt、
  InactiveCase / EffectiveFields
旧テスト: SchemaLoaderTest・ValidatorTest・PhaseTest 等が間接カバー（既存位置のまま緑）
新しい責務: yaml-core/schema/StructuralSchema.kt（計画書§4.1 の CompiledDocumentSchema に相当）。
  $ref 解決・cases 合成・フェーズ必須・パス→定義解決を単一文書×構造の純粋APIとして提供
新しい公開API: schema.StructuralSchema（InactiveCase / EffectiveFields も移動）
維持する挙動: すべて。SchemaDef は `structural`（lazy）を持ち、既存メソッド・拡張関数は委譲のみ
  （呼び出し側の変更ゼロ）
意図的に変える挙動: なし
未解決事項: SchemaDef 本体（match・意味宣言・source: Path）はルートに残存。
  スキーマ構文パースの構造部分は未移動（YM-004c）
完了確認コマンド: ./gradlew test
```

### YM-005 Validator の構造検証分離（done 2026-08-17）

```text
旧実装: validate/Validator.kt の Severity / DATE_RE / numText / checkRange / checkField と
  セクション1（フェーズ申告警告＋スキーマ適合の呼び出し）
旧テスト: ValidatorTest / ConstraintTest / PhaseTest（既存位置のまま緑。移動は YM-006）
新しい責務: yaml-core/validate/StructuralValidator.kt（単一文書×StructuralSchema の検証。
  IdIndex・ワークスペース・ファイルI/O 非依存）と StructuralIssue / Severity
新しい公開API: validate.StructuralValidator.validate(root, schema): List<StructuralIssue>
維持する挙動: 検証項目・メッセージ・重大度のすべて（ルート Validator は StructuralIssue → Issue へ
  写すだけで、Validator.validate の外部挙動は不変）
意図的に変える挙動: 構造指摘を StructuralIssue として意味指摘（Issue）と別型化
  （計画書 Phase 3 作業順9）
未解決事項: ファイル内ID重複は DocScanner（索引走査）依存のためルート側に残存
  （semantic-core 移行時に整理）。IssueDerivation は Path / Derivation.Input を含むためルート側
完了確認コマンド: ./gradlew test
```

### YM-004c スキーマ構文パースの構造部分の分離（done 2026-08-17）

```text
旧実装: schema/Schema.kt SchemaLoader の parseField / parsePhases と、
  parseSchemaText 内の definitions / root ブロック
旧テスト: SchemaLoaderTest（既存位置のまま緑）
新しい責務: yaml-core/schema/StructuralSchemaParser.kt。テキスト／raw Map から
  構造コア（フィールド木・definitions・root・phases）をパースする。
  parse(text, fallbackId): StructuralSchema が yaml-core 単体・テスト用の入口
新しい公開API: StructuralSchemaParser.parse / parseDefinitions / parseRoot / parsePhases / parseField
維持する挙動: パース結果・エラーメッセージすべて。ルート SchemaLoader は委譲呼び出しに変更
意図的に変える挙動: なし。derive スキーマの root 省略判定は「raw に derive キーがあるか」の
  フラグ渡し（derive の内容は yaml-core で解釈しない）
未解決事項: SchemaLoader 本体（match・意味宣言・ワークスペース探索・カタログのリンク）はルート残存
完了確認コマンド: ./gradlew test
```

### YM-006 構造検証テストの移動（done 2026-08-17）

```text
旧テスト: validate/ValidatorTest.kt（cases 合成4件・ネスト case・属性付き参照2件）、
  validate/ConstraintTest.kt（実行時制約4件）
新テスト: yaml-core validate/StructuralValidatorTest.kt・StructuralConstraintTest.kt。
  セットアップを SchemaLoader.parseSchemaText(Path付き) → StructuralSchemaParser.parse(text, fallbackId)、
  検証を Validator.validate(IdIndex付き) → StructuralValidator.validate へ変更（分類2: 境界に合わせて
  セットアップだけ変更）
ルート残存: ValidatorTest=メタスキーマ検証1件、ConstraintTest=メタスキーマ検証・id型patternのメタ判定・
  JSON Schema 伝搬（分類3: 旧構造固有＝validateDefinition / ai 依存）
意図的に変える挙動: 属性付き参照の「正しい形式」判定から INFO フィルタを除去
  （構造検証は参照解決を行わないため未解決参照 INFO が発生しない。挙動差ではなく境界差）
完了確認コマンド: ./gradlew :yaml-core:test / ./gradlew test
```

### YM-007a Patch.kt の分割移動（done 2026-08-17）

```text
旧実装: core/Patch.kt（PatchOp / Patcher / FileChange / ChangeSet / ChangeSets が同居）
旧テスト: core/PatchTest.kt（Patcher ケースと変更セット解析ケースが同居）
新しい責務: PatchOp / Patcher（単一文書への破壊的操作。inheritMetaFrom のスタイル継承込み）を
  yaml-core/core/Patch.kt へ git mv。FileChange / ChangeSet / ChangeSets（ファイル・baseHash・
  変更セットYAML解析＝ワークスペース関心）はルート core/ChangeSets.kt へ分離
  （Phase 8 で WorkspaceTransaction へ再構成予定）
新しい公開API: 変更なし（型・関数は同名。所属ファイルのみ変更）
維持する挙動: すべて（port のみ）
意図的に変える挙動: なし。テストは Patcher ケース → yaml-core PatchTest、変更セットケース →
  ルート ChangeSetsTest（新設・日本語名）へ分割。混在していた1ケースは2つに分けた
未解決事項: YamlOperation・逆操作・EditBatch は未着手（YM-007b）
完了確認コマンド: ./gradlew test
```

### YM-007b YamlOperation・逆操作・EditBatch の新設（done 2026-08-17）

```text
旧実装: なし（新API。計画書§4.1。実装は yaml-core 内の Patcher 機構の上の薄い層）
旧テスト: なし（YamlEditTest を新設・日本語名・14ケース）
新しい責務: 型付き編集操作（SetScalar / Replace / Insert / Remove / Move / RenameKey / ReorderKeys）、
  適用と同時の逆操作列生成、EditBatch の一括適用と途中失敗の巻き戻し。
  model/Copy.kt に YNode.deepCopy / YComments.deepCopy を新設
  （inheritMetaFrom が YComments を共有するため、逆操作が保持する旧ノードは所有コピー必須）
新しい公開API: edit.YamlOperation / edit.EditBatch / edit.YamlEdit.apply / applyBatch、model.deepCopy
維持する挙動: 操作の意味論は Patcher と同一（座標も同じ「対象を指す YPath」）
意図的に変える挙動: なし。計画書§4.1のスケッチとの差分（親+position → 対象YPath、
  逆操作の単数 → 列、ReorderKeys 追加）は実装に合わせて計画書側を確定形へ更新済み
未解決事項: Move の巻き戻しは逆操作適用で実現（構造保証は候補木のCoWが担う。ADR-0003）
完了確認コマンド: ./gradlew :yaml-core:test / ./gradlew test
```

**Phase 6 主要部完了判定（2026-08-19）＝ M2: 意味解析ライブラリ到達**: 6A（relation / entity type・
グラフ整合）・6B（aspect 合成）・6C（query / traverse）・6D（derivation / projection）が semantic-core で
完結。6E（view 非UI部）は §17-⑥⑦（仮想文書・表示系の置き場）のオーナー検討と重なるため方向決定まで
保留（SM-008。計画書§11 の保留可項目）。6F（schema inference）は計画書どおり初期コア完成後。
semantic-core: ID/ref/entity graph・意味検証（重複・参照・グラフ整合）・query・合成・導出が
文字列入力だけで動く（テスト33件）。

### WS-002 読取専用ワークスペースの組み立て（done 2026-08-19）

```text
旧実装: 該当パイプラインの参照元は Workspace.scan/load・IdIndex.scanFile・SchemaLoader.loadAllWithErrors
  （ルート側は無変更で併存）。意味宣言パース（parseEntities / parseEntityTypes / parseRelations /
  parseEnforcement）は SchemaLoader から semantic-core へ移動（ルートは委譲）
旧テスト: なし（新API）。WorkspaceReaderTest を新設（fixture 2ワークスペースの一気通貫）
新しい責務:
  - semantic-core/schema/SemanticDeclParser.kt——意味宣言の純粋パーサ（ルート SchemaLoader と共有）
  - workspace-core/workspace/WorkspaceReader.kt——DocumentStore → スキーマ読込（StructuralSchemaParser
    ＋SemanticDeclParser・$ref横断リンク）→ 文書ロード（DefaultYamlCodec）→ match 割当 → 構造検証 →
    SemanticInput 構築 → SemanticEngine → WorkspaceDerivedState。
    WorkspaceSchema / WorkspaceDocument / WorkspaceSnapshot / WorkspaceDerivedState / LoadedWorkspace
  - WorkspaceLayout.parseLedgerText（構成ファイル本文からの ledger パース。store 版 detectLayout と共有）
維持する挙動: fixture 期待値（Phase 0 に現行CLIで記録）と一致——legacy-basic: 意味エラー3件
  （PT-001 文書内重複×2・IT-404 未解決）／yde-basic: 台帳外（outside/）が索引されず issues 0。
  台帳・ゾーンの絞り込み規則は IdIndex.scanFile と同一（indexExcluded＋台帳外は定義ゾーンのみ索引）
意図的に変える挙動: なし
未解決事項: v1未対応＝派生スキーマ（derive はテンプレート扱いでマッチせず、導出スキーマの生成なし）・
  `_schemas` 文書へのメタスキーマ適用（schemas ゾーンは文書集合から除外）。CLI 配線（workspace validate）
  は WS-003（Phase 7 完了条件①の残り）
完了確認コマンド: ./gradlew :workspace-core:test / ./gradlew test
```

**Phase 9 主要部完了判定（2026-08-20）**: エンティティ操作（rename / merge / delete＝RF-001）・
文書移動（RF-003）・スキーマ・リファクタリング P25 全12操作（RF-002b〜e）が
plan（semantic-core・不変）／apply（workspace-core・1トランザクション）へ分離済み。
完了条件①「CLIでplanを表示してから明示的に適用できる」は `workspace rename-entity [--apply]` で充足
（全操作のCLI配線は Phase 10 の refactor コマンド群で行う）。②「リファクタリングコードがファイルを
直接書かない」は全テストで writeCount=0 を確認。必須テスト6点（plan不変・全影響文書と原本座標・
stale拒否・dry-run＝plan適用の一致・参照切れなし・1回Undo）は RefactoringTest 26ケースで固定。
残: ビュー追随（6E 仮想文書設計後）・旧経路（core/SchemaRefactor.kt・EntityOps・DocMove）の
シェル化はUI移行時（旧経路は現行UIが使用中のため併存）。

### RF-002e SchemaRefactor 残り操作の移植（done 2026-08-20）＝ P25全12操作のカーネル化完了

```text
旧実装: core/SchemaRefactor.kt の convertFieldType（R25-03）・renameDefinition / extractDefinition /
  inlineDefinition / renameRelation / renameSchemaId（R25-04）と補助（countDefinitionRefs）
旧テスト: core/SchemaRefactorTest.kt（ルート・旧経路の回帰を継続担保）。新経路は RefactoringTest へ
  6ケース追加（wrap＋制約のitem移動＋1回Undo／string→int型付けと解析不能・複数要素の中止／
  definitions改名と$ref追随／extract⇄inlineの往復（定義の自動削除込み）／relation改名と宣言済み名の拒否／
  schemaId改名と修飾$ref・fromSchemaIds追随・既存IDの拒否）
新しい責務: SemanticSchemaRefactor へ6操作を移植——
  - convertFieldType: 決定的変換のみ（スカラー→array・array→要素型・string→enum/int/number・
    enum→string・int/number/boolean/date→string）。逸脱値は一覧つきで中止。制約キーの item への
    移し替え・default 削除警告も同一
  - renameDefinition: definitions キーの RenameKey＋全スキーマの $ref（非修飾はモジュール内・修飾は横断）
  - extractDefinition: 構造キーを definitions へ移し $ref 参照へ置換（key/label/required 等は使用側に残す）
  - inlineDefinition: $ref を定義内容の複製へ展開（使用側の上書きキー維持）。参照が残らなければ定義も削除
  - renameRelation: レジストリの name＋全スキーマの relation:。宣言済み名の検査はレジストリ文書の走査で行う
  - renameSchemaId: id:＋修飾 $ref・definition・schemaId(s)系・schema:・fromSchemaIds / detailSchemaIds
維持する挙動: 解決規則・拒否条件・メッセージ・サマリ（該当6操作の範囲）
意図的に変える挙動: ビュー内の definition / relation / schemaId 参照の追随は警告降格（6E 仮想文書の
  設計後に対応。renameDefinition の同名あいまい警告などビュー走査由来の警告も同時に落ちる）。
  derive テンプレートは kernel 経路では source=null＝書き換え対象外（v1で derive 未対応のため実害なし）
未解決事項: なし（P25の12操作すべてカーネル化済み。CLI 配線は Phase 10）
完了確認コマンド: ./gradlew :workspace-core:test / ./gradlew test
```

### RF-002d SchemaRefactor R25-02 の移植（done 2026-08-20）

```text
旧実装: core/SchemaRefactor.kt の moveField / alignOrder（R25-02）と補助
  （moveDataOps / adjustAfterDelete / resolveContainer / childKeysOf / Crossing）
旧テスト: core/SchemaRefactorTest.kt（ルート・旧経路の回帰を継続担保）。新経路は RefactoringTest へ
  5ケース追加（中間 object 生成＋コメント保持＋1回Undo／空親削除／スキーマ側衝突・同じ親・
  境界またぎの拒否／文書側の移動先既存値の計画段階中止／alignOrder の整列＋冪等）
新しい責務: SemanticSchemaRefactor へ2操作を移植——
  - moveField: スキーマ定義の Move（fields 生成は Replace・同一 Seq 内削除の adjustAfterDelete）＋
    文書側 moveDataOps（中間 object の Insert(YNode.Map()) → Move → 空親の Remove。衝突は中止）。
    要素コンテキスト署名（Crossing: 配列・map 横断の prefixKeys × コンテナ ===）の一致を要求し、
    境界またぎ・definitions またぎ・自己配下・同じ親・同名衝突・phases キーのルート離脱を拒否
  - alignOrder: 文書キーをスキーマ順へ ReorderKeys（`_document` 先頭・未知キーは末尾に相対順維持。
    スキーマは変更しない・冪等）
  - RF-002b で省略していた Crossing 機構を Site 機構へ復元（resolveField が crossings / tailKeys を
    返す Resolution へ拡張・resolveContainer / childKeysOf を新設）
維持する挙動: 解決規則・拒否条件・メッセージ・サマリ（該当2操作の範囲）
意図的に変える挙動: ビュー等の言及追随は警告降格（renameField と同じ方針）。
  alignOrder の組込み除外は source == null 判定（旧: パスの startsWith）
未解決事項: 残り操作は RF-002e
完了確認コマンド: ./gradlew :workspace-core:test / ./gradlew test
```

### RF-002c SchemaRefactor R25-03 の移植（done 2026-08-20）

```text
旧実装: core/SchemaRefactor.kt の renameEnumValue / deleteField / requireField（R25-03）と
  補助（finalDef / walkYamlEntries / ownsEntryPaths / collectParentObjects）
旧テスト: core/SchemaRefactorTest.kt（ルート・旧経路の回帰を継続担保）。新経路は RefactoringTest へ
  3ケース追加（values・casesキー・default・文書値の追随＋新スキーマ検証／owns 掃除とID型拒否・
  データ損失の可視化／必須化と欠落充填＋必須検証の充足）
新しい責務: SemanticSchemaRefactor へ3操作を移植——
  - renameEnumValue: values 実体（文字列・リッチ選択肢）・cases キー（RenameKey）・default・
    phases.order / requiredFrom / activeFrom（フェーズ名改名）・文書の現在値
  - deleteField: ID型・フェーズ申告キーの拒否、文書キー削除（パス降順）、スキーマ定義削除、
    entity.owns の掃除、値あり件数のサマリ明示
  - requireField: required / requiredFrom の設定（併用不可の他方を削除）、型検査つき既定値、
    欠落・空値の充填（requiredFrom はフェーズ到達文書のみ）
維持する挙動: 解決規則・拒否条件・サマリ（該当3操作の範囲）
意図的に変える挙動: ビュー等の言及追随は警告降格（renameField と同じ方針）
未解決事項: 残り操作は RF-002d
完了確認コマンド: ./gradlew :workspace-core:test
```

### WS-006 スキーマ変更のセッション内 rebuild（done 2026-08-20）

```text
旧実装: 参照元は Workspace.noteDiskChanges（スキーマ変更→必ず rebuild。CLAUDE.md 落とし穴）の
  セッション版。ルートは無変更で併存
旧テスト: なし（WorkspaceSessionTest へ1ケース追加・RefactoringTest の rename ケースへ検証追記）
新しい責務:
  - WorkspaceReader.parseSchemas——スキーマ文書テキスト集合→スキーマ束（load とセッションが共有）
  - WorkspaceSession: schemas / declarations / schemaErrors を可変化し、schemas ゾーンの文書に触れる
    トランザクションで「候補の working 内容からスキーマ束を再構築→全文書の match 割当を再評価→
    新スキーマで候補検証」。undo / redo も同経路で対称（undo でスキーマも検証も戻る）。reload も反映
  - 是正: `_schemas` 配下の文書に一般スキーマの match を適用しない（「メタスキーマだけが適用される」
    規則の順守。default match "**/*" が schemas ゾーンを拾う穴を塞いだ）
維持する挙動: 「スキーマが変わったら必ず rebuild」（増分にしない）
意図的に変える挙動: なし
未解決事項: derive 導出元文書の変更→再構築（derive 自体が WS-002 v1 未対応）。メタスキーマ適用
完了確認コマンド: ./gradlew :workspace-core:test（required 除去→ERROR 消滅→undo で復活の対称性、
  renameField 適用直後に未定義キー警告が出ないこと）
```

### RF-002b renameField の plan/apply 化（done 2026-08-20）＝ SchemaRefactor 移植の第1弾

```text
旧実装: core/SchemaRefactor.kt の renameField（R25-01）と Site 機構（resolveField / derefChain /
  descendContainers / lookupChild / reaches / isRootField / collectFieldHits / pathDescending。
  ルートは無変更で併存）
旧テスト: core/SchemaRefactorTest.kt（ルート・旧経路の回帰を継続担保）。新経路は RefactoringTest へ
  3ケース追加（文書＋スキーマの1トランザクション書き換えとコメント保持・phases.key 追随・
  改名先キー衝突の計画段階拒否）
新しい責務:
  - semantic/SchemaRefactor.kt——SemanticSchemaRefactor.renameField と Site 機構一式
    （FieldDef インスタンス同一性＝=== はカーネル経路でも成立: StructuralSchemaParser の1回パース＋
    resolve 浅コピー＋SchemaCatalog.linked が定義インスタンスを保つ）
  - RefactoringRequest.RenameField・RefactorDocument（文書一覧の入力ビュー）・plan() の documents 引数
  - SchemaSemantics へ source（スキーマ定義文書の座標。組込み・derive は null＝編集不可）と kind を追加。
    WorkspaceSchema.source を新設し session が引き渡す
  - スキーマ側書き換え＝ `_schemas` 文書への SetScalar（fields[i].key・phases.key）、
    文書側＝スキーマ誘導走査（cases は実効フィールドのみ）で当てた RenameKey（深い方から・順序コメント保持）
維持する挙動: 解決規則・拒否条件・警告・メッセージ（rename に関する範囲）
意図的に変える挙動: ビュー定義・アクティビティ等の追随は行わず警告で案内
  （6E 仮想文書の設計後に対応。旧経路は従来どおり追随する）
未解決事項: 残り11操作（RF-002c）。適用後の派生状態が旧スキーマで検証される session の
  スキーマ再読込ギャップは WS-006 として登録（スキーマ変更→必ず rebuild の規則の session 版）
完了確認コマンド: ./gradlew :workspace-core:test
```

### RF-002a スキーマ文書の編集可能化と dirty 基準の修正（done 2026-08-20）

```text
旧実装: 参照元は現行の2挙動——①`_schemas` 文書はスキーマエディタの編集対象（文書である）
  ②EditBuffer の保存基準は正規化テキスト（savedSnapshot = canonicalText。表記ゆれで偽 dirty にしない）
旧テスト: WorkspaceReaderTest / WorkspaceSessionTest の期待値を更新＋スキーマ文書編集のケースを追加
新しい責務:
  - WorkspaceReader: schemas ゾーンを文書集合に含める（スキーマ・リファクタリングは `_schemas` 文書への
    EditDocument で自身を書き換えるため。意味解析には従来どおり載せない＝indexExcluded）
  - WorkspaceDocument.persistedText を「読取時に正規化（dump）した保存基準」に変更——外部で書かれた
    ファイルの表記ゆれ（末尾改行等）で偽 dirty にならない（現行 EditBuffer と同じ基準）
  - WorkspaceSession.storeTexts——ストア生テキストの別追跡（読取・保存・削除・reload で更新）。
    外部変更検出（externalChanges）はこちらと比較する（dirty 基準と分離）
維持する挙動: 現行の「_schemas は編集できる」「表記ゆれは dirty にしない」「外部変更は生テキストで検出」
意図的に変える挙動: なし（挙動を現行へ寄せる修正）
未解決事項: `_schemas` 文書へのメタスキーマ適用（builtin-schema）は未対応のまま（WS-002 v1 の残）
完了確認コマンド: ./gradlew test（スキーマ文書の EditDocument → save がストアへ届くケースを含む）
```

### RF-003 document move の参照更新の plan/apply 化（done 2026-08-20）＝ Phase 9 第2弾

```text
旧実装: core/DocMove.kt（plan＝パスID文書参照の書き換え＋警告、execute＝物理移動→変更セット適用。
  ルートは無変更で併存）
旧テスト: core/DocRefTest.kt（ルート・旧経路の回帰を継続担保）。新経路は RefactoringTest へ3ケース追加
新しい責務:
  - RefactoringRequest.MoveDocument＋RefactoringPlan.moves（from → to。適用は changes より先）
  - SemanticRefactor.moveDocument——_document.id 宣言済みなら書き換え不要（安定の注記）、
    パスID参照の書き換え計画（移動対象ファイル内の自己参照は移動後のパスで扱う）は DocMove.plan と同一
  - WorkspaceSession.planRefactoring——移動の前提検査（存在・衝突）とスキーマ適用変化の警告を付与。
    applyRefactoring は MoveDocument ＋ EditDocument を1トランザクションで適用
維持する挙動: 計画の内容・注記・警告（スキーマ match の変化）
意図的に変える挙動: **原子性の改善**——旧経路は「物理移動成功→参照書き換え失敗」の継ぎ目があった
  （メッセージで案内）。新経路は移動と書き換えが1トランザクションで、失敗時は何も起きず、
  1回の Undo で移動ごと戻る（意図的差分 D-09 として登録）
未解決事項: ビュー定義の対象外れ警告（file / fileGlob）は旧経路のみ——6E 仮想文書の設計後に再訪
完了確認コマンド: ./gradlew :workspace-core:test（移動＋参照の1トランザクション・_document.id 安定・
  スキーマ変化警告）
```

### RF-001 エンティティ操作の plan/apply 分離（done 2026-08-20）＝ Phase 9 第1弾

```text
旧実装: core/EntityOps.kt（rename / merge / delete の変更セット生成器。R21-02。ルートは無変更で併存）
旧テスト: core/EntityOpsTest.kt（ルート・旧経路の回帰を継続担保）。新経路は
  workspace-core/RefactoringTest（日本語名6ケース）と CliTest（plan→apply の一気通貫）
新しい責務:
  - semantic-core/semantic/Refactor.kt——RefactoringRequest（RenameEntity / DeleteEntity /
    MergeEntity）・PlannedDocumentChange（DocumentId × YamlOperation 列＝原本座標）・
    RefactoringPlan（sourceVersion / warnings / summary）・SemanticRefactor.plan。
    生成規則・拒否条件（改名先衝突・被参照ありの削除・ルート出現の要素削除）・
    idPattern 警告・Set先Remove後のパス降順整列は EntityOps と同一
  - WorkspaceSession.planRefactoring / applyRefactoring——plan の sourceVersion を baseVersion に
    したトランザクション適用（古い計画は自動拒否・1回の Undo で全変更が戻る）
  - CLI `workspace rename-entity <root> <old> <new> [--apply]`——plan 表示→明示適用→保存（exit 5=保存失敗）
維持する挙動: 変更対象の座標・サマリ・警告・オール・オア・ナッシング適用
意図的に変える挙動: なし（旧経路の ChangeSet／baseHash 方式は併存。新経路は baseVersion＝ADR-0007）
未解決事項: SchemaRefactor（RF-002。ビュー追随の書換は 6E 仮想文書の設計と関係）・
  DocMove の参照更新（RF-003）
完了確認コマンド: ./gradlew :workspace-core:test :cli:test（必須テスト: plan不変・全影響文書と原本座標・
  stale plan 拒否・参照切れなし・1回Undo・delete拒否・merge）
```

### WS-005 Phase 8 残の実装（done 2026-08-20）＝ Phase 8 完了・M3 到達

```text
旧実装: MoveDocument は新規（DocMove の参照書き換えは Phase 9）。外部変更検出は現行 FileWatcher の
  水準の代替（検出・通知・reload）。atomic 書込は Workspace 保存経路の安全性の移植
旧テスト: なし（WorkspaceSessionTest へ2ケース・DocumentStoreTest へ1ケース追加）
新しい責務:
  - WorkspaceOperation.MoveDocument——移動先でスキーマ割当・台帳判定を再評価。ストア上は save 時に
    「新パスへ書き・旧パスを削除」。undo は位置・割当・保存済みリンク（toPersistedText）ごと復元
  - WorkspaceSession.externalChanges()——保存済みテキストとストア内容の比較による外部変更検出
    （変更・削除。dirty＝ローカル編集は外部変更にしない。イベント駆動 watch は計画書どおり最終段階）
  - FileSystemDocumentStore.write——一時ファイル→ATOMIC_MOVE（非対応FSは REPLACE へフォールバック）
維持する挙動: 検出・通知・reload・最終書込み優先（ADR-0007。楽観ロックなし）
意図的に変える挙動: なし
未解決事項: 移動の save は「書き＋削除」でありストアの move を使わない（ファイル同一性が失われる。
  Git 履歴の継続性が要る場面は Phase 9 の DocMove 移行時に再訪）。イベント駆動 watch は最終段階
完了確認コマンド: ./gradlew :workspace-core:test / ./gradlew test
```

**Phase 8 完了判定（2026-08-20）＝ M3: ヘッドレスworkspace 到達**: load・working state・transaction
（Edit / Create / Delete / Move）・Undo/Redo・save 分離・外部変更検出のすべてが InMemoryDocumentStore
で検証でき、UIなしで「編集→検証→Undo→Redo→保存」が JUnit から確認できる。書込は
DocumentStore / save へ集約（ADR-0005）。workspace-core テスト22件。

### WS-004 WorkspaceSession（done 2026-08-19）＝ Phase 8 主要部

```text
旧実装: なし（新API。計画書§4.3。参照元は Workspace.apply（コピー→検証→書込の流儀）と
  EditBuffer（履歴の置き換え対象＝D-03/D-07））
旧テスト: なし（WorkspaceSessionTest を新設・日本語名9ケース）
新しい責務: workspace-core/workspace/WorkspaceSession.kt——
  - WorkspaceOperation（EditDocument / CreateDocument / DeleteDocument）と WorkspaceTransaction
    （baseVersion＋intent＋操作列）・ChangeRecord（forward / inverse・注入時刻）・TransactionResult / SaveResult
  - apply: baseVersion確認 → 候補構築（対象文書だけ deepCopy → YamlEdit.applyBatch＝CoW）→
    候補上で構造＋意味検証（WorkspaceReader.derive を共有）→ requireValid 判定 → 参照交換 →
    version+1 → ChangeRecord 追加（redo破棄）。失敗・拒否は候補を捨てるだけ（部分変更を公開しない）
  - undo / redo: 履歴の inverse / forward を同じ適用経路で実行（version は進む・派生状態は毎回再構築）
  - save: dirty（dump とpersistedTextの比較）と削除予約だけをストアへ書く。履歴・version に触れない。
    一部失敗は SaveResult.failures で成功済みと区別
  - 削除は save までストアに残る削除予約。undo は persistedText（保存済みリンク）ごと復元する
    （CreateDocument.persistedText は undo 復元専用の内部情報）
維持する挙動: 適用順序・原子性は現行 Workspace.apply の流儀（コピー→検証→確定）を踏襲
意図的に変える挙動: D-01（apply は書かない・save 分離）・D-03/D-07（テキストsnapshot履歴→
  ChangeRecord＝逆操作列）——意図的差分一覧のとおり。旧経路（root）は無変更で併存
未解決事項: MoveDocument（DocMove の参照更新と併せて WS-005）・外部変更検出（watch）・
  一時ファイル→atomic move の書込（保存の安全性）。WorkspaceDocument.persistedText を nullable 化
  （未保存の新規文書。計画書§4.3スケッチどおり）
完了確認コマンド: ./gradlew :workspace-core:test（必須テスト: applyはストア不変 writeCount=0／
  saveのみ書く／save後も履歴維持／途中失敗の非公開／古いbaseVersion拒否／undo・redoごとの
  semantic対応version／保存とUndo/Redoの独立シナリオ／削除undoの保存済みリンク復元／requireValid）
```

### WS-003 簡易CLIの最小配線（done 2026-08-19）＝ Phase 7 完了

```text
旧実装: なし（新規。core/Cli.kt からは移さず、計画書§6.4どおり dispatcher を新設して3コアAPIを呼ぶ）
旧テスト: なし（CliTest を新設・日本語名4ケース）
新しい責務: cli/Main.kt——`yaml parse <file>`・`yaml roundtrip <file>`・`workspace validate <root>`。
  dispatcher（引数解釈・表示）と処理本体（run 関数）を分離し、run は直接 JUnit テストできる。
  exit code 契約: 0=成功／1=コマンド・入力エラー／2=structural（スキーマ・文書パース失敗含む）／
  3=semantic。警告は exit code に影響しない。cli/build.gradle.kts に application プラグイン＋
  run の workingDir=rootDir（計画書§14の実行例どおり相対パスが通る）
維持する挙動: workspace validate の判定結果は WorkspaceReader（＝fixture 実測と一致）
意図的に変える挙動: なし（新規CLI。既存 runCli とは独立）
未解決事項: `./gradlew :cli:run` 経由では非ゼロ exit が Gradle のビルド失敗として包まれる
  （exit code 契約の検証は CliTest の run() 直接呼び出しで固定。配布物のスクリプト経由では生の code）
完了確認コマンド: ./gradlew :cli:test /
  ./gradlew :cli:run -q --args="workspace validate test-fixtures/workspaces/legacy-basic"
```

**Phase 7 完了判定（2026-08-19）**: ①`workspace validate <root>` で fixture を検証できる（CLI 配線済み・
legacy-basic で意味エラー3件と exit 3）②core モジュール内で workspace-core 以外に Path / Files が
漏れない ③読取時点の全結果が同じバージョンに紐付く ④legacy と .yde の対象文書が既存挙動と一致
（fixture 実測との一致で証明）——4条件すべて充足。

### WS-001 WorkspaceLayout の移動と DocumentStore の新設（done 2026-08-19）

```text
旧実装: schema/WorkspaceLayout.kt（配置ポリシー R13-02。git mv で workspace-core へ）。
  DocumentStore は新API（計画書§4.3）
旧テスト: core/WorkspaceLayoutTest.kt はルート残留（Workspace / Disclosure を使う統合テストのため。
  Workspace 本体の移行時＝Phase 7 後半に移動）。workspace-core に DocumentStoreTest を新設
  （日本語名7ケース: 両実装の契約・ワークスペース外拒否・YAML以外の除外・書込カウンタ）
新しい責務:
  - workspace-core/schema/WorkspaceLayout.kt——配置判定（ゾーン・台帳・索引除外）。予約ディレクトリ名の
    正を Zone のリテラルへ移し、ルート SchemaLoader の const は互換の同値リテラルとして残置
  - workspace-core/workspace/DocumentStore.kt——永続化ポート（list / read / write / delete / move。
    DocumentId=ワークスペース相対パス）。InMemoryDocumentStore（writeCount つき＝Phase 8 の
    「apply は書かない」検証用）と FileSystemDocumentStore（正規化・ルート外拒否）
維持する挙動: レイアウト判定のすべて（レガシー／.yde・ledger）。ルートの利用側は変更ゼロ
意図的に変える挙動: なし。ストアの契約は IllegalArgumentException に統一。
  atomic 書込（一時ファイル→move）は Phase 8 の保存の安全性で追加
未解決事項: Workspace.scan/load の移行（Phase 7 後半＝WS-002）。read の null 返し（計画書スケッチは
  非null）は Phase 7 の実装都合として計画書§4.3を後で合わせる
完了確認コマンド: ./gradlew :workspace-core:test / ./gradlew test
```

### SM-007 derivation / projection の移動（done 2026-08-19）＝ Phase 6D 完了

```text
旧実装: schema/Derivation.kt（宣言: DerivationSelector / DerivationLookup / DerivationDef /
  ConsistencyAssert / ConsistencyRule）、schema/Projection.kt（{パス} テンプレート評価器）、
  index/Derivation.kt（解決器）、Schema.kt の globToRegex
旧テスト: validate/ConsistencyTest.kt・core/ViewsTest.kt（計算列）——既存位置のまま緑＝シェル経由の
  一致を保証。カーネル側は SemanticDerivationTest を新設（日本語名3ケース）
新しい責務:
  - schema/Derivation.kt・schema/Projection.kt → semantic-core へ丸ごと移動（純宣言・純評価器）
  - schema/Globs.kt——globToRegex を Schema.kt から移動（DerivationSelector の前提。
    SchemaDef.matches / DeriveDef / WorkspaceLayout はルートから同名参照のまま）
  - semantic/Derivation.kt——SemanticDerivation.resolve（lookup 1ホップ・スコープ連鎖・値辞書。
    occurrences / rel / tree はすべて注入・DocumentId 座標）
維持する挙動: 解決順序（source → values → lookup 起点）・未解決プレースホルダの残置・来歴の
  すべて。ルート index/Derivation は変換シェル（Input / Result の Path 座標型を保つ）
意図的に変える挙動: なし。付随: モジュール跨ぎ smart cast 修正2箇所（Schema.kt / Validator.kt）
未解決事項: IssueDerivation（validate）はルート型（index.Derivation.Input / Path）のまま——
  Validator 本体が Phase 7〜8 でエンジンへ再構成されるときに SemanticDerivation.Input へ寄せる
完了確認コマンド: ./gradlew test
```

**Phase 6D 完了判定（2026-08-19）**: 導出値が入力座標（inputs: placeholder / value / document / path）と
規則座標（sourceDocument / sourcePath）の来歴を持ち、未解決理由がプレースホルダ単位で構造化される
（complete フラグ＋座標なし Input）。SemanticDerivationTest で固定。

### SM-006 query宣言の移動と照合エンジンのカーネル化（done 2026-08-19）＝ Phase 6C 完了

```text
旧実装: schema/Query.kt（宣言モデル: QuerySpec / QueryNodeStep / QueryEdgeStep / QueryWhere /
  ConstraintRule 等）、index/Query.kt（照合エンジン＝Evaluator・anyMatchFrom / startMatches・
  neighborIds）、index/IdIndex.kt companion の _document メタ読み取り
旧テスト: index/QueryTest.kt・validate/QueryConstraintTest.kt・core/TraverseTest.kt・core/ViewsTest.kt
  （すべて既存位置のまま緑＝シェル経由の一致を保証）。カーネル側は SemanticQueryTest を新設（2ケース）
新しい責務:
  - schema/Query.kt → semantic-core へ丸ごと移動（純宣言。model 依存のみ）
  - semantic/Query.kt——SemanticQuery.run / anyMatchFrom / startMatches / neighborIds と Evaluator
    （決定性・判定保留・循環停止・loader 注入の契約ごと移動。DocumentId 座標）
  - semantic/DocumentMeta.kt——_document メタ読み取り（IdIndex companion は委譲へ）
  - index/SemanticBridge.kt に KernelIndexView（scans / entries / refs / graph の一式）を追加
維持する挙動: 照合の決定性（起点ID昇順・展開 distinct＋昇順・行の辞書式比較・limit 打切り）・
  R22-03 の制約判定原子・Traverse 互換の隣接1段のすべて。ルート Query は変換シェル
意図的に変える挙動: なし
未解決事項: core/Traverse.kt（emit レンダラ部分）はルート残置——隣接展開の芯（neighborIds）は
  カーネル化済みで、残りはエージェント向け出力整形（読み出しモデル）。Disclosure と同時に扱う
完了確認コマンド: ./gradlew test（QueryTest / QueryConstraintTest / TraverseTest がシェル経由で通る）
```

**Phase 6C 完了判定（2026-08-19）**: query 宣言（schema/Query.kt）・評価（SemanticQuery）・
制約検証の判定原子（anyMatchFrom / startMatches）が semantic-core 内で追跡できる。

### SM-005 エンティティ合成のカーネル化（done 2026-08-19）＝ Phase 6B 完了

```text
旧実装: core/Aspects.kt（エンティティ合成 R23-01: 定義出現→アスペクト宣言順の決定的マージ・重複所有注記）
旧テスト: core/AspectTest.kt（既存位置のまま緑＝シェル経由の一致を保証）。カーネル側は
  SemanticAspectsTest を新設（日本語名3ケース: 来歴座標・重複所有注記・ownerSection）
新しい責務: semantic/Aspects.kt——SchemaSemantics（スキーマ1件の意味束＝StructuralSchema＋entity宣言）、
  SemanticAspectField / SemanticAspectSection / SemanticEntityComposite（DocumentId 座標）、
  SemanticAspects.compose / ownerSection。ツリーの読み取りは loader 注入（(DocumentId) -> YNode?）
新しい公開API: 同上。加えて index/SemanticBridge.kt（ルート側）に IdEntry/RefEntry ↔ ScannedId/ScannedRef
  変換・toDocumentScans・kernelGraphOf を一元化し、GraphIntegrity シェルも共有へ書き換え
維持する挙動: 合成順序（定義出現→宣言順→パス順。UX-80）・重複所有の duplicateOf 注記・
  書き先解決（ownerSection）のすべて。ルート Aspects は型（Path 座標）を保ち変換シェル化
意図的に変える挙動: なし
未解決事項: 合成結果（AspectField.value）は YNode 参照を持つ。スナップショット状態ではなく
  「その場で消費する読み出しモデル」なので ADR-0003 規律4（出力に YNode 参照を保持しない）の
  例外扱いだが、§17-⑦（表示系の置き場）の検討時に読み出しモデル層として整理するのが望ましい
完了確認コマンド: ./gradlew test（AspectTest がシェル経由で通ることが一致証明）
```

**Phase 6B 完了判定（2026-08-19）**: 合成結果の各値が原本文書（DocumentId）と YPath の来歴を持つ
（SemanticAspectsTest で固定）。aspect ownership・複数出現合成・重複所有検出が semantic-core で完結。

### SM-004 グラフ整合検証のカーネル化（done 2026-08-19）＝ Phase 6A 完了

```text
旧実装: validate/GraphIntegrity.kt（対象型・多重度・非循環＝Tarjan SCC・enforcement）
旧テスト: validate/GraphIntegrityTest.kt（既存位置のまま緑＝変換シェル経由の一致を保証）。
  カーネル側は SemanticGraphIntegrityTest を新設（日本語名5ケース）
新しい責務: semantic/GraphIntegrity.kt——SemanticGraphIntegrity.issues(refs, graph, declarations)。
  判定アルゴリズム（対象型・未宣言relation・多重度・非循環）を全文書分の SemanticIssue として返す。
  SemanticEngine.build へ接続（SemanticDeclarations の relations / enforcement が有効化）。
  SemanticEntityGraph.build は軽量ビュー DocumentScan 入力へ変更（公開化）
維持する挙動: メッセージ・座標・重大度・決定性（隣接ID昇順・SCC・代表閉路）すべて。
  ルート GraphIntegrity は IdIndex 走査結果→カーネル座標の変換シェル＋ファイル絞り込みへ縮小
  （inboundRefs / occurrenceCount のカバレッジ共有カウンタはルートに残置）
意図的に変える挙動: なし。シェルは呼び出しごとに変換＋グラフ導出を行う（従来も per-file で
  循環検出を再計算しており、コスト特性は同等。Phase 7〜8 の IdIndex 再構成で解消）
未解決事項: relation 宣言の重複検証は SchemaLoader（ルートのパース側）に残置
完了確認コマンド: ./gradlew test（GraphIntegrityTest の全ケースがシェル経由で通ることが一致証明）
```

**Phase 6A 完了判定（2026-08-19）**: 宣言語彙（SM-000）・エンティティグラフ（SM-003）・
グラフ整合検証（SM-004）が semantic-core に揃い、同じ文書集合＋宣言から決定的に構築・判定される。

### SM-003 SemanticEntityGraph（エンティティグラフのカーネル版）の新設（done 2026-08-18）

```text
旧実装: index/EntityGraph.kt（導出規則の参照元。ルート側は無変更で残る）
旧テスト: index/EntityGraphTest.kt（ルート側の回帰を継続担保）。カーネル側は
  SemanticEntityGraphTest を新設（日本語名4ケース: 定義出現優先・最内出現・出次エッジ・文書ノード）
新しい責務: semantic/Graph.kt——SemanticEntityNode / SemanticDocumentNode / SemanticEntityGraph
  （sourceEntryOf・outgoingRefs・エンティティ表・文書ノード表）。SemanticSnapshot.graph として
  SemanticEngine.build が全再構築のたびに導出する
維持する挙動: 導出規則はルート EntityGraph と同一（定義出現優先・最内のID出現・documentId 先勝ち）
意図的に変える挙動: なし
未解決事項: ルート index.EntityGraph（ディスク基準シェルのキャッシュ）と最内出現突合ロジック
  （約15行）が一時的に重複する。Phase 7〜8 で IdIndex が SemanticEngine の上へ再構成された時点で
  ルート側を削除して解消する
完了確認コマンド: ./gradlew :semantic-core:test / ./gradlew test
```

**Phase 5 完了判定（2026-08-18）**: ①ファイルシステムなしで複数文書の意味解析ができる
（SemanticEngine.build）②scalar の ID・配列内 ID・入れ子 ID を既存ルールどおり認識する
（SemanticScannerTest）③重複 ID・未解決 ref・逆参照がテストできる（SemanticEngineTest）
④最小の EntityGraph が構築される（SemanticEntityGraphTest）⑤semantic-core が workspace-core へ
依存していない（ビルド構成）——5条件とも充足。semantic-core テスト17件。
IdIndex のディスク基準シェル（rebuild / update / overlay）はルート残留のまま（Phase 7〜8 で再構成）。

### SM-002 SemanticEngine（意味解析の全再構築）の新設（done 2026-08-18）

```text
旧実装: なし（新API。計画書§4.2。集約規則はルート IdIndex.derive / Validator の ID・ref 検証と同じ）
旧テスト: なし（SemanticEngineTest を新設・日本語名6ケース）
新しい責務: semantic/Engine.kt——
  - SemanticDeclarations（relations / entityTypes / enforcement。現時点でエンジン未使用＝
    グラフ整合検証の接続は GraphIntegrity 移行時）
  - SemanticInput（workspaceVersion: Long ＋文書集合＋宣言）
  - SemanticIssue（意味検証の指摘。構造の StructuralIssue と別型）
  - SemanticSnapshot（sourceVersion 転記・ids / refs / documentIds / issues。YNode 参照なし＝ADR-0003）
    ＋ occurrences / resolve（DEFINING優先）/ resolve(ref) / backlinksTo
  - SemanticEngine.build: 全再構築（文書ID順で安定化）・文書内ID重複 ERROR・未解決参照 ERROR・
    スコープ外参照 ERROR・文書参照の別名前空間解決
  - idMatchesPattern / ScannedId.isAllowedBy（ルート index と同じ規則のカーネル版）
維持する挙動: 集約・解決の規則はルート実装と同一（文書間の同一ID合法・DEFINING優先など）
意図的に変える挙動: なし（旧経路は無変更）。新APIの文言は「ファイル内」でなく「文書内」。
  台帳（ledger）の絞り込み・indexed 降格は上位層の責務とし、エンジンは常に ERROR で報告
未解決事項: EntityGraph のカーネル化（SM-003）。増分更新は Phase 11（全再構築が oracle）
完了確認コマンド: ./gradlew :semantic-core:test / ./gradlew test
```

### SM-001 走査カーネル・表示解決の移動（done 2026-08-18）

```text
旧実装: index/IdIndex.kt の DocScanner、schema/Display.kt、schema/SchemaInference.kt の RefNaming
旧テスト: index/DocScannerTest.kt・schema/DisplayTest.kt（既存位置のまま緑＝委譲シェルの回帰確認）。
  加えて semantic-core に SemanticScannerTest を新設（日本語名6ケース: スキーマあり走査・entity宣言・
  スキーマ外境界・命名規約ヒューリスティクス・属性付き参照・_document 除外）
新しい責務:
  - semantic/Scan.kt: SemanticDocument（計画書§4.2）・ScannedId / ScannedRef（DocumentId 座標）・
    SemanticScanner（走査カーネル。Path・ファイルI/O 非依存）
  - schema/DisplayCore.kt: 表示解決の実装本体（StructuralSchema 版。summarize / titleOf / headerText /
    seqHeaderText / inline / tableColumns）
  - schema/RefNaming.kt: 参照認識の命名規約
新しい公開API: semantic.SemanticScanner.scan(SemanticDocument) → (List<ScannedId>, List<ScannedRef>)
維持する挙動: 走査規則・表示解決のすべて。ルート側 DocScanner / Display は委譲シェル
  （Path ↔ DocumentId 変換・SchemaDef → structural）で呼び出し側変更ゼロ
意図的に変える挙動: なし
未解決事項: IdIndex 本体（rebuild / update / overlay のディスク基準シェル）と EntityGraph はルート残留。
  SemanticInput / SemanticSnapshot / SemanticEngine は SM-002
完了確認コマンド: ./gradlew test / ./gradlew :semantic-core:test
```

### SM-000 Relations.kt（二層モデル宣言語彙）の移動（done 2026-08-18）

```text
旧実装: schema/Relations.kt（EntityDecl / EntityRole / RelationDef / CardinalityDef / AspectDecl /
  EntityTypeDef / EnforcementDef / Relations / idGlobToRegex / entityFor / ownedFieldKeys）と
  Schema.kt の CoverageLevel
旧テスト: schema/RelationRegistryTest.kt・EntityDeclTest.kt 等が間接カバー（既存位置のまま緑）
新しい責務: semantic-core/schema/Relations.kt（semantic-core 最初の内容）。宣言モデルと純粋関数
新しい公開API: 同名。ただし entityFor(entities, id) と ownedFieldKeys(StructuralSchema, ...) に
  再パラメータ化（SchemaDef 非依存＝依存方向の遵守）
維持する挙動: すべて。SchemaDef 向けの旧シグネチャはルート schema/RelationsSchemaExt.kt の
  委譲アダプタで維持（呼び出し側変更ゼロ）
意図的に変える挙動: なし。付随: CardinalityDef のモジュール跨ぎ smart cast 修正1箇所（Schema.kt）
未解決事項: RelationRegistryTest 等の移動は後続（テストが SchemaLoader 依存のため）
完了確認コマンド: ./gradlew test
```

**Phase 4 完了判定（2026-08-17）＝ M1: YAMLライブラリ到達**: ①すべての編集操作に逆操作がある
（dump完全一致の往復テスト＝コメント・スタイル・キー順込み）②操作失敗・EditBatch途中失敗で文書が
部分変更されない ③`SetScalar` がスカラー値域の閉集合と往復型安全（§4.1）を満たす ④Undo/Redo
スタックは作っていない（機構のみ。ADR-0006）——4条件とも充足。yaml-core 単独で
parse/dump・構造schema検証・編集操作と逆操作が動く（テスト67件）。

**Phase 3 完了判定（2026-08-17）**: ①単一文書の検証に IdIndex・workspace が不要（StructuralValidator）
②schema の parse・resolve・validate がメモリ上の文字列だけで実行できる（StructuralSchemaParser.parse →
StructuralSchema → StructuralValidator）③代表的な既存 constraint テストが新境界で通る
（StructuralConstraintTest 4件・StructuralValidatorTest 7件）——3条件とも充足。
残整理は YM-006b（急がない）。

**Phase 2 完了判定（2026-08-16）**: ①yaml-core単独で全yaml fixtureをround-trip（FixtureRoundTripTest・YamlCodecTest）②`YamlCodec` 公開APIにSnakeYAMLの型なし ③文字列だけでテスト可能（ファイルI/Oはfixture読込のみ）——3条件とも充足。判断ポイントのうち可変YNodeは決定済み（ADR-0003）、anchor/aliasの完全サポートと複数ドキュメント編集対象の限定は§11どおり保留。

## 作業項目の記録様式

各項目の着手時に、この節へ次の記録を追記する。

```text
### <ID> <名称>
旧実装:
旧テスト:
新しい責務:
新しい公開API:
維持する挙動:
意図的に変える挙動:（INTENTIONAL-DIFFS.md のID）
未解決事項:
完了確認コマンド:
```
