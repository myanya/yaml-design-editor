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
