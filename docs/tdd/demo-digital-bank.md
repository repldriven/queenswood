# Demo digital bank

> **Status: proposal.** The customer app in
> `bases/demo-digital-bank-app`, the seed, the `demo-digital-bank-core`
> component, the `demo-digital-bank` base and its service project exist:
> a sign-up from the app reaches a registered party, a returning
> customer signs in, and the app's home is the me read, served from the
> platform and proved against a local monolith. The platform capabilities
> the bank calls, the mono bricks it is built from and the local tooling
> it seeds against exist, and Background names them. Everything else
> under Proposed Solution is the build list, and "First slice" says
> which part of it comes next: the payee check, payments, transfers and
> opening an account, the third slice.

## Objective

The demo digital bank is a customer engineering solution built inside
this workspace: the backend a product built on Queenswood needs, and
the seam between it and the platform. The app is the PRD's. This TDD
decides what stands behind it: the organisation and products the bank
runs against, the component that holds the organisation's credential
and the base that serves the app, the store that holds what the
platform does not, how a customer's identity is kept apart from the
platform's, and how the platform's notifications reach the app.

In scope: the seed that stands the bank up on a local monolith; the
`demo-digital-bank-core` component, the `demo-digital-bank` base and
its service project; the client it calls the platform through;
customer sessions and the isolation they carry; the bank's own store;
the contract with the app; the webhook receiver and the change the
platform needs to reach a local one; and the order of building.

Out of scope: the app's screens and copy, which the PRD and the design
handoff beside the app decide, see
[demo-digital-bank](../prd/demo-digital-bank.md); the platform
capabilities the bank calls, each designed in its own TDD, see
[parties](parties.md),
[cash-account-products](cash-account-products.md),
[cash-accounts](cash-accounts.md), [payments](payments.md) and
[webhooks](webhooks.md); how a service is imaged and deployed, see
[deployment](../recipes/infra/deployment.md); and the other demos to
come, which take this one as their pattern.

## Background

- **The app.** `bases/demo-digital-bank-app`, a React web app on Vite
  that renders every screen of the design. Its edge, `api.js`, calls the
  base's routes, keeps the session the bank minted across reloads, and
  maps the me read onto the shape the screens take — pounds from minor
  units, the fixture's day labels from RFC 3339 — so the screens stay
  as designed. Its root holds that state and the mutations — `send`,
  `transfer` and `openAccount` — that this design moves behind the
  backend next.
- **The operator's door.** `POST /v1/banks`, an `admin` route that
  creates an organisation with its party, its settlement accounts and
  its service-account client, and returns the credential once. Locally
  the `queenswood-admin` client that both realms seed holds the role,
  with the dev profile's secret in `system/keycloak.yml`. See
  [banks](banks.md) and [authentication](authentication.md).
- **The token endpoint.** `POST /oauth/token`, the RFC 6749
  client-credentials grant and no other, proxied to the identity
  provider. The scope names the status the credential reaches,
  `queenswood-api-test` or `queenswood-api-live`. The API scenarios'
  `mint-token` in `test-api-scenarios` is the reference call.
- **Products.** Created from a template — current, savings and
  term-deposit ship under `cash-account-product-templates/` — as a
  draft version carrying a name, a currency, a rate in basis points
  and an effective date, then published. The micro tier allows one
  product per type. See
  [cash-account-products](cash-account-products.md).
- **Parties and identity verification.** `POST /v1/parties` registers
  a person and starts a check, against the Onfido simulator locally,
  which decides from the applicant's name. See [parties](parties.md).
- **Payments.** A payee check, an outbound Faster Payment against the
  ClearBank simulator, and an internal transfer, each carrying an
  `Idempotency-Key` the caller mints. See [payments](payments.md) and
  [idempotency](idempotency.md).
- **Webhooks.** An endpoint registered per organisation, a delivery
  signed under the Standard Webhooks convention, and an address rule
  in `webhook/domain.clj` refusing HTTP and every loopback, link-local
  and private range, held as constants. See [webhooks](webhooks.md).
