# view-flow

ビューの種類 `kind: flow`（文書を図に）を足す拡張です。文書 1 つを変換定義（`projection`）でノード・辺・レーンに射影し、図の要素から原本の場所へ戻れるようにします。図は保存せず、開くたびに原本から作ります。

- 差し込み口の実装は `FlowViewKind`（`ViewKind`）で、入口（desktop・cli）の登録の一覧に並べて使います。
- 文書に縛られる種類です。解決には対象の文書が要り、台帳のビュータブの一覧には並べません。画面は台帳の「フロー」タブと、文書ビューの区画（`flow: {view}`）です。
- 描画は拡張の外（desktop の `ui/FlowViewPane.kt`・`ui/FlowSwimlanes.kt`・`ui/GraphLayouts.kt`）にあるので、描画が読む定義の型（`VirtualFlowDef`）・図のモデル（`FlowModel` など）・選択（`FlowSelection`）・表示の設定（`FlowDisplayOptions`）も公開します（ADR-0014）。
- 定義の形の断片は資源 `view-flow/schema.yaml`、図のモデルの構造の決まりは資源 `view-flow/flow-model.yaml` です。適用先（`match`・`schemaId`）は文書ビューと共有するので中核に残しています。
- 文書の移動で対象から外れるときの警告と、改名で自動に書き換えない言及（`projection` の中の固定 ID や関係名）は、差し込み口の `moveWarnings`・`mentionSites` で知らせます。

定義の例:

```yaml
id: business-flow
name: 業務フロー図
kind: flow
schemaId: business-flow-detail
layout: { direction: down }
projection:
  id: business-flow-projection
  build:
    nodes:
      $each: nodes
      $build:
        key: { $copy: id }
        label: { $copy: name }
        detail: { $copy: . }
    edges:
      $each: transitions
      $build:
        from: { $copy: from }
        to: { $copy: to }
        detail: { $copy: . }
```

書き方の全体は [docs/manual/flow-views.md](../docs/manual/flow-views.md)、仕組みは [docs/architecture/views.md](../docs/architecture/views.md)「ビューの種類の差し込み口」と「フローの解決」。
