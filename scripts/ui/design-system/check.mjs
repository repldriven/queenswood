// Render every preview the build wrote, in both themes, outside the page
// that will serve them: compile tokens.json to a stylesheet as that page
// does, load each preview with the bundle, and report a script error, an
// empty card, or content taller than the height components.json gives its card.
// Writes contact sheets to <out-dir>/check. See justfiles/ui.just.

import { chromium } from "playwright";
import fs from "node:fs";
import path from "node:path";

if (process.argv.length !== 3) {
  console.error("usage: check.mjs <out-dir>, as build.py wrote it");
  process.exit(1);
}
const out = path.resolve(process.argv[2]);
const project = path.join(out, "project");
const dir = path.join(out, "check");
fs.rmSync(dir, { recursive: true, force: true });
fs.mkdirSync(dir, { recursive: true });

const tokens = JSON.parse(fs.readFileSync(path.join(project, "tokens.json"), "utf8"));
const themes = tokens.color.themes.map((t) => t.id);
const FIXED = new Set(["Drawer", "Menu", "ToastHost", "Sidenav", "Cover"]);

function themed(value, theme) {
  const v = typeof value === "string" ? value : (value[theme] ?? value[themes[0]]);
  const alias = /^\{(.+)\}$/.exec(v);
  return alias ? `var(--${alias[1]})` : v;
}

function tokensCss() {
  const css = [];
  themes.forEach((theme, i) => {
    const rows = [];
    for (const family of ["color", "shadow"]) {
      for (const t of tokens[family].tokens) {
        if (i === 0 || typeof t.value !== "string") rows.push(`  --${t.name}: ${themed(t.value, theme)};`);
      }
    }
    const selector = i === 0 ? `:root, [data-theme="${theme}"]` : `[data-theme="${theme}"]`;
    css.push(`${selector} {\n${rows.join("\n")}\n}`);
  });
  const rows = ["spacing", "radius", "size", "zIndex"].flatMap((family) =>
    tokens[family].tokens.map((t) => `  --${t.name}: ${t.value};`),
  );
  for (const [key, stack] of Object.entries(tokens.type.families)) rows.push(`  --font-${key}: ${stack};`);
  css.push(`:root {\n${rows.join("\n")}\n}`);
  for (const f of tokens.type.fonts) {
    css.push(
      `@font-face { font-family: "${f.family}"; src: url("${project}/${f.file}") format("woff2"); ` +
        `font-weight: ${f.weight}; font-style: ${f.style}; font-display: block; }`,
    );
  }
  return css.join("\n") + "\n";
}

fs.writeFileSync(path.join(dir, "tokens.css"), tokensCss());
const head =
  `<link rel="stylesheet" href="${dir}/tokens.css">` +
  `<link rel="stylesheet" href="${project}/components/bundle.css">` +
  `<script src="${project}/components/bundle.js"></script>`;

const cards = fs
  .readdirSync(path.join(project, "components"), { withFileTypes: true })
  .filter((e) => e.isDirectory() && fs.existsSync(path.join(project, "components", e.name, "preview.html")))
  .map((e) => {
    const html = fs.readFileSync(path.join(project, "components", e.name, "preview.html"), "utf8");
    for (const theme of themes) {
      fs.writeFileSync(
        path.join(dir, `${e.name}.${theme}.html`),
        html.replace('<html lang="en">', `<html lang="en" data-theme="${theme}">`).replace("<head>", "<head>" + head),
      );
    }
    return { name: e.name, height: Number(/height=(\d+)/.exec(html)[1]) };
  });

const browser = await chromium.launch();
const findings = [];
for (const theme of themes) {
  const shots = [];
  for (const { name, height } of cards) {
    const page = await browser.newPage({ viewport: { width: 960, height } });
    const errors = [];
    page.on("pageerror", (e) => errors.push(String(e)));
    page.on("console", (m) => m.type() === "error" && errors.push(m.text()));
    await page.goto("file://" + path.join(dir, `${name}.${theme}.html`));
    await page.waitForTimeout(350);
    const seen = await page.evaluate(() => {
      const root = document.getElementById("root");
      const bottoms = [...(root?.querySelectorAll("*") ?? [])].map((e) => e.getBoundingClientRect().bottom);
      return { children: root?.childElementCount ?? -1, content: Math.ceil(Math.max(0, ...bottoms) + 20) };
    });
    const file = path.join(dir, `${name}.${theme}.png`);
    await page.screenshot({ path: file });
    await page.close();
    shots.push({ name, height, file });
    if (errors.length) findings.push({ fatal: true, text: `${name} ${theme}: ${errors.join(" | ")}` });
    if (seen.children === 0) findings.push({ fatal: true, text: `${name} ${theme}: rendered nothing` });
    if (!FIXED.has(name) && seen.content > height) {
      findings.push({ fatal: false, text: `${name} ${theme}: content is ${seen.content}px, card is ${height}px` });
    }
  }
  let sheet = [];
  let tall = 0;
  let n = 0;
  const flush = async () => {
    if (!sheet.length) return;
    const html =
      `<body style="margin:0;background:#888;font:12px monospace">` +
      sheet
        .map(
          (s) =>
            `<div style="background:#000;color:#fff;padding:2px 6px">${s.name} · ${s.height}</div>` +
            `<img src="file://${s.file}" style="display:block;width:960px">`,
        )
        .join("") +
      `</body>`;
    fs.writeFileSync(path.join(dir, "sheet.html"), html);
    const page = await browser.newPage({ viewport: { width: 960, height: 100 } });
    await page.goto("file://" + path.join(dir, "sheet.html"));
    await page.waitForTimeout(200);
    await page.screenshot({ path: path.join(dir, `sheet-${theme}-${String(n++).padStart(2, "0")}.png`), fullPage: true });
    await page.close();
    sheet = [];
    tall = 0;
  };
  for (const s of shots) {
    if (tall + s.height > 1500) await flush();
    sheet.push(s);
    tall += s.height + 18;
  }
  await flush();
}
await browser.close();

for (const f of findings) console.log(f.text);
const fatal = findings.filter((f) => f.fatal).length;
console.log(`${cards.length} cards in ${themes.length} themes, ${fatal} failing; sheets in ${path.relative(process.cwd(), dir)}`);
process.exit(fatal ? 1 : 0);