- **mono's bricks.** `server`, `system`, `http-client`, `jdbc`,
  `migrator`, `json`, `error`, `utility` and `log`, consumed as every
  service here consumes them.
- **A service's shape.** A project under `projects/*-service/` plus a
  base owning `main.clj`, imaged from the shared Dockerfile. See
  [deployment](../recipes/infra/deployment.md).
- **Local secrets.** `pass`, under
  `queenswood/local/dev/auth/clients/`, where `ids/<name>` holds a
  client id and `<name>` its secret, the way the local Google client is
  kept. See [local-install](../recipes/infra/local-install.md).

## Proposed Solution

### The bank on the platform

One organisation, named for the brand, in test status on the micro tier
with sterling as its one currency. Three products, one per template the
platform ships, effective from the day they are published:

- **Everyday**, from the current template, at 0 basis points.
- **Rainy Day**, from the savings template, at 410 basis points.
- **1 Year Fixed**, from the term-deposit template, at 465 basis points.

`just demo-digital-bank-seed`, in `justfiles/demo-digital-bank.just`,
stands this up against `DEMO_API_URL`. It mints an operator token from
the dev client, creates the organisation, stores the client id and
secret in `pass` as `demo-digital-bank` under the local prefix, mints
the organisation's own token with the test scope, lists the products
already there, and creates and publishes each of the three that is
missing, by name. Run again it does nothing, which is what lets it be
run without looking first; run against a monolith restarted since, whose
containers hold nothing of the last one, it finds the credential in
`pass` refused and creates the organisation again. It registers no
webhook endpoint yet: the
platform's address rule refuses a local one, and the notifications
slice below adds registration alongside the allowance.

### The component, the base and its project

`components/demo-digital-bank-core` is the bank: the store, the
customers and their sessions, the platform client and the reads the
app is served from, behind one `interface.clj`. `bases/demo-digital-bank`
owns `main.clj` and the routes, and `projects/demo-digital-bank-service`
holds its `deps.edn` and `resources/application.yml`, on the same
`system/defcomponents` shape as every service. The configuration it
reads: the platform's URL, the client id, secret and status, the
database, and the webhook secret once one exists. Locally the secret
values come from `pass`, and deployed they arrive the way every other
service's do.

One component rather than several, since the app is its only consumer
and nothing in the workspace shares it. A base may not own a store, so
the store and everything that reads it sit in the component, and the
base keeps the HTTP surface. The first thing a second demo would share
is the platform client below, and that is the moment it becomes a
component of its own.

### The platform client

`platform.clj`, one function per operation the screens need:
registering and reading a party, listing products, opening and reading
an account, listing its transactions, checking a payee, submitting a
payment, and moving money. Each goes through mono's `http-client` with
a bearer token, and each returns an anomaly at the edge, through
`error/try-nom`, rather than throwing.

The token comes from `/oauth/token` with the client credentials and the
scope the configured status names, cached until shortly before it
expires. Every submission carries an `Idempotency-Key` the bank mints
with `utility/uuidv7` and writes to its store before the call is made,
so a retry after a timeout reuses the key and the platform recognises
the repeat.

### Customer identity and isolation

The bank mints its own sessions, and the platform's identity server
plays no part. Sign-up follows the screens: a phone number, a code,
the person's details, an identity check, a four-digit passcode. The
code is fixed under the dev and test profiles, as the design assumes,
and a sender for live is a later concern. The details — name, date of
birth, address and National Insurance number — register a party while
the identity scan plays, and the bank reads the verification back. The
passcode is stored as a salted hash. A session is an opaque random id
held in the store with an expiry, sent by the app as a bearer, and a
returning customer opens one with their phone number and passcode.

Isolation is the bank's, on every request: the session resolves to a
customer, the customer to a party id and the account ids the bank
opened for it, and a request naming any other account is answered as
not found. The platform's credential reaches every account the bank
holds, so nothing below this line can be relied on for it.

### The bank's store

Postgres, through mono's `jdbc` and `migrator`, with a Liquibase
changelog under the component's resources. The tables:

