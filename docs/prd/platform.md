# Platform

## Objective

Queenswood is a multi-tenant banking platform. A fintech can
integrate against one OpenAPI 3.x compliant API to offer their
customers UK current/savings/term-deposit accounts, internal
transfers, UK Faster Payments (inbound and outbound), and
interest accrual with operator-scheduled capitalisation.
Tenants are isolated by organisation, the rules that govern
them are configured via policies, and identity verification is
part of customer onboarding.

This PRD names the platform as a whole — what it offers, who
it serves, and what's deliberately out of scope. Per-capability
PRDs go deep on individual product surfaces.

## Users and stakeholders

Five personas. Every PRD names its readers from this list, so the same
word means the same person everywhere.

**Platform operator.** Runs the platform itself — creates organisations
for customers, moves them between tiers and between test and live,
authors and binds policies, schedules interest, watches delivery health,
and recovers a locked-out organisation. Signs in to the operator app, or
acts through back-office automation. A member of no organisation. Today
this is a single-operator role; Queenswood is a research-grade platform,
not a production SaaS.

**Customer team.** The people at a customer who run its relationship
with the platform — a founder, an operations lead, finance and support
staff, and engineers acting as themselves. Sign in to the console with
their own identity and hold a role in each organisation they belong to,
so every act is attributable to a name. Cares about: getting colleagues
in, never being one departure from lock-out, seeing what their role
allows, and knowing who did what.

**Customer engineering team.** The engineers who build the customer's
integration, and the systems they build, which act with the
organisation's credential and are, to the platform, the organisation.
Cares about: contract clarity, OpenAPI fidelity, a sandbox that behaves
like live, safe re-submission, error handling, and handling the
credential safely. The same human is often on both customer teams. The
difference is whether they are acting as a person in the console or as
the organisation through its credential.

**End customer.** The human or business holding accounts through a
customer's product. Interacts with Queenswood indirectly via the
customer's app, and is a party, never someone who signs in. Cares about:
balance visibility, payment correctness, interest accrual, trust.

**Compliance and risk.** The reviewer who asks after the fact — at the
customer, at the platform, or auditing either. Reaches the platform
through a viewer's role, an operator's view or exported records, and
changes nothing. Cares about: who had access to what and when, which
rules were in force, whether a change was authorised, and nothing
happening silently.

The clearing partner and the identity verification provider are
counterparties, not personas. They appear as stakeholders where a PRD
integrates with them, and nobody reads a PRD as one.

## Goals

What Queenswood delivers.

- **A single unified banking API.** One OpenAPI 3.x compliant
  surface covering organisations, parties, products, accounts,
  payments, balances, transactions, and policies. No
  microservices to glue together.
- **Multi-tenant isolation.** Every record carries an
  organisation identifier. Tenants don't see each other's
  data.
- **Customer onboarding with KYC.** Parties go through an
  identity-verification flow before they can transact.
- **Cash account products as versioned templates.** Tenants
  define their own products (currency, interest rate, allowed
  payment-address schemes, balance-bucket layout) and publish
  versioned releases. Existing accounts stay on the version
  they were opened under.
- **Cash account lifecycle.** Open, close, suspend, and
  resume today. Reopening a closed account (un-close) and
  dormancy remain out of scope. UK SCAN payment addresses
  (sort code + account number) assigned at open.
- **Internal transfers** between two accounts of the same
  tenant. Settle immediately.
- **UK Faster Payments.** Inbound and outbound, via a
  pluggable scheme adapter (a simulator base today; a
  clearing-bank partner adapter as the production target).
- **Interest accrual.** Daily accrual on posted balances with
  sub-minor-unit fractional carry; capitalisation at any
  cadence the operator schedules (daily, weekly, monthly,
  quarterly — the cadence is a product choice, not a
  platform constraint).
- **Policy-based authorization.** Capabilities (allow / deny
  actions) and limits (count or amount, with optional
  curative permits) as data, scoped to tenants via bindings.
  Tenants customise rules without code changes.
- **Audit trail through the transaction ledger.** Every
  movement of money is recorded with idempotency keys,
  timestamps, and references back to the originating
  intent.

## Non-goals

What Queenswood deliberately does not provide.

- **Card issuing or acquiring.** No virtual or physical
  cards; no merchant terminals.
- **Lending products.** No credit, overdrafts, mortgages, or
  buy-now-pay-later.
