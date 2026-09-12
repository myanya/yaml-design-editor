# ADR-0009: YDE Core Calculus を最上位の設計方針とする

- 日付: 2026-09-05
- 状態: 承認済み

## 文脈

P28（設計検証の拡充）で `count` / `notEquals` / matrix / `same` など意味語彙が実ユースケース起点で拡大し始めた。このまま機能要求ごとに専用の評価器・検証器・変更器を追加すると、似た意味の計算が複数箇所へ再実装され、概念の境界が曖昧になり、「YDEで何が計算可能か」を体系的に説明できなくなる。オーナー起草の方針文書 [CORE-CALCULUS.md](../CORE-CALCULUS.md) を採択し、以後の機能追加・リファクタリングの判断基準とする。

## 決定

- YAML への計算を **Term / Query / Expression / Predicate / Rewrite の5体系**（補助として Aggregate、下位に Patch / Transaction）へ分解し、**「機能ではなく直交プリミティブを増やす」を最上位原則**とする。詳細規範は [CORE-CALCULUS.md](../CORE-CALCULUS.md) を正とする（本ADRは採択の宣言と要点）。
- 1つの意味には1つの評価器（1実装・多面開示の一般原則化）。読取（Query）・計算（Expression）・判定（Predicate）・変更生成（Rewrite）・適用（Transaction）を混在させない。Rewrite の結果は必ず ChangeSet であり、適用は workspace-core へ委譲する（ADR-0005/0006 と整合）。
- 評価は決定的・三値論理（True / False / Unknown）を基本とし、provenance を計算結果の一級情報とする。LLM は定義を作る側であり、確定済み定義の実行結果を LLM に依存させない。
- 新しい決定論的機能の追加時は、文書 §20 のチェックリスト（10項目）を実装前に通す（[CONVENTIONS.md §9](../CONVENTIONS.md) へ接続）。専用評価器・専用書込経路の追加は「既存 calculus で表現不能であること」を条件とする。
- 導入は大規模 rewrite にせず、文書 §22 の Phase 1（意味論の紙上固定）から段階的に進める。Phase 1 の意味論スペックは `specs/` で管理する（外部仕様化ワークスペース）。

## 帰結

- P28 は「要求 → Constraint DSL の機能追加」という直接経路を禁じ、必ず Core Calculus を一段挟む（文書 §17）。テンプレート先行（実データで需要を確認してから語彙を追加する）の規律は維持する。
- P27（スキーマ2層化）は SchemaDef の単なるクラス分割ではなく、構造・意味・表示のそれぞれがどの Calculus を所有するかを明確にした上で実施する（文書 §16）。
- Phase 1 で意味論として固定すべき難所: ①束縛の正体（Location か、エンティティ＝出現集合か）②where の所属（起点＝母集団の限定／途中＝解の絞り込み）③Unknown の伝播規則（未解決参照の沈黙が解集合を空にし exists 違反へ化ける現挙動の扱い）④Validation orchestration の配置（現在地は root Validator。文書 §15 の workspace-core 配置とのギャップ＝P27・D-4 の既知残置）。
- 既存機能は直ちに改修しない。文書 §21 の分類表を用いて重複実装を段階的に可視化する（Phase 2 以降）。
