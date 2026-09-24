# Idempotency: gap analysis

Subject: [idempotency.md](../idempotency.md) — Idempotency.

## Verdict

**Fail.**

The API-layer cache the TDD describes is implemented as written: the
header contract, the principal-and-operation scope, the two-state
entry, the single claim-or-replay transaction, the EDN replay, the
completed and stale-pending lifetimes, the release on a 5xx and on the
error path, and the record schema all match the code. The verdict
fails on the layer beneath it and on the routes around it:

- The per-store key index the TDD presents as the inner loop carries
  no bank for payments and transactions, so a key two banks happen to
  share hands the second bank the first bank's payment and never makes
  its own (F1).
- The objective covers every write operation; the interceptor pair
  covers the four route groups the scope list names and nothing added
  since, bank creation included, which the design ADR names as the
  write whose duplication does damage (F2).
- When the cache releases its claim on a 5xx, which a lost command
  reply is, the one-transaction guarantee rests on the store index the
  TDD says was not needed; party creation and address rotation have
  none (F3).
- Nothing exercises the cache: the brick has no tests and no scenario
  sends a key twice (Missing evidence).

The verdict flips to pass when the payment and transaction indexes
carry the bank, every write route either declares the pair or is
recorded as an exception the TDD explains, the TDD says which routes
rely on which layer, and the scenario suite replays a key.

## What was examined

- The subject TDD, and the documents it cites: the service-APIs and
  transaction-processing TDDs and ADR-0021, together with ADR-0018,
  which the unprotected routes cite.
- The `idempotency` brick: interface, interceptor, core, store and
  system files.
- The `api` base: the router assembly and its interceptor order, every
  route file's write methods and interceptor declarations, the command
  dispatch, the auth principal shapes, the anomaly-to-response mapping,
  the shared OpenAPI parameters, and the simulate, product and
  migration handlers.
- The `fdb` brick's transaction, keyspace and record-store components,
  and the record-type metadata under `resources`.
- The proto for the cache entry and for payments and transactions, and
  the payment, transaction, cash-account, party, product and migration
  processors' command dispatch and cores.
- The `server`, `command` and `error` bricks of the `mono` dependency
  at the tag pinned under `deps/mono`.
- Tests: the brick's own (none), the store tests for payments,
  transactions and cash accounts, the API scenario suite and the
  domain scenario suite.

Nothing was executed. Every finding below was traced in source, and
the ones that predict runtime behaviour say so.

## What matches

- `require-idempotency-key` accepts 16 to 255 characters of letters,
  digits, `_` and `-`, and terminates with a 400 problem body of type
  `server/missing-idempotency-key` or `server/invalid-idempotency-key`.
- The envelope's `:id` and `:correlation-id` carry the header value
  and the payload never does; a route without the header gets a
  server-generated id at dispatch.
- The `idempotency` record store's primary key is
  `[principal_id, operation, idempotency_key]` with no secondary
  index, and the proto's fields, types and optionality match the
  table.
- `claim-or-replay` runs one Record Layer transaction under `run`,
  which retries on conflict, and yields completed, in-flight or
  claimed exactly as tabled; the stale-pending window is 60 s off
  `created_at`, the completed lifetime 24 h off `expires_at`, and a
  stale or expired entry is overwritten by a fresh `pending` marker in
  the same transaction.
- `:leave` writes `completed` for any status below 500 and deletes the
  marker otherwise; `:error` deletes it; the body is written with
  `pr-str` and read back with `edn/read-string`.
- The pair sits at route level, after the `/v1` group's authenticate
  and authorize interceptors, on party creation and merge, both
  payment submissions, the three simulate routes and the five
  cash-account writes.
- Service principals scope by `azp`, so every holder of the
  `queenswood-admin` client shares one scope, as the limitation says;
  user principals scope by user id.
- The 409 in-flight response is an RFC 9457 body of type
  `mono/idempotent-request-in-flight`.
- There is no sweeper; `system.clj` registers nothing.
- Cash accounts, products and migrations index
  `[bank_id, idempotency_key]` uniquely and read the existing record
  back on a violation, and the adapter outbox and intent stores are
  unique on `dedup_key`.

