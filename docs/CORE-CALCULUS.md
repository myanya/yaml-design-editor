# YDE Core Calculus  
## YAMLに対する計算体系の設計方針

**Status:** Accepted Architecture Policy（[ADR-0009](decisions/0009-core-calculus.md)）  
**Scope:** yaml-core / semantic-core / workspace-core およびその上に構築される検証・ビュー・リファクタリング・エージェント機能  
**Purpose:** 今後の機能追加・リファクタリングに対する設計判断の基準を定める

---

## 1. 背景

YAML Design Editor（以下 YDE）は、当初の汎用 YAML エディタから、スキーマ、ID、参照、関係、ビュー、検証、リファクタリング、エージェント実行を備えた情報管理・実行基盤へ発展してきた。

現在までに、以下のような基盤が成立している。

- `YNode` / `YPath` による YAML 構造と位置の表現
- Query Core による構造検索・トラバース・ビュー行決定・foreach・導出 lookup の統合
- Expression（値の式）による決定論的な値導出
- Constraint / Validation による意味的検証
- PatchOp / ChangeSet による部分変更と原子的適用
- Schema Refactoring による複数文書・定義の決定論的変更
- `yaml-core` / `semantic-core` / `workspace-core` の3層コア

Query Core は、グラフパターン照合を単一エンジンに統合する設計とする。

また、決定論的射影では、評価器を一元化し、任意式やコード実行を導入せず、1段評価・判定保留・provenance を重視する設計が採用されている。

一方で、設計検証の拡充では、`count`、`notEquals`、matrix、`same`、遷移検証など、実ユースケースに応じて意味語彙が拡大し始めている。

これは自然な進化である一方、このまま機能要求ごとに個別の評価器・検証器・変更器を追加すると、次の問題が起こる。

1. 似た意味の計算が複数箇所に再実装される
2. 概念同士の境界が曖昧になる
3. 新しい要求のたびに専用語彙が増える
4. リファクタリングしても、再び同じ構造的重複が発生する
5. 「YDEで何が計算可能なのか」を体系的に説明できなくなる

今後は個別機能を先に設計するのではなく、**YAML に対する計算を少数の直交したプリミティブとして定義し、その合成として機能を実現する**。

これを **YDE Core Calculus** と呼ぶ。

---

## 2. 基本方針

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

6つ目の体系として **Construct Calculus（組み立て）**——束縛と値から、新しい項を組み立てる——を足した（§26）。
Rewrite が「いまある文書への変更の一式」を作るのに対し、Construct は「新しい項」を作る。

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

## 3. 設計原則

### 3.1 機能ではなくプリミティブを増やす

新しい要求が出たとき、

> この機能をどのクラスに追加するか

から考えてはならない。

最初に、

> 既存の Query / Expression / Predicate / Rewrite の組合せで表現できるか

を検討する。

既存プリミティブで表現できない場合だけ、その不足している演算を特定する。

新しい上位機能の追加より、**計算体系に不足している最小演算の追加を優先する**。

---

### 3.2 1つの意味には1つの評価器

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

### 3.3 読取・判定・変更を分離する

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

### 3.4 決定性を既定とする

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

### 3.5 説明可能性を計算結果の一部とする

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

## 4. Semantic Domain

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

## 5. Semantic Value と Presentation Metadata

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

## 6. Query Calculus

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

Subject のエンティティは出現集合（定義出現＋詳細化出現）を持ち、Location は Subject の派生属性（代表出現＝定義出現優先）である。同一IDの段階的詳細化（detailIn）を一級で扱うため、束縛は単一の Location ではなく Subject とする（Phase 1 確定 2026-09-05。DP-01・CALC-11）。

Query は値を計算しない。

Query 内の `where` は、対象集合を決定するために必要な限定された述語として扱うが、一般的な値計算や変更を持ち込まない。

現在の Query Core をこの体系の基礎実装とする。

---

## 7. Expression Calculus

Expression は Query によって得られた Environment を入力とし、値を生成する純粋計算である。

```text
Workspace, Environment, σ ⊢ Expression ⇓ Value | Unknown
```

