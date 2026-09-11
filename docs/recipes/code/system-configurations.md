# System configurations

<!-- tessl-plugin: design -->

## Problem

You want to write or modify a system configuration that
assembles and runs a Queenswood system.

## Solution

We use a YAML (or EDN) file that declares which registered
components from each brick are wired together to form a running
system. A base loads the file via `env/config`, the result is
fed into donut.system, and `system/start` brings everything up
in dependency order.

### File layout

System configurations live alongside the brick or base they
correspond to. Conventionally:

```
bases/<base>/test-resources/<base>/application-test.yml
components/<brick>/test-resources/<brick>/application-test.yml
```

Loaded by classpath URL — e.g.
`classpath:monolith/application-test.yml`.

### Top-level shape

A configuration has a top-level `system:` key whose entries are
groups, each matching the keyword group passed to `defcomponents`
— see [system-components.md](system-components.md). Each group's
entries name components within it.

Adapted from
`bases/monolith/test-resources/monolith/application-test.yml`:

```yaml
system:
  cash-accounts: !include system/cash-account.yml
  fdb: !include system/fdb-test.yml
  kafka: !include system/kafka-all-test.yml
  ## ...
  server:
    handler: !system/required-component
    interceptors: !system/component
      system/component-kind: server/interceptors
      record-db: !system/ref fdb.record-db
    jetty-adapter: !system/component
      system/component-kind: server/jetty-adapter
      handler: !system/local-ref handler
      interceptors: !system/local-ref interceptors
      options: !profile
        dev:
          port: 8080
        default:
          port: 0
```

### Tag literals

The reader resolves these tag literals into donut.system data
structures at load time.

- **`!system/component`** — declares a component instance.
  Requires a `system/component-kind` key whose value names a
  registered kind (e.g. `server/interceptors`).
- **`!system/ref <group>.<component>`** — cross-group reference.
  Resolves to the started instance of that component.
- **`!system/local-ref <component>`** — reference to a component
  in the same group.
- **`!system/required-component`** — declares a slot that must
  be injected by the bootstrap (typically an HTTP `handler`).
  See "Required-component injection" below.
- **`!profile`** — selects a value (or whole subtree) by the
  active profile, via aero.
- **`!include <path>`** — inlines another YAML file at this
  point. Useful for splitting large configurations.
- **`!env <NAME>`** — reads an environment variable.
- **`!strs`** — forces string keys on the subtree below. Used
  when a config map's keys must be strings (e.g. protobuf
  record-type config).

### Required-component injection

Some components — most often HTTP handlers — can't be declared
in YAML because they are function values produced by code. The
YAML declares the slot:

```yaml
server:
  handler: !system/required-component
```

The base's bootstrap injects the value before starting:

```clojure
(nom-> (env/config "classpath:monolith/application-test.yml"
                   :dev)
       system/defs
       (assoc-in [:system/defs :server :handler] api/app)
       system/start)
```

### Profile selection

`!profile` picks a value or subtree by the active profile
(`:dev`, `:test`, `:prod`). Aero resolves at load time:

```yaml
options: !profile
  dev:
    port: 8080
  default:
    port: 0
```

Whole groups can be profile-gated — for example swapping
testcontainers-backed infrastructure in dev/test for production
config in prod:

```yaml
fdb: !profile
  default:
    container: !system/component
      system/component-kind: fdb/container
      ## ...
  prod:
    db: !system/component
      system/component-kind: fdb/db
      cluster-file-path: /etc/fdb/fdb.cluster
```

### Starting and stopping

The bootstrap pattern (in a base's `main.clj`):

```clojure
(nom-> (env/config config-file profile)
       system/defs
       (assoc-in [:system/defs :server :handler] api/app)
       system/start)
```

- `env/config` loads the YAML, resolving `!profile`, `!include`,
  `!env`, and the system tag literals.
- `system/defs` lifts the parsed config into a donut system map.
- `assoc-in` injects any required-component slots.
- `system/start` walks the dependency graph in reverse-topsort
  and starts each component.

`system/stop` walks topsort and stops them.

At the REPL, the same calls reach the same code through the
base's `main/start`:

```clojure
(comment
  (def sys (main/start "classpath:monolith/application-test.yml"
                       :dev))
  (tap> sys)
  (main/stop sys))
```

`tap>` ships the started system to Portal for inspection.

### Include the shared file, don't inline a copy of it

A component group that exists in `components/resources/resources/system/`
is included, never pasted. An inlined copy is invisible to the two things
that would otherwise catch it drifting:

- **`config-includes-resolve`** only follows `!include` and
  `-c classpath:` paths. An inlined block has no path to resolve, so a
  component-kind that no longer exists reads as ordinary data.
- **Tests**, because nothing loads a project's production
  `application.yml`. Every brick test can pass against a service that
  cannot start.

Three have been found this way. The relay wiring inlined its relay
handlers and kept naming two component-kinds after they were deleted —
the service could not have started, and `poly check` was green.
`bootstrap-service` inlined all four cash-account-product template
components. `api-service` still inlines its ten dispatchers rather than
including `system/<domain>-dispatcher.yml`.

Inline only what is genuinely per-service: a handler slot the base
injects, a port, a profile branch. If two services would write the same
block, it belongs under `system/`.

## Rules

**MUST:**

- A system YAML has a top-level `system:` key.
- Each component instance under a group uses
  `!system/component` with a `system/component-kind` value
  naming a registered kind.
- `!system/required-component` slots are injected by the
  bootstrap before `system/start` is called.

**MUST NOT:**

- Inline a copy of a component group that exists under
  `components/resources/resources/system/` — include it.
  Neither `config-includes-resolve` nor any test can see an
  inlined block drift, because it has no `!include` to
  resolve and nothing loads a project's production
  `application.yml`.
- Reference an unregistered component kind — the system will
  fail to start with a "no method" error.
- Reference another component by bare string where a
  `!system/ref` is intended; the resolver will not promote
  strings to refs.

**SHOULD:**

- Split large configurations into per-group sub-files via
  `!include`.
- Profile-gate testcontainers infrastructure groups so tests
  and prod can share the same top-level configuration.

## Discussion

YAML was chosen for the human-friendliness of the form —
operators and reviewers can read a system shape without knowing
EDN. The reader supports EDN equivalently if a project prefers
it; the tag literals are the same.

The `!include` pattern keeps individual files manageable.
`bank-monolith/application-test.yml` would be unmaintainable
without it; including per-group files at the top level lets
each group's configuration evolve independently.

Required-component injection exists because HTTP handlers (and
similar function-valued components) can't be declared in data —
they're code. Treating the slot as data and injecting at
bootstrap is the cleanest seam between the data-driven system
definition and the procedural bootstrap.

The reader is implemented in the `env` and `system` bricks; the
[systems-as-data slides](../slides/systems-as-data/slides.md)
walk through the assembly mechanics in detail.

## References

- [ADR-0007 — System-as-data](../../adr/0007-system-as-data.md)
- [system-components.md](system-components.md)
- [bases.md](bases.md)
- [donut.system](https://github.com/donut-party/system)
- [aero](https://github.com/juxt/aero)
- [Systems-as-data slides](../slides/systems-as-data/slides.md)