- `customers` — id, party id, phone, names, passcode hash, created at.
- `customer_accounts` — customer id, account id, product kind, name.
- `sessions` — id, customer id, expires at.
- `payees` — id, customer id, name, sort code, account number, and when
  and how much they were last paid.
- `submissions` — idempotency key, customer id, kind, the request, and
  the platform's answer once it has one.
- `notifications` — the platform's message id, customer id, kind, the
  record as delivered, and when the customer saw it.

No balance, no payment status and no transaction is stored. Locally
the database is a container beside the monolith's; deployed it is a
database on the instance's Cloud SQL, which non-prod may share under
[ADR-0022](../adr/0022-cloud-foundation-and-environment-lifecycle.md).

### The contract with the app

The base serves the app over JSON in the shape the app's fixture data
already has, so the app changes at its edges and not in its screens.
The routes, all under a session except sign-up and sign-in:

- **Sign-up** — the phone number, the code, the details, and the
  passcode, each a `POST` that advances a sign-up ticket, the last
  answering with a session.
- **Sign-in** — phone number and passcode to a session.
- **Me** — one `GET` answering the user, the accounts with their
  balances and addresses, the transactions across them newest first,
  the payees, and the products the bank publishes.
- **Accounts** — a `POST` that opens an account against a product,
  moves an opening deposit where one is given, and answers the account.
- **Payee check** — a `POST` with a name, sort code and account number,
  answering match, close match with the name held, or no match.
- **Payments** — a `POST` with the account, the payee or a new one, the
  amount and the reference, answering the payment and its status.
- **Transfers** — a `POST` between two of the customer's accounts.
- **Events** — a `GET` holding a server-sent event stream open, on
  which the customer's notifications arrive as they are recorded.

The app replaces its seed with the me read, its mutations with the
calls, and its interstitials with the status the events carry.

### Being told

One route receives the platform's deliveries. It checks the Standard
Webhooks headers — the message id, the timestamp and the signature,
HMAC-SHA256 under the endpoint's secret and, during a rotation, either
of two — refuses a stale timestamp, treats a message id already in
`notifications` as done, and answers 2xx before doing anything else.
Then it reads the record back from the platform, writes the
notification against the customer the record belongs to, and pushes it
on that customer's event stream.

The platform cannot reach a local receiver as it stands: the address
rule refuses HTTP and every range a developer's machine answers on.
The change is small and in the platform: the scheme and the blocked
ranges in `webhook/domain.clj` become configuration the `webhook`
component reads, defaulting to what the constants say, and the dev
profile relaxes them. The API handler and the delivery runner already
share the one rule, so both follow the configuration. With that in
place the seed registers the endpoint, and the secret it returns joins
the credential in `pass`.

### What the platform does not serve as drawn

- **The sparkline.** The last seven daily closing balances, computed
  by the bank from the account's transactions.
- **The category.** Derived from the kind of record: an outbound
  payment, an inbound one, a transfer between own accounts, or interest.
- **Member since.** The party's creation time.
- **The fixed-term minimum.** Enforced by the bank from the product
  until the platform carries a minimum deposit.
- **The identity scan.** An interstitial and no call; the check runs
  on the details.
- **The code.** Fixed under the dev and test profiles.

### Deployment

Later, and in two images: the service through the shared Dockerfile,
the app through an nginx image like the console's, both in the one
`queenswood` release with the service waiting on the API. Nothing here
is decided until the local loop works end to end.

### First slice

1. This TDD and the seed recipe, proved against a local monolith.
2. The component, the base and its project: the system, the store,
   sessions, sign-up through to a registered party, and the me read
   served from the platform. The app reads it, and the home screen is
   real.
3. The payee check, payments, transfers and opening an account.
4. The receiver, the event stream, the address-rule allowance in the
   platform, and endpoint registration in the seed.
5. Deployment, under the deployment recipe's design.

### Tests

- **`demo-digital-bank-core`** — unit tests over the client's token
  cache and idempotency-key reuse, over session resolution refusing
  another customer's account, and over signature verification against
  a known vector; and `with-test-system` tests, against a Postgres
  container and a stand-in for the platform served in the same
  process, for sign-up to a registered party and the me read. A
  brick's tests may not boot the platform, and the platform's own
  contract is pinned in `test-api-scenarios`, so the stand-in answers
  in the shapes those scenarios pin.
