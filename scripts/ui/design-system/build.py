#!/usr/bin/env python3
"""Write the Queenswood design system's files from components/ui.

Reads components/ui/src/tokens.css for every colour and font stack, the
bundle vite built from entry.js, and the files beside this script, and
writes the tree a Design System artifact holds under `project/`. Nothing
is written back to components/ui. See justfiles/ui.just.
"""

import datetime
import json
import os
import re
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
UI = os.path.join(ROOT, "components/ui/src")

NAMESPACE = "Queenswood"
THEMES = [{"id": "light", "name": "Light"}, {"id": "dark", "name": "Dark"}]

PRELUDE = """/* Queenswood — component styles for @queenswood/ui.
   Colours come from tokens.css (generated from tokens.json). This sheet adds
   what a token cannot hold: the colour scheme each theme selects, which the
   components' own light-dark() values follow, and the three stack names the
   components read. */
:root { color-scheme: light dark; %s }
[data-theme="light"] { color-scheme: light; }
[data-theme="dark"] { color-scheme: dark; }
"""


def fail(message):
    print(message, file=sys.stderr)
    sys.exit(1)


def read(*parts):
    with open(os.path.join(*parts)) as f:
        return f.read()


def load(name):
    return json.loads(read(HERE, name))


def git(*args):
    return subprocess.run(["git", "-C", ROOT, *args], check=True, capture_output=True,
                          text=True).stdout.strip()


# ---------------------------------------------------------------- tokens.css

def split_args(text):
    """The top-level, comma-separated arguments of a CSS function's body."""
    args, depth, start = [], 0, 0
    for i, ch in enumerate(text):
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        elif ch == "," and depth == 0:
            args.append(text[start:i].strip())
            start = i + 1
    args.append(text[start:].strip())
    return args


def token_value(css):
    """One side of a declaration as tokens.json spells it."""
    alias = re.fullmatch(r"var\(--([A-Za-z0-9_-]+)\)", css)
    if alias:
        return "{%s}" % alias.group(1)
    if re.fullmatch(r"#[0-9A-Fa-f]{3,8}", css):
        return css.lower()
    if "var(" in css or "color-mix(" in css or "calc(" in css:
        fail(f"tokens.css: tokens.json cannot hold `{css}`")
    return css


def parse_tokens_css():
    """Every custom property on :root, in order, as (colours, font stacks)."""
    css = re.sub(r"/\*.*?\*/", "", read(UI, "tokens.css"), flags=re.S)
    root = re.search(r":root\s*\{(.*?)\n\}", css, re.S)
    if not root:
        fail("tokens.css: no :root block")
    colours, stacks = [], {}
    for name, raw in re.findall(r"--([A-Za-z0-9_-]+)\s*:\s*([^;]+);", root.group(1)):
        value = re.sub(r"\s+", " ", raw).strip().replace("( ", "(").replace(" )", ")")
        if value[0] in "'\"":
            stacks[name] = value
            continue
        both = re.fullmatch(r"light-dark\((.*)\)", value)
        if both:
            sides = split_args(both.group(1))
            if len(sides) != 2:
                fail(f"tokens.css: --{name} is not light-dark(a, b)")
            value = dict(zip(("light", "dark"), map(token_value, sides)))
        else:
            value = token_value(value)
        colours.append((name, value))
    return colours, stacks


def tokens(order):
    colours, stacks = parse_tokens_css()
    notes = load("colour-notes.json")
    declared = [name for name, _ in colours]
    missing = [n for n in declared if n not in notes]
    stale = [n for n in notes if n not in declared]
    if missing or stale:
        fail("colour-notes.json is out of step with tokens.css."
             + (f"\n  no note for: {', '.join(missing)}" if missing else "")
             + (f"\n  no longer declared: {', '.join(stale)}" if stale else ""))
    scales = load("scales.json")
    families = {name: stack for name, stack in stacks.items()}
    for group in scales["type"]:
        if group["family"] not in families:
            fail(f"scales.json: type group {group['name']} names --{group['family']}, "
                 "which tokens.css does not declare")
    remote = git("remote", "get-url", "origin")
    repo = re.sub(r"\.git$", "", re.split(r"github\.com[:/]", remote)[-1])
    ref = f"{git('rev-parse', '--abbrev-ref', 'HEAD')}@{git('rev-parse', '--short', 'HEAD')}"
    doc = {
        "name": NAMESPACE,
        "version": 1,
        "color": {"themes": THEMES,
                  "tokens": [{"name": n, "value": v, "usage": notes[n]} for n, v in colours]},
        "type": {
            "fonts": [{"family": f["family"], "file": "fonts/" + f["file"], "weight": f["weight"],
                       "style": f["style"]} for f in scales["fonts"]["files"]],
            "families": families,
            "groups": scales["type"],
        },
    }
    for family in ("spacing", "radius", "shadow", "size", "zIndex"):
        doc[family] = {"tokens": scales[family]}
    doc["meta"] = {
        "source": "github",
        "repo": repo,
        "ref": ref,
        "package": "components/ui",
        "paths": {
            "tokens": ["components/ui/src/tokens.css"],
            "fonts": [scales["fonts"]["from"]],
            "assets": ["site/assets/logo.svg", "bases/console/public/favicon.svg", "site/assets/*.png"],
            "docs": ["scripts/ui/design-system/brand-book.md"],
        },
        "components": {n: f"components/ui/src/{n}.svelte" for n in order},
        "synced": datetime.date.today().isoformat(),
    }
    names = declared + [t["name"] for f in ("spacing", "radius", "shadow", "size", "zIndex")
                        for t in scales[f]]
    twice = sorted({n for n in names if names.count(n) > 1})
    if twice:
        fail(f"token names used twice: {', '.join(twice)}")
    return doc, families, scales


