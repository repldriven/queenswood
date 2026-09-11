# CLAUDE.md

Queenswood is a Clojure core-banking system, organised as a Polylith
workspace that consumes shared infrastructure from
[mono](https://github.com/repldriven/mono) as a pinned git-dependency
(the `ext/mono` shims under `deps/`). The workspace holds only
Queenswood's own domain bricks — `com.repldriven.queenswood.*`; the
shared infra lives in the dependency as `com.repldriven.mono.*`. See
[ADR-0001](docs/adr/0001-reuse-mono-as-upstream.md).

## Topic router

CLAUDE.md is the routing layer. Every `docs/recipes/*.md` and
`docs/adr/*.md` file below is labeled `<!-- tessl-plugin: <name> -->`,
and that plugin's rule (always loaded via `AGENTS.md`) already
distills its `## Rules` / `## Decision` — you don't need to open the
doc to rediscover that. Open it for the *why* behind the rule instead:
Context, Consequences, Discussion. `docs/tdd/`, `docs/prd/`,
`docs/plan/` and `docs/compliance/` docs are the exception — nothing
distills them, so open those in full before non-trivial work on their
topic.

### Code

- **Clojure code style** — naming, requires, destructuring, anon
  fns, `cond->`, `let`-binding format, ID generation (`util/uuidv7`),
  timestamps (`util/now`), interceptor short-circuit
  (`sieppari.context/terminate`).
  See [code-style.md](docs/recipes/code/code-style.md).
- **Common helpers** — when to add a helper to `utility`, when to
  re-export from a library (medley etc.), the convergence rule.
  See [common-helpers.md](docs/recipes/code/common-helpers.md).
- **Component interfaces and docstrings** — `interface.clj` is the
  documentation surface; impl files stay bare.
  See [ADR-0015](docs/adr/0015-comments-and-docstrings.md) and
  [components.md](docs/recipes/code/components.md).
- **Error handling** — anomalies at component boundaries; never
  throw from `interface.clj`; `error/try-nom` and `error/nom->` at
  library edges. See
  [ADR-0005](docs/adr/0005-error-handling-with-anomalies.md) and
  [error-handling.md](docs/recipes/code/error-handling.md).
- **Data shapes** — kebab-case keyword keys throughout, with
  string-typed currency (ISO 4217) as a deliberate exception.
  See [ADR-0006](docs/adr/0006-kebab-case-keyword-keys.md).

### Architecture

- **System wiring** — `system/defcomponents`, `system.clj` vs
  `system/` folder, the test-bundle pattern, naming shared
  resource components without baking environment names in.
  See [system-components.md](docs/recipes/code/system-components.md)
  and [ADR-0007](docs/adr/0007-system-as-data.md).
- **Brick boundaries** — bricks react to events relayed off
  each other's changelogs rather than orchestrating across each
  other; `bank-api` stays ignorant of cross-brick effects.
  See [ADR-0021](docs/adr/0021-changelog-relay.md) and
  [components.md](docs/recipes/code/components.md).
- **Transactional guarantees** — the line between work inside FDB
  (one transaction, commit-then-ack) and work that crosses a
  boundary (ingress idempotent consume-then-ack; egress via an
  outbox for events and an intent for external calls, drained by
  a relay). The external adapters (ClearBank, Onfido) are the
  worked example.
  See [tdd/transaction-processing.md](docs/tdd/transaction-processing.md),
  [tdd/payments.md](docs/tdd/payments.md), and
  [ADR-0021](docs/adr/0021-changelog-relay.md).
- **Schema evolution** — the declared meta-data `version`, `added`
  and `modified` per index, former indexes, deprecating a proto field
  rather than removing it, and the guard against the last `stable-*`
  tag. See [schema-evolution.md](docs/recipes/code/schema-evolution.md).
- **Processor bricks** — paired `bank-X-processor` base and
  `bank-X` component (commands / core / domain / store /
  events), the `txn-or-config` threading convention, FDB
  confined to `store.clj`, rejections originating in
  `domain.clj`.
  See [tdd/processor-bricks.md](docs/tdd/processor-bricks.md).
- **Bases and projects** — entry points, per-service projects,
  the development project that includes everything.
  See [bases.md](docs/recipes/code/bases.md) and
  [projects.md](docs/recipes/code/projects.md).
- **System configurations** — YAML system definitions, profiles,
  `!system/component` / `!system/ref` / `!env`.
  See [system-configurations.md](docs/recipes/code/system-configurations.md).
- **Service APIs** — Reitit + Sieppari + Muuntaja, RFC 9457
  problem details, two-tier auth, OpenAPI assembly. The API style
  is resource-based, not CRUD-shaped.
  See [tdd/service-apis.md](docs/tdd/service-apis.md),
  [ADR-0013](docs/adr/0013-single-unified-api.md),
  [ADR-0014](docs/adr/0014-openapi-3x-compliance.md).
- **Lifecycle transitions** — the ten-point definition-of-done for
  a new entity state or transition, source-state guards in
  `domain.clj` (`:<entity>/invalid-status`, HTTP 409), and event-
  handler guards as an idempotency gate rather than a rejection.
  See [lifecycle-transitions.md](docs/recipes/code/lifecycle-transitions.md)
  and [ADR-0021](docs/adr/0021-changelog-relay.md).

### Tests

- **General testing** — `with-test-system`, `nom-test>`, no
  `use-fixtures`, what a brick's own tests may require and what
  makes a case a scenario, brick-level vs project-level test runs.
  See [testing.md](docs/recipes/test/testing.md).
- **Testcontainers** — FDB and Pulsar containers, reuse, image
  selection. See
  [testcontainers.md](docs/recipes/test/testcontainers.md).
- **Scenario testing** — two sibling scenario bricks, both
  data-driven EDN + fugato-style runner. `bank-test-scenarios`
  drives the domain layer for model-equality property tests;
  `bank-test-api-scenarios` drives the HTTP surface (`bank-api`)
  via real requests and is the home for what used to be
  per-base / per-component `*_test.clj` API tests.
  See [tdd/scenario-testing.md](docs/tdd/scenario-testing.md).

### Writing docs

- **Markdown formatting, mermaid, tone, PRD register** — wrap at
  80, link hygiene, no semicolons in mermaid labels, no maturity
  overclaim, no competitor names, PRDs use product language and
  describe what users do via "the banking API" rather than
  naming operations. See
  [writing-docs.md](docs/recipes/practices/writing-docs.md).

### Operations

- **Git workflow** — merge `main` before committing (Renovate
  auto-merges deps weekly), stage user-initiated deletions and
  moves with `git add` not `git rm`, include the user's
  untracked drafts in workspace-wide ops.
  See [git-workflow.md](docs/recipes/practices/git-workflow.md).
- **Code generation** — protoc + protojure for protobuf, Lancaster
  for Avro, prep alias for the bank profile.
  See [code-generation.md](docs/recipes/code/code-generation.md)
  and [ADR-0010](docs/adr/0010-code-generation-via-prep-lib.md).
- **Deployment** — Helm chart, kind dev loop, per-service
  Docker images. See
  [deployment.md](docs/recipes/infra/deployment.md).
- **Rebuilding an instance's cluster** — the runbook for a planned
  cluster rebuild: why a ForceNew field reports `Synced` and does
  nothing, the order, and what it leaves standing. See
  [instance-rebuild-cluster](docs/recipes/infra/instance-rebuild-cluster.md).
- **Replacing the plane's own cluster** — the plane builds its
  successor and swaps onto it, why a reused slot makes the rename a
  no-op, and what has to be installed by hand for the composite to
  observe. See
  [plane-rebuild-cluster](docs/recipes/infra/plane-rebuild-cluster.md).
- **Recovering FoundationDB** — the scenario matrix, restore mode
  against destination state, RPO per scenario, why scale-to-zero is not
  a recovery scenario, and the CIS and DORA controls each open item
  lands on. See [fdb-recovery.md](docs/recipes/infra/fdb-recovery.md).
- **Obligations, and how they are met** — a register of what an outside
  body requires and which recipe addresses it, gaps included. Recipes
  themselves carry no regulation; the citation lives here. See
  [compliance/readme.md](docs/compliance/readme.md).
- **Infrastructure** — GCP via Crossplane on a kind management
  plane; Argo CD wires the bootstrap chain; the installation's
  composites drive everything else. See
  [tdd/infrastructure.md](docs/tdd/infrastructure.md) and
  [ADR-0016](docs/adr/0016-crossplane-over-terraform.md).
- **Cloud foundation** — the folder/seed/hub-and-spoke project
  layout, one management plane on GKE, foundations protected rather
  than deleted, and why "down" is a declared state. See
  [ADR-0022](docs/adr/0022-cloud-foundation-and-environment-lifecycle.md).
- **Designing a Crossplane kind** — how much one kind covers, what the
  caller chooses, what each part is called, what may be deleted, when
  it is ready. See
  [crossplane-design.md](docs/recipes/infra/crossplane-design.md).
- **What cannot be a building block** — the things with no API at all,
  which are a recipe rather than a kind nobody got round to writing.
  See
  [ADR-0025](docs/adr/0025-building-blocks-and-what-cannot-be-one.md).
- **Recovering data** — proposed: why `down` no longer empties anything
  and so no restore path exists, why a destructive state must not share
  a word with a reversible one, and why a corrupted primary is
  evidence. See
  [ADR-0026](docs/adr/0026-recovering-data-and-the-states-that-do-it.md).
- **The plane and the instances on it** — what a management plane is
  for, why an instance is its own composite rather than a field on the
  plane's, the kind and the group, and what `down` leaves standing. See
  [ADR-0024](docs/adr/0024-instances-are-their-own-composites.md).
- **The folder, and who hands it over** — why the folder is its own
  composite rather than the plane's, how a handover is the same XR in
  either direction, and why groups are bound here and created
  elsewhere. See
  [ADR-0027](docs/adr/0027-the-folder-is-a-subsidiary.md).
- **An installation's boundary** — the folder declared as its own kind,
  composed or adopted by one field, and why the manifest carries only
  the code. See
  [boundary-install](docs/recipes/infra/boundary-install.md).
- **Up and running** — every recipe from an empty Google account to a
  bank serving traffic, in order, and what each produces. See
  [up-and-running](docs/recipes/infra/up-and-running.md).
- **An organisation's secure foundation** — Cloud Identity, the
  organisation, a billing account, and capabilities nobody holds by
  default; skipped entirely in an established organisation. See
  [organisation-foundation](docs/recipes/infra/organisation-foundation.md).
- **An installation's contract** — the folder, manifests repository,
  billing account and principals its `environment.yml` names, created
  before the file that names them since IAM rejects a binding to a
  principal that does not exist. See
  [contract-install](docs/recipes/infra/contract-install.md).
- **The identity that builds installations** — the seed project and the
  organisation-scoped rights it holds for a bootstrap and no longer,
  and why creating a folder is checked on the parent. See
  [organisation-bootstrap.md](docs/recipes/infra/organisation-bootstrap.md).
- **An installation's management plane** — why a plane of the wrong kind
  cannot install this at all, what the composite builds, the path from a
  throwaway plane to a durable one, the four identities, and the
  credential and zone that finish it. See
  [management-plane-install](docs/recipes/infra/management-plane-install.md).
- **Adding an instance to an installation** — the unit's two places,
  why the secrets are written while the composite builds, and why
  `down` is not a starting state. See
  [instance-deploy.md](docs/recipes/infra/instance-deploy.md).
- **Upgrading or reconfiguring Argo CD** — the steps for a plane whose
  own Argo is `Observe`, the `extraObjects` a values file must carry,
  and why a merged change does not reach it. See
  [argocd-upgrades.md](docs/recipes/infra/argocd-upgrades.md).
- **Upgrading or reconfiguring Crossplane** — the same `Observe` tier as
  Argo's with the opposite hazards: no values block to lose, and a
  restart that stops every managed resource being reconciled. See
  [crossplane-upgrades.md](docs/recipes/infra/crossplane-upgrades.md).
- **Debugging an installation** — where a failure reports, which field
  manager owns what, and why a status field disappears. See
  [crossplane-debug.md](docs/recipes/infra/crossplane-debug.md).
- **Changing a live resource** — whether a change applies, is refused
  or destroys, why the parent's deletion policy governs a move, and
  what a withdrawal removes. See
  [crossplane-live](docs/recipes/infra/crossplane-live.md).
- **Crossplane providers** — upjet's refusal to replace, external names
  as cloud identifiers, late-initialisation, reading the CRD rather
  than the Terraform docs. See
  [crossplane-providers.md](docs/recipes/infra/crossplane-providers.md).
- **Argo CD Applications** — what a parent may hold, the sync policy
  each one carries, waves against missing kinds, server-side apply for
  large CRDs, retry budgets, and reading a sync that is not applying.
  See [argocd-apps.md](docs/recipes/infra/argocd-apps.md).
- **Checking a plane grades what it serves** — the entries a plane
  should carry, the status-less kinds a precedence bug grades Healthy
  rather than the list intended to, and why the chart carries corrected
  copies until the upstream fix ships. See
  [argocd-health.md](docs/recipes/infra/argocd-health.md).
- **Reading a private repository** — the GitHub App, the one entry its
  three values live in, and the field both URLs derive from. See
  [argocd-github.md](docs/recipes/infra/argocd-github.md).
- **GCP IAM for automation** — Workload Identity's two halves, node
  identities, rights held by accident, role scopes. See
  [gcp-iam.md](docs/recipes/infra/gcp-iam.md).
- **Security scanning** — how the organisation is scanned, which
  findings are accepted and why, and the difference between a muted
  finding and a deferred one. See
  [security-scanning.md](docs/recipes/infra/security-scanning.md).
- **External secrets** — the declared container and the written
  version, why the read happens on the destination cluster, and why a
  credential you can regenerate gets no second copy anywhere. See
  [external-secrets.md](docs/recipes/infra/external-secrets.md).
- **Google sign-in** — the OAuth client no API creates, the redirect
  URI Google refuses only after the user has left, and why the realm's
  placeholder pair reaches it by two different routes. See
  [google-sign-in.md](docs/recipes/infra/google-sign-in.md).
- **The apex, and the names below it** — why the zone a registrar
  points at belongs to no installation, lives in a project at the
  organisation, and is declared in git rather than composed; and why
  every serving name is a delegation to a zone one installation holds.
  See
  [ADR-0028](docs/adr/0028-the-apex-belongs-to-no-installation.md) and
  [apex-install](docs/recipes/infra/apex-install.md).
- **Cloud DNS** — the manual half: proving domain ownership before a
  public zone may be created, and what has to survive a registrar move.
  See [gcp-dns.md](docs/recipes/infra/gcp-dns.md).
- **Moving a domain's delegation** — the diff that makes the
  propagation window a no-op, replacing all four nameservers, and why
  the registry rather than the zone is what you ask. See
  [gcp-dns-delegation.md](docs/recipes/infra/gcp-dns-delegation.md).
- **Writing about an installation** — what counts as an identifier,
  what to write instead, and why masking happens while you write rather
  than when a check fails. See
  [cloud-identifiers.md](docs/recipes/practices/cloud-identifiers.md).
- **Cloud naming** — the installation code, the prefix/code/env/label
  rule and its exceptions, the inventory of every kind and a worked
  example of one installation. See
  [cloud-naming.md](docs/recipes/practices/cloud-naming.md) and
  [ADR-0023](docs/adr/0023-installation-naming-and-access.md).
- **Justfile recipes** — the `set -e` shapes that abort silently,
  capturing before piping, taking the identity rather than discovering
  it, where a constant or a helper is declared, and what a comment in a
  body is for. See
  [justfile-recipes.md](docs/recipes/practices/justfile-recipes.md).
- **Pre-commit hooks** — zprint, clj-kondo, before-commit
  formatting. See
  [ADR-0012](docs/adr/0012-pre-commit-hooks.md).

### Domain reference

- **Per-capability designs** — `docs/tdd/` has one TDD per
  capability or subsystem (authentication, banks,
  cash-account-migration, cash-account-products, cash-accounts,
  idempotency, infrastructure, interest, onboarding, parties,
  payments, policy-evaluation, scenario-testing, service-apis,
  traceability, transaction-processing,
  transactions-and-balances, webhooks).
- **Per-capability requirements** — `docs/prd/` has the
  product-shaped requirements (cash-account-products,
  cash-accounts, interest, memberships, onboarding, parties,
  payments, platform, policies, users, webhooks).
- **In-flight implementation plans** — `docs/plan/`.

## Guardrails

The rules most load-bearing across the codebase — no throwing from
`interface.clj`, ID/timestamp generation via `utility`, no
`use-fixtures` in tests, pulling `main` before committing, crossing
brick boundaries only via `interface.clj`, minimal inline commentary —
live in the Tessl rule plugins (`idioms`, `framework`, `workflow`),
always loaded via `AGENTS.md`. See
[plugins/README.md](plugins/README.md) for the full plugin map. They
don't need restating here, and they're already in context on every
task. Don't hand-add a guardrail bullet to this file again — edit the
rule (and its source recipe/ADR, kept in sync by the
`sync-rules-from-docs` skill) instead.

## Common commands

```bash
# Run the bricks changed since the last stable-* tag (what `just test`
# and so the Gas City lane run). With no such tag reachable the
# comparison falls back to the initial commit and everything runs.
# `:dev` is what reaches the scenario bricks: they belong to the
# development project alone, which polylith excludes without it.
clojure -M:poly test :dev

# The full run: every brick in every service project, and the
# development project with it, so the scenarios run too. This is what
# `just test-all` does (and it starts docker first). When something
# says "the full suite", this is the command. The two narrower forms
# below each drop a dimension, so neither stands in for it.
clojure -M:poly test :all :dev

# Every service project's matrix, without the development project —
# so no scenario bricks.
clojure -M:poly test :all

# The development project alone. It carries every brick, scenarios
# included, but tests each one against dev's dependency set only: a
# test dependency a service project lacks still fails only there.
clojure -M:poly test project:dev :all

# Run tests for one or more bricks, in every project that hosts them
# plus the development project. Never restrict to project:dev alone: it
# carries every brick, so a test dependency a service project lacks
# only fails there.
clojure -M:poly test brick:<brick-name> :dev
clojure -M:poly test brick:<brick1>:<brick2> :dev

# Code generation prep (add :force true after a schema change)
clj -X:deps prep :aliases '[:dev]'

# Install the git hooks. .envrc already does this on entering the
# primary checkout, so this is the fallback for a clone with no direnv.
# A hook is a copy, not a symlink, so anything edited under
# scripts/hooks/ needs it again. post-checkout is among them, which is
# what gives every new worktree its .tessl/ rules.
just install-hooks
```

@AGENTS.md