- **Investment products.** No stocks, bonds, funds, managed
  portfolios.
- **Cryptocurrency.** Not in scope.
- **Cross-border or multi-scheme payments.** UK Faster
  Payments is the only scheme today. No SEPA, SWIFT, or
  international wires.
- **A consumer surface.** Queenswood doesn't ship a customer
  app or end-user admin UI; that's the tenant's product.
- **Production hosting / SaaS offering.** Queenswood is a
  research-grade platform. A prospective tenant would
  self-deploy and integrate; there's no managed SaaS to sign
  up to.
- **Banking license.** Queenswood doesn't hold one.
  Production use as an actual bank would require legal and
  regulatory work outside this codebase.
- **Real-time fraud monitoring.** Policy denials are
  available; behavioural fraud detection isn't.
- **Regulatory or tax reporting frameworks.** No built-in
  reporting for AML, FATCA, CRS, etc.

## Functional scope

The platform is delivered through these capabilities, each covered by
its own PRD.

- **Onboarding** — multi-tenant tenancy creation:
  organisation setup, credential issuance, default product and
  bookkeeping accounts bootstrapped in one transaction.
  PRD: [onboarding](onboarding.md).
- **Parties and identity** — customer registration with
  national identifiers and person identifications; identity
  verification that activates a person party for transacting.
  PRD: [parties](parties.md).
- **Cash account products** — versioned product templates
  defining account terms. Drafts mutable; published versions
  immutable. PRD:
  [cash-account-products](cash-account-products.md).
- **Cash accounts** — accounts opened against a published
  product version, owned by an active party, in a chosen
  currency, with payment addresses. PRD:
  [cash-accounts](cash-accounts.md).
- **Payments** — internal transfers (instant), inbound UK
  Faster Payments (settlement notification from the scheme),
  outbound UK Faster Payments (submission via scheme
  adapter). All safe to re-submit — duplicates are
  recognised and skipped. PRD:
  [payments](payments.md).
- **Interest** — daily accrual with fractional carry;
  capitalisation at the operator's chosen cadence.
  PRD: [interest](interest.md).
- **Authorization and policies** — capabilities and limits as
  data, bindings scoped to tenants. Curative permits let a
  customer self-correct out of breach. PRD:
  [policies](policies.md).
- **Access** — the people who operate an organisation: signing in,
  invitations, roles, removal, working across organisations, and
  operator recovery. PRD: [access](access.md).
- **Webhooks** — the platform telling a customer's systems that a
  record changed, signed and retried until acknowledged. PRD:
  [webhooks](webhooks.md).

## User journeys

Five flows give a feel for how the platform is used end to
end.

### 1. Tenant onboarding

```mermaid
sequenceDiagram
    participant A as Platform operator
    participant Q as Queenswood
    participant T as Customer engineer

    A->>Q: create organisation (name, type, tier, currencies)
    Q->>Q: mint credential, party, default product,<br/>accounts, policy bindings
    Q-->>A: organisation, credential (handed over once)
    A->>T: hand over the credential
    T->>Q: subsequent calls carry a token the tenant<br/>obtains with its credential
```

A platform admin creates a tenant in one operation. The
tenant gets a complete starting state: their organisation, a
credential (delivered once), a settlement product, settlement
accounts in each requested currency, and policy bindings for
their tier.

Neither the tier nor the status is fixed at creation. A
platform operator uses the banking API to move a tenant to
another tier, which rebinds it to that tier's policies, and
to move it between test and live, which changes what the
tenant's existing credential reaches without a new one being
issued.

### 2. Customer onboarding

```mermaid
sequenceDiagram
    participant T as Customer engineer
    participant Q as Queenswood
    participant I as IDV provider<br/>(or simulator)
    participant E as End customer<br/>(via tenant's app)

    E->>T: signs up with personal details
    T->>Q: register party (type=person, identifiers)
    Q->>Q: party pending — IDV pending
    Q->>I: submit identity check
    I-->>Q: check completed
    Q->>Q: IDV accepted — party active
    Q-->>T: party active and ready to hold accounts
```

Person parties go through identity verification before they
can transact. The check runs in the background through an
Onfido-shaped provider (or simulator); the tenant sees the
active status the next time they read the party.

### 3. Account opening

