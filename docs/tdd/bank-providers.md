# A bank's providers

> **Status: implemented.**

## Objective

An installation runs every provider it offers side by side, and each
bank runs on the one of each kind it chose when it was created, as
[ADR-0030](../adr/0030-a-bank-chooses-its-providers-when-it-is-created.md)
decides. Payments and identity verification are the kinds today. This
TDD decides what a kind of provider is, how a bank records its
providers, how an installation declares the providers it offers, how a
command reaches the bank's provider, and how every check that reads a
declaration reads the bank's, so a kind added later, open banking for
example, follows the same shape.

In scope: a kind of provider and its providers component; the bank's
providers and the create that sets them, from the operator's call or
the console; what an installation offers, as the console reads it; a
command channel per provider; every reader of the payment or IDV
declaration; the deployed builds and scenario rigs running several
adapters at once.

Out of scope: moving a running bank to another provider, a migration
ADR-0030 leaves outside the platform; what each provider's adapter
does, which [payments.md](payments.md) and [parties.md](parties.md)
decide; the company register, which stays one per installation as
[ADR-0020](../adr/0020-providers-are-deployment-facts.md) decided;
running any provider's sandbox, a known limitation of
[payments.md](payments.md).

## Background

- **Bank creation.** `POST /v1/banks` sends `create-bank`, and
  `bank/new-bank` opens the house accounts under the payment
  declaration and checks the tier's IDV criteria. An operator may name
  the status, tier and currencies; a signed-in person naming any of
  them is refused 403, and names the company instead, which the
  console finds on the register first.
- **The adapters.** Payments have `modulr-adapter`, `clearbank-adapter`
  and `form3-adapter`, and IDV `zyphe-adapter` and `onfido-adapter`,
  each with its relay, webhook and simulator, meeting the contract
  [payments.md](payments.md) or [parties.md](parties.md) sets. The
  deployed builds run each against its simulator.

## Solution

### A kind of provider

A kind is a brick, `<kind>-provider`, and a group of the same name in
every system that offers it:

- **The declaration.** `<kind>-provider/declaration`, one component
  per provider, named by its key, holding what that provider's adapter
  carries.
- **The providers.** `<kind>-provider/providers`, whose config names
  the default and, per provider, its declaration and what reaches it,
  and whose start refuses a default that names no provider.
  `<kind>-provider/for-bank` takes the instance and a bank and answers
  the bank's provider's entry, the default's for a bank naming none,
  or `:<kind>-provider/unknown` for a key not offered. Its instance
  carries its kind, so `bank` and `api` treat every kind alike.
- **The kinds offered.** `bank`'s processor and `api` take a
  `providers` map from kind to its providers component, whose keys are
  the kinds the installation offers. A kind added later is a new brick,
  its group and a key in that map.

The payment kind, in a system offering two providers:

```yaml
payment-provider:
  modulr: !include system/payment-providers/modulr.yml
  form3: !include system/payment-providers/form3.yml
  providers: !system/component
    system/component-kind: payment-provider/providers
    default: modulr
    providers:
      modulr:
        declaration: !system/local-ref modulr
        payment-command-channel: !keyword modulr-payment-command
        account-command-channel: !keyword modulr-account-command
      form3:
        declaration: !system/local-ref form3
        payment-command-channel: !keyword form3-payment-command
        account-command-channel: !keyword form3-account-command
```

- **Payments.** `components/payment-provider` holds the providers
  component beside its declaration.
- **IDV.** `idv-provider` is a brick of the same shape, with a
  `command-channel` per provider, and each provider's declaration is
  `system/idv-providers/<key>.yml`.
- **Adapters.** Each refers to its own declaration,
  `payment-provider.form3` or `idv-provider.zyphe`, and consumes its
  own command channels, so several run in one JVM.
- **Start-up.** `idv/criteria-check` checks the platform policy against
  every IDV provider offered, since any bank may choose any of them.

### The bank's providers

- **The record.** `Bank` holds `providers`, a repeated `BankProvider`
  of `kind` and `provider` keys, bumping the meta-data version per
  [schema-evolution](../recipes/code/schema-evolution.md). A bank
  created before carries none and reads as the installation's default
  of each kind.
