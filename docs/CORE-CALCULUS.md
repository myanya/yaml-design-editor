# YDE Core Calculus  
## YAMLに対する計算体系の設計方針

**Status:** Accepted Architecture Policy（[ADR-0009](decisions/0009-core-calculus.md) として 2026-09-05 採択）  
**Scope:** yaml-core / semantic-core / workspace-core およびその上に構築される検証・ビュー・リファクタリング・エージェント機能  
**Purpose:** 今後の機能追加・リファクタリングに対する設計判断の基準を定める

---

## 1. 背景

YAML Design Editor（以下 YDE）は、当初の汎用 YAML エディタから、スキーマ、ID、参照、関係、ビュー、検証、リファクタリング、エージェント実行を備えた情報管理・実行基盤へ発展してきた。

現在までに、以下のような基盤が成立している。

- `YNode` / `YPath` による YAML 構造と位置の表現
- Query Core による構造検索・トラバース・ビュー行決定・foreach・導出 lookup の統合
- Projection / Derivation による決定論的な値導出
- Constraint / Validation による意味的検証
- PatchOp / ChangeSet による部分変更と原子的適用
- Schema Refactoring による複数文書・定義の決定論的変更
- `yaml-core` / `semantic-core` / `workspace-core` の3層コア

Query Core はすでに、それまで複数箇所で個別実装されていたグラフパターン照合を単一エンジンへ統合する方向で設計されている。

また、決定論的射影では、評価器を一元化し、任意式やコード実行を導入せず、1段評価・判定保留・provenance を重視する設計が採用されている。

一方で、P28 の設計検証拡充では、`count`、`notEquals`、matrix、`same`、遷移検証など、実ユースケースに応じて意味語彙が拡大し始めている。

これは自然な進化である一方、このまま機能要求ごとに個別の評価器・検証器・変更器を追加すると、次の問題が起こる。

1. 似た意味の計算が複数箇所に再実装される
2. 概念同士の境界が曖昧になる
3. 新しい要求のたびに専用語彙が増える
4. リファクタリングしても、再び同じ構造的重複が発生する
5. 「YDEで何が計算可能なのか」を体系的に説明できなくなる

今後は個別機能を先に設計するのではなく、**YAML に対する計算を少数の直交したプリミティブとして定義し、その合成として機能を実現する**。

これを **YDE Core Calculus** と呼ぶ。

---

# 2. 基本方針

YDE Core Calculus の目的は、新しい万能 DSL を作ることではない。

目的は、

> YDE が YAML 文書集合に対して行う「選択・計算・判定・変更」を、最小限の意味論へ分解し、すべての上位機能をその組合せとして説明できる状態にすること

である。

基本構造を次の5体系に分離する。

```text
Term Algebra
    YAMLそのもの

Query Calculus
    対象を選び、名前を束縛する

Expression Calculus
    束縛された値から新しい値を計算する

Predicate Calculus
    値・ノード・関係について判定する

Rewrite Calculus
    条件に一致した対象から変更を生成する
```

これらの下位には変更を実行するための Patch / Transaction が存在する。

```text
Workspace
   │
   ▼
 Query
   │
   ▼
Bindings
   │
   ├───────────────┐
   ▼               ▼
Expression      Predicate
   │               │
   └───────┬───────┘
           ▼
        Rewrite
           │
           ▼
        PatchOp*
           │
           ▼
       ChangeSet
           │
           ▼
     atomic apply
```

---

# 3. 設計原則

## 3.1 機能ではなくプリミティブを増やす

新しい要求が出たとき、

> この機能をどのクラスに追加するか

から考えてはならない。

最初に、

> 既存の Query / Expression / Predicate / Rewrite の組合せで表現できるか

を検討する。

既存プリミティブで表現できない場合だけ、その不足している演算を特定する。

新しい上位機能の追加より、**計算体系に不足している最小演算の追加を優先する**。

---

## 3.2 1つの意味には1つの評価器

同一概念の評価ロジックを複数の消費者へ実装してはならない。

たとえば Query は、

- CLI
- View
- Validator
- foreach
- Agent
- Context recipe

のいずれから利用されても同じ評価器を使用する。

