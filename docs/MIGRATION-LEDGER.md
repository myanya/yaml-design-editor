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
| YM-003 | YDocument / DocumentId / YamlCodec 新設 | yaml-core | todo | - | Phase 2 |
| YM-004 | Schema.kt の構造部分分離（FieldType・FieldDef・$ref） | yaml-core | todo | - | Phase 3 |
| YM-005 | Validator.kt の型・required・range検証分離 | yaml-core | todo | - | Phase 3 |
| YM-006 | ConstraintTest 単文書ケース・ValidatorTest の移動 | yaml-core | todo | - | Phase 3 |
| YM-007 | Patch.kt → YamlOperation 再構成・PatchTest | yaml-core | todo | - | Phase 4 |
| SM-001 | IdIndex / DocScanner / Display(titleOf) の移動 | semantic-core | todo | - | Phase 5。IdEntry.title が Display.titleOf に依存 |
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