σ は**出現割当て**——Environment 中の各エンティティ束縛に、その出現集合から1出現を選ぶ関数である。Expression は常に σ を固定して評価し、出現集合の量化を持ち込まない。出現集合の集合性は、述語を Environment へ適用するときの σ の量化（§8）にだけ現れる（CALC-12。ここで言う集合は複数の文書・出現・解であり、1 つの項の中の配列は値の一部。ADR-0010）。

**値は項と出どころ**である。項は §4 の Term（スカラー・配列・マップ）で、出どころは項の各場所から入力の文書と場所への対応。読み出しは項をそのまま返し、`[*]` を含むパスは合う場所の値を並べた配列を返す（各要素が出どころを持つ）。

語彙は 2 種類に分かれる。

```text
関数（引数を先に評価して値を受け取る。型が合わなければ Unknown）:
  Literal(value)  Get(binding, path)
  Sum / Min / Max / Count(array)              -- 配列を受け取る。Count は件数。種類は項目の型か引数
  Length(string)  Upper  Lower  Number  Map(expr, dictionary)
  Add  Subtract  Multiply  Divide
形を受け取る語（自分の規則で評価する）:
  Format(template, scopes, with)  First(expr...)
  All(...)                                    -- 集合から配列へ（§9）
  Each(array expr, value expr)                -- 各要素を「いまの要素」として式を当てる（§9）
```

Expression の目的は万能式言語になることではない。スクリプト・ユーザ定義関数・ラムダ・条件分岐を持つ式は導入しない。
`Each` の値の式が受け取るのは暗黙の「いまの要素」だけで、名前を付けた引数は無い。文字数・大文字と小文字の変換・数値の表記を揃える計算・四則演算は、
「想定される処理と計算は、需要を待たずに用意しておく」（§25）に沿って用意した。数値として読めない値・0 での割り算は Unknown（値が得られない＝判定保留）。
四則演算は小数の誤差を持ち込まない。`Equals` は文字列の等値のままで、数値どうしを等値で比べるときは `Number` で表記を揃える。比較・穴埋め・四則演算はスカラーだけを受け取る。

Projection は `Format` の穴埋めの実装として残り、専用の Derivation は無い。ビューの派生列は Derivation = Query + Expression の形
（値の式。参照を 1 段辿る `via` は Get の相手の選び方で、量化はしない）で書く。

**検査の外での評価**: Expression の評価器は、Query の check 判定の中に閉じていた（解の束縛と σ の割当てを前提）。
`SemanticValues.evaluate(式, 環境)` として外へ出し、環境に「いまの要素」（`of` を省略した読み出しの相手）・予約の名前（`{id}` `{title}`）・
穴に入る値の直し方を足した。同じ式が、検査の check と、スキーマの値（新しい文書のファイル名 `filePattern`）とビューの列で同じ意味を持つ
（1つの意味には1つの評価器）。書き方は2つ（`{パス}` を含む文字列＝`Format` の省略形・値の式そのもの）で、パイプ記法のような別の式言語は作らない。

---

## 8. Predicate Calculus

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

**検査の結果は4つ**（CALC-31。architecture/checks.md「4 つの結果」）: 判定の結果を利用者へ返すときは、`True` / `False` / `Unknown` を
**充足・違反・保留・対象外**の4つへ写す。`Unknown` のうち、データの不備（相手の見つからない参照・数値として読めない値）によるものが保留、
確かめるものが無いこと（検査の対象でない・フェーズ未到達・比べる値が書かれていない）によるものが対象外。指摘を生成するのは違反だけだが、
保留は「確かめられなかった」として記録し、充足に数えない（沈黙は指摘を出さないという意味であって、結果を捨てるという意味ではない）。
どの結果になるかの判定は `semantic.SemanticRules` の1箇所に置く。

基本 Predicate の候補:

```text
Equals(a, b)
NotEquals(a, b)
LessThan(a, b)
LessThanOrEqual(a, b)
GreaterThan(a, b)
GreaterThanOrEqual(a, b)
OneOf(a, b...)
Matches(a, regex)  StartsWith(a, b)  EndsWith(a, b)  Contains(a, b)

Exists(a)
IsEmpty(a)

SameNode(a, b)

Not(p)
And(p...)
Or(p...)
```

`same` のようなノード同一性と、値 equality は明確に区別する。

