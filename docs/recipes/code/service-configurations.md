# Service configurations

<!-- tessl-plugin: design -->

## Problem

You are writing or changing the `application.yml` a service project
ships — which processors, consumers and dispatchers its JVM hosts, and
which shared groups it includes.

## Solution

Each deployable project carries the system definition its base loads at
startup, under the project's `resources/`, and the runtime container
picks it up via `-c classpath:application.yml -p default`. The mechanics
of the file — the `system:` key, the tag literals, profiles, injection —
are [system-configurations](system-configurations.md), mono's recipe.

```
projects/<project-name>-service/
  resources/
    application.yml           ; the system definition the base loads
    bank/                     ; per-service domain config (optional)
      <domain>.yml            ; e.g. cash-account.yml on the
                              ;      cash-account processor service
```

The `bank/<domain>.yml` files are domain-scoped includes referenced from
`application.yml` for processor services.

The test-only fall-through is different: the `monolith` base (which
bundles every component for in-process end-to-end tests) loads its
config from
`bases/monolith/test-resources/bank-monolith/application-test.yml`, not
from a project's `resources/`.

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

### Checking a change

`just test-all` runs `test-startup`, a test-only component every service
project pulls in through its `:test` alias. It finds its project from
where `application.yml` sits on the classpath, loads the entry base the
project is named after, parses the config with the default profile and
builds the system definitions without starting anything, so a library
the project lacks, a require missing from a base's `system.clj`, or a
component-kind nothing registers fails there in seconds.

## Rules

**MUST:**

- Keep a service's system definition in its project's
  `resources/application.yml`, with domain-scoped includes under
  `resources/bank/`.
- Check a change to a project's `application.yml` with `just test-all`,
  whose `test-startup` component loads every deployable project's
  production config against its own classpath and fails on a
  `system/component-kind` nothing registers.

**MUST NOT:**

- Inline a copy of a component group that exists under
  `components/resources/resources/system/` — include it. Neither
  `config-includes-resolve` nor any test can see an inlined block
  drift, because it has no `!include` to resolve and nothing loads a
  project's production `application.yml`.

## References

- [system-configurations](system-configurations.md) — the file's
  mechanics, mono's recipe
- [projects](projects.md) — what a project carries, mono's recipe
- [ADR-0019](../../adr/0019-processor-packaging.md) — which processors
  a project hosts
- [deployment](../infra/deployment.md) — how the container names the
  config
- [testing](../test/testing.md) — what `just test-all` runs
