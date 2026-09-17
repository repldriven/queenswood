# Policy evaluation engine

## Objective

Banking has many rules — what an organisation can do, what an
account can do, balance and transaction limits, KYC
restrictions. The temptation is to scatter rule logic through
domain code (an `if balance < 0` here, a daily-limit check
there). We resisted that temptation. All rules go through one
evaluator, with **Policy** and **Binding** as first-class
domain entities.

This TDD describes the policy evaluation engine: what a
Policy and a Binding are; how capabilities and limits are
checked; the matching engine the two share; the curative-
permit pattern; and the contract between the engine and the
domains that consume it.

In scope: the `policy` brick, capability evaluation,
limit evaluation, the matching engine, bindings, the anomaly
contract.

Out of scope: HTTP authentication, see
[service-apis.md](service-apis.md); idempotency, see
[idempotency.md](idempotency.md); how individual domains
structure their per-leg evaluation calls (covered in their
respective TDDs).

## Background

Two anti-patterns we explicitly resist.

**Hardcoded rules in command handlers.** A line like
`(when (neg? balance) (error/reject :balance/negative))`
inside `cash-account/commands.clj` is invisible to anyone who
isn't reading that file. Multiply across every command across
every domain and the bank's actual rules become emergent
rather than designed. Edits require code changes and
redeployments.

**Side-loaded YAML config per domain.** Each domain reads its
own rules file and enforces locally. Avoids hardcoding but
fragments the engine: every domain interprets its own config
schema; no central audit answers "which rules apply to this
organisation?"; rule sets drift between domains.

The first cut at fixing this was **tiers** — organisations
had a tier, and each tier carried a hardcoded rule set. Better
than scatter, but coarse: you couldn't say "this organisation
gets these specific limits but not those," and the rules were
still semi-baked into brick code.

The current design (landed in PR #14 after roughly two weeks
of design work) replaced tiers with **policies and bindings**,
both first-class records:

- Rules are **data** — stored, listable, edit-able, pausable
  via `:enabled`. You can answer "what rules apply here?" by
  loading effective policies.
- Rules are **centralised** — domains call
  `check-capability` and `check-limit`; they do not implement
  rule logic. No domain has its own deny branch.
- Rules **compose** — multiple small policies stack via
  bindings; adding a constraint is adding a policy and a
  binding, not a code change.

The engine returns binary outcomes (true or anomaly) at each
check; richer outcomes (permitted-but-curative, etc.) emerge
from how callers compose checks and how policies set their
allow modes.

## Proposed Solution

### Architecture

The `policy` brick owns the engine, with a deliberate
file split:

```
components/policy/src/com/repldriven/queenswood/policy/
  domain.clj       ; record shapes, validation
  store.clj        ; FDB record store + indices
  match.clj        ; common matching engine
  capability.clj   ; capability evaluator
  limit.clj        ; limit evaluator
  core.clj         ; orchestration, CRUD, get-effective-policies
  interface.clj    ; public API
```

Two checks (`check-capability`, `check-limit`) sharing one
matching engine is the spine. The split lets capability and
limit semantics evolve independently while the matching
contract stays uniform.

### Data model

**Policy** — a unit of rules:

- `:policy-id`
- `:enabled` — boolean. Disabled policies are skipped during
  evaluation.
- `:capabilities` — list of capability rules.
- `:limits` — list of limit rules.
- `:labels` — keyword-keyed labels (e.g.
  `{:tier "platform"}`).

**Capability** — "is this kind of operation allowed?":

- `:kind` — single-entry oneof map. The variant key is the
  operation kind (e.g. `:cash-account/open`); the value is
  the field map specific to that kind, optionally including
  `:filters`.
- `:effect` — `:effect-allow` or `:effect-deny`.
- `:reason` — human-readable reason (used in deny anomalies).

**Limit** — "is this aggregate within bounds?":

- `:kind` — same oneof shape as capability.
- `:bound` — single-entry oneof: `:max`, `:min`, or `:range`,
  each carrying `:aggregate {:kind ... :value ...}`.
- `:allow` — `:limit-allow-strict` or
  `:limit-allow-improving` (the curative permit).
- `:reason`.

**Binding** — links a policy to a target:

- `:binding-id`
- `:policy-id`
- `:selectors` — keyed map identifying the target (e.g.
  `{:bank-id <id>}`).

Both Policy and Binding have full CRUD on the brick interface
(`new-policy` / `get-policy` / `get-policies`; same for
bindings). They are first-class entities, paginated, indexed,
auditable.

### The matching engine

Capabilities and limits both match against a `(kind, request)`
pair. The shared rule, in `match.clj`:

1. The rule's `:kind` variant must equal `kind`.
2. The kind's payload, minus `:filters`, must agree with the
   request — every top-level field equals the request's slot
   of the same key.