値の条件（Equals〜Contains）は、判定（`check`）と選択（`where`）が同じ演算子・同じ判定を共有する。
`OneOf` は「どれかと等しければ True・等しい選択肢がなく Unknown の選択肢が残れば Unknown」、`Matches` は全体一致で、
正規表現として読めなければ Unknown。`Exists` / `IsEmpty` は値そのものの有無を問うため、選択（`where`）でだけ使う
（判定では、値が得られない組は Unknown として飛ばされるため）。
`Not` / `Or` は、選択（`where`）・判定（`check`）・ノードの条件（node の `not`・型の並び）に入れた。
入れ子は1段まで・`And` は並べて書く。3値で評価し、`Not(Unknown)` は `Unknown`、`Or` は「どれかが True なら True・True がなく
Unknown が残れば Unknown・すべて False なら False」。

---

## 9. Aggregate

集合（複数の文書・出現・解）から値へ落とす入口は `All` の 1 つに限り、畳む計算は配列を受け取る関数（§7）に任せる。

```text
All(binding, path)                        -- 同じ起点の全ての解にわたり、束縛の要素の path の値を並べた配列
All(from, Query, binding, path)           -- 起点（from の束縛か、いまの要素）を固定した照合の解から並べた配列。検査の中でも書ける
Each(array, expr)                         -- 各要素を「いまの要素」として式を当てた配列
RefCount(binding, dir, relation)          -- ノードの参照の本数（グラフの隣接の集合への Count）
OccurrenceCount(binding, schema, role)    -- その ID が書かれた場所（出現集合）への Count
```

`All` は出現を量化せず、値を読む出現を定義した側を優先して1つに決める（CALC-19）。path の `[*]` は 1 つの要素の中の配列に広がり、
`All` と重ねると平らな配列になる。読めない要素が1つでもあれば配列そのものが Unknown（集める側で判定する）。各要素は出どころを持ち、
`Min` / `Max` の結果は選ばれた要素の出どころ、`Sum` / `Count` は出どころを持たない。順序の種類は項目の型で決まり（引数で上書き）、表記から意味を決めない。

Constraint の count（解の組の件数の範囲）は数え上げの単位が異なるため統合しない。

今後、all / any・set containment 等の需要が生じても、個別 Constraint の特殊機能として追加せず、関数か形を受け取る語として
一般化可能かを先に検討する。DB エンジン化を避けるため、実需要なしに語彙を拡大しない。

---

## 10. Constraint

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

## 11. Rewrite Calculus

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

## 12. PatchOp を変更命令の最小集合とする

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

各種 Schema Refactoring は、この整理の最初の候補となる。

---

## 13. Rewrite Strategy

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

## 14. Transaction

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

## 15. 3層コアとの対応

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

## 18. LLMとの境界

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

## 19. 導入しないもの

需要が実証されるまでは、次を Core Calculus に導入しない。

- 汎用プログラミング言語
- 任意ラムダ式
- 高階関数
- ユーザ定義再帰関数（ただし、Construct の「規則を、入力の木の子に当てる」形は、必ず終わるのでこれに当たらない。§26）
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

## 20. 新機能追加時の設計レビュー

新しい決定論的機能を追加するとき、最低限次を確認する。

1. **対象選択は Query で表現できるか**
2. **値導出は Expression で表現できるか**
3. **条件判定は Predicate で表現できるか**
4. **集合演算が必要なら既存 Aggregate で足りるか**
5. **変更は Rewrite → PatchOp に lower できるか**（新しい YAML を作るなら、Construct の変換定義で書けるか。§26）
6. **既存評価器と意味が重複していないか**
7. **結果に provenance を付与できるか**
8. **評価は決定的か**
9. **Unknown / Conflict の意味が定義されているか**
10. **新しい書込経路を作っていないか**

この確認なしに専用評価器・専用変更経路を追加しない。

---

## 21. 既存機能の再分類

既存機能を直ちに全面改修する必要はない。

概念上の分類：