- **`demo-digital-bank`** — the routes over HTTP, on the same
  container and stand-in: sign-up through to a session, sign-in, and
  the me read.
- **The seed recipe** — run twice against a local monolith: the first
  creates, the second reports everything already done.
- **`demo-digital-bank-app`** — the build in CI, and a walk of the
  screens by hand against the running base and a local monolith,
  which is where the real platform is met.

## Alternatives Considered

- **A repository of its own.** Rejected for now: the bank stays in
  this workspace as a customer engineering solution, where the
  monolith, the simulators and the shared tooling are a `just` recipe
  away. Separating it, so a prospective customer reads only the bank,
  and moving what the infrastructure turns out to share into mono,
  are a future state and not this one.
- **React Native for the app.** Rejected: the design handoff is React
  for the web, a PWA gives the phone feel, and a store presence, if
  ever wanted, wraps the same app.
- **A Keycloak realm for customers.** Rejected: the screens are a
  phone number, a code and a passcode, which Keycloak's flows do not
  give without custom authenticators, and a session the bank mints is
  a few lines. The platform's identity server keeps its two realms.
- **A generated client from the OpenAPI document.** Rejected: nothing
  in the Clojure toolchain generates a readable one, and a hand-written
  client of ten functions is itself the worked example.
- **FoundationDB for the bank's store.** Rejected: it would put the
  bank's records beside the platform's and blur the line the demo
  exists to draw.
- **Polling the platform instead of webhooks.** Rejected: the PRD's
  point is told, not asked, and the receiver is what shows a customer
  how to be told.
- **A tunnel for local webhooks.** Rejected: every developer would need
  one, and the address rule as configuration is a smaller change than
  a tunnel in every dev loop.
- **Everything in one base.** Rejected: a base may not own a store,
  since persistence belongs in a component and the pre-commit
  guardrail refuses a `store.clj` under `bases/`. One component holds
  the store and everything that reads it, and the base keeps the HTTP
  surface, until a second demo shares something.

## Known Limitations

- **Happy path.** The design carries no rejected verification, no held
  or failed payment and no money-arrived screen, which the PRD's
  journeys have. Each needs a screen before it needs a route.
- **Test only.** The organisation is in test status against simulators.
  Live needs a sender for the code, the real providers, and a live
  credential, none of which this design touches.
- **One person per customer.** A customer is one party. A business
  customer is not designed.
- **A platform change for a demo.** The address-rule allowance is a
  change in `webhook` carried so a local receiver can be reached.
- **Undeployed.** Nothing here runs anywhere but a developer's machine
  until the last slice.
- **A future separation.** Whether the demos move to a repository of
  their own, and what of this workspace's infrastructure belongs in
  mono when they do, is left open on purpose.

## References

- [demo-digital-bank](../prd/demo-digital-bank.md) — the PRD this
  design serves: the screens, the personas and the line between what
  the bank keeps and what it reads.
- [banks](banks.md) — the organisation, its credential and its
  starting state.
- [authentication](authentication.md) — the token endpoint, the two
  principal types and the audience the scope names.
- [parties](parties.md) — registering a person and the verification
  that follows.
- [cash-account-products](cash-account-products.md) — templates, draft
  versions and publishing.
- [cash-accounts](cash-accounts.md) — opening an account, its address
  and its balances.
- [payments](payments.md) — the payee check, outbound payments and
  transfers.
- [idempotency](idempotency.md) — the key a submission carries.
- [webhooks](webhooks.md) — the endpoint, the signing convention and
  the address rule.
- [ADR-0022](../adr/0022-cloud-foundation-and-environment-lifecycle.md)
  — what non-prod may share, the database among it.
- [deployment](../recipes/infra/deployment.md) — a service as project,
  base and shared Dockerfile.
- [local-install](../recipes/infra/local-install.md) — the local
  prefix in `pass` the seed writes under.
- [Standard Webhooks](https://www.standardwebhooks.com/) — the headers
  and signature the receiver verifies.
