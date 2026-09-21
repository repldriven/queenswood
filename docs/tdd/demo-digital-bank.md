# Demo digital bank

> **Status: proposal.** The customer app in
> `bases/demo-digital-bank-app`, the seed, the `demo-digital-bank`
> component, the `demo-digital-bank-api` base and its service project
> exist:
> a sign-up from the app reaches a registered party, a returning
> customer signs in, the app's home is the me read, opening an account,
> checking a payee, paying and moving money are calls the app makes to
> the bank and the bank to the platform, and the platform tells the
> bank through a webhook the seed registers, which the bank verifies
> and pushes to the app on an event stream, proved against a local
> monolith. The platform capabilities the bank calls, the mono bricks it
> is built from and the local tooling it seeds against exist, and
> Background names them. Everything else under Proposed Solution is the
> build list, and "First slice" says which part of it comes next:
> deployment, the fifth slice.

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
`demo-digital-bank` component, the `demo-digital-bank-api` base and
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
  as designed. Its root holds that state, and each mutation — `send`,
  `transfer` and `openAccount` — is a call to the bank under a key the
  screen minted, then the me read again.
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
- **An organisation's owner.** The operator's create call takes an
  `owner-email`, which writes an owner invitation, and a signed-in user
  whose verified email matches accepts it under `/v1/me/invitations`
  with no link. See [access](access.md).
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

`just demo-digital-bank-seed`, in `justfiles/demo-digital-bank.just`, stands
this up against `DEMO_API_URL`. It mints an operator token from the dev client,
creates the organisation with its owner's email on the call, stores the client
id and secret in `pass` as `demo-digital-bank` under the local prefix, mints the
organisation's own token with the test scope, lists the products already there,
and creates and publishes each of the three that is missing, by name. Run again
it does nothing, which is what lets it be run without looking first; run against
a monolith restarted since, whose containers hold nothing of the last one, it
finds the credential in `pass` refused and creates the organisation again. Last
it registers the bank's webhook endpoint at `DEMO_WEBHOOK_URL`, the receiver
below on the developer's own machine, and keeps the secret the registration
returns in `pass` beside the credential; an endpoint already registered at that
address is left as it is, and one whose secret `pass` no longer holds has it
rotated, so the bank always starts with a secret the platform signs under. Last
of all it signs in as the owner — a user the local realm seeds beside `dev`,
through the console's public client, which allows the password grant locally —
and accepts the owner invitation the create call wrote, so the console shows the
bank to its owner rather than sending a new user to create one; an owner already
a member is left alone. Beside it, `just demo-digital-bank-fund` pays money into
one of the bank's accounts from outside, through the platform's sandbox
affordance, since nothing else on a developer's machine does.

### The component, the base and its project

`components/demo-digital-bank` is the bank: the store, the
customers and their sessions, the platform client and the reads the
app is served from, behind one `interface.clj`. `bases/demo-digital-bank-api`
owns `main.clj` and the routes, and `projects/demo-digital-bank-api-service`
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
the repeat. The app sends a key of its own with each submission, minted
when the customer reaches the screen that confirms it; the bank keeps it
beside the key it minted, answers a repeat the platform has already
answered from its store, and calls the platform under the same key for
one it has not.

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
- `submissions` — idempotency key, customer id, the app's key, kind, the
  request, and the platform's answer once it has one.
- `notifications` — the platform's notification id, the delivery it
  last arrived under, kind, the delivery as received, the customer it
  resolved to, the record read back, and when the customer was shown
  it.

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
  waits for the platform to report it opened, moves an opening deposit
  from the customer's current account where one is given, and answers
  the account and the deposit. The bank refuses a second account of a
  kind the customer holds, a fixed-term account below its minimum, and
  a deposit with no current account to come from.
- **Payee check** — a `POST` with a name, sort code and account number,
  answering match, close match with the name held, no match, or
  unavailable.
- **Payments** — a `POST` with the account, the payee or a new one, the
  amount and the reference, answering the payment and its status. A new
  payee is kept, and a payee paid again has its last payment recorded.
- **Transfers** — a `POST` between two of the customer's accounts.
- **Events** — a `GET` holding a server-sent event stream open, on
  which the customer's notifications arrive as they are recorded.
- **Webhooks** — a `POST` under no session: the endpoint the platform
  delivers to.

The app replaces its seed with the me read, its mutations with the
calls, and holds the event stream open while it is open: each
notification is said, and the home is read again.

### Being told

One route receives the platform's deliveries, decoding nothing: the
signature covers the body byte for byte. It checks the Standard
Webhooks headers — the message id, the timestamp and the signature,
HMAC-SHA256 under the endpoint's secret, any one of the signatures the
header carries being enough, which is what lets the platform rotate
the secret — refuses a timestamp more than five minutes from now,
records the delivery under the platform's notification id, and answers
2xx before doing anything else. A notification already resolved is
answered `done` and left alone, so a re-send is recognised; one
recorded but never resolved is taken again. Then, on a thread of its
own, it reads the record back from the platform for the kinds whose
resource it can read, writes the notification against the customer the
record belongs to — an account is a party's, and a party is one
customer — and pushes it on that customer's event stream.

The stream is one request held open. It says nothing first, once it is
subscribed, so nothing recorded from then on is missed; then what the
customer has not been shown, oldest first, each marked shown as it is
sent; then each notification as it is recorded, and a comment at each
keep-alive, fifteen seconds by default, so nothing between the app and
the bank closes it as idle. The app reads it with `fetch` rather than
`EventSource`, which cannot carry the session as a bearer, and opens it
again after a moment when it drops.