| 既存機能 | Core Calculus 上の位置付け |
|---|---|
| Search / Traverse / Query Core / View rows | Query |
| Matrix view（クロス集計） | Query ＋ Aggregate（列を広げたグリッドへ下ろす） |
| Flow view | Construct＋構造検査・接続解決 |
| foreach | Query |
| Projection | Expression |
| Derivation（ビューの派生列。専用の語彙は廃止） | Expression（`via`＝1 段の参照）＋ Aggregate |
| consistency | Query + Predicate（**組込み定義体**。`check`＝クエリ＋値の比較へ展開される語彙になった） |
| query constraint | Query + Predicate |
| relation cardinality（多重度） | Query + Aggregate（参照の本数）+ Predicate（**組込み定義体**） |
| relation source / target（対象型） | Query（ノードの条件の否定・参照の束縛）+ Predicate（**組込み定義体**） |
| coverage（detailIn） | 出現集合の Aggregate ＋ Predicate（CALC-21。**組込み定義体**。書かれた場所の数 `occurrenceCount` の比較へ展開される語彙になった） |
| entity lint（浮遊詳細化・定義競合・型競合） | 出現集合の Aggregate ＋ Predicate（**組込み定義体**に直接書いた検査。idPattern 逸脱と重複所有は専用の lint として残す） |
| matrix | Query ＋「すべての要素について」の判定（**組込み定義体**。検査の基本の書き方 `forAll` へ展開される語彙になった。専用の検査プログラムは無い） |
| flow（遷移検証） | Query（母集団選択）＋専用高水準演算（到達閉包。§20 通過）。**専用チェッカー（SP-13）を組込み定義体から使う形になった**——演算はコードに残し、語彙への結び付け・文面・重大度は定義の宣言 |
| relation acyclic（非循環） | Query（何段でも辿る線 `repeat` ＋ノード一致 `same`）＋ Predicate（**組込み定義体**。辿る計算だけが専用アルゴリズムで、判定は YAML。専用チェッカー `cycle` は廃止） |
| Validator | Constraint executor |
| Transform（YAML → YAML） | Query + Construct（＋Predicate＝項目を出す条件） |
| 構造のスキーマの導出・スキーマの図 | Construct（変換定義・宣言からの導出） |
| Scaffold・Import | Expression ＋ Construct（`scaffold` / `build`）＋ Rewrite（作成・追記・採番） |
| Bulk edit / Schema Refactoring / Entity operations | Query + Rewrite |
| Agent patch | Rewrite / Patch |
| ChangeSet | Transaction input |
| Workspace.apply | Transaction |
| ユーザ定義の型 | 宣言。検証は構造チェックへ導出 |
| 自動で入る値（`auto`） | Rewrite（要素 × 保存済みとの違い × SetScalar） |
| 操作（`methods` / `actions`） | Rewrite の利用者定義体（受け取った値 × Query × Predicate × 操作 ＋ Construct） |

この表を用いて、重複している実装を段階的に発見する。

---

## 24. 最終原則

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

## 25. 定義層モデル（本来の目的）

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
解釈寄りの部分（`requiredFrom`・ID と参照の型）の切り分けは、スキーマ2層化で行う。
**`cases` は yaml-core に残す**: 選択肢（enum）の一部であり、見るのは同じ object の中の選択肢の値だけ
（1つの文書の中で閉じる）。仮説の「選択肢」は、値によって有効な属性が変わる形を含むものとして読む。
**この仮説は実装済み（architecture/schema.md「構造スキーマの導出」）**: 構造の検証は、利用者が書くスキーマから組込みの変換定義（§26）で
導出した最小の構造のスキーマで行い、利用者が書く木（ID・参照・フェーズ・表示のヒントを持つ）は semantic-core へ移した。値の形式的なチェック
（範囲・文字数・形式・選択肢）は yaml-core に置く——分け方の基準は「その値1つと、その項目の決まりだけを見て決まるか」（オーナーの判断）。

**組込み定義体の非特権**: 組込み定義体にできることは、すべてユーザ定義にもできる。確認の方法は
「組込み定義体をすべて取り除いてもツールは動き、利用者が同じ定義を自分で書き直せる」こと。

**プリミティブの拡張**: プリミティブは必要に応じて拡張してよい（§7・§9 の「実需要が明確になるまで導入しない」と、§19 の
クエリの制限は、語彙の肥大化を防ぐためのものであり、想定される処理と計算の範囲については、制限を付け直したうえで緩める）。
用意する範囲・緩める範囲・新しい制限は、本文書（本節と §21 の分類表）で管理する。
利用者が定義する関数と再帰・条件分岐を持つ式・任意のコードの実行は、これからも入れない。

---

## 26. Construct Calculus（組み立て）——YAML から YAML への変換