3. If `:filters` is non-empty, at least one filter must agree
   with the request. A filter agrees when each *set* field
   equals the request's slot; unset fields don't constrain.

Unset proto fields are recognised by the `match/none?` helper
— `nil` for missing message-typed fields, `:<x>-unknown` for
proto2 enums at the zero default. This lets policies stay
agnostic to fields they don't care about without false
non-matches.

### Capability evaluation

```clojure
(check matching-rules-only)
;;
;; matching = enabled-policies → :capabilities
;;          → filter (matches? c kind request)
;;
;; if any deny in matching → :unauthorized/policy-denied
;; else if any allow      → true
;; else                   → :unauthorized/policy-denied
;;                          ("No matching allow capability")
```

**Deny wins.** An explicit `:effect-deny` overrides every
allow in scope. The default in the absence of any matching
rule is denial — the engine is *positive*, not permissive.

### Limit evaluation

The request shape:

```clojure
{:aggregate :count | :amount
 :window    :instant | :daily | :weekly | :monthly | :rolling
 :value     <number-or-amount>      ; post-state
 :pre-value <number-or-amount>}     ; optional, pre-state
```

The decision rule:

- For each enabled policy's matching limit (kind + filters):
  - Evaluate each side of the bound (`:max`, `:min`, or both
    sides of `:range`) against the request's aggregate.
  - The bound's aggregate must match the request's
    `:aggregate` variant, `:window`, and (for `:amount`)
    `:currency`. Non-matching aggregates are silently
    skipped.
  - If the request's `:value` is out of bound:
    - **Strict** (`:limit-allow-strict`): violation.
    - **Improving** (`:limit-allow-improving`): permitted iff
      `:pre-value` is also out-of-bound and post is no worse
      than pre.
- First violation short-circuits → anomaly.
- No violations (including no matching limits) → permit.

### Curative permits

`:limit-allow-improving` is the *curative pattern*: a request
that would breach a limit is permitted when the pre-state
already breaches and the post-state is no worse.

Example: an account is at `-100` with a `min: 0` limit
(overdrawn). An inbound transfer of `50` brings the balance
to `-50`. Strictly, `-50` is still below the minimum. With
`:limit-allow-improving`, the rule notices that the pre-state
(`-100`) was already in breach and the post-state (`-50`) is
better, so it permits the transfer.

This is genuinely useful in banking: a customer who's already
in breach can correct themselves without being locked out of
the corrective transaction. Strict limits would force a
support intervention or a manual override; curative permits
let the system self-heal under defined conditions.

### Bindings and effective policies

`get-effective-policies` takes a selectors map (e.g.
`{:bank-id <id>}`) and returns the policies that apply. The
lookup follows bindings to find every policy whose binding
matches the selectors.

Today's reality: the function loads platform-tier policies
plus policies bound to the selector's `:bank-id` (via
`PolicyBinding` records); finer selectors (account-type,
product) aren't honoured yet (see Known Limitations).
`get-policies-by-tier` is used at bank creation to apply
tier-labelled policy sets.

### How domains use the engine

A processor that owns a write threads the policy check
through its `error/let-nom>` chain:

```clojure
(error/let-nom>
 [policies (policy/get-effective-policies
            tx
            {:bank-id bank-id})
  _ (policy/check-capability policies
                                  :cash-account/open
                                  {:account-type :savings})
  _ (policy/check-limit policies
                             :cash-account/open
                             {:aggregate :count
                              :window :instant
                              :value (count existing-accounts)})]
  ;; capability allowed and limit not exceeded — proceed
  (open-account! tx ...))
```

If either check returns an anomaly the chain short-circuits
and the anomaly propagates back through the command pipeline
to the API edge, where the error mapping (service-apis TDD)
turns `:unauthorized/policy-*` into 403.

An inbound payment is the exception. Its money has already
arrived, so a refusal parks it rather than rejecting it: the
payment event processor posts the receipt to the bank's 2500
suspense and records a `suspended` InboundPayment, on
settlement and on the release of a hold alike. See
[payments.md](payments.md).

### Why declarative beats imperative here

- **Visible.** A list of policies is a list of rules. You
  can answer "what rules govern outbound transfers?" by
  filtering the policy set.
- **Editable.** Pause a policy with `:enabled false`. Edit
  a limit value. No code change, no redeploy.
- **Composable.** Multiple policies stack via bindings.
  Adding a constraint is adding a policy + binding.
- **Testable.** The evaluator is pure data → result. Test
  fixtures construct policies inline.
- **Auditable.** "Why was this rejected?" is answerable
  from the policy set + the request — no spelunking through
  command handlers.

## Alternatives Considered

