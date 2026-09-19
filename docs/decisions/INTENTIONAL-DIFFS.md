# 意図的差分一覧

3層コア移行で**最初から意図して変える**既存挙動の台帳（計画書§2.3）。
新旧比較テストでこの表に該当する差分が出た場合、移植ミスとして扱わず、新旧それぞれの期待値を明記する。
ここに載っていない差分はすべて移植ミスの疑いとして調査する。

| ID | 現行挙動 | 新APIの挙動 | 関連ADR |
|---|---|---|---|
| D-01 | `Workspace.apply` が変更セットを検証してそのままファイルへ書く | `apply` はworking snapshotだけを更新し、`save` が別途ファイルへ書く | ADR-0005 |
| D-02 | UIの文書キャッシュ・ディスク索引・agent stagingが別々のoverlayを持つ | すべて `WorkspaceSnapshot` を入力とする共通semantic engineを使う | ADR-0004 |
| D-03 | `EditBuffer` が正規化text snapshotを履歴に持つ | 型付き `YamlOperation` とinverseを基本履歴にする | ADR-0005 / 0006 |
| D-04 | `AppState` や複数のcore機能が直接ファイルを書く | coreの書込を `DocumentStore` と `WorkspaceSession.save` へ集約する | ADR-0005 |
| D-05 | `Schema.kt` と `Validator.kt` が構造規則と意味規則を同時に扱う | structuralとsemanticのcompile/validationを分ける | ADR-0002 / 0004 |
| D-06 | `Path` がschema・index・coreへ広く渡る | `Path` はworkspace-coreのI/O境界に閉じ、下位層には `DocumentId` を渡す | ADR-0002 |
| D-07 | 単文書ごとのUndoスタック（EditBuffer単位） | Undoスタックはworkspace履歴のみ。単文書編集も文書1件の `ChangeRecord` | ADR-0006 |
| D-08 | ソース編集はパース不能テキスト状態（`rawText` / `parseError`）を保持しUndoできる | coreの履歴は型付き操作のみ。生テキスト編集のUndoは将来UI層の責務（core外） | ADR-0006 |
| D-09 | 文書移動（DocMove）は「物理移動成功→参照書き換え失敗」の継ぎ目がありうる（メッセージ案内） | 移動と参照書き換えを1トランザクションで適用し、失敗時は何も起きない。1回のUndoで移動ごと戻る | ADR-0005 |
| D-10 | リファクタリング（rename等）がビュー定義・アクティビティ等の言及を自動追随・警告スキャンする | カーネル経路（SemanticRefactor）は追随せず警告で案内する（ビューはUI層の産物でコアから見えない）。**B-1（RB-01）で解決**: GUI 経路はアプリ層（`core/RefactorImpact`）がカーネル計画にビュー追随・言及警告を補強＝旧挙動を回復。CLI（新CLI `workspace refactor`）は引き続き警告案内のみ | ADR-0002 |
| D-11 | 一括更新（bulkUpdate）はファイルごとに逐次書込（途中失敗で部分適用が残りうる・Undo不可）。保存は `Files.writeString` の直接書込 | 一括更新は**1トランザクション**として適用してから保存（1件でも適用できなければ何も書かない）。保存・一括更新の書込は `WorkspaceSession.save`＝`DocumentStore` 経由（一時ファイル→atomic move）。履歴一元化（A-3）後は一括更新が Undo 1回で戻る（**B-1 で発効**: 適用後に reload しない追随へ変更） | ADR-0005 / 0006 |
| D-12 | リファクタリング適用（エンティティ操作・スキーマ・リファクタリング）は `Workspace.apply`（baseハッシュ競合検出）でディスクへ書き、`reload()` で全再読込（履歴は文書単位のまま・操作自体は Undo 不可）。計画の生成はディスク索引（保存済み状態）が基準 | 適用は `session.applyRefactoring`＝**1トランザクション＋保存**（reload しない）——**リファクタリング全体が Undo 1回で戻る**。計画はセッションの working 状態＝「見たまま」基準（RA-02 の overlayIndex と同じ原則）で、古い計画は sourceVersion 不一致で適用拒否（baseハッシュに相当する競合検出）。適用→保存が未保存編集まで書かないよう、対象に dirty 文書があれば従来どおり拒否。複数ファイルの書込は `DocumentStore` 経由のファイル単位 atomic（旧ステージング一括と異なり、I/O 障害時にファイル単位の部分書込がありうる——working は常に一貫） | ADR-0005 / 0006 |

| D-13 | 文書内ID重複の指摘文言が UI 経路「ID「x」がこの**ファイル**内で重複しています」／カーネル経路「この**文書**内」の2種 | B-3（RB-06）の判定単一所有化でカーネル文言「この文書内で重複しています」へ統一（テスト・機械読取の参照なし。UI 表示のみの変化） | ADR-0002 |

| D-14 | 新経路 cli（workspace validate / catalog / entity / refactor / shell）は組込みスキーマ未注入＝`_schemas`・定義ゾーンの構造検証が効かない | C-4（RC-07）で `BuiltinSchemaResources.kernelSchemas` を注入——メタスキーマ・定義ゾーン検証がアプリと同じ面になる（定義不備が新たに構造エラーとして報告されうる＝検出強化の方向のみ） | ADR-0002 |

| D-15 | パターン制約（V-09）の `expect: exists`／`count` 下限は、解探索の途中で未解決参照により候補が失われていても「解なし＝違反」として発火する（沈黙が違反に化ける） | CALC-17（Core Calculus Phase 1 の決定 2026-09-05）: エッジ展開で参照の端をノードへ解決できない場合（OUT: 対象IDが索引に存在しない／IN: 参照の書き手をエンティティへ解決できない）を「候補の喪失（unknown-dropped）」として評価結果へ記録し、**解の不足を理由とする違反（exists の解なし・count の下限未達）は判定保留＝沈黙へ降格**する。解の実在を根拠とする違反（absent で解あり・count の上限超過）と、実在するノードの絞り込み（型・idPattern・where 不一致＝選択）は降格しない。未解決参照そのものは参照検証（§2）が従来どおり指摘する | ADR-0009 |

| D-16 | カバレッジの参照ベース `coverage.requiredBy`（被参照数の数え上げ）が宣言でき、参照が不足すると「参照が不足しています」を指摘する | Core Calculus Phase 2 の語彙刈り込み（MP-01・2026-09-19 オーナー承認・本運用前のため互換より正準優先）: **requiredBy を削除**。数え上げの正準は「1ホップ・無条件＝関係レジストリの cardinality／条件付き・多段＝constraints の count / exists」、coverage は出現ベース（detailIn＝詳細文書の存在）専用になる。`requiredBy` を書いたスキーマは移行案内つきの定義エラーになる（黙って無視しない） | ADR-0009 |
| D-17 | 期待値導出（consistency の expected・導出値を適用）の出現選択（lookup / source / values の selector 一致）は「索引挿入順の先頭」の先勝ち | Core Calculus Phase 2（MP-02・2026-09-19 オーナー承認）: 出現候補を**定義出現優先→索引順**に並べてから先勝ち（CALC-11 の代表出現と同じ規則＝σ 選択方針 CALC-19）。selector が1出現に絞れている既存運用では挙動不変。複数出現に一致する場合のみ、定義出現の値が採用されるようになる | ADR-0009 |

## 記入規則

- 新しい意図的差分が生じたら、実装より先にこの表へ行を足す（1行=1差分。関連ADRがなければ先にADRを書く）。
- 差分を廃止（旧挙動へ戻す）した場合は行を消さず、取り消し線と理由を残す。
