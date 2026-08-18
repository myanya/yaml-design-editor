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
| SM-002 | SemanticInput / SemanticSnapshot / SemanticEngine（全再構築・重複/未解決ref検出）と EntityGraph | semantic-core | todo | - | Phase 5後半 |
| WS-001 | WorkspaceLayout / DocumentStore | workspace-core | todo | - | Phase 7 |

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
