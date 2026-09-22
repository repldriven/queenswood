<!-- markdownlint-configure-file { "MD033": false, "MD041": false } -->

<p align="center">
  <img src="docs/assets/logo.svg" alt="Queenswood" width="200" />
</p>

# Queenswood

**Open-source core banking.** Whether you're building a bank or embedding
banking into your product, Queenswood runs the banking behind it. It provides
customer onboarding with identity checks, products and accounts, payments,
interest and rewards, a general ledger, policies, end-of-day processing,
webhooks and an operator console, all of it configured and driven through one
API. You bring the banking licence, the clearing partner and the identity
provider. The platform is yours to read, to run and to change.

## Demo

[![Video: a tour of what your bank can do, in the operator console](docs/assets/demo-console.png)](https://github.com/user-attachments/assets/f112c363-b7ba-4f83-b96a-88219e3bc554)

A tour of what your bank can do, in the operator console: **Publish** products,
**Invite** operators, **Verify** customers, **Fund** the bank, **Open**
accounts, **Reward** customers, **Move** money, **Refuse** an overdraft, **Pay**
someone, **Migrate** accounts and **Accrue** interest.

[![Video: the same bank from your customer's side, in the demo digital bank's app](docs/assets/demo-app.png)](https://github.com/user-attachments/assets/2a4d01fe-3caf-452a-9667-7de984d5bcf4)

The same bank from your customer's side, in the
[demo digital bank](docs/prd/demo-digital-bank.md)'s app: sign up and open two
accounts.

## How it is used

- **Your product team designs products in the console.** They publish each
  version and plan the migrations that move holders onto a new one.
- **Your engineers integrate it into your systems.** They call its API, and act
  on its webhooks as your customers bank with you.
- **Your operators run the bank day to day in the console.** They watch
  end-of-day processing, look into a customer's account when something does
  not add up, and manage who on the team can do what.
- **Your customers use your app.** They see you, never Queenswood.
- **Your finance team keeps the books in the console.** They read the general
  ledger, check the trial balance ties, and see what waits in suspense.
- **Compliance reads the records.** The access log, the policies in force and
  the ledger say who did what, and under which rules.

## What you bring

- **A banking licence**, or a partner who holds one.
- **A clearing partner** for Faster Payments. An adapter for one is
  included, with a simulator that stands in for it locally.
- **An identity verification provider.** As above: an adapter and a
  simulator.
- **Somewhere to run it.** A Helm chart deploys the platform to any
  Kubernetes cluster, and a Google Cloud blueprint declares an
  installation as a manifest.

Queenswood has no production miles yet. It is a research-grade
platform, built in the open, and the documentation says what is done
and what is not.

## What you get

- **One API.** One base URL and one OpenAPI 3.x document, generated from the
  routes themselves, so what the document says is what the API does. Every write
  takes an idempotency key, so a retried request replays the first answer rather
  than paying twice.
- **Isolated banks.** Your organisation is a bank on the platform, with its own
  products, customers, books, policies and team, and nothing of one is visible
  to another. A bank starts in test and moves to live; tiers set what it may do
  and how much.
- **Versioned products, with migrations.** Current, savings and term deposit
  products come from templates. A rate change, a new welcome reward, a new term:
  each is a new version, and the holders of the old one are moved by a migration
  you plan, approve and let the scheduler run.
- **Payments on a double-entry ledger.** Internal transfers post at once. UK
  Faster Payments go out through a clearing partner, with the payee's name
  checked first, and come in matched to the account or parked in suspense. Every
  movement is a double-entry posting in integer minor units, and interest keeps
  the fraction of a penny it has not paid yet.
- **Webhooks.** A webhook endpoint per bank, told when an account opens, a
  payment settles, is held or fails, money arrives, or a reward is paid.
  Deliveries are signed, retried, and re-sendable.
- **Policies as data.** What a bank may do, and up to what limit, is a policy
  record evaluated when the request arrives, not a conditional compiled into a
  release. Changing what a bank permits is a write.
- **Team roles and an access log.** Invite colleagues as owner, admin, developer
  or viewer. Every act is attributable to a person or to the bank's own
  credential, and an access log says who did what.
- **A sandbox on the live code.** Fund your bank with simulated money and run
  the same code, the same API and the same books your customers will.
- **Source, design and tests in one repository.** The source, the product and
  design documents, the decisions and the tests, including property tests that
  hold the running system to a model of it, are all here.

API reference:
[repldriven.github.io/queenswood](https://repldriven.github.io/queenswood/),
or live OpenAPI at [localhost:8080](http://localhost:8080) when
running.

## Architecture

The Message Bus (Kafka or Pulsar) carries commands and events
between Queenswood's processors and external providers; a distributed
database (FoundationDB) manages the data.

<picture>
  <source media="(prefers-color-scheme: dark)"  srcset="docs/diagrams/system-diagram-dark.svg">
  <source media="(prefers-color-scheme: light)" srcset="docs/diagrams/system-diagram-light.svg">
  <img alt="Queenswood system diagram" src="docs/diagrams/system-diagram-light.svg">
</picture>

**Writes as commands, processed in parallel and in order.** Where a write
needs it, the API puts a command on the bus instead of doing the work itself.
Processors consume those commands and scale independently of the web tier, so
the work spreads across as many instances as it takes while a request costs
the API only an open connection. Commands sharing an ordering key are consumed
one at a time and in order, however many processors are running — something no
number of web servers writing directly can give you. Delivery is at-least-once
and the envelope absorbs a redelivery, so repeating a request replays the
first outcome rather than doing the work twice. Today the API waits for the
reply and answers on the same connection; the same split would let it
acknowledge immediately and return the outcome out of band. A write that needs
none of this stays a direct call. Processors deploy individually, or bundled
along lines of responsibility such as financial and operational.

**Reads are queries.** The API read-side loads records directly through a
separate query surface — no command, no bus, no round-trip. Query bricks
read and nothing else. Once a domain's writes have earned a command, its
write brick becomes private to the processor and the API reaches only the
query side, which the build enforces rather than leaves to habit. A read
therefore never travels the write path, and a busy or unavailable bus
does not make the bank unreadable.

**Processors react, they never call one another.** Where a change has to
be reacted to, it is recorded in a changelog in the same transaction as
the write itself, so a change and the news of it cannot diverge. One
system-wide relay tails those changelogs in order and publishes each
entry to the message bus as an event, and the processors that care
subscribe. An event reports what happened and asks nothing of whoever
reads it, unlike a command. Nor is this event sourcing: the records
stay the source of truth, and an event exists to cross a boundary
rather than to rebuild state from.

**External calls are recorded before they are made.** A database write and an
outbound HTTP call cannot be made atomic: no transaction spans the two,
and there is no two-phase commit to reach for across someone else's API.
Committing first risks a call that never happens; calling first risks a
call that happened but was never recorded. So the adapter commits the
_intent_ to call, and a separate poller makes the call afterwards,
retrying each pending intent until it succeeds or exhausts its attempts.
Webhook events received from an external service are normalized
by the adapter and written to a deduplicating outbox, atomically with
its changelog record, and relayed to the message bus in order through
the system-wide changelog relay: processors can and do react to
external adapter events too.

## What's interesting

The engineering decisions that make this codebase worth reading, each
with a doc that goes deep:

- **One unified API for the whole bank, with full OpenAPI 3.x
  compliance.** One base URL and one document, not a service per
  domain, and the document is generated from the routes themselves so
  it cannot drift from what the API does.
  See [ADR-0013](docs/adr/0013-single-unified-api.md) and
  [ADR-0014](docs/adr/0014-openapi-3x-compliance.md).
- **Policy is data, not code.** Capabilities and limits are records
  evaluated at runtime, not conditionals compiled into a release, so
  changing what a bank permits is a write.
  See [policy-evaluation](docs/tdd/policy-evaluation.md).
- **Money is integers, and the remainder is kept.** Amounts are
  integers end to end, never floats, and interest carries the
  sub-minor-unit remainder between days rather than rounding it away.
  See [interest](docs/tdd/interest.md).
- **System-level and model-equality property testing.** Two state
  machines fed the same commands: the real system, and a model that
  imports nothing from it, no database, no protobuf, no shared code.
  A divergence shrinks to the shortest sequence that causes it.
  See [scenario-testing](docs/tdd/scenario-testing.md).
- **Anomalies, not exceptions, at every component interface.** An
  interface returns a value or an anomaly and never raises. Three kinds
  separate a fault from a refusal from a forbidden call, which is how
  the API picks a status family without inspecting a payload.
  See
  [ADR-0005](https://github.com/repldriven/mono/blob/main/docs/adr/0005-error-handling-with-anomalies.md).
- **System-as-data.** Test and production share one bootstrap path, and
  what a given process runs is decided by its configuration rather than
  its code: the same bricks start as a modular monolith in one JVM or as
  separate services.
  See
  [ADR-0007](https://github.com/repldriven/mono/blob/main/docs/adr/0007-system-as-data.md)
  and the
  [slides](https://github.com/repldriven/mono/blob/main/docs/slides/systems-as-data/slides.md).
- **FoundationDB Record Layer.** Multi-record ACID across stores in one
  transaction, so creating a bank writes its party, ledger chart, house
  accounts and policy bindings, or none of them. Changelog entries are
  keyed by versionstamp, so the log is ordered by commit and a relay
  resumes exactly where it stopped. Counts and sums are kept current as
  records commit, so reading one costs the same whether a bank has ten
  accounts or ten million.
  See [ADR-0002](docs/adr/0002-foundationdb-record-layer.md).
- **Built on `mono`.** The generic half lives upstream: messaging, identity,
  observability, HTTP, error handling, and the system assembly the bullet
  above describes. It arrives tested on its own terms and pinned to a tag
  and a sha, so the suite here proves banking rather than plumbing, and the
  ground under a bank moves only when someone decides it should.
  See [ADR-0001](docs/adr/0001-reuse-mono-as-upstream.md).

## Documentation

The bank is documented end to end — the why, the how, and the
decisions in between:

- **[docs/prd/](docs/prd/)** — what each capability is for and who
  uses it, in product language: intended scope, users, and the domain
  rules that follow. Companion to the TDDs' _how_.
- **[docs/tdd/](docs/tdd/)** — how it is built, one document per
  capability and subsystem, from the transaction substrate up through
  the API surface.
- **[docs/adr/](docs/adr/)** — the decisions, each with the context
  that forced it and the consequences accepted. Kept as a record, so
  one that has been superseded says so rather than being rewritten.
  The decisions mono owns are laid down beside these by
  `just mono-import`, at the sha the dependency pins.
- **[docs/recipes/](docs/recipes/)** — task-oriented guides in a fixed
  shape (Problem, Solution, Rules, Discussion, References) for the
  things you do repeatedly in this codebase. The recipes mono owns —
  writing to its bricks, and testing against them — are laid down
  beside these by the same import.
- **[docs/slides/](https://github.com/repldriven/mono/tree/main/docs/slides)**
  — mono's slidev walk-through of how systems-as-data assembles a
  running system, laid down here by the same import.

These are not only for people. Nearly every ADR and recipe carries a
label binding it to a rule plugin, and the rules an agent loads on every
task in this repo are regenerated from those documents rather than
written alongside them, so the guidance cannot quietly drift from the
decision it came from. It is also why a recipe has a fixed shape: the
`Rules` block is the part that gets extracted. What is load-bearing is
then checked again at commit time, by formatting, linting, and a set of
repo-specific guardrails.

## Running

The released Helm chart deploys the entire platform (API,
processors, adapters/simulators, web console,
Kafka or Pulsar, FoundationDB) onto any Kubernetes cluster.

### Run local

**Start a cluster** on macOS, with Colima and kind:

```bash
brew install colima kind kubectl helm
colima start --vm-type vz --vz-rosetta --cpu 6 --memory 24
kind create cluster --name queenswood \
  --config <(curl -fsSL https://raw.githubusercontent.com/repldriven/queenswood/main/infra/kind/queenswood-config.yaml)
```

**Install** the platform onto it:

```bash
helm install queenswood \
  oci://ghcr.io/repldriven/queenswood \
  -n queenswood --create-namespace \
  --wait --timeout 10m
```

**Reach the API, console and tracing web apps**:

```bash
kubectl -n queenswood port-forward svc/queenswood-api-service 8080:8080
kubectl -n queenswood port-forward svc/queenswood-console     8081:8080
kubectl -n queenswood port-forward svc/queenswood-jaeger      16686:16686
```

In the console, **Sandbox › Scenarios** runs the platform for real
against your cluster — open Jaeger alongside it at
[localhost:16686](http://localhost:16686) to watch the spans each
scenario produces.

**Or run it on a laptop**, with no cluster, from a checkout with the
development environment below active: `just monolith-start` starts the
platform as one process with its containers, `just console-start`
serves the console on port 5173, and the demo digital bank is
`just demo-digital-bank-seed`, `just demo-digital-bank-start` and
`just demo-digital-bank-app-start`, in that order.

The full quickstart — including tear-down — ships with
each
[release](https://github.com/repldriven/queenswood/releases/latest).

### Run on Google Cloud

A work in progress: a blueprint for deploying and managing a Queenswood
instance on Google Cloud. The pieces below exist and run; the path
through them is still being worked out.

It is a different kind of thing from the local path rather than a
larger version of it. No command deploys an installation. An
installation is a manifest in a private repository, and a management
plane running Crossplane and Argo CD reconciles the folder, the
projects, the clusters and the workloads toward what that manifest
says — so changing what exists means editing the manifest and merging
it.

[Up and running](docs/recipes/infra/up-and-running.md)
is where to start: every recipe from an empty Google account to a bank
serving traffic, in order, and what each leaves for the next. There are
two paths through it — from nothing, or from an established
organisation, where the first steps are somebody else's.

<a href="docs/diagrams/infrastructure-diagram-light.svg">
  <picture>
    <source media="(prefers-color-scheme: dark)"  srcset="docs/diagrams/infrastructure-diagram-dark.svg">
    <source media="(prefers-color-scheme: light)" srcset="docs/diagrams/infrastructure-diagram-light.svg">
    <img alt="Queenswood infrastructure diagram" src="docs/diagrams/infrastructure-diagram-light.svg">
  </picture>
</a>

The tier across the top is durable and never torn down: the management
project reconciles the installation, the recovery project holds the
backups and the key they are encrypted under, and the DNS zone outlives
anything disposable. The instance project below it is rebuilt whenever
an instance is.

## Developing

### Nix

Nix is used to manage the many tools and binaries required to
develop Queenswood. There are several ways to install Nix -
these are not prescribed here.

Nix flakes with `direnv` ensures everything required is
on the path automatically whenever you `cd` to it.

```bash
❯ cd queenswood
direnv: loading ~/Documents/github.nosync/repldriven/queenswood/.envrc
direnv: using flake .
FDB libs: /nix/store/i3abz3pz7p6mw9dzg9kr2praag0s6zqz-foundationdb-7.3.75/lib
fdbcli: /nix/store/i3abz3pz7p6mw9dzg9kr2praag0s6zqz-foundationdb-7.3.75/bin/fdbcli
protoc-gen-clojure: protoc-gen-clojure version: v2.1.2
Clojure monorepo environment loaded
Preparing repo...
merge drivers configured
Prepping libraries...
direnv: export +AR +AS +CC +CLASSPATH ...
```

### REPL

REPL-driven development follows the standard Polylith pattern.

```clojure
(ns dev.monolith
  "Start the system as a modular monolith and testcontainers
   for FoundationDB, Kafka or Pulsar, Keycloak, etc:
   * start docker (just docker-start),
   * start repl (just repl),
   * load this namespace, and evaluates lines from the comment block

   After the system has started:
   * start web console (just console-start), login and explore

   NOTE: on a fresh install, it may take several minutes to download
         required images for FoundationDB, Kafka, Keycloak, etc"
  (:require
    [com.repldriven.queenswood.testcontainers.interface]
    [com.repldriven.queenswood.monolith.main :as main]))

(comment
  (def sys (main/start "classpath:monolith/application-test.yml" :dev))
  (tap> sys)
  (main/stop sys)
  :-)
```

## Built on mono

[mono](https://github.com/repldriven/mono) is a Clojure component
library for distributed systems, built on
[Polylith](https://polylith.gitbook.io/polylith). Its components are
documented in the
[mono README](https://github.com/repldriven/mono#mono-components).

For the workspace layout, see `components/`, `bases/`, and
`projects/`. Brick conventions are documented in
[recipes/components](https://github.com/repldriven/mono/blob/main/docs/recipes/code/components.md).