## Findings

Severity is High where a tenant boundary or a stated guarantee is
broken, Medium where the design and the code disagree in a way a
reader would act on, Low where the document is incomplete.

### F1. High — the payment and transaction key indexes span every bank

The TDD's Background offers the `transaction` and `payment` stores as
the inner loop, "atomic with the write". Both stores are opened by
name under an environment prefix, so one index covers every bank, and
neither index carries the bank: `InternalPayment_by_idempotency_key`
and `OutboundPayment_by_idempotency_key` are unique on
`idempotency_key` alone, and `Transaction_by_idempotency_key` on
`[transaction_type, idempotency_key]`; the transaction record has no
bank field at all. The cash-account, product and migration indexes are
`[bank_id, idempotency_key]`. The payment processor takes the envelope
id as the payment's key, and on a uniqueness violation
`or-already-submitted` reads back by key alone and returns whatever it
finds. Traced, not executed: bank A submits an internal payment under
key K; bank B later submits one under the same K; B's transaction
rolls back at commit, the read-back returns A's payment, and B
receives A's payment id, accounts, amount, reference and bank id as a
200, which the cache then replays to B for a day. B's payment is never
made. The simulate inbound-transfer route records its transaction
through the same index under the client's header value and collides
the same way. The API-layer scope by principal masks this rather than
preventing it, and the store tests reuse a key within one bank only.
Evidence:
[fdb-record-types.yml](/components/resources/resources/system/fdb-record-types.yml),
[core.clj](/components/payment/src/com/repldriven/queenswood/payment/core.clj)
in the `payment` brick and
[store.clj](/components/payment-query/src/com/repldriven/queenswood/payment_query/store.clj)
in `payment-query`.

Fix: index payments on `[bank_id, idempotency_key]`, add a bank field
to the transaction record and index
`[bank_id, transaction_type, idempotency_key]`, pass the bank into
both read-backs, bump the metadata version, and add a two-bank test
for each store. Say in the TDD that the inner layer's scope is the
bank.

### F2. High — the objective says every write; the pair covers four groups

The Objective promises the guarantee "for every write operation in the
bank", the scope list names cash accounts, parties, payments and the
simulate endpoints, and those are the only routes that declare the
pair. Every write route added since carries neither interceptor: bank
creation and the two bank changes, the five product writes,
payee-check creation, migration creation, approval, cancellation and
preview, the job schedule update and forced run, and user onboarding.
The token proxy is the one deliberate omission. Two of these have
their own guard: product writes and migration creation read an
optional, unvalidated `Idempotency-Key` off the request and let their
`[bank_id, idempotency_key]` index return the existing record, the
synchronous regime ADR-0018 describes and the TDD does not mention.
The rest have nothing. Bank creation is the sharpest case: ADR-0018
names "two banks" as the damage an idempotency check exists to
prevent, the command reaches the processor with a server-generated id,
the bank store's only unique index is a sort code the processor
allocates, and the processor mints a Keycloak client before the write,
so a client retry after a lost reply makes two banks and two clients.
A payee check calls an external service per request; a migration
preview creates a run per request and counts against the daily cap;
a forced job run starts a run per request. The limitation that calls
this "worth a lint check" is unmet; nothing checks a route's
declarations. Evidence:
[routes.clj](/bases/api/src/com/repldriven/queenswood/api/bank/routes.clj)
in the `bank` namespace,
[handlers.clj](/bases/api/src/com/repldriven/queenswood/api/cash_account_product/handlers.clj)
in `cash-account-product`, and
[commands.clj](/bases/api/src/com/repldriven/queenswood/api/commands.clj).

Fix: add the pair to bank creation, payee checks, job runs and
migration previews; give the status-guarded transitions the pair or
record them as exceptions with the guard that covers them; describe
the ADR-0018 regime in the TDD and say whether its optional key is
enough; add a router test that every write route either declares the
pair or appears in an allow-list.

### F3. Medium — after a release, the guarantee rests on the store layer