```mermaid
sequenceDiagram
    participant T as Customer engineer
    participant Q as Queenswood

    T->>Q: open account (party, product, currency)
    Q->>Q: validate party + currency<br/>generate SCAN, create balances
    Q->>Q: account opening → opened
    Q-->>T: account with payment addresses
```

An account is opened against a published product version,
inheriting the version's terms (interest rate, balance-bucket
shape, allowed schemes). The SCAN address is generated at open
time and stays for the account's life.

### 4. Money in (inbound Faster Payments)

```mermaid
sequenceDiagram
    participant P as Payer's bank
    participant S as Scheme adapter
    participant Q as Queenswood

    P->>S: FPS payment to account's SCAN
    S->>Q: settlement notification (amount, BBAN)
    Q->>Q: recognise duplicates,<br/>record incoming payment
    Q->>Q: credit posted balance,<br/>save inbound payment
    Q-->>S: acknowledge
```

Inbound payments arrive as settlement notifications from
the scheme adapter. The bank looks up the account by BBAN,
ignores duplicates of the same scheme transaction, and
credits the posted balance.

### 5. Interest earning

```mermaid
sequenceDiagram
    participant Sch as Scheduler
    participant Q as Queenswood

    Note over Sch,Q: Daily, per organisation
    Sch->>Q: accrue daily interest (organisation, as-of-date)
    loop Each customer account
        Q->>Q: compute daily interest<br/>with sub-minor-unit carry
        Q->>Q: post if whole-units > 0,<br/>update carry
    end

    Note over Sch,Q: At capitalisation cadence
    Sch->>Q: capitalise (organisation, as-of-date)
    loop Each customer account
        Q->>Q: sweep accrued into posted balance
    end
```

The operator schedules daily accrual and chooses a
capitalisation cadence. Daily capitalisation produces
daily-compounded interest; monthly produces monthly-compounded.
The math is integer arithmetic with fractional carry between
days, so no pennies are lost over time.

## Open questions

Things deliberately left unresolved or future work.

- **Production identity-verification provider.** The IDV
  adapter speaks Onfido's HTTP API and runs against a
  simulator today. Pointing it at production Onfido needs
  real credentials, a production webhook URL, and signature
  verification. The architecture is pluggable; the
  production deployment isn't yet there.
- **Real scheme adapter.** UK FPS settlement is wired through
  a simulator today; the production target is a clearing-bank
  partner with scheme API access. The architecture is
  pluggable; the production integration isn't yet deployed.
- **End-customer attribution.** A `User` model now exists —
  operators and org users sign in via Keycloak OIDC, so a
  request can resolve to a specific human. What's still thin
  is *end-customer*-attributable audit ("which of the
  tenant's customers did this?"): that needs end-customer
  identity federated to the tenant's own OIDC provider, which
  isn't modelled. See
  [tdd/authentication](../tdd/authentication.md).
- **Multi-currency rate support.** A product version carries
  a single interest rate; multi-currency products earning
  different rates per currency would need rate-per-currency.
- **Dormancy and un-closing.** Cash account lifecycle now
  covers open, close, suspend, and resume. Dormant flows and
  reopening a closed account (closing is terminal) aren't
  wired. Real banking needs the full lifecycle.
- **Production deployment story.** Queenswood as a research
  platform doesn't address whether it would be offered as
  SaaS, on-prem appliance, self-hosted-by-tenant, or
  something else. The deployment model affects isolation
  guarantees, operational ownership, and pricing.
- **Regulatory framework.** Queenswood doesn't hold a
  banking license. Production use as an actual bank would
  require legal and regulatory work — sponsor-bank
  relationships, AML / CTF compliance, FCA authorisation,
  deposit guarantee scheme membership, regulatory reporting
  — outside the scope of this codebase.

## References

- **Per-capability PRDs**:
  [onboarding](onboarding.md), [access](access.md),
  [parties](parties.md),
  [cash-account-products](cash-account-products.md),
  [cash-accounts](cash-accounts.md), [payments](payments.md),
  [interest](interest.md), [policies](policies.md),
  [webhooks](webhooks.md).
- **Engineering view** — the corresponding TDDs at
  [docs/tdd/](../tdd/) describe how these capabilities are
  built.
- **Architecture decisions** — [docs/adr/](../adr/) for the
  load-bearing engineering choices (single unified API,
  multi-tenant model, FoundationDB substrate, anomaly-based
  error handling, system-as-data, and others).
