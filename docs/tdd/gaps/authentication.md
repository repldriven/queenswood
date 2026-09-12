# Authentication: gap analysis

Subject: [authentication.md](../authentication.md) — Authentication.

## Verdict

**Fail.**

The edge verification the TDD describes is implemented as written:
provider selection on the unverified issuer, RS256 with issuer, expiry
and audience checks, the two principal shapes, the role vocabulary and
the 401 / 403 gates all match the code. The verdict fails on what sits
around that core:

- One route under the `org` gate takes its bank from the path and never
  compares it with the principal's, which breaks the attribution
  invariant every other handler relies on (F1).
- The service-account lifecycle the TDD documents is not the one the
  code runs: a limitation it lists as unwired is wired, an operation it
  omits is called on every status change, and the credential reaches
  the caller by a different path (F2, F3).
- The onboarding and platform PRDs the TDD is meant to satisfy still
  specify the static API-key model the TDD says was removed (F4).
- The user path, the operator realm and every verification failure
  mode have no test evidence (Missing evidence).

The verdict flips to pass when F1 is closed with a test, the TDD's
lifecycle and limitations describe the code, the PRDs describe the
credential model in product terms, and the user path is exercised by
the scenario suite.

## What was examined

- The subject TDD, and the sibling TDDs it cites: banks, service APIs
  and policy evaluation.
- The `api` base: the auth interceptors, the router assembly, every
  route's `bearerAuth` declaration, the OAuth proxy handlers, the bank
  handlers and the claims projection.
- The `user`, `membership` and `bank` components, and the system YAML
  and realm imports under `resources` and `test-resources`.
- The `identity-provider` and `keycloak` bricks of the `mono`
  dependency, at the tag pinned under `deps/mono`, together with the
  `http-client` brick they call through.
- The Helm realm-import Job and the API deployment's Keycloak
  environment.
- The API scenario suite (`test-api-scenarios`) and the upstream brick
  tests.
- The PRDs that cite the TDD: users, memberships, onboarding and
  platform.

Nothing was executed. Every finding below was traced in source, and the
ones that predict runtime behaviour say so.

## What matches

- `authenticate` never short-circuits, reads `iss` unverified, picks
  the provider by `get-issuer`, verifies with the configured
  `expected-audiences`, and discriminates user from service on `azp`
  against `user-client-ids`.
- Both `:auth` maps match the TDD field for field, including the
  admin-without-bank rule and `:org` from the first membership.
- `authorize` passes routes without security, returns 401 with
  `auth/unauthenticated` on an empty role set and 403 with
  `auth/forbidden` on no intersection.
- The Keycloak adapter verifies RS256 against JWKS, force-refreshes once
  on an unknown `kid`, caches JWKS for ten minutes and the admin token
  to 80 % of its lifetime, and rejects with `:auth/unauthenticated`
  rather than throwing.
- The user upsert is keyed on the `(issuer, sub)` pair through a unique
  index and skips the write when no claim changed.
- The realm imports carry the two realms, the two PKCE (S256) SPA
  clients, the `queenswood-admin` service account holding the `admin`
  realm role, the two status-derived audience scopes with their
  audience mappers, and the one-hour access-token lifespan.
- `create-service-account` runs before the FDB write, `client_id` is
  the bank id, the token proxy is `POST /oauth/token`,
  `revoke-service-account` has no caller, and nothing consumes
  `:token-jti`.

## Findings

Severity is High where a tenant boundary or a stated guarantee is
broken, Medium where the design and the code disagree in a way a
reader would act on, Low where the document is incomplete.

### F1. High — the simulator credits another tenant's bank

The TDD states that the rest of the system reads `:bank-id` off the
request and never asks how it got there, and lists the simulator under
the `admin` gate. The inbound-transfer route replaces that gate with
`org` so a tenant can fund its own bank, and takes `bank-id` from the
path. The handler's `check-bank` only confirms that bank exists. Any
principal carrying `org`, which is every bank's service token and every
member, can therefore name another bank in the path and post a credit
into that bank's books. Evidence: the simulator's
[routes.clj](/bases/api/src/com/repldriven/queenswood/api/simulate/routes.clj)
and its `check-bank` in `handlers.clj`. No scenario sends a foreign
bank id.

Fix: in the handler, reject when the path's bank differs from
`(:bank-id auth)` unless the principal carries `admin`, add a scenario
asserting that rejection, and record the exception in the TDD's gate
list.

### F2. High — the lifecycle section and its limitation are stale

The TDD says the client secret is surfaced once in the create-bank
response and that `rotate-secret` has no callers. The code discards the
secret minted at creation, because the reply crosses the command bus,
and the API handler then calls `rotate-secret` to mint the one it
returns. Creation itself runs in the operational processors service,
which wires its own Keycloak identity-provider for the purpose, so two
services hold the admin credential rather than one. Evidence:
[commands.clj](/bases/api/src/com/repldriven/queenswood/api/bank/commands.clj)
in the API base, `new-bank` in
[core.clj](/components/bank/src/com/repldriven/queenswood/bank/core.clj),
and [bank.yml](/components/resources/resources/system/bank.yml). The
banks TDD carries the same stale sentence.

