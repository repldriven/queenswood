# Lifecycle transitions

<!-- tessl-plugin: design -->

## Problem

You're adding a new state, or a new transition between existing
states, to an entity's lifecycle (a cash-account, a party, a
payment, a policy, …). You need to know everywhere a transition
touches, and how to stop it firing from the wrong source state.

## Solution

Every lifecycle transition — synchronous or event-driven — goes
through the same ten-point definition-of-done, and every transition
function guards its source state before doing anything else.

### The definition-of-done checklist

1. **Proto enum** — the new status value is appended to the
   entity's status enum, never inserted or reordered (proto enums
   are append-only on the wire).
2. **Avro schema** — if the transition introduces a new command or
   response shape, its `.avsc.json` is registered in **both**
   `bank/avro.yml` and `avro-test.yml`. See
   [common-helpers](common-helpers.md) for the wider
   registration-in-two-places pattern this follows.
3. **`domain.clj`** — a pure transition function with a source-state
   guard as its first check (see below).
4. **`core.clj`** — orchestration inside `store/transact`, saving the
   updated record with a before/after changelog entry.
5. **`commands.clj`** — command dispatch wired to the new
   `core.clj` entry point.
6. **`interface.clj`** — a public fn exposing the transition to
   callers outside the brick.
7. **`events.clj`** — the async second leg, if the transition is
   two-phase (a command moves the entity to an intermediate status;
   an event relayed off the changelog completes it). Carries the
   same source-state guard, as an idempotency gate (see below).
8. **`api`** — the HTTP route, its OpenAPI request/response
   components, and the rejection→status mapping for any new
   rejection kind the transition can produce.
9. **Service YAML** — the event channel, its topic, and the
   consumer that subscribes it are declared in every system that
   needs the reaction, including the monolith and the test rigs.
   The consumer is the brick's own `<brick>/event-processor` kind
   wrapped in mono's `event-processor/event-processor`, which
   redelivers when the handler throws or returns an anomaly.
10. **Tests** — a model-command test (or update to an existing one)
    and an API-scenario test covering the new transition.

### Source-state guards in `domain.clj`

A transition function asserts its source state as the *first*
binding of its `let-nom>`, before any capability or limit check:

```clojure
(defn close-account
  [account policies]
  (let-nom>
    [_ (when-not (= :cash-account-status-opened (:account-status account))
         (error/reject :cash-account/invalid-status
                       {:message "Account is not in a closeable state"
                        :account-id (:account-id account)
                        :status (:account-status account)
                        :allowed #{:cash-account-status-opened}}))
     _ (check-capability :cash-account-action-close
                         (:account-type account)
                         policies)]
    (assoc account
           :account-status :cash-account-status-closing
           :updated-at (utility/now))))
```

The rejection kind is `:<entity>/invalid-status` — one kind per
brick, not one per transition — and the payload always carries
`:message`, the entity's id key, `:status` (the actual, offending
status), and `:allowed` (the set of source statuses the transition
accepts).

There's no shared `utility` helper for this guard. The rejection
kind and payload are domain-specific per entity, the inline form is
about three lines, and a generic helper would need `error` — a
mono-inherited brick, so widening it for a bank-only convention
means an upstream PR. Revisit once three or more bricks have landed
literally identical guard shapes (the [common-helpers](common-helpers.md)
convergence rule).

### Event-handler guards are an idempotency gate, not a rejection

The async second leg checks the *loaded* record's current status
against the expected source before transitioning, and silently
skips — no rejection, no error — when it doesn't match. Delivery
off the relay is at-least-once, so a handler must tolerate
redelivery and replay; skipping a transition whose source state has
already moved on is correct, rejecting it is not.

`party/core.clj`'s `apply-idv-status` is the canonical exemplar:

```clojure
(when (= :party-status-pending (:status party))
  (let [updated (transition party)]
    (store/save-party txn updated ...)))
```

`cash-account/core.clj`'s `complete-status-transition` carries the
same gate: the `opening -> opened` leg only fires when the loaded
account is still `:cash-account-status-opening`, and
`closing -> closed` only from `:cash-account-status-closing`.

### Rejection mapping

`:<entity>/invalid-status` maps to HTTP 409 (a state conflict, not a
validation failure) in `api`'s `rejection-status-overrides`
table — it doesn't fit the default 422 or the `not-found`/`exists`
heuristics `rejection-kind->status` otherwise applies.

## Rules

**MUST:**

- Guard a transition function's source state as the first binding
  of its `let-nom>`, before any capability or limit check.
- Name the rejection kind `:<entity>/invalid-status`, one per
  brick, with payload `{:message … :<id-key> … :status … :allowed
  #{…}}`.
- Gate an event handler's transition leg on the loaded record's
  current status matching the expected source, and skip silently
  (not reject) when it doesn't.
- Map `:<entity>/invalid-status` to HTTP 409 in `api`'s
  rejection→status table.
- Work through the ten-point checklist for every new lifecycle
  state or transition: proto enum, Avro schema (both YAMLs),
  `domain.clj` guard, `core.clj` orchestration, `commands.clj`
  dispatch, `interface.clj` fn, `events.clj` leg (if two-phase),
  `api` route/OpenAPI/rejection mapping, the event channel and
  consumer wiring, and tests.

**MUST NOT:**

- Reach for a shared `utility` guard helper before three or more
  bricks have landed identical guard shapes.
- Let an event handler reject on an unexpected source state —
  replay and redelivery must be a no-op, not a failure.

## Discussion

The guard belongs in `domain.clj`, not `core.clj` or `commands.clj`,
because it's a domain rule ("you can't close an account that's
already closing") and `domain.clj` is where domain rules live —
pure functions, no FDB, no transaction. Ordering it first, ahead of
capability and limit checks, means a request against the wrong
entity state fails fast and cheaply, before any policy evaluation
runs.

The synchronous-guard and event-guard shapes look similar but
answer different questions. A synchronous transition is a request:
the caller asked for something that isn't valid right now, and the
system says no — a 409. An event-driven transition is a reaction to
a changelog entry the write side already committed: by the time the
handler runs, the request has already succeeded, and the handler's
job is just to catch the record up. If the record has already moved
past the expected source — because this event was redelivered, or
because a later change overtook it — there is nothing to reject;
the desired end state is already true or superseded.

That guard is also what hides reordering, which is worth knowing
when it stops being harmless. Every topic is single-partition today
and `message-bus/send` passes no partition key, so a `closing`
event overtaking its `opening` would land on the guard and skip
silently. See [ADR-0021](../../adr/0021-changelog-relay.md).

## References

- [ADR-0021](../../adr/0021-changelog-relay.md) — the changelog
  relay for reactive state transitions
- [error-handling](error-handling.md) — anomaly kinds and
  `let-nom>`
- [common-helpers](common-helpers.md) — the convergence rule for
  promoting inline patterns to shared helpers
- `components/cash-account/.../domain.clj`,
  `components/cash-account/.../core.clj` (`complete-status-transition`)
  and `.../events.clj` — the worked example
- `components/party/.../core.clj` (`apply-idv-status`) and
  `.../events.clj` — the event-gate exemplar
