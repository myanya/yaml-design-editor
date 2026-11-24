#!/usr/bin/env python3
"""設計書の依存マップ（HTML）を作り直す。

設計書の種類ごとに、ほかのどの設計書から作られているかを、変換定義（.yde/transforms/）から計算して 1 枚の HTML にする。
線は 4 種類: 内容を読む・補正後の業務モデル経由・名前の付け替えだけ・順序だけ。

使い方（リポジトリのどこからでも。Windows は py -3）:
    python3 samples/todos-sysdev/scripts/design-map.py            # docs/design-dependency-map.html を作り直す（Git に入れる）
    python3 samples/todos-sysdev/scripts/design-map.py --check    # 作り直した結果が、いまのファイルと同じかを確かめる（違えば 1）
    python3 samples/todos-sysdev/scripts/design-map.py --out 場所.html --workspace 作業用コピー

同じ入力からは同じ HTML になる（日時を入れない）。Git の差分が、依存の変化になる。

読むもの:
    - .yde/transforms/**/*.yaml   変換定義の dependsOn と、読むビュー（$view）
    - .yde/views/**/*.yaml        ビューが選ぶ ID の型（rows の idPattern・match）
    - YDE CLI の generate --lineage  変換定義が業務モデルのどの部分を読むか（--lineage でファイルを渡せば CLI を呼ばない）
    - scripts/design-map.json     箱の並べ方と、どの変換定義がどの箱を作るか（人が書く）
    - scripts/design-map.template.html  ページのひな形

標準ライブラリだけで動く（YAML は、必要な書き方だけを正規表現で読む）。
"""
import argparse
import fnmatch
import html
import json
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SAMPLE = HERE.parent
REPO = SAMPLE.parent.parent
TYPES = ["c", "m", "r", "o"]  # 内容・業務モデル経由・名前だけ・順序だけ（強い順）
BODY_MARK = "<!--BODY-->"  # ひな形の、head に置くものと body に置くものの境


def unquote(value):
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        return value[1:-1]
    return value


def top_value(text, key):
    """先頭の桁にあるキーの、1 行で書かれた値。"""
    found = re.search(rf"^{re.escape(key)}:[ \t]*(.*)$", text, re.M)
    return unquote(found.group(1)) if found else None


def top_list(text, key):
    """先頭の桁にあるキーの並び（[a, b] と、行ごとの - a の両方）。"""
    found = re.search(rf"^{re.escape(key)}:[ \t]*(.*)$", text, re.M)
    if not found:
        return []
    inline = found.group(1).strip()
    if inline.startswith("["):
        return [unquote(item) for item in inline.strip("[]").split(",") if item.strip()]
    items = []
    for line in text[found.end():].splitlines()[1:] if inline == "" else []:
        entry = re.match(r"^\s+-\s+(\S.*)$", line)
        if not entry:
            if line.strip() == "" or line.lstrip().startswith("#"):
                continue
            break
        items.append(unquote(entry.group(1)))
    return items


def top_block(text, key):
    """先頭の桁にあるキーから、次の先頭の桁のキーの手前まで。"""
    found = re.search(rf"^{re.escape(key)}:.*$", text, re.M)
    if not found:
        return ""
    rest = text[found.start():]
    following = re.search(r"^\S", rest[len(found.group(0)):], re.M)
    return rest if not following else rest[: len(found.group(0)) + following.start()]


def read_transforms(workspace):
    transforms = {}
    for path in sorted((workspace / ".yde" / "transforms").rglob("*.yaml")):
        text = path.read_text(encoding="utf-8")
        identifier = top_value(text, "id")
        if not identifier:
            continue
        final_paths = re.findall(r"(?:designPath:|\$format:)\s*['\"]?(design/[^\s'\"]+\.yaml)", text)
        transforms[identifier] = {
            "id": identifier,
            "name": top_value(text, "name") or identifier,
            "file": path.relative_to(workspace).as_posix(),
            "output": top_value(text, "output") or "",
            "dependsOn": top_list(text, "dependsOn"),
            "views": sorted(set(re.findall(r"\$view:\s*([A-Za-z0-9_.-]+)", text))),
            "finalPaths": sorted(set(final_paths)),
        }
    return transforms