Fix: rewrite the lifecycle section around the bus round trip and the
rotate-after-reply step, restate the limitation as "no rotate or revoke
endpoint", and name both services that authenticate to Keycloak.

### F3. Medium — the audience swap on status change is undocumented

The interface exposes `update-service-account-audience`, and
`change-status` calls it before the FDB write so a bank moving between
test and live re-points its client's audience. The TDD lists neither
the operation nor the transition, and the onboarding PRD says status is
fixed once minted. The banks TDD does describe the swap.

Fix: add the operation to the lifecycle list, and say that a status
change swaps the audience and that outstanding tokens keep the old one
until they expire.

### F4. High — the approved PRDs specify the removed credential model

The onboarding PRD's goals require a `sk_live.` or `sk_test.` prefixed
API key delivered once, its flows mint and hand over a key, and its
open questions say status cannot change. The platform PRD's onboarding
flow ends with calls "authenticated by Bearer key". The TDD opens by
saying static keys were removed. Only the users PRD describes the
Keycloak model. Evidence: [onboarding.md](../../prd/onboarding.md) and
[platform.md](../../prd/platform.md).

Fix: reconcile both PRDs to a service-account credential delivered once
at creation, a status change that keeps the credential and changes what
it may reach, and rotation and revocation as the open gap. Keep the
product register.

### F5. Medium — a refused Keycloak write looks like success

The TDD promises that an identity-provider failure aborts bank creation
cleanly. In the `http-client` brick a non-2xx status is an ordinary
response, and the `keycloak` brick's `core.clj` never checks the status
of the client POST, the audience PUT, the client DELETE or the secret
POST. Creation is caught indirectly, because the secret lookup that
follows fails to find the client. The audience swap is not: a refused
PUT lets the status change persist with the old audience, which is the
outcome the code comment says it exists to prevent. A refused secret
regeneration returns a nil secret as success.

Fix: upstream, treat a non-2xx admin response as an anomaly carrying
Keycloak's error body, then bump the pin. Add a fault-injection test
for each write.

### F6. Medium — wrong credentials at the token proxy do not yield 401

Traced, not executed. `exchange-client-credentials` returns whatever
body Keycloak sends, so a refused grant arrives as a plain map holding
`error`. The proxy handler only maps anomalies to 401 and returns
everything else as 200, where response coercion against
`TokenResponse` fails and the caller receives a 500 `mono/bad-response`
instead of the 401 `invalid_client` the route advertises. Evidence:
[handlers.clj](/bases/api/src/com/repldriven/queenswood/api/oauth/handlers.clj)
in the `oauth` namespace. The only token scenario is the happy path.

Fix: map a non-2xx token response to an anomaly upstream, or check for
`error` in the handler, and add a wrong-secret scenario asserting 401.

### F7. Medium — a failed user upsert degrades silently

`user-auth` discards an anomaly from `upsert-by-sub` without logging
and continues as a `:user` principal with a nil `:principal-id` and no
memberships. The request reaches `/v1/me`, whose docstring guarantees
the row exists. The TDD documents no failure mode for the store write it
puts on every user request. Evidence: `nilable-result` in
[auth.clj](/bases/api/src/com/repldriven/queenswood/api/auth.clj).

Fix: decide whether a store failure is a 503 or a degraded principal,
document it, log the anomaly either way, and cover it with a unit test.

### F8. Medium — an admin on an org route forwards a nil bank

Admin principals carry `org` and no `:bank-id`, so they pass every
`org` gate. Those handlers take the bank from the principal, not the
path, and forward nil into the command. The TDD says admin routes are
platform-wide or take the bank from the path, and says nothing about
admins on org routes. What the processor does with a nil bank is
untested.

Fix: state the rule, then either reject an `org` route without a
`:bank-id` in `authorize` or accept a bank parameter for admins.

### F9. Medium — the bare `bearerAuth` default is undocumented

`required-roles` maps a security entry with no explicit roles to
`#{:org}`, while its docstring says such an entry permits any
authenticated principal. No route uses the bare form today. The TDD
describes only the explicit form.

Fix: document the default in the TDD and correct the docstring, or
reject the bare form at router build time.

### F10. Low — the gate summary has drifted

Beyond F1, the `companies` routes carry `user`, and the jobs,
ledger-account, cash-account-migration, cash-account-product,
payee-check and `/me/policies` routes carry `org`.

Fix: replace the observed list with the rule behind it, platform-wide
resources under `admin`, tenant resources under `org`, identity routes
under `user`, and list the exceptions.

### F11. Low — accepted audiences and the issuer override