The `:leave` table deletes the marker on a 5xx so the client can
retry, and a lost command reply is a 5xx: the dispatcher waits ten
seconds by default, and the transaction-processing TDD says the
command may have committed meanwhile, with the store-level index
making the retry safe. The subject TDD's alternatives section says the
API-layer cache "already satisfies the durability and correctness
requirements without per-store integration work". Both cannot hold.
Behind the pair, cash-account opening and both payment submissions
have an index; party creation does not (a retried party whose national
identifier is already indexed is refused with a 422 rather than
returned, and any other is created twice), party merge does not, and
the four account transitions do not, of which address rotation
allocates fresh addresses on every call. The same window opens when
the `complete` write itself fails, which F4 covers. Evidence:
[core.clj](/components/party/src/com/repldriven/queenswood/party/core.clj)
in the `party` brick and
[transaction-processing.md](../transaction-processing.md).

Fix: state in the TDD that the cache guarantees exact replay only
while its own write succeeds and the handler's outcome is known, that
a 5xx hands the guarantee to the processor, and which processors
behind the pair carry a key index; give party creation and address
rotation one, or accept the duplicate in writing.

### F4. Medium — the cache's own failures are silent or opaque

The brick logs nothing. In `:leave` the result of `complete` or
`release` is discarded, so a failed completion leaves the `pending`
marker in place: the client holds a 2xx, the next retry within a
minute gets a 409, and the one after that re-runs the handler. In
`:enter` an anomaly from `claim-or-replay`, whether an FDB outage or a
stored body `edn/read-string` cannot parse, is a vector, so
`(:type result)` is nil and the `case` throws; the router's default
exception handler logs and returns that dispatch exception as a 500 of
type `server/internal-error`, and the anomaly, with the FDB exception
it carries, is lost. Traced, not executed. The TDD documents no
failure mode for the store it puts in front of every protected write.
Evidence:
[interceptors.clj](/components/idempotency/src/com/repldriven/queenswood/idempotency/interceptors.clj)
and
[core.clj](/components/idempotency/src/com/repldriven/queenswood/idempotency/core.clj)
in the `idempotency` brick.

Fix: map an anomaly at claim time to a 503 problem detail, log an
anomaly from `complete` or `release` with the key, decide whether a
failed completion should fail the response, and state the policy
under a failure-modes heading.

### F5. Medium — the cache scope ignores path parameters

The operation is the route template, so
`[principal, POST /v1/cash-accounts/{account-id}/close, K]` is one
entry for every account. A client that reuses K to close a second
account, to merge a second party, or as an admin to accrue for a
second bank, receives the first resource's cached response as a 200
and the second request never runs. The TDD cites Stripe's model, which
refuses a key reused with different parameters; the design neither
fingerprints the request nor says that it does not. Evidence: the
`operation` function in the interceptor file cited under F4.

Fix: either fold the matched path, or a hash of path and body, into
the entry and reject a mismatch with a 422, or state the client
contract, one key per request rather than per resource, in the TDD and
the API reference.

### F6. Medium — the principal and the two lifetimes are described wrongly

The Cache scope section keys org-scoped requests on an `api-key-id`, a
credential model the authentication TDD says was removed, and Keycloak
requests on `azp`, which is right for service tokens and wrong for
user tokens, which the code scopes by user id, as the TDD's own
limitations section says. The brick's docstrings say admin requests
share the literal `"admin"`; they share `queenswood-admin`, the `azp`.
The proto comment repeats the API-key wording. The limitation "key
reuse is allowed after 24 h" describes the cache alone: the store
indexes never expire, so a key reused after a day on account opening
or a payment returns the original resource rather than a fresh
transaction, while on party creation it creates a second party.
Evidence:
[interface.clj](/components/idempotency/src/com/repldriven/queenswood/idempotency/interface.clj)
and
[idempotency.proto](/components/schema/resources/schemas/idempotency/idempotency.proto).

Fix: rewrite the scope section around `:principal-id`, the bank id
for service tokens, `queenswood-admin` for the admin client and the
user id for users; correct the docstrings and the proto comment; state
both lifetimes and what a reused key does on each kind of route.

### F7. Low — the status the cache records is the handler's, not the client's