これは現在の Query Core や Projection がすでに採用している「1実装・多面開示」の方針を一般原則へ昇格させるものである。

---

## 3.3 読取・判定・変更を分離する

次を混在させてはならない。

```text
Query       : 何を対象とするか
Expression  : 何という値になるか
Predicate   : 条件を満たすか
Rewrite     : どの変更を生成するか
Apply       : 実際に状態を変更する
```

特に Rewrite は Workspace を直接変更してはならない。

Rewrite の結果は必ず `PatchOp` または `ChangeSet` とし、適用は workspace-core のトランザクション機構へ委譲する。

現在の Schema Refactoring も「Plan = 変更セット＋警告＋サマリ」を生成し、既存の apply 経路へ渡す方針を採っている。

この原則をすべての決定論的変更へ拡張する。

---

## 3.4 決定性を既定とする

Core Calculus は原則として決定的でなければならない。

同一 Workspace Snapshot と同一定義からは、同一結果を得る。

次は Core Calculus には原則導入しない。

- 任意コード実行
- 暗黙の副作用
- 評価順に依存する意味
- 無制限再帰
- 無制限ループ
- 暗黙の優先順位による競合解消
- LLM による意味決定

必要な場合は Core Calculus の外側に明示的な effect として配置する。

---

## 3.5 説明可能性を計算結果の一部とする

YDE では計算結果だけでなく、

> なぜその結果になったか

が重要である。

したがって provenance は UI の付加情報ではなく、計算結果の一級情報として扱う。

概念的には、

```text
Result<T> {
    value: T
    provenance: Evidence*
}
```

とする。

Expression は値の出典を、Predicate は判定根拠を、Rewrite は変更理由を保持できることを基本とする。

既存の Projection でも、導出元・入力値・結果を provenance として保持し、人間と AI が同じ導出説明を見る設計が採られている。

---

# 4. Semantic Domain

Core Calculus の最下位は YAML の構造である。

概念的には次の Term を扱う。

```text
Term ::=
    Null
  | Bool
  | Number
  | String
  | Seq(Term*)
  | Map((Key, Term)*)
```

Workspace は、

```text
Document  = DocumentId × Term
Workspace = Path → Document
```

とみなす。

Term 内の位置は YPath で表す。

```text
Location = Document × YPath
```

実装上は既存の `YNode` / `YPath` をこの役割へ対応させる。

---

# 5. Semantic Value と Presentation Metadata

YAML の意味的内容と表記情報を概念上分離する。

意味的な計算対象:

- scalar value
- sequence
- mapping
- key
- node identity
- document identity

表記上の情報:

- コメント
- block / flow style
- quote style
- 空行
- その他 round-trip 用情報

後者は保存時に可能な限り保持するが、Core Calculus の意味値には含めない。

これにより、

> rewrite による意味変更

と

> YAML 表記保持

を別問題として扱う。

---

# 6. Query Calculus

Query の責務は、

> Workspace から対象を選択し、名前付きの束縛を生成すること

だけとする。

```text
Query : Workspace → Set<Environment>
```

Environment は束縛名と Subject の対応である。

```text
Environment = Name → Subject
Subject     = Entity(kind, 論理ID, occurrences: Location*) | Document
```

例:

```text
scr → screen SCR-001（出現集合を持つエンティティ）
uc  → usecase UC-001（出現集合を持つエンティティ）
```

Subject のエンティティは出現集合（定義出現＋詳細化出現）を持ち、Location は Subject の派生属性（代表出現＝定義出現優先）である。同一IDの段階的詳細化（detailIn）を一級で扱うため、束縛は単一の Location ではなく Subject とする（Phase 1 確定 2026-09-05。specs/20-semantics/calculus/evaluation-model.yaml の DP-01・CALC-11）。

Query は値を計算しない。

Query 内の `where` は、対象集合を決定するために必要な限定された述語として扱うが、一般的な値計算や変更を持ち込まない。

現在の Query Core をこの体系の基礎実装とする。

---

# 7. Expression Calculus

Expression は Query によって得られた Environment を入力とし、値を生成する純粋計算である。

```text
Workspace, Environment, σ ⊢ Expression ⇓ Value | Unknown
```

