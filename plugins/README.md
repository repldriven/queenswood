# Tessl plugins

Queenswood's [Tessl](https://tessl.io) plugins. Each subdirectory is a
**self-contained plugin** — its own `.tessl-plugin/plugin.json`,
`tessl.json` (project link), `skills/`, and `evals/` — installable on
its own (`tessl install file:./plugins/<name>`).

They are split by **task-trigger**, not tidiness: a plugin is the unit
that gets loaded into an agent's context, so the goal is that any task
pulls only the one or two plugins it needs, and nothing else. Three
axes: *portability* — code hygiene that would apply to any Clojure
repo; the *framework* — Polylith mechanics, independent of how
Queenswood specifically uses them; and the *design* that's specific to
how Queenswood is built on top of that framework.

## The map

| Plugin | Reach for it when… | Status |
|--------|--------------------|--------|
| **[idioms](idioms/)** — `queenswood/idioms` | writing Queenswood Clojure on top of mono's idioms: which test form a case takes, what a brick's tests may require | **live** (rule: `idioms`) |
| **[framework](framework/)** — `queenswood/framework` | Queenswood's Polylith conventions on top of mono's: the aggregator bases, the `pin/` shims under `deps/` | **live** (rule: `framework`) |
| **[design](design/)** — `queenswood/design` | how the system is built, brick to topology: the processor pattern, CQRS split, changelog-as-outbox, transaction boundaries, system-as-data | **live** (rule: `design`) |
| **[workflow](workflow/)** — `queenswood/workflow` | committing, branching, PRs, the git hooks, mono's share of the tree | **live** (rule: `workflow`) |
| **[docs](docs/)** — `queenswood/docs` | writing a PRD in the product register, on top of mono's docs rule and its `check-docs` skill | **live** (rule: `docs`) |
| **security** — `queenswood/security` | secrets, auth, SAST, security review | planned |
| **[deployment](deployment/)** — `queenswood/deployment` | deploying / running the cluster (Helm, Tilt, kind, Crossplane) | **live** (rule: `deployment`) |

Decision rule when a new skill or rule wants a home: *would it hold in
any workspace built on mono → a mono recipe, distilled by mono's plugin
of the same name and imported here; would it help on any Clojure repo
but only this one has it → `idioms`; is it Polylith-the-tool, not
Queenswood-specific → `framework`; is it how Queenswood specifically is
built on top of Polylith → `design`.* Keep `design` whole (low-level
system wiring and high-level topology are one body of knowledge); split
it only if it ever bloats context.

## Two roots

mono's plugins — `mono/design`, `mono/framework`, `mono/idioms` and
`mono/workflow`, the last carrying the `sync-rules-from-docs` skill —
are laid down under `.mono/plugins/` by `just mono-import` at the sha
`deps/mono-dev/deps.edn` pins, and installed beside these by the same
recipes: `TESSL_PLUGIN_ROOTS` names both roots. `plugins/profiles`
names the plugins each profile links, as `<workspace>/<plugin>`, mono's
first so the general rule precedes the Queenswood specialisation.
`just tessl-plugins-install` installs every plugin under both roots and
lays the active profile down in `.tessl/RULES.md`;
`just tessl-plugins-check` reports an installed copy behind its source;
`just tessl-profile name=<profile>` switches.

## Toolchain

`tessl` is provided by the nix dev shell (`nix develop`, or automatic
via direnv). `.mcp.json` at the repo root wires the `tessl mcp start`
server for Claude Code (workspace-level; one server serves every
plugin). These plugins are distinct from `.claude/skills/` — Claude
Code's built-in skills — this is the Tessl-managed, evaluable set.

## Adding a plugin

`tessl init` re-syncs the *parent* project when run from a directory
already nested inside an initialized Tessl tree (which every
`plugins/<name>` is, once `plugins/idioms` exists) — it won't create a
fresh nested project. Skip it and scaffold directly with `tessl tile
new`, then hand-write `tessl.json` to match a sibling plugin's shape:

```bash
mkdir -p plugins/<name> && cd plugins/<name>
tessl tile new --name queenswood/<name> --path . --workspace queenswood \
  --rules <rule> --rule-description "…"        # scaffolds rules/<rule>.md
# …or --skill --skill-name <skill> --skill-description "…" for a
# triggered skill instead of a rule

cat > tessl.json <<'EOF'
{ "name": "queenswood/<name>", "mode": "vendored", "dependencies": {} }
EOF
```

Then register the new plugin as a dependency in the repo-root
`tessl.json` (alongside the existing entries), and run `tessl install`
from the repo root to link it in.

Run `tessl` commands from inside the plugin directory.