def read_views(workspace):
    views = {}
    for path in sorted((workspace / ".yde" / "views").rglob("*.yaml")):
        text = path.read_text(encoding="utf-8")
        identifier = top_value(text, "id")
        if not identifier:
            continue
        rows = top_block(text, "rows")
        patterns = re.findall(r"idPattern:\s*[\"']?([^\"',}\s]+)", rows)
        if not patterns:
            patterns = re.findall(r"node:\s*\{?\s*(?:\n\s+)?id:\s*[\"']?([^\"',}\s]+)", rows)
        views[identifier] = patterns
    return views


def read_lineage(text):
    """generate --lineage の出力から、変換定義ごとに、読む場所の頭のキーを集める。"""
    start = text.find("generators:")
    if start < 0:
        raise SystemExit("系譜の出力に generators: がありません")
    heads, current, reading = {}, None, False
    for line in text[start:].splitlines():
        entry = re.match(r"^  - id:\s*(\S+)", line)
        if entry:
            current, reading = unquote(entry.group(1)), False
            heads[current] = set()
            continue
        if current is None:
            continue
        if re.match(r"^    reads:", line):
            reading = True
            continue
        item = re.match(r"^      - (.*)$", line)
        if reading and item:
            heads[current].add(re.split(r"[.\[]", unquote(item.group(1)))[0])
        elif re.match(r"^    \S", line):
            reading = False
    return heads


def run_lineage(workspace, target):
    """YDE CLI で系譜を出す（Gradle が CLI の一式を最新にしてから起動する）。"""
    target.parent.mkdir(parents=True, exist_ok=True)
    arguments = ["generate", str(workspace), "--lineage", "--output-file", str(target)]
    if sys.platform.startswith("win"):
        command = ["cmd", "/c", str(REPO / "yde.cmd")] + arguments
    else:
        command = ["bash", str(REPO / "yde")] + arguments
    print("YDE CLI で系譜を出します（初回はビルドに時間がかかります）…", file=sys.stderr)
    done = subprocess.run(command, cwd=REPO)
    if done.returncode != 0 or not target.exists():
        raise SystemExit("系譜を出せませんでした（generate --lineage）。CLI のエラーを確かめてください")
    return target.read_text(encoding="utf-8")


def expand_nodes(config):
    """kind の略記を、導出と確定の変換定義・ID の型に広げる。"""
    nodes = []
    for raw in config["nodes"]:
        node = dict(raw)
        transforms, ids = list(node.get("transforms", [])), list(node.get("ids", []))
        kind = node.get("kind")
        if kind:
            transforms = [f"{kind}-derived"] + transforms + [kind]
            ids = [f"DOC-{kind}.*", f"DRV-document-{kind}.*"] + ids
        node["transforms"], node["ids"] = transforms, ids
        nodes.append(node)
    return nodes