σ は**出現割当て**——Environment 中の各エンティティ束縛に、その出現集合から1出現を選ぶ関数である。Expression は常に σ を固定して評価する**単一値の計算**であり、値集合の演算を導入しない。出現集合の集合性は、述語を Environment へ適用するときの σ の量化（§8）にだけ現れる（Phase 1 確定 2026-09-05。CALC-12）。

初期の基本演算は、小さく保つ。

例:

```text
Literal(value)
Get(binding, path)
Coalesce(expr...)
Map(expr, dictionary)
Format(template, expr...)
```

Expression の目的は万能式言語になることではない。

算術式・スクリプト・ユーザ定義関数などは、実需要が明確になるまで導入しない。

既存の Projection / Derivation は段階的に Expression Calculus へ整理する。

特に現在の `DerivationDef` に含まれる責務は、

```text
対象探索       → Query
値の取得・変換 → Expression
```

へ分離できるかを検討する。

長期的には、

```text
Derivation = Query + Expression
```

として説明可能な状態を目標とする。

---

# 8. Predicate Calculus

Predicate は値またはノード間の関係を判定する。

```text
要素述語:  Workspace, Environment, σ ⊢ Predicate ⇓ True | False | Unknown
束縛述語:  Workspace, Environment ⊢ Predicate ⇓ True | False | Unknown
```

要素述語は出現割当て σ を固定した単一値の判定であり、束縛述語は要素述語を σ で量化して得る。量化の向きは役割で分ける——**選択述語（Query の where）は存在量化**（∃σ で成立。多段定義OR）、**判定述語（Constraint の本体）は反例量化**（False になる σ があれば False・評価可能な σ がなければ Unknown・あってどれも False でなければ True。反例の σ は provenance に記録する）（Phase 1 確定 2026-09-05。CALC-13）。

二値ではなく三値論理を基本とする。

`Unknown` は、

- 値が存在しない
- 参照が解決できない
- Expression が評価できない
- 判定材料が不足している

場合を表す。

これは現在の consistency が採用している「判定保留は沈黙」という方針を一般化する。

基本 Predicate の候補:

```text
Equals(a, b)
NotEquals(a, b)
LessThan(a, b)
LessThanOrEqual(a, b)
GreaterThan(a, b)
GreaterThanOrEqual(a, b)

Exists(a)

SameNode(a, b)

Not(p)
And(p...)
Or(p...)
```

`same` のようなノード同一性と、値 equality は明確に区別する。

---

# 9. Aggregate

集合に対する計算は Expression / Predicate と区別して明示する。

初期段階では必要最小限に限定する。

```text
Count(Query)
```

今後、

- min / max
- all / any
- set containment

等の需要が生じても、個別 Constraint の特殊機能として追加せず、Aggregate として一般化可能かを先に検討する。

ただし DB エンジン化を避けるため、実需要なしに集約語彙を拡大しない。

ROADMAP でも Query Core の語彙肥大化をリスクとして認識し、任意式・任意深さ再帰などを避ける方針が明示されている。

---

# 10. Constraint

Constraint は独自の計算体系を持たない。

基本形は、

```text
Constraint =
    Query
    × Predicate
    × Severity
    × Diagnostic
```

とする。

意味的には、

```text
for each env in Query(workspace):
    evaluate Predicate(workspace, env)
```

だけである。

`False` のとき Issue を生成し、`Unknown` は原則として指摘を生成しない。

これにより、

- consistency
- query constraint
- relation cardinality
- coverage
- traceability
- same-node check
- completeness check

などについて、専用検証器でなければならない理由があるかを再評価する。

特殊なアルゴリズムを必要とする検査自体は禁止しないが、

> 既存 calculus で表現不能であること

を専用検証器追加の条件とする。

---

# 11. Rewrite Calculus

Rewrite は Workspace を検索し、条件を満たした場所に対する変更案を生成する。

```text
Rewrite =
    Query
    × optional Predicate
    × Edit*
```

評価結果は Workspace ではなく ChangeSet である。

```text
Workspace ⊢ Rewrite ⇓ ChangeSet
```

Rewrite 自体は副作用を持たない。

---

# 12. PatchOp を変更命令の最小集合とする

最終的な書込は PatchOp へ正規化する。

