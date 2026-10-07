# 45. A person's identity evidence stays with the customer's provider

<!-- tessl-plugin: design -->

## Status

**Proposed**

## Context

The property wanted is that the platform holds personal data only where
moving money or keeping the ledger needs it, and that what proves who a
person is stays with the customer and the identity-verification provider
the customer uses. The customer already owns that relationship: it
configures the provider account and sends the person to the provider's
page. The platform opens the session, hears the provider's result and
tells the customer the party's status.

Some personal data cannot go. An account carries its holder's name,
which Confirmation of Payee answers with. An outbound payment carries
the creditor's name and account number, an inbound one the debtor's, and
either carries a free-text reference. A ledger of balances and
transactions tied to a named holder is itself personal data, so the
platform processes personal data on the customer's behalf whatever else
it drops. What this decides is how much.

Without a line, a person party is registered with a date of birth, a
nationality, an address and a national identifier's value, held as plain
fields in `PersonIdentification` and `PartyNationalIdentifier`. The
provider's webhook carries the names and date of birth it read off the
document, the adapter's outbox writes them to FDB on the way to `idv`,
and `Idv.evidence` keeps them. The comparison of claimed against read
details runs on stored values. Every installation becomes a store of
identity documents' contents for every customer's end customers: a
target in a breach, a subject of every erasure request, backups
included, and a provider account that is the platform's rather than the
customer's.

The shortlist:

- **Keep the claimed identity and the read evidence, encrypted per
  party.** Rejected: encryption narrows who can read the data, but the
  platform still holds it, still answers for it through every backup,
  and decrypts it to compare.
- **Take no part in verification, the customer attesting a party
  verified.** Rejected: the session the platform opens and the result
  it hears are what make a party's status something other than the
  customer's word, and the customer already drives the provider through
  the platform.
- **Hold what the rails need and each check's outcome, comparing the
  read name with the party's name in memory.** The person gives their
  details to the provider alone, the evidence stays in the customer's
  provider account, and the platform keeps a name it needs anyway and a
  state per criterion.

## Decision

The platform keeps a person's legal name and the outcome of each
identity check, and never what proves who the person is: the person
gives their details to the provider in the customer's own provider
account, and the adapter reduces the provider's result to outcomes
before anything is written, comparing the name the provider read with
the party's in memory and discarding it.

The decision has these parts:

- Register a person party with its legal name, as given, middle and
  family names, and an optional reference to the customer's own record
  of the person, and nothing else that identifies them: no date of
  birth, nationality, address or national identifier.
- Clear what is already stored beyond that, in the records and in the
  stores a message passes through, rather than only stop writing it.
- Never take a person's identity details through the API to pass on to
  a provider: the person gives them on the provider's page.
- Hold a provider's credentials and webhook secret per bank, configured
  by the customer, so the evidence sits in the customer's provider
  account and the provider is the customer's processor rather than the
  platform's.
- Reduce the provider's webhook to outcomes in the adapter, so nothing
  the provider read reaches a topic, an outbox, a log or a trace.
- Settle `claimed-identity` in the adapter, comparing the name the
  provider read with the party's, read by the party id the run carries,
  and reporting the grade, never either name.
- Record a verification as each criterion's state and the provider's
  reference to its result, and tell the customer a party's status,
  never evidence.
- Keep a value passed to a provider for delivery alone, an email the
  provider sends its link to, only until the provider has it.
- Keep counterparty names, account numbers and references on the
  payment records for as long as the ledger, and never log or trace
  them.

## Consequences

Easier:

- A breach of an installation exposes names, accounts and payments, and
  no identity document's contents.
- An erasure request reaches a name the ledger already has to keep, and
  nothing else about the person.
- The provider is the customer's processor, so the platform's own
  sub-processors are the ones it runs on rather than every provider a
  customer might choose.
- The party API asks for less, and a customer's onboarding form has no
  field the platform's schema has to keep in step with.

Harder:

- A check is bound to its person by the session and the name alone, so
  a link reaching somebody of the same name verifies the wrong person.
  Catching that is the customer's, who sees the full result in its
  provider account.
- A bank cannot verify a person until its customer has configured a
  provider account, and the platform holds a credential per bank where
  it held one per installation.
- A regulator asking for the evidence behind a party is answered by the
  customer, from the provider account, not by the platform.
- A provider that returns only a pass or a fail cannot settle
  `claimed-identity`, as before.
- A field carrying a read value or an identifying detail can be added to
  a party, a verification or an evidence event in any change. Reading
  the party create's request schema and the `idv-evidence` schema
  against this list is the audit; a check in `enforce-idioms.sh`
  refusing the removed field names in `components/schema` is what would
  make it visible.

Related: [ADR-0030](0030-a-bank-chooses-its-providers-when-it-is-created.md),
on the provider a bank chooses when it is created.