- **The create.** `POST /v1/banks` and `create-bank` take optional
  `providers`, a map from kind to provider key such as
  `{"payment": "form3", "idv": "zyphe"}`, from the operator's call or
  the console's alike. A kind or a key the installation does not offer
  is refused 422 `:bank/unknown-provider`, naming what it offers. Each
  kind left out takes its default, and every kind offered is recorded
  on the bank.
- **The read.** `GET /v1/bank` answers the bank's `providers`. No route
  changes them.
- **What is offered.** `GET /v1/providers`, open to anyone signed in,
  answers each kind the installation offers with its providers' keys
  and its default.
- **The console.** Its create form, after the company is confirmed,
  offers a choice of provider for each kind from `GET /v1/providers`,
  the default selected, and sends the choice with the create. The Bank
  page shows the bank's providers from `GET /v1/bank`.

### Routing a command

A domain brick reads the bank inside its transaction through
`bank-query/find-bank`, takes its provider of the command's kind from
`for-bank`, and publishes to the entry's channel:

- **`payment`.** `submit-payment` and `return-payment` go to the
  bank's `payment-command-channel`, as the sweep's republish does, and
  `transfer-between-accounts` from the transaction-event processor.
- **`cash-account`.** `open-payment-account`, `close-payment-account`
  and `reissue-payment-address` go to the bank's
  `account-command-channel`.
- **`idv`.** `submit-idv-check` goes to the bank's `command-channel`.
- **`payee-check`.** It calls the adapter URL its `adapter-urls` names
  for the bank's provider. A provider
  with no URL there answers `unavailable`, as an unreachable one does.

Replies share the response channels they use now, correlated by command
id. Events share `schemes-payments-event`, `schemes-account-event` and
`idv-event`: a handler resolves an inbound by BBAN and everything else
by an id the platform or the provider issued, each unique across
providers. `kafka-topics.yml` and every local bus declare the
per-provider channels.

### Reading the bank's declaration

Every check that reads a declaration reads the bank's provider's:

- **Outbound.** `payment` checks the scheme against the bank's
  `schemes`.
- **Mirroring.** The transaction-event processor mirrors a posting
  only where the bank's provider holds `balances: per-account`, so a
  pooled bank and a per-account bank share one processor.
- **Returns.** `payment` returns a parked inbound where the bank's
  provider declares `returns: [inbound]`.
- **Publishing.** `cash-account-product` checks an address scheme
  against the bank's `addresses`, and `api`'s interceptors pass the
  providers instance rather than one declaration.
- **Creation.** `new-bank` opens the house accounts under the chosen
  payment provider and checks the tier's criteria against the chosen
  IDV provider; `change-bank-tier` checks against the bank's.
- **A later kind.** Its readers take the bank's provider of that kind
  the same way.

### The provider-name guardrail

The semgrep rule `provider-name-in-domain` refuses a provider's key as
a string or keyword in the source of any brick not named after that
provider, the aggregators, `schema` and the test bricks aside, with
`;; nosemgrep: provider-name-in-domain — <reason>` on the line above
for an exception, so a branch on a key is caught where it is written.

### Deployment and rigs

- **Builds.** Every service offers the providers
  `system/payment-provider.yml` and `system/idv-provider.yml` offer,
  which is every one with an adapter, Modulr and Zyphe the defaults, so
  every service routes to the same set. `external-adapters-service`
  and `monolith-service` compose every adapter and its simulator,
  `exclusive-dispatchers-service` relays each one's outbox, and the
  processor services publish on each one's channels. The image carries
  every capability and the configuration decides which run: the
  external-adapters and monolith entry points fill the handler of each
  adapter and simulator server the configuration declares. The Helm
  values point each deployed adapter at its simulator, and the console
  proxies each IDV simulator's hosted page, Onfido's at
  `/identity-provider/onfido/`.
- **Rigs.** The API scenario rig runs every payment and IDV adapter
  with its simulator in one boot: every scenario on the default
  providers, then each scenario declaring `:runs-on {:payment :every}`
  on each other payment provider and each declaring
  `{:idv :every}` on each other IDV provider, the bank each creates
  naming that provider. A scenario's `:requires` names what a provider
  must offer, `:inbound-notified`, `:inbound-admitted`, `:screened` or
  `:needs-email`, and a run skips, and reports, a scenario its
  providers' declarations rule out or one needing what an adapter does
  not carry yet, `:outbound-returned` on ClearBank's. A scenario
  between banks on different providers lives under
  `scenarios/providers/` and runs once.
