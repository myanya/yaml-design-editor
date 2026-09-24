# Core Calculus の記録（テーマとの関係・移行の Phase・完了条件）

[../CORE-CALCULUS.md](../CORE-CALCULUS.md) から、経緯にあたる節（§16・§17・§22・§23）をそのまま移したもの。書き換えない。

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

設計の判断点の記録は [ROADMAP.md](../ROADMAP.md) の P29 Phase 6 の行にある。

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