# ---------------------------------------------------------------- components

def props_interface(dts, name):
    found = (re.search(r"^export interface %sProps[^\n{]*\{[^\n]*\}$" % name, dts, re.M)
             or re.search(r"^export interface %sProps[^\n{]*\{\n.*?^\}" % name, dts, re.S | re.M))
    return found.group(0) if found else None


def source_notes(name):
    """The comment that opens a component's script, as its author wrote it."""
    found = re.search(r"<script>\s*/\*(.*?)\*/", read(UI, name + ".svelte"), re.S)
    if not found:
        return None
    lines = found.group(1).rstrip().split("\n")
    if lines[0].strip():
        lines[0] = "     " + lines[0].lstrip()
    indent = min((len(l) - len(l.lstrip()) for l in lines if l.strip()), default=0)
    return "\n".join(l[indent:] if l.strip() else "" for l in lines).strip("\n")


def preview_html(component, style):
    name = component["name"]
    return f"""<!-- @dsCard group="{component['group']}" height={component['height']} -->
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>{name} — preview</title>
<style>
{style}</style>
</head>
<body>
<div id="root"></div>
<script>
  // The demo is the Svelte source in this component's README, compiled into the bundle.
  {NAMESPACE}.mount({NAMESPACE}.previews.{name}, {{ target: document.getElementById("root") }});
</script>
</body>
</html>
"""


def component_readme(component, dts):
    name, members = component["name"], component.get("members", [])
    md = [f"# {name}", "", component["text"], ""]
    for n in [name] + members:
        iface = props_interface(dts, n)
        if iface is None:
            fail(f"index.d.ts declares no {n}Props")
        if iface.rstrip().endswith("{}"):
            md += [f"`{n}` takes no props.", ""]
        else:
            md += [f"## {n} props" if members else "## Props", "", "```ts", iface, "```", ""]
    md += ["## Usage", "", "The live preview is this Svelte 5 source, compiled.", "",
           "```svelte", read(HERE, "previews", name + ".svelte").strip(), "```", "",
           "From plain JavaScript, with the bundle loaded:", "", "```js",
           f"{NAMESPACE}.mount({NAMESPACE}.{name}, {{ target: el, props: {{ /* as above */ }} }});",
           "```", "",
           f"A snippet prop such as `children` takes `{NAMESPACE}.snippet(html)`; "
           f"`{NAMESPACE}.render(Component, el, {{ children: \"text\" }})` wraps a plain string for you.",
           ""]
    for n in [name] + members:
        notes = source_notes(n)
        if notes:
            md += [f"## Source notes: {n}" if members else "## Source notes", "", "```text", notes,
                   "```", ""]
    md += [f"Source: `components/ui/src/{name}.svelte`.", ""]
    return "\n".join(md)


# ---------------------------------------------------------------- main

def main():
    if len(sys.argv) != 2:
        fail("usage: build.py <out-dir>, holding bundle/bundle.js and bundle/bundle.css")
    out = os.path.abspath(sys.argv[1])
    project = os.path.join(out, "project")

    def write(path, text):
        target = os.path.join(project, path)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, "w") as f:
            f.write(text)

    components = load("components.json")
    order = [c["name"] for c in components]
    previews = sorted(f[:-len(".svelte")] for f in os.listdir(os.path.join(HERE, "previews")))
    if sorted(order) != previews:
        fail("components.json and previews/ name different components: "
             + ", ".join(sorted(set(order) ^ set(previews))))

    doc, families, scales = tokens(order)
    write("tokens.json", json.dumps(doc, indent=2, ensure_ascii=False) + "\n")

    dts = read(HERE, "index.d.ts")
    write("components/index.d.ts", dts)
    style = read(HERE, "preview.css")
    for component in components:
        name = component["name"]
        write(f"components/{name}/preview.html", preview_html(component, style))
        write(f"components/{name}/README.md", component_readme(component, dts))
    write("components/Cover/preview.html", read(HERE, "cover.html"))

    header = json.dumps({"format": 4, "namespace": NAMESPACE,
                         "components": [{"name": n} for n in order]}, separators=(",", ":"))
    js = read(out, "bundle", "bundle.js").replace("<!--", "\\x3C!--").replace("</script", "<\\/script")
    write("components/bundle.js", f"/* @ds-bundle: {header} */\n" + js)
    css = read(out, "bundle", "bundle.css")
    if "</style" in css.lower():
        fail("bundle.css holds a </style, which ends the element a consumer inlines it in")
    aliases = " ".join(f"--{name}: var(--font-{name});" for name in families)
    write("components/bundle.css", PRELUDE % aliases + css)

    write("README.md", read(HERE, "brand-book.md"))
    for group in sorted(os.listdir(os.path.join(HERE, "assets"))):
        write(f"assets/{group}/README.md", read(HERE, "assets", group, "README.md"))
    fonts = os.path.join(ROOT, scales["fonts"]["from"])
    for sub, files in (("fonts", [f["file"] for f in scales["fonts"]["files"]]),
                       ("licenses", scales["fonts"]["licences"])):
        os.makedirs(os.path.join(project, sub), exist_ok=True)
        for name in files:
            shutil.copy(os.path.join(fonts, name), os.path.join(project, sub, name))

    count = sum(len(files) for _, _, files in os.walk(project))
    print(f"{os.path.relpath(project)}: {len(doc['color']['tokens'])} colours, "
          f"{len(order)} components, {count} files")


if __name__ == "__main__":
    main()