- **The scheme.** The payment simulators share `scheme-simulator`,
  which each joins under the sort code it issues addresses from, so a
  payment one sends to an account another holds arrives there as an
  inbound, and is returned or failed where that simulator refuses it.

### Tests

- **`payment-provider`** — the default refused where it names no
  provider, and `for-bank` for a bank carrying a key, none, or one not
  offered.
- **`idv-provider`** — the same, for IDV.
- **`bank`** — the providers recorded at creation, the default taken
  for a kind not named, and an unknown kind or key refused.
- **`payment`** — each command on its bank's channel, and mirroring
  and returns following the bank's declaration.
- **`cash-account`** — the account commands on the bank's channel.
- **`idv`** — the check on the bank's channel, and the criteria check
  over every provider run.
- **`scheme-simulator`** — a payment sent to a sort code no member
  holds, to a member that cannot be reached, and after a member leaves.
- **`test-api-scenarios`** — every payment and IDV scenario on each
  provider, banks on different providers paying each other, what an
  installation offers, and the create's refusals.
- **`console`** — the create form offering each kind's providers, the
  default selected, checked by driving it against the monolith, since
  the console has no test suite of its own.

## Alternatives Considered

- **The channel on the bank record.** Rejected: a channel name is
  wiring, which configuration owns, and would stay on every bank after
  a rename.
- **A list of providers in each consumer's config.** Rejected: every
  consumer would repeat it, which the declaration component was made to
  stop.
- **The adapter URL on the providers entry.** Rejected: only
  `payee-check` reads it, and where an adapter is reached differs by
  deployment, which a providers component shared by every system cannot
  say; `payee-check`'s own configuration, already per project, holds
  it.
- **A response channel per provider.** Rejected: a reply is correlated
  by command id, so one response channel serves every adapter.
- **Resolving the provider from the account.** Rejected: an account
  carries no provider, and every account of a bank shares the bank's.
- **A field per kind on the bank.** Rejected: each kind added later
  would change the bank record, its API and its command, where a
  list of kind and provider takes a new kind unchanged.

## Known Limitations

- **A bank cannot change provider.** Moving one is a migration the
  platform does not offer.
- **The default is fixed while old banks exist.** A bank created
  before carries no provider and reads as the default, so changing the
  default moves it.
- **Issued numbers are kept apart by configuration.** An adapter
  issuing numbers under a configured sort code relies on no other
  adapter being configured with it.
- **Every provider offered runs everywhere.** An installation cannot
  offer a provider to some banks and not others, beyond the default.

## References

- [prd/onboarding.md](../prd/onboarding.md) — the organisation's
  providers, chosen at creation.
- [prd/platform.md](../prd/platform.md) — providers chosen per
  organisation, as a platform goal.
- [prd/access.md](../prd/access.md) — creating an organisation from
  the console.
- [prd/payments.md](../prd/payments.md) — what a bank and its
  customers need from payments.
- [prd/parties.md](../prd/parties.md) — what verifying a person
  serves.
- [payments.md](payments.md) — the payment declaration, the adapter
  contract and the three kinds of provider.
- [parties.md](parties.md) — the IDV declaration and adapter contract.
- [banks.md](banks.md) — bank creation and tier changes.
- [ADR-0030](../adr/0030-a-bank-chooses-its-providers-when-it-is-created.md)
  — a bank's providers, chosen at creation.
- [ADR-0020](../adr/0020-providers-are-deployment-facts.md) — the
  decision ADR-0030 supersedes, and the parts it keeps.
- [ADR-0019](../adr/0019-processor-packaging.md) — which service hosts
  the adapters and relays.
- [system-configurations](../recipes/code/system-configurations.md) —
  groups, components and the tags the providers component uses.
- [schema-evolution](../recipes/code/schema-evolution.md) — the `Bank`
  fields and the meta-data version they bump.
