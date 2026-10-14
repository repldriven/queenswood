# Record protos

<!-- tessl-plugin: design -->

## Problem

You are adding or changing a record FoundationDB stores, and want its
proto laid out the way every other record's is: which file it lives in,
how its fields are numbered and named, which are `required`, and what
its comments say. A record read beside its neighbours should not need
its own legend.

## Solution

Lay a folder under
[schemas](/components/schema/resources/schemas/) out one record per
file, named for the record in kebab case, with the outer class the
record's name and `Proto`: `balance/account-balance.proto` holds
`AccountBalance` in `AccountBalanceProto`. Name a folder in the
singular for the brick that owns its records, and give its files the
package `com.repldriven.queenswood.schema.<folder>` and its Avro
schemas the same namespace, a hyphen written as an underscore. A type
several folders use gets a folder of its own named for it, as `actor/`
and `scheme-type/` do. A message only that record
uses stays in its file. An enum or message several files use goes in
the folder's `types.proto`, and another folder imports it from there.
A folder is a domain, and a schema goes in the folder of the domain it
belongs to, an Avro event beside the record it describes, never in a
folder named for what carries it, such as the activity log.

Number a record's fields in bands, a blank line between them:

```proto
message CashAccountMigration {
  option (com.apple.foundationdb.record.record).usage = RECORD;

  required string bank_id = 1;
  required string migration_id = 2;

  required string name = 3;
  required CashAccountMigrationStatus status = 4;
  optional int64 notified_on = 9; // epoch day

  required string idempotency_key = 100;
  required int64 created_at = 101;
  required com.repldriven.queenswood.schema.actor.Actor created_by = 102;
  optional int64 updated_at = 103;
  optional com.repldriven.queenswood.schema.actor.Actor updated_by = 104;
}
```

- **1 to 50, the record's own fields.** The primary key's fields first,
  in key order and set apart by a blank line, then the rest in the
  order the domain reads them. A value the domain reads to decide,
  such as the status a refused close returns an account to, is one of
  these.
- **100 to 104, creation and update.** `idempotency_key` 100,
  `created_at` 101, `created_by` 102, `updated_at` 103, `updated_by`
  104, each only where the record has it and it is not in the primary
  key. `updated_at` and `updated_by` are never required: a record
  nobody has changed has neither, so its create leaves them unset. A
  change a member, an operator or the bank's credential makes sets
  both, and so does one the platform makes, naming the platform, an
  actor of kind `platform` whose principal is the brick that acted.
  `created_by` is required: what the platform makes names it too. The
  idempotency key is the key a repeat of the request that created the
  record is recognised by, whether the request is an API call carrying
  an `Idempotency-Key` or a changelog event a consumer may be handed
  twice.
- **200, the failure.** `failure_reason` on any record whose own
  processing can go wrong, as an interest run or a delivery can. An
  outcome the domain expects, such as a payment the scheme declines,
  is not a failure in this sense: its details are the record's own
  fields, each named for the state it explains, as `failed_kind`,
  `failed_reason_code` and `failed_reason`.
- **201 to 203, delivery.** On a record that retries an outbound call,
  as an email delivery does: `attempt_count` 201, `next_attempt_at` 202
  and `traceparent` 203. An in-flight item's `next_attempt_at` is when
  its claim lapses, so no other field holds a lease or who claimed it.

Name a field by what it holds: `_on` for a date as an epoch day, `_at`
for an instant in milliseconds, `_by` for an `Actor` and never a
process, `failure_reason` for why a record's processing went wrong, and
an expected outcome's details by the state they explain. A record that
retries keeps no error from an attempt it will retry, which goes to the
log, and `error` belongs to the envelope or response carrying a record
rather than to the record. Name a count `<thing>_count`, keeping a
plural for a `repeated` field. The one date not named `_on` is
`business_day`, the banking term for the day a payment or a run counts
for.

Name a classifying enum `…Type` or `…Kind` by what it classifies. A
type is what a thing is as banking, accounting or an outside standard
has it, kept for the thing's life: `IsoCashAccountType`, `SchemeType`,
`TransactionType`, `AccountType`. A kind is which of the
platform's own variants a record is, the one that selects the code
handling it: `EmailKind`, `RewardKind`, `SchedulerTaskKind`,
`ActorKind`. Its field is `<thing>_type` for a type, and `kind` for a
kind, or `<what>_kind` where a record has more than one.

Hold a state as a status enum, never a `bool`: a flag that something
is pending or done gains a third state, as an address rotation that was
pending gained one that failed. A record with one status calls it
`status`, its enum named for what it is the status of.

Declare a field `required` wherever every write gives it a value, a
zero, `false` or empty string included, and `optional` only where a
record legitimately lacks it: an outcome that has not happened, a
term a version does not offer. Give no field an explicit
`[default = …]`.