既存の、

```text
Set
Insert
Delete
Move
RenameKey
ReorderKeys
```

等を YAML 変更命令の基礎語彙として扱う。

上位の操作は可能な限りこの命令列へ lower する。

例えば、

```text
renameField
renameEnumValue
convertFieldType
alignOrder
renameRelation
renameSchemaId
```

などは利用者向け・ドメイン向けの高水準操作であり、Core Calculus 上のプリミティブとは区別する。

P25 で実装された各種 Schema Refactoring は、この整理の最初の候補となる。

---

# 13. Rewrite Strategy

Rewrite Rule と、その適用戦略を分離する。

将来的に必要となる場合は、

```text
once(rule)
all(rule)
sequence(rule...)
repeat(rule, max)
untilStable(rule, max)
```

などを Strategy として定義できる。

ただし初期状態では、

```text
snapshot
→ 全一致を列挙
→ ChangeSet生成
→ conflict検出
→ atomic apply
```

を基本とする。

複数 Rewrite が同一 Location に競合する場合、暗黙の順序で解決してはならない。

原則は、

> 曖昧な変更は Conflict とする

である。

---

# 14. Transaction

ChangeSet の生成と適用を明確に分離する。

```text
WorkspaceSnapshot
    │
    ▼
calculation
    │
    ▼
ChangeSet
    │
    ▼
validation
    │
    ▼
conflict detection
    │
    ▼
atomic apply
```

workspace-core は、

- snapshot / version
- base hash
- conflict
- transaction
- undo / redo
- persistence

を担当する。

semantic-core が直接ファイルを書き換えてはならない。

現在の3層コアも `yaml-core`、`semantic-core`、`workspace-core` に責務を分けており、この方向と整合する。

---

# 15. 3層コアとの対応

Core Calculus を現在の3層へ概ね次のように配置する。

```text
yaml-core
├── Term / YNode
├── YPath
├── Structural Schema
└── Patch primitives

semantic-core
├── Query
├── Expression
├── Predicate
├── Aggregate
├── Constraint
├── Rewrite planning
├── Semantic graph
└── Provenance

workspace-core
├── Workspace Snapshot
├── ChangeSet
├── Transaction
├── Validation orchestration
├── Version
├── History
└── Persistence
```

これは絶対的なクラス配置規則ではなく、依存方向を判断するための意味的責務である。

---

# 16. P27 との関係

P27 では SchemaDef を、

- Structural Schema
- Semantic declarations
- Presentation hints

へ分割する方針がある。

Core Calculus はこの分割の意味的基盤となる。

```text
Structural Schema
    Term がどのような構造を取れるか

Semantic Schema
    Query / Constraint / Relation 等により
    その構造が何を意味するか

Presentation
    人間へどう表示・編集させるか
```

P27 を単なる SchemaDef のクラス分割として実施せず、

> 構造・意味・表示のそれぞれがどの Calculus を所有するか

を明確にした上で進める。

---

# 17. P28 との関係

P28 の目的である「システム開発の設計情報管理と機械的検証」は維持する。

ただし今後は、実ユースケースから新しい検証要求が見つかったとき、

```text
新しい検証要求
      ↓
既存 calculus で表現可能か
      ↓
YES ──→ 組合せとして実装
      │
      NO
      ↓
不足している最小プリミティブを特定
      ↓
Calculus 自体を拡張
      ↓
その上で検証を実装
```

という順序を取る。

P28 の「テンプレート先行」という進め方自体は有効である。実データから需要を確認してから語彙を追加するという規律は維持する。

変更するのは、

> 要求 → Constraint DSL の機能追加

という直接経路である。

今後は必ず Core Calculus を一段挟む。

---

# 18. LLMとの境界

Core Calculus は LLM に依存しない。

LLM は、

- Query の候補を作る
- Constraint の候補を作る
- Rewrite の候補を作る
- 自然文から定義を生成する
- 計算結果を解釈・説明する

ことはできる。

しかし、

> 同一の確定済み定義を実行した結果

は LLM に依存してはならない。

原則として、

```text
AI = 定義を作る
Core Calculus = 定義を実行する
```

とする。

これにより、

> AIが書き、機械が検証し、人間が承認する