Route-level `:leave` runs before the router-level response coercion
and exception interceptors, so the cache sees the handler's response.
A 200 whose body fails response coercion reaches the client as a 500
of type `server/bad-response` while the cache holds a completed 200 with
the same body, and every replay repeats the 500 until the entry
expires. The `:leave` table describes a 5xx as released and retryable.
Traced, not executed. Evidence: the router data in the `server` brick
of `mono`, and
[api.clj](/bases/api/src/com/repldriven/queenswood/api/api.clj).

Fix: note the ordering in the TDD, and either move the pair inside the
coercion interceptor or accept that a coercion failure pins the key
for a day.

### F8. Low — the replayed and in-flight responses are undeclared

No protected route declares the 409 in-flight response in its
`:responses`, so the OpenAPI document advertises a 409 only for domain
rejections and only on the routes that have them; the header's two 400
types have no example either. A replay is indistinguishable from a
fresh response, where the model the TDD cites marks one with a header.
The cache stores status and body only, so a protected route that later
adds a `Location` header would not replay it. The simulate routes
advertise a 422 of type `transaction/already-recorded` that nothing
emits: a duplicate key returns the existing transaction through the
store index instead.

Fix: add a shared 409 response and the 400 examples to the OpenAPI
assembly, add an `Idempotent-Replayed` header on replay and mention it
in the TDD, and drop or implement the simulate 422.

### F9. Low — the in-scope bricks live upstream

The References list the `server` and `command` bricks as if they were
local; both are `mono` bricks consumed at a pinned tag, so a change to
the header contract or the envelope needs a release and a pin bump.
The Background also cites ADR-0021 for the envelope's `:id`, which
that ADR does not discuss; the transaction-processing TDD does.

Fix: say they are upstream, link
[ADR-0001](../../adr/0001-reuse-mono-as-upstream.md), and point the
envelope sentence at the transaction-processing TDD.

## Missing evidence

- The `idempotency` brick has no test directory, so `claim-or-replay`,
  `complete`, `release`, the interceptor's three `:enter` outcomes,
  its `:leave` branches and its `:error` stage have no unit tests.
- The API scenario suite sends a distinct key on every write and never
  sends one twice, so no scenario asserts a replayed body, a 409 while
  a request is in flight, a 400 for a missing or malformed header, or
  a released claim after a 5xx.
- No test sends two principals the same key on the same operation, so
  the per-principal scope is unverified and the cross-bank collision
  in F1 is untested at every layer.
- No test covers the stale-pending reclaim after 60 s, expiry after
  24 h, a failed completion write, or the cache store being
  unavailable.
- The payment and transaction store tests reuse a key within one bank;
  nothing tries a second bank.
- The domain scenario suite's idempotency scenario covers scheme
  settlement redelivery, which is the outbox path the TDD puts out of
  scope, not the client-key path.

## Recommended fixes

In order:

1. Close F1: carry the bank in the payment and transaction indexes and
   read-backs, with a two-bank test per store.
2. Put the pair on bank creation, payee checks, job runs and migration
   previews, and add the router test that every write route declares
   the pair or an explicit exception (F2).
3. Add scenarios that replay a key, race a key, omit and malform the
   header, and send one key from two principals; add unit tests for
   the brick.
4. Give party creation and address rotation a store-level key, or
   document the duplicate (F3), and log and map the cache's own
   failures (F4).
5. Rewrite the TDD's scope, lifetimes, principal, failure-mode and
   reference sections against the code (F3, F5 to F9), and describe
   the ADR-0018 regime.
6. Declare the 409 and 400 responses and the replay header in the
   OpenAPI assembly, and remove the simulate 422 nothing emits (F8).

## References

- [idempotency.md](../idempotency.md) — Idempotency
- [service-apis.md](../service-apis.md) — Service APIs
- [transaction-processing.md](../transaction-processing.md) —
  Transaction processing
- [authentication.md](../authentication.md) — Authentication
- [ADR-0001](../../adr/0001-reuse-mono-as-upstream.md) — Reuse mono as
  upstream
- [ADR-0018](../../adr/0018-command-writes-are-earned.md) — Command
  writes are earned, not default
- [ADR-0021](../../adr/0021-changelog-relay.md) — Changelog relay
- [authentication.md](authentication.md) — Authentication: gap analysis