The platform could not reach a local receiver as it stood: the address
rule refused HTTP and every range a developer's machine answers on.
The change is small and in the platform: the scheme and the blocked
ranges in `webhook/domain.clj` are configuration the rule takes beside
the platform's hosts, `address-rule`, defaulting to what the constants
say. The API handler reads it from the server's interceptors as
`webhook-address-rule` and the delivery runner from its own
configuration, and only the local monolith's dev profile relaxes
either, in `monolith/server-test.yml` and `monolith/webhook-test.yml`.
The local monolith also gained the consumer and the runner it never
hosted, under a channel of the consumer's own,
`webhook-cash-accounts-event`: its bus is Kafka, where a channel is one
consumer group member with one subscription, and the cash-account
processor already holds `cash-accounts-event`.

### What the platform does not serve as drawn

- **The sparkline.** The last seven daily closing balances, computed
  by the bank from the account's transactions.
- **The category.** Derived from the kind of record: an outbound
  payment, an inbound one, a transfer between own accounts, or interest.
- **A payment in flight.** The platform sets the amount aside on
  submission and posts the outflow at settlement, as legs in two
  transactions, and releases the reservation in either case. The bank
  nets the account's `pending-outgoing` legs — each credit releases the
  earliest debit of its amount — and lists what is still standing as
  one row with the status `pending`, beside the posted legs.
- **The payee's name.** A leg carries no counterparty, so an outbound
  leg is named from the bank's own submissions: the one whose
  transaction it belongs to, else the latest before it out of the same
  account for the same amount and reference.
- **Member since.** The party's creation time.
- **The fixed-term minimum.** Enforced by the bank from the product
  until the platform carries a minimum deposit.
- **The identity scan.** An interstitial and no call; the check runs
  on the details.
- **The code.** Fixed under the dev and test profiles.
- **What a notification says.** The platform delivers a record; the
  bank composes the line the customer reads from the kind and the
  record — an account's name and that it is open, an amount and that
  it arrived, a payee and that the payment was sent — and tells a
  kind it does not know as the change it names. A sender is named only
  where the scheme names one, so a payment from the bank's own funds
  reads as money arriving and not as a transfer from an account the
  customer cannot see.

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
3. The payee check, payments, transfers and opening an account, each a
   route on the base and a call on the platform, and the app's
   mutations behind them.
4. The receiver, the event stream, the address-rule allowance in the
   platform, and endpoint registration in the seed.
5. Deployment, under the deployment recipe's design.

### Tests

- **`demo-digital-bank`** — unit tests over the client's token
  cache and idempotency-key reuse, over session resolution refusing
  another customer's account, over the netting of a payment's legs,
  and over signature verification against a known vector; and
  `with-test-system` tests, against a Postgres container and a
  stand-in for the platform served in the same process, for sign-up to
  a registered party, the me read, a payee check in each outcome, a
  payment made once under the app's key and read as pending then
  posted, an account opened with its deposit, and a transfer. A
  brick's tests may not boot the platform, and the platform's own
  contract is pinned in `test-api-scenarios`, so the stand-in answers
  in the shapes those scenarios pin, and settles or fails a payment
  when a test says so; and, for being told, a delivery signed as the
  platform signs it taken and the customer told on a stream held open,
  a re-send answered done, a wrong secret and a malformed envelope
  refused, a test notification told to nobody, and what was told while
  no stream was open replayed on the next and then never again.
- **`demo-digital-bank-api`** — the routes over HTTP, on the same
  container and stand-in: sign-up through to a session, sign-in, the
  me read, each submission with its key and its refusals, and a signed
  delivery reaching a stream the test holds open, with the stale,
  wrongly signed and unsigned deliveries each answered 401.
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
  journeys have. Each needs a screen before it needs a route. A payment
  the scheme refuses leaves the transaction list as its reservation is
  released, and the customer learns why only once the platform tells
  the bank.
- **What is told.** The receiver resolves a customer for an opening,
  by party, and for a payment or a reward, by the account it lands on
  — the one credited, or the one debited where the scheme changed a
  payment the customer sent — so money arriving, a payment sent,
  failed or held, and a reward paid reach the app. An account the bank
  opened for nobody, the house account included, is told to nobody. A
  verification completing reaches no receiver until the party entries
  land in the platform's catalogue, and a kind the bank does not know
  is told as the change it names.
- **Nothing after the answer is retried.** A record the bank cannot
  read back leaves the notification unresolved, and it is taken again
  only when the platform delivers it again.
- **A stream is a thread.** Each open event stream holds one of the
  server's threads for its life, which is fine for a demo and not for
  a bank.
- **A deposit into an account still opening.** The bank reads an
  account back for up to five seconds after opening it before moving
  the deposit, since the platform refuses a payment into an account
  not yet `opened`. Past that the deposit is attempted regardless, and
  the platform's refusal is the answer while the account stands.
- **Test only.** The organisation is in test status against simulators.
  Live needs a sender for the code, the real providers, and a live
  credential, none of which this design touches.
- **One person per customer.** A customer is one party. A business
  customer is not designed.
- **A platform change for a demo.** The address-rule allowance is
  configuration in `webhook`, set only under the local monolith's dev
  profile, carried so a local receiver can be reached.
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