という YDE の基本モデルを維持する。

---

# 19. 導入しないもの

需要が実証されるまでは、次を Core Calculus に導入しない。

- 汎用プログラミング言語
- 任意ラムダ式
- 高階関数
- ユーザ定義再帰関数
- Turing complete な式評価
- 任意コード実行
- 無制限グラフ再帰
- 暗黙的な mutation
- 自動的な競合解消
- evaluation order に依存する意味
- spreadsheet 型の暗黙再計算グラフ

ラムダ計算や Term Rewriting System は、**設計思想と意味論の参考**として用いる。

それらを完全実装すること自体を目的としてはならない。

---

# 20. 新機能追加時の設計レビュー

新しい決定論的機能を追加するとき、最低限次を確認する。

1. **対象選択は Query で表現できるか**
2. **値導出は Expression で表現できるか**
3. **条件判定は Predicate で表現できるか**
4. **集合演算が必要なら既存 Aggregate で足りるか**
5. **変更は Rewrite → PatchOp に lower できるか**
6. **既存評価器と意味が重複していないか**
7. **結果に provenance を付与できるか**
8. **評価は決定的か**
9. **Unknown / Conflict の意味が定義されているか**
10. **新しい書込経路を作っていないか**

この確認なしに専用評価器・専用変更経路を追加しない。

---

# 21. 既存機能の再分類

既存機能を直ちに全面改修する必要はない。

まず概念上、次のように分類する。

| 既存機能 | Core Calculus 上の位置付け |
|---|---|
| Search | Query |
| Traverse | Query |
| Query Core | Query |
| View rows | Query |
| foreach | Query |
| Projection | Expression |
| Derivation | Query + Expression |
| consistency | Query + Predicate（**組込み定義体**。P29 Phase 6 第1段で、`check`＝クエリ＋値の比較へ展開される語彙になった。専用の検査プログラムは無い） |
| query constraint | Query + Predicate |
| coverage（detailIn） | 出現集合の Aggregate ＋ Predicate（CALC-21。参照ベース requiredBy は R29-01 で削除） |
| matrix | Query / Aggregate / Predicate または専用高水準演算 |
| flow（遷移検証） | Query（母集団選択）＋専用高水準演算（到達閉包。R28-04・§20 通過） |
| Validator | Constraint executor |
| Scaffold | Query + Rewrite |
| Bulk edit | Query + Rewrite |
| Schema Refactoring | Query + Rewrite |
| Entity operations | Query + Rewrite |
| Agent patch | Rewrite / Patch |
| ChangeSet | Transaction input |
| Workspace.apply | Transaction |

この表を用いて、重複している実装を段階的に発見する。

---

# 22. 移行方針

Core Calculus 導入を大規模 rewrite として実施しない。

以下の順で進める。

## Phase 1: 意味論の固定

コードを変更する前に、以下を文書として定義する。

- Semantic Domain
- Query
- Environment / Binding
- Expression
- Predicate
- Unknown
- Constraint
- Rewrite
- Patch
- ChangeSet
- Provenance

最低限の評価規則を記述する。

---

## Phase 2: 既存機能のマッピング

既存の、

- Query
- Projection
- Derivation
- Constraint
- Coverage
- Matrix
- SchemaRefactor
- PatchOp

を Core Calculus 上へマッピングする。

この時点ではコード統合を目的としない。

まず、

> 同じ意味なのに異なる実装になっている箇所

を可視化する。

---

## Phase 3: Expression / Predicate の共通化

最も小さく純粋な評価部分から共通化する。

特に、

- Projection
- Derivation
- consistency
- query where
- query constraint

間の重複を確認する。

---

## Phase 4: Rewrite の形式化

SchemaRefactor 等の高水準操作から、

```text
Query
→ Predicate
→ Rewrite
→ PatchOp
```

として自然に表現できるものを抽出する。

高水準操作の API は維持してよい。

内部実装だけを共通基盤へ寄せる。

---

## Phase 5: 新規機能を Calculus First にする

既存機能の完全移行を待たず、

> 新しく追加する決定論的機能は Core Calculus を経由する

ことを規約化する。

これにより、既存負債を増やさず段階移行できる。