Give a field holding a person's data, a credential or the platform's own
workings its data category, a key from
[Fideslang's](https://ethyca.github.io/fideslang/taxonomy/data_categories/)
taxonomy, importing `schemas/fideslang/data-category.proto`:
`[(fideslang.data_category) = "user.name"]` for a name,
`"user.contact.email"` for an address, `"user.financial.bank_account"`
for a sort code or account number, `"system.authentication"` for a
secret the platform holds or a token's hash,
`"user.authorization.credentials"` for a person's own way in, such as a
hand-off link, and `"system.operations"` for what the platform keeps to
run itself, such as a provider's id or a scheduler's next run. A field
with none holds the bank's ordinary business data, a timestamp that is
a fact of the business among them. The key is one of those in the
pinned copy of the taxonomy,
[data-categories.json](/components/schema/resources/fideslang/data-categories.json),
which `just fideslang-update` replaces at a new Fideslang tag.

Comment a record with one line saying what it is, and a field only with
its unit or format (`// minor units`, `// ISO 4217`) or the meaning of a
code. Keep why the record is shaped as it is in its TDD or ADR.

Change a folder's `.avsc.json` files in the same change as its protos:
a removed field removed, a field made required no longer nullable, and
the record's fields in the proto's order.

## Rules

**MUST:**

- Keep one stored record per file, named for the record, with the
  outer class `<Record>Proto`, and an enum or message shared across
  files in the folder's `types.proto`.
- File a schema in its domain's folder, never in one named for the
  mechanism that carries it, the folder named in the singular for the
  brick that owns it, and give its protos the package
  `com.repldriven.queenswood.schema.<folder>` and its Avro schemas the
  same namespace.
- Set the primary key's fields apart from the rest by a blank line.
- Number the primary key's fields first in key order, the record's own
  fields from 1 to 50, `idempotency_key`, `created_at`,
  `created_by`, `updated_at` and `updated_by` at 100 to 104,
  `failure_reason` at 200, and, on a record that retries an outbound
  call, `attempt_count`, `next_attempt_at` and `traceparent` at 201 to
  203.
- Take a record's `idempotency_key` from the request that created it,
  an API call's `Idempotency-Key` or the changelog event it answers.
- Name a date `_on`, `business_day` excepted, an instant `_at`, an
  actor `_by`, a count `_count`, the reason a record's processing went
  wrong `failure_reason`, and an expected outcome's details by the
  state they explain.
- Name an enum `…Type` for what a thing is in banking, accounting or an
  outside standard, and `…Kind` for which of the platform's own variants
  selects the code that handles it.
- Declare a field `required` wherever every write gives it a value.
- Hold a state as a status enum, never a `bool`, and call a record's one
  status `status`.
- Change the folder's `.avsc.json` files with its protos.
- Give a field holding a person's data, a credential or the platform's
  own workings its Fideslang data category with the
  `fideslang.data_category` option, a key not deprecated in
  the pinned copy of the taxonomy.
- Replace the pinned copy of the taxonomy only through
  `just fideslang-update`.

**MUST NOT:**

- Give a field an explicit `[default = …]`.
- Declare `updated_at` or `updated_by` `required`, or set either when
  a record is created.
- Give a record a field for when or by whom an act happened: the act's
  audit entry holds both.
- Write `reserved` for an audit number a record does not use.
- Comment a record or field with how something elsewhere uses it.
- Keep a retried attempt's error, a lease or a claim's holder on a
  record.

## Discussion

We settled these while clearing out every record for the clean-slate
reset, folder by folder. The bands are layout, not meaning: no code
reads a field's purpose from its number, and Protocol Buffers lets a
file list fields in any order, so a band costs nothing and buys a
record that reads like its neighbours. Field numbers up to 15 take one
byte on the wire and up to 2047 two, which nothing here notices.

`required` became safe to use for every type once the build stopped
protoc-gen-clojure leaving a zero, `false` or empty string off the
wire; [code-generation](code-generation.md) says how. Before then a
required field that could hold its type's default had to be optional,
and several fields were optional for that reason alone. An explicit
default would not help: the plugin ignores it, so a Java reader and a
Clojure one would disagree about the same record.

Who did what, and when, is an audit entry committed with the act, so a
record keeps only its latest change's time and actor: see
[ADR-0047](../../adr/0047-acts-are-audited-at-commit-and-calls-logged-at-the-edge.md).
A platform change names the platform in `updated_by`, so that a person
is never named for what the platform did after them and every record
says who last changed it. The entry's changeset copies the
fields an act changed into a log nothing can take back, so a field's
data category is what keeps a person's details, a credential and the
platform's workings out of it. The category is Fideslang's rather than
our own because it is published, versioned and machine-readable, and
covers a person's data and the system's own in one tree; the copy is
pinned because Fideslang is a working draft whose keys may move.

## References

- [schema-evolution](schema-evolution.md) — changing a record that
  already holds data.
- [code-generation](code-generation.md) — the build that writes a set
  required field.
- [ADR-0047](../../adr/0047-acts-are-audited-at-commit-and-calls-logged-at-the-edge.md) —
  acts are audited at commit, and calls logged at the edge.