§2 の5体系は「選ぶ・値を計算する・判定する・書き換える」を持つが、**新しい YAML を組み立てる体系が無かった**。
SQL にたとえると、Query は FROM・JOIN・WHERE に当たり、SELECT 句（結果をどんな形で出すか）に当たるものが無い。
そのため、変換に当たる仕組み（ビューの列・skill の書き出し・派生スキーマ・AI 生成用の JSON Schema・ひな型の文書の生成・
文脈の開示など。棚卸し済み）は、それぞれが別の書き方と別のプログラムを持っていた。

方針: YAML から YAML への変換を汎用にし、汎用の変換器の土台にする。構造のスキーマの導出は、
その内部利用の1つ。いずれはビューなどでも使い、テンプレートエンジンなどの派生を足して、YAML から Markdown への変換器なども作っていく。

```text
Construct =
    optional Query          （選ぶ。書かなければ、渡された項1つが入力）
    × Template              （組み立てる。出力の形をそのまま書いたひな型の穴を、値で埋める）
    × Rule*                 （入れ子には、名前を付けた規則を子に当てる）

Workspace, Params ⊢ Construct ⇓ Term（＋出どころ）
```

**SQL と違う点は2つ**: ①つなぎ方——Query は値の一致ではなく、参照・入れ子・同じ ID の出現でつなぐ（§6。ここは既存のまま使う）
②出力の形——SQL の出力は平らな表だが、YAML は入れ子の木。木を出すために、SQL に無い道具が2つ要る:
**繰り返し**（出力の中に配列・マップを作る）と、**規則を子に当てること**（入れ子の深さが決まっていない入力を扱う）。

**決めたこと**（判断点 TF-01〜09）:

- **選び方**: 要素と文書を選んで名前を付けるのは、既存の Query をそのまま使う。選んだものの「中」は、パスと繰り返しで辿る。
  **Query 自体を ID の無い場所まで広げない**（Query は索引の上の評価と、判定を保留する規則が土台のため）。
- **規則を子に当てること（apply）は必要**: 「本質的に計算器には apply がどうしても必要であり、木構造である YAML では、再帰を扱えることが必須」
  （オーナー）。認めるのは**入力の木の子へ向かう形**で、必ず終わる——同じ規則を同じ場所に当てている最中に、もう一度同じ規則を
  同じ場所に当てる定義は、定義エラーにする（入力の場所は有限なので、入れ子の深さは「規則の数 × 場所の数」を超えない）。
  §19 の「ユーザ定義再帰関数を入れない」は、止まらない計算を入れないための決まりで、この形はそれに当たらない。
- **値**: 既存の Expression は項（スカラー・配列・マップ）を返す。Construct には、部分木をそのまま写す・条件に合うときだけ項目を出す・
  繰り返しで配列とマップを作る、を足した。値の条件・文字のひな型・辞書での置き換えは、既存の共有部品（§7・§8）を使う。
  式の中の条件分岐と、キーを計算で作ることは、必要になるまで入れない。
- **出力は保存しない**: 出力は、メモリ上の読み取り専用の成果物（派生スキーマと同じ扱い）。原本主義（書き出し skill は YAML への出力を
  拒否する）と「導出の結果を仮想の文書として永続化しない」を守る。原本へ書き込みたいときは、出力の項を Rewrite の入力にして、
  今までどおり変更セットと人間の承認を通す。
- **出どころ（§3.5）**: 出力の各値が、入力のどの文書のどの場所から来たかを、最初から結果の一部にする
  （ビューの「セルは原本の場所に解決される」を、変換一般の性質にする）。
- **置き場**: semantic-core（`schema/Transform.kt`＝宣言、`semantic/Transform.kt`＝評価器 `SemanticTransform`）。yaml-core には足さない。
- **文字の出力（Markdown など）は、土台の外の派生**（検討メモ。いまは実装に影響させない）: 帳票の種類ごとに Markdown のテンプレート
  （値を埋め込む形のひな形）を用意し、YAML から YAML への変換でそのひな形が求める値の一式を作り（表の形への変換もここ）、
  テンプレートエンジンが値の一式からパスで項目を取り出してひな形にはめ込む、という2段。土台に要るのは
  「出力が、パスで取り出せる素直な YAML であること」だけ。

**書き方と既存の仕組みとの関係**は architecture/transform.md「変換」にある。