---

## Phase 6: 定義層の実体化（2026-09-19 追加）

Phase 2 の写像は「説明できる」ことを成果物とし、コード統合を目的としなかった。Phase 6 は次の段として、
個別に実装された検証語彙（整合・多重度・詳細文書の存在・網羅・遷移など）を、§25 の定義層モデルに沿って
**組込み定義体（YAML）へ実際に移す**。

```text
個別の評価器
→ 組合せで書けない残差を最小のプリミティブとして切り出す
→ 残りを組込み定義体（YAML）として書く
→ 個別の評価器・宣言モデル・パーサを削除する
```

プリミティブは一括で入れず、語彙を1つ移すたびに、必要になったものだけを1つずつ採択する。
各段は、検証出力の一致（または意図的差分の登録だけで説明できる差）を完了条件とする。
スキーマ2層化（ROADMAP P27）は Phase 6 の後に置く——意味スキーマ層の中身を「定義体の呼び出しの集合」に
してから分割する（先に分割すると、個別の語彙がそのまま新しい層の型構造として固定される）。

設計の詳細と判断点は `specs/20-semantics/calculus/definition-layers.yaml`。

---

# 23. 完了条件

Core Calculus の導入完了とは、全コードが1つの巨大な汎用エンジンへ統合されることではない。

次を満たした状態を完了とする。

1. YAML に対する主要な計算を5体系で説明できる
2. 新機能追加時にどの体系へ属するか判断できる
3. 同じ意味を持つ評価器の重複が原則なくなる
4. 読取・判定・変更・適用の境界が明確である
5. 上位機能が基礎演算の組合せとして説明できる
6. provenance が人間・AI・CLI・検証で共有される
7. 決定論的機能と LLM による非決定的処理の境界が明確である
8. 新しい要求が出ても、専用機能を追加する前に Calculus 上で検討される
9. 検証の個別実装が、意味プリミティブの合格基準（§25）を満たすものだけになり、それ以外の検証語彙は組込み定義体として YAML で書かれている（2026-09-19 追加。条件 5 の「説明できる」を「実際にそう作られている」へ強める）
10. 組込み定義体にできることは、利用者も同じ定義言語で書ける（2026-09-19 追加）

目標はコード量を最小化することではない。

**独立した概念の数を最小化すること**である。

---

# 24. 最終原則

YDE の今後の発展では、次を最上位の設計原則とする。

> **複雑な機能を直接実装しない。  
> YAML に対する少数の直交した計算プリミティブを定義し、その合成として機能を実現する。**

新しい要求によってシステムの機能数が増えても、基礎概念の数は可能な限り増やさない。

YDE を、

> YAML を編集する多数の機能を持つアプリケーション

ではなく、

> **構造化された文書集合に対する、小さく決定的で説明可能な計算体系と、その上に構築された情報管理環境**

として発展させる。

これを今後のアーキテクチャ設計・リファクタリング・機能追加における基本方針とする。

---

# 25. 定義層モデル（本来の目的の明文化。2026-09-19 オーナー方針）

Core Calculus の本来の目的は、機能を直交させることで個別実装をなくし、

> **本当にプリミティブな実装 ＋ 組込み定義 ＋ ユーザ定義**

だけで様々な検証を行えるようにすることである。§24 の「合成として機能を実現する」を、
説明の上だけでなく実装の上でも成り立たせる。そのために、検証に関わる実装と定義を次の4層に区別する。

| 層 | 実体 | 内容 |
|---|---|---|
| 文法プリミティブ | ベース実装（コード）。**yaml-core の領域** | **YAML の構造そのものと、最小の構造チェック**——項とパス・値の読み出し・変更の基礎命令（6種）・構造チェック（属性の有無・属性の値の型・値の基本的なチェック・選択肢・再帰）。ID・参照・出現・関係・フェーズの「意味」を知らない。**小さく保つ** |
| 意味プリミティブ | 個別実装（コード）。**semantic-core の領域** | **YAML をどう解釈するか**——ID と参照の走査・グラフの構築・参照解決・クエリ・判定・判定を保留する規則・値の計算と値の条件・フェーズへの到達など。解釈に関わるものはすべてこちら。形は2種類: **部品**（定義の中で組み合わせる小さな演算。特定の設計上の問いを知らない）と **専用チェッカー**（複雑なアルゴリズムの検査をまるごと実装したもの。YAML で定義できる必要はなく、定義体から名前と渡す値で使う） |
| 組込み定義体 | 組込み YAML 定義 | プリミティブだけで書かれた、名前と渡す値を持つ検査の定義と、それを短く書くための語彙の定義。現在は個別の検査プログラムとして実装されているものの行き先 |
| ユーザ定義 | ユーザ YAML 定義 | 同じ書き方で利用者がワークスペースに置く定義。組込み定義体と同じ仕組み・同じ能力を持つ |