`expected-audiences` also holds the two SPA client ids, because each
SPA client's audience mapper points at itself. An `expected-issuer` per
provider overrides the base-url-derived issuer for both provider
selection and the `iss` check, which is how an in-cluster base URL
coexists with a public issuer. Neither appears in the TDD. Evidence:
[application.yml](/projects/api-service/resources/application.yml) and
[keycloak.yml](/components/resources/resources/system/keycloak.yml).

Fix: list all four audiences and say which principal carries which, and
describe the override.

### F12. Low — the API's own identity is undocumented

The `queenswood-admin` client authenticates to Keycloak with a client
secret under the dev and test profiles and with `private_key_jwt` when
deployed, the realm import declaring `client-jwt` and the key arriving
as a file. The TDD mentions only the cached admin token.

Fix: add a paragraph on the credential, where it lives per profile, and
what it is entitled to do.

### F13. Low — deployed realms keep local redirect URIs

The committed realm imports list `http://localhost` redirect URIs on
both SPA clients with `webOrigins` set to `+`. The import Job appends
the deployed console origin and reconciles it on later runs, but never
removes the local entries, and does not reconcile the operator app's
origin at all. The SPA flow is out of the TDD's scope, but the realm
and client layout is in it. Evidence:
[keycloak-realm.json](/components/resources/resources/keycloak-realm.json)
and
[job-realm-import.yaml](/infra/helm/queenswood/templates/job-realm-import.yaml).

Fix: keep local redirects in the test realms only, and reconcile the
operator app's origin the way the console's is.

### F14. Low — a Keycloak call inside a retried transaction

`new-bank` calls `create-service-account` inside the function that
FDB's `run` may re-execute on conflict. A retry mints a new bank id and
a second client, and nothing removes the first. The TDD covers the
identity-provider failing before the write, not the write failing after
the identity-provider succeeded.

Fix: document the orphan as a known limitation, or move the call behind
an intent as the transaction-processing TDD prescribes for external
calls.

### F15. Low — unknown `kid` refreshes are unthrottled

Every bearer whose header names an unknown `kid` forces a JWKS fetch
from Keycloak, so an unauthenticated caller can drive one round trip
per request against the dependency the stateless design is meant to
tolerate losing.

Fix: rate-limit the forced refresh, and note it under limitations.

### F16. Low — the in-scope bricks live upstream

The TDD scopes in the `identity-provider` brick and its `keycloak`
implementation, but both are `mono` bricks consumed at a pinned tag,
and the References section names them without a link or a location.

Fix: say they are upstream, link
[ADR-0001](../../adr/0001-reuse-mono-as-upstream.md), and note that a
fix there needs a release and a pin bump.

## Missing evidence

- The `api` base has no test directory, so `authenticate`,
  `authorize` and `required-roles` have no unit tests.
- The scenario suite mints service tokens from the orgs realm only, as
  its own comment says. No scenario presents an SPA user token, calls
  `/v1/me` or `/v1/onboarding`, gains `org` through a membership,
  exercises an admin user with no bank, or dispatches to the operator
  realm.
- No test covers an expired token, a rejected audience, an unknown
  issuer, an unknown `kid` with a refreshed key, or JWKS cache expiry.
  The upstream `keycloak` tests cover client assertions and config
  validation, not `verify-token-impl`, `jwks!` or `admin-token!`.
- No test covers a refused Keycloak write (F5), a refused grant at the
  token proxy (F6), a failed user upsert (F7), or a foreign bank id at
  the simulator (F1).
- Two scenario names still describe the removed model, "bank key" and
  "wrong key", which hides what they exercise.

## Recommended fixes

In order:

1. Close F1 in the simulator handler and add the scenario.
2. Upstream, make a non-2xx admin or token response an anomaly (F5,
   F6), release, and bump the pin under `deps/mono`.
3. Add the missing scenarios for the user path, the operator realm and
   the verification failures, and unit tests for the interceptors.
4. Rewrite the TDD's lifecycle, limitations, gates, audiences and
   identity sections against the code (F2, F3, F8 to F12, F14 to F16).
5. Reconcile the onboarding and platform PRDs with the credential model
   (F3, F4).
6. Settle the upsert failure policy (F7) and the realm redirect hygiene
   (F13).

## References

- [authentication.md](../authentication.md) — Authentication
- [banks.md](../banks.md) — Banks
- [service-apis.md](../service-apis.md) — Service APIs
- [transaction-processing.md](../transaction-processing.md) —
  Transaction processing
- [access.md](../../prd/access.md) — Access
- [onboarding.md](../../prd/onboarding.md) — Onboarding
- [platform.md](../../prd/platform.md) — Platform
- [ADR-0001](../../adr/0001-reuse-mono-as-upstream.md) — Reuse mono as
  upstream
- [ADR-0013](../../adr/0013-single-unified-api.md) — Single unified API
