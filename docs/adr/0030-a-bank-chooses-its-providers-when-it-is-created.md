# 30. A bank chooses its providers when it is created

<!-- tessl-plugin: design -->

## Status

**Accepted**

## Context

An installation serves many banks, and payments and identity
verification each have more than one provider behind the same contract,
each an adapter of its own. Other kinds of provider may follow, open
banking among them. The property wanted is that each bank on one
installation runs on the provider of each kind that suits it, with
every provider the installation offers running side by side.

[ADR-0020](0020-providers-are-deployment-facts.md) made a provider a
deployment fact: which adapter runs, and so which provider answers, is
settled by the installation's configuration. Every bank on an
installation shares its one payment provider and its one IDV provider,
so offering a second means a second installation, with its own
platform, its own operations and its own copy of every bank that wants
the other. Changing a running bank's provider is a separate problem: its
accounts' addresses were issued by the provider and its money is held
there, so moving it is a migration.

The shortlist:

- **One installation per provider.** Rejected: every provider offered
  multiplies the platform an operator runs, and a bank cannot move
  between installations any more easily than between providers.
- **The provider on each request.** Rejected for the reason ADR-0020
  gives: a caller choosing per payment or per verification threads a
  parameter through every brick, and one account's addresses and money
  would belong to whichever provider the last request named.
- **The provider on each account.** Rejected: a bank's own funds, its
  settlement money in GL 1100 and its suspense are the bank's, not an
  account's, and would be split across providers that each hold part.
- **One command channel, each adapter discarding what is not its own.**
  Rejected: every adapter reads every other adapter's commands, and a
  provider's outage backs up the channel the others share.
- **The provider on the bank, chosen at creation, and a command channel
  per provider.** The bank's choice is made once, is the same for every
  account and payment it holds, and routes by configuration as
  ADR-0020's channels already do.

## Decision

A bank chooses one provider of each kind its installation offers when
it is created — payments and identity verification today — and keeps
them for its life; every provider the installation runs consumes its own
command channels, and a domain component routes each command by the
bank's provider of that kind to the channel configuration names for it.

The decision has these parts:

- Record the bank's providers at creation as one provider per kind,
  each defaulting to the installation's default for its kind where the
  create names none, whoever creates the bank.
- Refuse a create naming a provider the installation does not run, and
  never change a bank's provider afterwards: moving a running bank is a
  migration, not a request.
- Declare every provider an installation runs by name, with one default
  per kind, and check each adapter at start-up against its own
  declaration.
- Give each provider its own command channels, and route a command to
  the bank's provider's channel from configuration; share the event
  channels, whose ids and addresses are unique across providers.
- Read the bank's provider's declaration wherever behaviour follows a
  declaration, never the installation's.
- Never name a provider in a domain component: it holds a bank's
  provider as a key into configuration, and branches on the declaration
  the key selects, never on the key.
- Offer a new kind of provider the same way: a declaration per
  provider, a default, a channel per provider, and the kind's entry on
  the bank, with no change to how the bank records the others.
- Keep the rest of ADR-0020: a vendor's HTTP contract lives in its
  adapter alone, anomaly kinds stay provider-neutral, and a company
  register stays one per installation.

## Consequences

Easier:

- One installation offers every provider it runs, and a bank's provider
  is a choice made when it is created, not an installation built for it.
- Adapters run side by side in one service, since none shares another's
  command channel.
- A provider's outage backs up only its own command channels, and only
  its banks wait.
- Adding a provider to an installation is configuration: its adapter,
  its declaration and its channels, with no domain brick changed.

Harder:

- A domain brick reads the bank before it can publish a command or
  apply a declaration, a read inside the transaction that did not exist
  when there was one provider.
- The bank's provider is dispatch, the thing ADR-0020's test sends to
  deployment. It is allowed here because it is set once and read, never
  chosen per request; a provider key reaching a request other than the
  create, or a branch on the key rather than the declaration, is the
  drift this allows. A provider-name check in `enforce-idioms.sh`,
  refusing a provider's name in a domain brick's source, is what makes
  it visible.
- A bank cannot leave its provider. Moving one is a migration the
  platform does not offer.
- Numbers an adapter issues under a sort code its configuration names
  must not collide with another adapter's, and only configuration keeps
  them apart.

Supersedes [ADR-0020](0020-providers-are-deployment-facts.md).
Related: [ADR-0019](0019-processor-packaging.md), on which
service hosts the adapters.