層の境界は3層コアの依存の向き（yaml-core ← semantic-core。ADR-0002・§15）と一致する——文法プリミティブは意味プリミティブを知らず、
意味プリミティブは文法プリミティブの上に解釈を載せる。指摘の配送・ゲートなどの進行管理は4層の外に置く。

**層の決め方**: ①既存のプリミティブの組合せで無理なく書けるなら YAML の定義体にする（コードを書かない）。
②書けないなら、足りない部分を小さな部品として切り出し、意味プリミティブに足す。**想定される処理と計算は、需要を待たずに用意しておく**。
③アルゴリズムが複雑で、部品の組合せで書くと不自然になるものは、**専用チェッカーとして個別に実装してよい**
（すべてを YAML で定義できる必要はない。例: 状態遷移に時相論理を適用する検査）。
④yaml-core に足すのは YAML の構造と最小の構造チェックに属するものだけ——**迷うものは意味側に置く**。

**検査の書き方の3段**: 第1段＝基本の書き方（スキーマの検査の一覧に、プリミティブを直接書くか、定義を名前と渡す値で使う。これがベース）／
第2段＝関数のような定義（名前・受け取る値・中身）／第3段＝語彙の定義（どこに書けるか・書き方の形・どの定義をどの値で使うか）。
上の段は下の段へ展開される。関係の宣言の `cardinality` やスキーマの `consistency`・`coverage`・`matrix`・`flow` は、
組込み定義体が作る語彙になり、利用者は今までどおり書ける。語彙の定義は利用者も書ける。

**メッセージとエラー時の対応の直交性**: 指摘のメッセージと、エラー時の対応（その場所へ移動する・文書を新しく作る・値を書き込む、など）も、
対応の種類というプリミティブと、それを選ぶ定義の組合せで決める。検査ごとの対応を、検査の実装の中に作り込まない。

**意味プリミティブの条件**: 純粋で決定的であること／判定を保留する規則（Unknown）を持つこと／単独でテストできること／
定義体から名前と渡す値で使えること。部品はこれに加えて、組合せで書けない理由を説明できることと、特定の設計上の問いを知らないこと。

**yaml-core の構造チェックについての仮説**: YAML の構造を検証するスキーマは、本質的には、通常の構造チェック
（属性の有無・属性の値の型・値の基本的なチェック）に、選択肢（enum）と再帰を足せば足りる。利用者が書くスキーマは
意味のスキーマであり、そこから最小の構造チェック用のスキーマを導出して yaml-core で検証する。現在の yaml-core にある
解釈寄りの部分（`cases`・`requiredFrom`・ID と参照の型）の切り分けは、スキーマ2層化（ROADMAP P27）で行う。

**組込み定義体の非特権**: 組込み定義体にできることは、すべてユーザ定義にもできる。確認の方法は
「組込み定義体をすべて取り除いてもツールは動き、利用者が同じ定義を自分で書き直せる」こと。

**プリミティブの拡張**: プリミティブは必要に応じて拡張してよい（§7・§9 の「実需要が明確になるまで導入しない」と、§19・ROADMAP P22 の
クエリの制限は、語彙の肥大化を防ぐためのものであり、想定される処理と計算の範囲については、制限を付け直したうえで緩める）。
用意する範囲・緩める範囲・新しい制限は `specs/20-semantics/calculus/definition-layers.yaml`（用意しておく処理と計算・プリミティブ台帳）で管理する。
利用者が定義する関数と再帰・条件分岐を持つ式・任意のコードの実行は、これからも入れない。