def build(workspace, config, lineage_text):
    transforms, views, heads = read_transforms(workspace), read_views(workspace), read_lineage(lineage_text)
    nodes = expand_nodes(config)
    warnings = []
    owner_of_transform = {}
    for node in nodes:
        for identifier in node["transforms"]:
            if identifier not in transforms:
                warnings.append(f"設定の変換定義「{identifier}」（{node['name']}）がワークスペースにありません")
            owner_of_transform[identifier] = node["id"]
    models = {item["id"]: item for item in config.get("models", [])}
    renames = {item["id"]: item for item in config.get("renames", [])}
    intermediates = {item["transform"] for item in list(models.values()) + list(renames.values())}
    ignored = set(config.get("ignore", []))
    for identifier in sorted(set(transforms) - set(owner_of_transform) - intermediates - ignored):
        warnings.append(f"変換定義「{identifier}」は、どの箱にも入っていません（design-map.json の nodes か ignore に足します）")
    for identifier in sorted((intermediates | ignored) - set(transforms)):
        warnings.append(f"設定の変換定義「{identifier}」がワークスペースにありません")

    def owners(pattern):
        """ID の型を作る箱（無ければ空）。"""
        return [node["id"] for node in nodes if any(fnmatch.fnmatchcase(pattern, own) for own in node["ids"])]

    def is_input(pattern):
        return any(fnmatch.fnmatchcase(pattern, own) for own in config.get("inputs", []))

    def sources_of_rename(rename):
        found = set()
        for view in transforms.get(rename["transform"], {}).get("views", []):
            for pattern in views.get(view, []):
                found.update(owners(pattern))
        return found

    reads = {node["id"]: {kind: set() for kind in TYPES} for node in nodes}
    for node in nodes:
        mine = reads[node["id"]]
        for kind, sources in node.get("reads", {}).items():
            mine[kind].update(sources)
        for identifier in node["transforms"]:
            transform = transforms.get(identifier)
            if not transform:
                continue
            for view in transform["views"]:
                if view not in views:
                    warnings.append(f"変換定義「{identifier}」が読むビュー「{view}」が見つかりません")
                    continue
                for pattern in views[view]:
                    if pattern in models:
                        model = models[pattern]
                        parts = dict(config.get("modelParts", {}), **model.get("parts", {}))
                        mine["m"].update(parts[head] for head in heads.get(identifier, ()) if head in parts)
                        mine["c"].update(model.get("carries", []))
                    elif pattern in renames:
                        mine["r"].update(sources_of_rename(renames[pattern]))
                    elif is_input(pattern):
                        continue
                    else:
                        found = owners(pattern)
                        if not found:
                            warnings.append(f"変換定義「{identifier}」のビュー「{view}」が選ぶ「{pattern}」を作る箱が分かりません")
                        mine["c"].update(found)
            for dependency in transform["dependsOn"]:
                if dependency in owner_of_transform:
                    mine["o"].add(owner_of_transform[dependency])
                elif dependency not in transforms:
                    warnings.append(f"変換定義「{identifier}」の依存先「{dependency}」がありません")
    for node in nodes:  # 自分自身を外し、強い種類だけを残す
        mine, seen = reads[node["id"]], {node["id"]}
        for kind in TYPES:
            mine[kind] -= seen
            seen |= mine[kind]

    # 作る順（依存の深さ）
    depth = {}

    def level(identifier, trail=()):
        if identifier not in depth:
            below = [d for d in transforms[identifier]["dependsOn"] if d in transforms and d not in trail]
            depth[identifier] = 1 + max((level(d, trail + (identifier,)) for d in below), default=-1)
        return depth[identifier]

    for identifier in transforms:
        level(identifier)

    column_index = {column["id"]: index for index, column in enumerate(config["columns"])}
    rows_used, out_nodes = {}, []
    for order, node in enumerate(nodes):
        own = [t for t in node["transforms"] if t in transforms]
        own.sort(key=lambda t: depth[t])
        slot = (node["column"], node["lane"])
        row = rows_used.get(slot, 0)
        rows_used[slot] = row + 1
        if node.get("input") and not (workspace / node["input"]).exists():
            warnings.append(f"「{node['name']}」の人の入力「{node['input']}」がありません")
        out_nodes.append({
            "id": node["id"], "name": node["name"], "col": column_index[node["column"]], "lane": node["lane"], "row": row,
            "out": node.get("place") or describe_outputs(workspace, [transforms[t] for t in own]),
            "by": describe_transforms(own) if own else "人が書く",
            "input": node.get("input", ""),
            "level": max((depth[t] for t in own), default=-1), "order": order,
        })

    by_id = {node["id"]: node for node in out_nodes}
    matrix_columns = [column_index[c] for c in config.get("matrixColumns", [])]
    matrix_rows = sorted((n for n in out_nodes if n["col"] in matrix_columns), key=lambda n: (n["col"], n["level"], n["order"]))
    first = [n["id"] for n in out_nodes if n["col"] == 0]
    out_reads = {key: {kind: sort_ids(value[kind], by_id) for kind in TYPES if value[kind]} for key, value in reads.items()}
    data = {
        "title": config["title"], "select": config.get("select"),
        "cols": [{"label": c["label"], "sub": c.get("sub", "")} for c in config["columns"]],
        "lanes": config["lanes"], "nodes": out_nodes, "reads": out_reads,
        "matrix": {"rows": [n["id"] for n in matrix_rows], "cols": first + [n["id"] for n in matrix_rows]},
        "notes": config.get("notes", []), "footnotes": config.get("footnotes", []), "warnings": warnings,
    }
    data["stats"] = statistics(workspace, config, transforms, out_nodes, out_reads, matrix_rows, ignored, renames)
    return data


