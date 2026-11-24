#!/usr/bin/env python3
"""設計書の依存マップと、ファイル単位の生成グラフ（HTML）を作り直す。

1 枚目（docs/design-dependency-map.html）: 設計書の種類ごとに、ほかのどの設計書から作られているか。
    線は 4 種類: 内容を読む・補正後の業務モデル経由・名前の付け替えだけ・順序だけ。
2 枚目（docs/design-file-graph.html）: 変換定義 1 本の出力と人の入力を箱にして、どのファイルを読んで、どの順に作られるか。

使い方（リポジトリのどこからでも。Windows は py -3）:
    python3 samples/todos-sysdev/scripts/design-map.py            # 2 枚とも作り直す（Git に入れる）
    python3 samples/todos-sysdev/scripts/design-map.py --check    # 作り直した結果が、いまのファイルと同じかを確かめる（違えば 1）
    python3 samples/todos-sysdev/scripts/design-map.py --workspace 作業用コピー --out 場所.html --files-out 場所.html

同じ入力からは同じ HTML になる（日時を入れない）。Git の差分が、依存の変化になる。

読むもの:
    - .yde/transforms/**/*.yaml   変換定義の dependsOn と、読むビュー（$view）
    - .yde/views/**/*.yaml        ビューが選ぶスキーマ（schemaIds）と ID の型（rows の idPattern・match）
    - .yde/schemas/**/*.yaml      スキーマが当たるファイルの型（match。2 枚目）
    - design/**/*.yaml            いまあるファイルの ID（2 枚目。ビューが読むファイルを決める）
    - YDE CLI の generate --lineage  変換定義が業務モデルのどの部分を読むか（--lineage でファイルを渡せば CLI を呼ばない）
    - scripts/design-map.json     箱の並べ方と、どの変換定義がどの箱を作るか（人が書く）
    - scripts/design-map.template.html・design-files.template.html  ページのひな形

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
            "hasInput": bool(re.search(r"^input:", text, re.M)),
        }
        output = transforms[identifier]["output"]
        if output and "{file}" not in output:
            produces = [output]
        else:  # 出力先を中で組み立てる定義（{file}.yaml）は、書かれている場所から拾う
            found = re.findall(r"(design/[A-Za-z0-9_./{}-]+?)(?:\.yaml)?(?=['\"\s]|$)", text, re.M)
            produces = sorted({f"{path}.yaml" for path in found})
        transforms[identifier]["produces"] = produces
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
        schema_ids = re.search(r"schemaIds:\s*\[([^\]]*)\]", rows)
        if schema_ids:
            schemas = [unquote(item) for item in schema_ids.group(1).split(",") if item.strip()]
        else:
            block = re.search(r"schemaIds:[ \t]*\n((?:\s+-\s+\S.*\n?)+)", rows)
            schemas = [unquote(item) for item in re.findall(r"-\s+(\S.*)", block.group(1))] if block else []
        views[identifier] = patterns
        VIEW_SCHEMAS[identifier] = schemas
    return views


VIEW_SCHEMAS = {}  # ビューが選ぶスキーマ（rows の schemaIds）


def read_schemas(workspace):
    """スキーマの ID と、当たるファイルの型（match）。"""
    schemas = {}
    for path in sorted((workspace / ".yde" / "schemas").rglob("*.yaml")):
        text = path.read_text(encoding="utf-8")
        identifier, match = top_value(text, "id"), top_value(text, "match")
        if identifier and match:
            schemas[identifier] = match
    return schemas


def glob_regex(pattern):
    """ファイルの型を正規表現にする（** は階層をまたぎ、* と {名前} は 1 階層の中）。"""
    out = ""
    for part in re.split(r"(\*\*|\*|\{[^}]+\})", pattern):
        if part == "**":
            out += ".*"
        elif part == "*" or (part.startswith("{") and part.endswith("}")):
            out += "[^/]*"
        else:
            out += re.escape(part)
    return re.compile(out + r"\Z")


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


def build_files(workspace, config, lineage_text):
    """ファイルの種類ごとのグラフ: 箱は変換定義 1 本の出力・人の入力・業務モデル、線は「読む」と「順序だけ」。"""
    transforms, views, heads = read_transforms(workspace), read_views(workspace), read_lineage(lineage_text)
    schemas = {identifier: glob_regex(match) for identifier, match in read_schemas(workspace).items()}
    settings = config.get("files", {})
    warnings = []

    depth = {}

    def level(identifier, trail=()):
        if identifier not in depth:
            below = [d for d in transforms[identifier]["dependsOn"] if d in transforms and d not in trail]
            depth[identifier] = 1 + max((level(d, trail + (identifier,)) for d in below), default=-1)
        return depth[identifier]

    for identifier in transforms:
        level(identifier)

    def closure(identifier, seen=None):
        seen = set() if seen is None else seen
        for dependency in transforms[identifier]["dependsOn"]:
            if dependency in transforms and dependency not in seen:
                seen.add(dependency)
                closure(dependency, seen)
        return seen

    # いまあるファイルを、作る変換定義・当たるスキーマ・中の ID で引けるようにする
    producers = [(glob_regex(pattern), identifier) for identifier, t in transforms.items() for pattern in t["produces"]]
    files = []
    for path in sorted((workspace / "design").rglob("*.yaml")):
        relative = path.relative_to(workspace).as_posix()
        text = path.read_text(encoding="utf-8")
        made_by = sorted({identifier for regex, identifier in producers if regex.match(relative)})
        is_input = "/inputs/" in relative
        if not made_by and not is_input:
            warnings.append(f"「{relative}」は、どの変換定義の出力でもありません")
            continue
        if len(made_by) > 1:
            warnings.append(f"「{relative}」を作る変換定義が 1 つに決まりません（{'、'.join(made_by)}）")
        files.append({
            "path": relative, "node": f"in:{relative}" if is_input else f"t:{made_by[0]}", "input": is_input,
            "schemas": {identifier for identifier, regex in schemas.items() if regex.match(relative)},
            "top": {unquote(v) for v in re.findall(r"^(?:id|inputId|designId|documentId):[ \t]*(\S+)", text, re.M)},
            "ids": {unquote(v) for v in re.findall(r"^\s*(?:-\s+)?(?:id|inputId|designId|documentId):[ \t]*(\S+)", text, re.M)},
        })

    def sources_of(view):
        """ビューが読むファイルの箱（スキーマと ID の型で絞る）。"""
        patterns, wanted = views[view], set(VIEW_SCHEMAS.get(view, []))
        matches = lambda ids: any(fnmatch.fnmatchcase(i, p) for i in ids for p in patterns)
        found = set()
        if wanted:
            candidates = [f for f in files if f["schemas"] & wanted]
            found = {f["node"] for f in candidates if f["input"] or matches(f["ids"])}
            if not found:
                found = {f["node"] for f in candidates}
        else:
            found = {f["node"] for f in files if matches(f["top"])}
        if not found:
            for pattern in patterns:
                for owner in settings.get("idOwners", {}).get(pattern, []):
                    if owner.startswith("in:"):
                        found.update(f["node"] for f in files if f["input"] and glob_regex(owner[3:]).match(f["path"]))
                    else:
                        found.add(f"t:{owner}")
        return found

    nodes, edges = {}, {}
    for identifier, transform in transforms.items():
        mine = [f["path"] for f in files if f["node"] == f"t:{identifier}"]
        pattern = (transform["produces"] or [transform["output"]])[0]
        shown = re.sub(r"\{[^}]+\}", "*", pattern)
        parts = shown.split("/")
        kind = next((k for k in ("derived", "composed", "final") if k in parts), "other")
        rest = [part for part in parts[3:] if part != "documents"] or parts[-1:]  # design/段/役割/ より後ろ
        if len(rest) >= 2 and rest[-1] == f"{rest[-2]}.yaml":  # 種類/種類.yaml は、種類.yaml と書く
            rest = rest[:-2] + rest[-1:]
        short = "/".join(rest[:-1]) + "/*" if "*" in rest[-1] else "/".join(rest)
        nodes[f"t:{identifier}"] = {
            "id": f"t:{identifier}", "label": short, "tag": "/".join(parts[1:3]), "kind": kind,
            "path": shown + (f"（{len(mine)}）" if "*" in shown else ""), "by": identifier, "name": transform["name"],
            "level": depth[identifier],
        }
        if not mine:
            warnings.append(f"変換定義「{identifier}」の出力（{shown}）が、いまはありません")
        reads = set()
        if not transform["hasInput"] and heads.get(identifier):
            reads.add("bm")
        for view in transform["views"]:
            if view not in views:
                warnings.append(f"変換定義「{identifier}」が読むビュー「{view}」が見つかりません")
                continue
            found = sources_of(view) - {f"t:{identifier}"}
            if not found and not (sources_of(view) & {f"t:{identifier}"}):
                warnings.append(f"変換定義「{identifier}」のビュー「{view}」（{'、'.join(views[view])}）が読むファイルが分かりません")
            reads |= found
        allowed = closure(identifier)
        for source in sorted(reads):
            if source.startswith("t:") and source[2:] not in allowed:
                warnings.append(f"変換定義「{identifier}」は「{source[2:]}」の出力を読みますが、依存（dependsOn）をたどっても届きません")
        for source in reads:
            edges[(source, f"t:{identifier}")] = "c"
        for dependency in transform["dependsOn"]:
            if dependency in transforms and (f"t:{dependency}", f"t:{identifier}") not in edges:
                edges[(f"t:{dependency}", f"t:{identifier}")] = "o"

    nodes["bm"] = {"id": "bm", "label": "業務モデル", "tag": ".yde/…/business", "kind": "bm",
                   "path": ".yde/schemas/business/、.yde/views/business/", "by": "", "name": "人が書く（samples/todos の写し）"}
    for f in files:
        if f["input"]:
            parts = f["path"].split("/")
            nodes[f["node"]] = {"id": f["node"], "label": "/".join(parts[-2:]), "tag": "/".join(parts[1:3]), "kind": "input",
                                "path": f["path"], "by": "", "name": "人が書く入力"}
    for identifier, node in nodes.items():  # 人が書くものは、最初に読まれる段の 1 つ上に置く
        if "level" not in node:
            readers = [nodes[t]["level"] for (s, t) in edges if s == identifier]
            node["level"] = (min(readers) if readers else 0) - 1
            if not readers and identifier != "bm":
                warnings.append(f"人の入力「{node['path']}」を読む変換定義がありません")
    lowest = min(node["level"] for node in nodes.values())
    for node in nodes.values():
        node["level"] -= lowest

    ordered = sorted(nodes.values(), key=lambda n: (n["level"], n["id"]))
    edge_list = sorted([s, t, kind] for (s, t), kind in edges.items())

    def ancestors(identifier):
        """線（読む・順序だけ）を上へたどって届く箱すべて。"""
        seen, waiting = set(), [identifier]
        while waiting:
            current = waiting.pop()
            for s, t, kind in edge_list:
                if t == current and s not in seen:
                    seen.add(s)
                    waiting.append(s)
        return seen

    stats = []
    name = lambda i: f"{nodes[i]['tag']}/{nodes[i]['label']}"
    for model in config.get("models", []):
        identifier = f"t:{model['transform']}"
        if identifier not in nodes:
            continue
        direct = sorted(s for s, t, kind in edge_list if t == identifier and kind == "c")
        above = sorted(a for a in ancestors(identifier) if nodes[a]["kind"] == "final")
        stats.append([f"{name(identifier)} が直接読むファイル", "、".join(name(i) for i in direct) or "ありません"])
        stats.append([f"{name(identifier)} より前に確定している必要があるもの（final）", "、".join(name(i) for i in above) or "ありません"])
    counts = {kind: sum(1 for n in nodes.values() if n["kind"] == kind) for kind in ("bm", "input", "derived", "composed", "final", "other")}
    stats.append(["箱の数", f"人の入力 {counts['input']}、導出（derived）{counts['derived']}、合成・検査（composed）{counts['composed']}、"
                  f"確定（final）{counts['final']}" + (f"、そのほか {counts['other']}" if counts["other"] else "")])
    stats.append(["線の数", f"読む {sum(1 for e in edge_list if e[2] == 'c')} 本、順序だけ {sum(1 for e in edge_list if e[2] == 'o')} 本"])
    select = settings.get("select")
    return {"title": settings.get("title", config["title"]), "select": select if select in nodes else None,
            "nodes": ordered, "edges": edge_list, "stats": stats, "notes": settings.get("notes", []), "warnings": warnings}


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


def render(template_path, data, fragment):
    template = template_path.read_text(encoding="utf-8")
    if "__DATA__" not in template or "__TITLE__" not in template or BODY_MARK not in template:
        raise SystemExit(f"ひな形 {template_path.name} に __DATA__・__TITLE__・{BODY_MARK} がありません")
    payload = json.dumps(data, ensure_ascii=False, separators=(",", ":")).replace("</", "<\\/")
    page = template.replace("__TITLE__", html.escape(data["title"])).replace("__DATA__", payload)
    head, body = page.split(BODY_MARK, 1)
    if fragment:
        return head + body
    # そのままブラウザで開ける 1 枚の文書にする
    return ('<!doctype html>\n<html lang="ja">\n<head>\n<meta charset="utf-8">\n'
            '<meta name="viewport" content="width=device-width, initial-scale=1">\n'
            + head + "</head>\n<body>" + body + "</body>\n</html>\n")


def main():
    parser = argparse.ArgumentParser(description="設計書の依存マップと、ファイル単位のグラフ（HTML）を作り直す")
    parser.add_argument("--workspace", type=Path, default=SAMPLE, help="読むワークスペース（既定はこのサンプル）")
    parser.add_argument("--out", type=Path, help="設計書の依存マップ（既定はワークスペースの docs/design-dependency-map.html）")
    parser.add_argument("--files-out", type=Path, help="ファイル単位のグラフ（既定はワークスペースの docs/design-file-graph.html）")
    parser.add_argument("--check", action="store_true", help="書かずに、作り直した結果がいまのファイルと同じかを確かめる（違えば終了コード 1）")
    parser.add_argument("--lineage", type=Path, help="generate --lineage の出力ファイル（渡すと CLI を呼ばない）")
    parser.add_argument("--config", type=Path, default=HERE / "design-map.json", help="箱の並べ方の設定")
    parser.add_argument("--template", type=Path, default=HERE / "design-map.template.html", help="設計書の依存マップのひな形")
    parser.add_argument("--files-template", type=Path, default=HERE / "design-files.template.html", help="ファイル単位のグラフのひな形")
    parser.add_argument("--fragment", action="store_true", help="html・head・body の枠を付けずに書く（枠を足す側へ渡すとき）")
    arguments = parser.parse_args()

    workspace = arguments.workspace.resolve()
    config = json.loads(arguments.config.read_text(encoding="utf-8"))
    if arguments.lineage:
        lineage_text = arguments.lineage.read_text(encoding="utf-8")
    else:
        lineage_text = run_lineage(workspace, REPO / "scratchpad" / "design-map" / "lineage.yaml")
    try:  # 置き場所で中身が変わらないよう、リポジトリの外のコピーは名前だけにする
        place = workspace.relative_to(REPO).as_posix()
    except ValueError:
        place = workspace.name

    pages = []
    data = build(workspace, config, lineage_text)
    data["workspace"] = place
    edge_count = sum(len(sources) for groups in data["reads"].values() for sources in groups.values())
    pages.append((arguments.out or workspace / "docs" / "design-dependency-map.html", arguments.template, data,
                  f"箱 {len(data['nodes'])}、線 {edge_count}"))
    data = build_files(workspace, config, lineage_text)
    data["workspace"] = place
    pages.append((arguments.files_out or workspace / "docs" / "design-file-graph.html", arguments.files_template, data,
                  f"箱 {len(data['nodes'])}、線 {len(data['edges'])}"))

    stale = False
    for out, template, data, summary in pages:
        out = out.resolve()
        for warning in data["warnings"]:
            print(f"注意（{out.name}）: {warning}", file=sys.stderr)
        page = render(template, data, arguments.fragment)
        if arguments.check:
            if (out.read_text(encoding="utf-8") if out.exists() else None) != page:
                print(f"最新ではありません: {out}（--check を外して作り直し、コミットします）", file=sys.stderr)
                stale = True
            else:
                print(f"最新です: {out}（{summary}）")
            continue
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(page, encoding="utf-8", newline="\n")
        print(f"書きました: {out}（{summary}）")
    if stale:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