- **Hardcoded rules per domain.** The natural starting
  point. Rejected — invisible, tightly coupled, fragmented,
  unmaintainable at scale.
- **Tier-based system (the previous design).** Each
  organisation had a tier carrying a baked-in rule set.
  Coarse and inflexible; rule changes still required
  brick-level edits. Replaced by the policy/binding model.
- **External policy engine (Open Policy Agent, Cerbos).**
  Mature and powerful. Rejected because: introduces an
  external service (operational cost, latency); imports a
  policy language (Rego) the team would need to learn;
  doesn't compose cleanly with the protojure record types
  already used for kinds and aggregates; the bank's
  current scale doesn't justify the operational complexity.
  Worth reconsidering if rule complexity grows past what an
  in-process evaluator can express clearly.
- **Side-loaded YAML config per domain.** Each domain reads
  its own rules. Rejected — fragments the engine, drifts
  per-domain, breaks central audit.
- **One big policy per domain.** Each domain has a single
  policy carrying all its rules. Rejected — defeats the
  composability of multiple small policies; binding becomes
  brittle (you'd need partial-policy bindings to opt out of
  some rules).
- **Single evaluator returning rich outcome maps.** Rather
  than two binary checks, one evaluator returning
  `{:permitted? bool :outcome :permitted | :curative |
  :denied | ...}`. Considered. Rejected for now: two simple
  binary fns compose cleanly with `error/let-nom>`, and the
  curative-vs-strict distinction is encoded in the policy
  itself (via `:allow`), not at the evaluation interface.
  Could revisit if call sites repeatedly want richer info.
- **Capability and limit unified into one rule kind.**
  Capabilities are qualitative (allow/deny); limits are
  quantitative (within bounds). Treating both as one shape
  conflates two different decisions. The split keeps each
  evaluator's semantics clean.

## Known Limitations

- **Binding resolution is bank-scoped and unindexed.**
  `get-effective-policies` resolves platform-tier policies
  plus policies bound to the selector's `:bank-id` via
  `PolicyBinding` records (`policy/.../core.clj`).
  Finer selectors (account-type, product) aren't honoured
  yet, and `get-bindings-for-bank` does a full scan filtered
  in memory (`store.clj`) — a `BankTarget` index is the
  natural follow-up as binding cardinality grows.
- **Capabilities don't have a curative-equivalent.**
  `:limit-allow-improving` is limit-only. Capabilities are
  inherently allow/deny without a quantitative dimension, so
  this may be correct rather than a gap — flagged for
  visibility.
- **Deny-wins is global.** A policy author can't write "this
  binding overrides the deny from that other policy." If
  per-binding overrides become necessary, the combinator
  vocabulary needs extending (priority, scope, override
  semantics).
- **Per-leg evaluation is a call-site concern.** The engine
  takes one `(kind, request)`. For multi-leg postings (a
  transfer touches two balances), each leg's check is the
  caller's responsibility. There's no engine-level "apply to
  all legs" helper.
- **No request-level simulation helper.**
  `get-effective-policy` now returns the resolved decision
  set with provenance (`policy/.../interface.clj`), but
  there's still no `simulate` fn answering "would *this*
  request pass?" without executing — callers must assemble
  the policy set and request shape manually.
- **No policy versioning at evaluation time.** A policy
  edit takes effect for any subsequent evaluation. There's
  no "evaluate against the policy version that was current
  when the request was made" mode. For audit trails, this
  matters; the policy record itself carries no version
  history beyond what FDB's record-versioning gives us.
- **In-process evaluation only.** Policies are loaded and
  evaluated in the calling process. No remote evaluation
  service, no federation across deployments. Acceptable for
  current scale; would need rethinking for multi-tenant
  policy stores at higher tenancy.
- **Anomaly attribution is reason-text only.** Capability /
  limit rejections carry only `:message`, `:kind`, `:request`
  (`capability.clj`, `limit.clj`) — not the deciding
  `:policy-id` or clause index. Provenance exists on the
  resolution side (`effective.clj` `:origin`) but isn't
  threaded into the live evaluators' anomalies. A future
  enrichment could surface `:policy-id` / `:capability-index`
  at rejection time.

## References

- [ADR-0002](../adr/0002-foundationdb-record-layer.md) —
  FoundationDB Record Layer (policy storage)
- [ADR-0005](../adr/0005-error-handling-with-anomalies.md) —
  Error handling with anomalies (the
  `:unauthorized/policy-*` anomaly kinds)
- [transaction-processing.md](transaction-processing.md) —
  Transaction processing (envelope, command flow)
- [service-apis.md](service-apis.md) — Service APIs (anomaly
  → HTTP status)
- [error-handling.md](../recipes/code/error-handling.md)
- `policy` brick interface