def sort_ids(identifiers, by_id):
    return sorted(identifiers, key=lambda i: (by_id[i]["col"], by_id[i]["order"]))


def describe_transforms(identifiers):
    if len(identifiers) > 4:
        return f"{identifiers[0]} から {identifiers[-1]} までの {len(identifiers)} 本"
    return " → ".join(identifiers)


def describe_outputs(workspace, transforms):
    """箱の確定物（final/ に書くものを優先）。繰り返しの出力は、いまある本数を添える。"""
    paths = []
    for transform in transforms:
        if transform["output"] and "{file}" not in transform["output"]:
            paths.append(transform["output"])
        paths.extend(transform["finalPaths"])
    final = [p for p in paths if "/final/" in p]
    shown = []
    for path in sorted(set(final or paths[-1:])):
        pattern = re.sub(r"\{[^}]+\}", "*", path)
        if "*" in pattern:
            count = len(list(workspace.glob(pattern)))
            shown.append(f"{pattern}（{count}）")
        else:
            shown.append(pattern)
    return "、".join(shown)


def statistics(workspace, config, transforms, nodes, reads, matrix_rows, ignored, renames):
    by_id = {node["id"]: node for node in nodes}
    design = {node["id"] for node in matrix_rows}
    edges = [(source, target, kind) for target, groups in reads.items() for kind, sources in groups.items() for source in sources]
    name = lambda identifier: by_id[identifier]["name"]
    arrow = lambda source, target: f"{name(target)} ← {name(source)}"
    stats = []

    inside = [e for e in edges if e[0] in design and e[1] in design]
    counts = {kind: sum(1 for e in inside if e[2] == kind) for kind in TYPES}
    stats.append(["基本設計と詳細設計の間の線",
                  f"内容を読む {counts['c']} 本、補正後の業務モデル経由 {counts['m']} 本、名前の付け替えだけ {counts['r']} 本、順序だけ {counts['o']} 本"])

    readers = {}
    for source, target, kind in inside:
        if kind in ("c", "m"):
            readers.setdefault(source, set()).add(target)
    ranking = sorted(readers.items(), key=lambda item: (-len(item[1]), by_id[item[0]]["order"]))[:3]
    if ranking:
        stats.append(["多く読まれている設計書", "、".join(f"{name(i)}（{len(r)} 種から）" for i, r in ranking)])

    strong = {(s, t) for s, t, kind in edges if kind in ("c", "m")}
    mutual = sorted({tuple(sorted((s, t), key=lambda i: by_id[i]["order"])) for s, t in strong if (t, s) in strong})
    stats.append(["互いに読み合っている組", "、".join(f"{name(a)} ⇄ {name(b)}" for a, b in mutual) or "ありません"])

    skipping = [(s, t) for s, t, kind in edges if kind == "c" and by_id[s]["col"] >= 1 and by_id[t]["col"] - by_id[s]["col"] >= 2]
    skipping.sort(key=lambda e: (by_id[e[1]]["order"], by_id[e[0]]["order"]))
    stats.append([f"工程を 1 つ飛ばして内容を読む線（{len(skipping)} 本）", "、".join(arrow(s, t) for s, t in skipping) or "ありません"])

    backward = [(s, t) for s, t, kind in edges if kind in ("c", "m") and by_id[t]["col"] < by_id[s]["col"]]
    stats.append([f"後ろの工程を読む線（{len(backward)} 本）", "、".join(arrow(s, t) for s, t in backward) or "ありません"])

    tables = []
    for rename in renames.values():
        output = transforms.get(rename["transform"], {}).get("output", "")
        path = workspace / output
        if not output or not path.exists():
            continue
        text = path.read_text(encoding="utf-8")
        pairs = re.findall(r"^\s*- before:[ \t]*(.*)\n\s+after:[ \t]*(.*)$", text, re.M)
        changed = sum(1 for before, after in pairs if unquote(before) != unquote(after))
        tables.append(f"{rename.get('label', rename['transform'])}（{len(pairs)} 件のうち付け替え {changed} 件）")
    if tables:
        stats.append([f"名前の付け替え表（{len(tables)} 枚）", "、".join(tables)])

    order_only = sum(1 for e in edges if e[2] == "o")
    stats.append(["順序だけの線（全体）", f"{order_only} 本"])
    stats.append(["図に入れていない変換定義", f"{len(ignored)} 本（入力の検査・全体の検査・出力の計画: {'、'.join(sorted(ignored))}）"])
    return stats


