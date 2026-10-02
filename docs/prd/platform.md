# Platform

## Objective

Queenswood is a banking platform that customers build their own
products on. A customer integrates against one OpenAPI-compliant API to
offer accounts, payments and interest to its end customers, while the
platform keeps each customer's organisation apart from every other,
applies the rules set for it as policies, and verifies the identity of
the people and businesses it holds accounts for.

This PRD is the platform as a whole: what it is for, who it serves, and
what it deliberately leaves out. Each capability has a PRD of its own,
listed under Functional scope, which goes into the detail.

## Users and stakeholders

Every PRD names its readers from this list, so the same word means the
same person everywhere.

**Platform operator.** Runs the platform itself — creates organisations
for customers, moves them between tiers and between test and live,
authors and binds policies, schedules interest, watches delivery health,
and recovers a locked-out organisation. Signs in to the operator app, or
acts through back-office automation. A member of no organisation. Today
this is a single-operator role.

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

- **One banking API.** A single, documented API covers everything a
  customer's systems do, so a customer integrates with Queenswood rather
  than with each part of it.
- **Isolation between customers.** Everything a customer holds belongs
  to its organisation, and no customer sees another's.
- **Rules as data.** What each customer may do, and how much, is set by
  policies the platform operator binds, not by code.
- **Safe to retry.** A request sent twice, or a notification received
  twice, has the effect of one.
- **A record of everything.** Every movement of money, and every change
  to who may do what, is recorded with who did it and when.
- **Providers chosen per organisation.** An installation offers the
  payment and identity-verification providers it integrates with side
  by side, and each organisation runs on those it chose.
- **A sandbox that behaves like live.** A customer builds and tests its
  integration against a test organisation that answers as a live one
  does.

## Non-goals

- **Products beyond accounts, payments and interest.** No cards,
  lending, investments or cryptocurrency.
- **A consumer surface.** The app an end customer uses is the
  customer's product, not Queenswood's.
- **A managed service or a banking licence.** Queenswood is software an
  operator deploys. Running it as a bank needs regulatory work outside
  this codebase.
- **Fraud monitoring and regulatory reporting.** Policies refuse what
  they are set to refuse; behavioural fraud detection and reporting
  frameworks are not provided.

## Functional scope

Each capability is covered by its own PRD.

- **Onboarding**: creating an organisation for a customer, with
  everything it needs to start. [onboarding](onboarding.md).
- **Memberships**: the people who operate an organisation, and what
  each may do. [memberships](memberships.md).
- **Parties**: the people and businesses a customer holds accounts for,
  and the verification of who they are. [parties](parties.md).
- **Cash account products**: the terms a customer offers its accounts
  on. [cash-account-products](cash-account-products.md).
- **Cash accounts**: the accounts themselves, from opening to closing.
  [cash-accounts](cash-accounts.md).
- **Cash account migrations**: moving existing accounts onto other
  terms. [cash-account-migrations](cash-account-migrations.md).
- **Payments**: moving money between accounts and to and from other
  banks. [payments](payments.md).
- **Interest**: what an account earns, and when it is paid.
  [interest](interest.md).
- **Policies**: the rules that govern what a customer may do.
  [policies](policies.md).
- **Webhooks**: telling a customer's systems what changed.
  [webhooks](webhooks.md).

## User journeys

Each capability PRD describes its own user journeys, and each of them
is checked against the API. The console's sandbox walks a bank through
them end to end, from publishing a product to paying interest.

## Open questions

- **Deployment model.** Whether Queenswood is offered as a service,
  installed by a customer or run some other way decides who operates
  it, how isolation is guaranteed and how it is paid for.
- **Production providers.** The provider integrations run against
  simulators. Each needs credentials, agreements and a production
  address before it serves real money or real people.
- **Regulation.** Use as an actual bank needs authorisation, a sponsor
  bank, anti-money-laundering controls, deposit protection and
  regulatory reporting, none of which this codebase provides.

## References

- **Capability PRDs**: listed under Functional scope.
- **Engineering view**: the TDDs under [docs/tdd/](../tdd/) describe
  how each capability is built.
- **Architecture decisions**: [docs/adr/](../adr/) records the
  load-bearing engineering choices.