def main():
    parser = argparse.ArgumentParser(description="設計書の依存マップ（HTML）を作り直す")
    parser.add_argument("--workspace", type=Path, default=SAMPLE, help="読むワークスペース（既定はこのサンプル）")
    parser.add_argument("--out", type=Path, help="書き出す HTML（既定はワークスペースの docs/design-dependency-map.html）")
    parser.add_argument("--check", action="store_true", help="書かずに、作り直した結果がいまのファイルと同じかを確かめる（違えば終了コード 1）")
    parser.add_argument("--lineage", type=Path, help="generate --lineage の出力ファイル（渡すと CLI を呼ばない）")
    parser.add_argument("--config", type=Path, default=HERE / "design-map.json", help="箱の並べ方の設定")
    parser.add_argument("--template", type=Path, default=HERE / "design-map.template.html", help="ページのひな形")
    parser.add_argument("--fragment", action="store_true", help="html・head・body の枠を付けずに書く（枠を足す側へ渡すとき）")
    arguments = parser.parse_args()

    workspace = arguments.workspace.resolve()
    out = (arguments.out or workspace / "docs" / "design-dependency-map.html").resolve()
    config = json.loads(arguments.config.read_text(encoding="utf-8"))
    if arguments.lineage:
        lineage_text = arguments.lineage.read_text(encoding="utf-8")
    else:
        lineage_text = run_lineage(workspace, REPO / "scratchpad" / "design-map" / "lineage.yaml")
    data = build(workspace, config, lineage_text)
    try:  # 置き場所で中身が変わらないよう、リポジトリの外のコピーは名前だけにする
        data["workspace"] = workspace.relative_to(REPO).as_posix()
    except ValueError:
        data["workspace"] = workspace.name

    template = arguments.template.read_text(encoding="utf-8")
    if "__DATA__" not in template or "__TITLE__" not in template or BODY_MARK not in template:
        raise SystemExit(f"ひな形に __DATA__・__TITLE__・{BODY_MARK} がありません")
    payload = json.dumps(data, ensure_ascii=False, separators=(",", ":")).replace("</", "<\\/")
    page = template.replace("__TITLE__", html.escape(data["title"])).replace("__DATA__", payload)
    head, body = page.split(BODY_MARK, 1)
    if arguments.fragment:
        page = head + body
    else:  # そのままブラウザで開ける 1 枚の文書にする
        page = ('<!doctype html>\n<html lang="ja">\n<head>\n<meta charset="utf-8">\n'
                '<meta name="viewport" content="width=device-width, initial-scale=1">\n'
                + head + "</head>\n<body>" + body + "</body>\n</html>\n")
    for warning in data["warnings"]:
        print(f"注意: {warning}", file=sys.stderr)
    edge_count = sum(len(sources) for groups in data["reads"].values() for sources in groups.values())
    if arguments.check:
        current = out.read_text(encoding="utf-8") if out.exists() else None
        if current != page:
            print(f"最新ではありません: {out}（--check を外して作り直し、コミットします）", file=sys.stderr)
            raise SystemExit(1)
        print(f"最新です: {out}（箱 {len(data['nodes'])}、線 {edge_count}）")
        return
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(page, encoding="utf-8", newline="\n")
    print(f"書きました: {out}（箱 {len(data['nodes'])}、線 {edge_count}）")


if __name__ == "__main__":
    main()
