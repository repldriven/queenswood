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
record's name and `Proto`: `balances/account-balance.proto` holds
`AccountBalance` in `AccountBalanceProto`. A message only that record
uses stays in its file. An enum or message several files use goes in
the folder's `types.proto`, and another folder imports it from there.

Number a record's fields in three bands, a blank line between them:

```proto
message CashAccountMigration {
  option (com.apple.foundationdb.record.record).usage = RECORD;

  required string bank_id = 1;
  required string migration_id = 2;
  required string name = 3;
  required CashAccountMigrationStatus status = 4;
  optional int64 notified_on = 9; // epoch day

  optional int64 approved_at = 51;
  optional com.repldriven.queenswood.schemas.memberships.Actor approved_by = 52;
  optional int64 completed_at = 53;
  optional int64 cancelled_at = 55;
  optional com.repldriven.queenswood.schemas.memberships.Actor cancelled_by = 56;

  required string idempotency_key = 100;
  required int64 created_at = 101;
  required com.repldriven.queenswood.schemas.memberships.Actor created_by = 102;
  required int64 updated_at = 103;
}
```

- **1 to 50, the record's own fields.** The primary key's fields first,
  in key order, then the rest in the order the domain reads them.
- **51 to 99, its transitions.** One pair per transition, the `_at` on
  the odd number and the `_by` on the even one after it. A transition
  nobody performs, as the scheduler completing a migration, leaves its
  `_by` number unused.
- **100 to 104, creation and update.** `idempotency_key` 100,
  `created_at` 101, `created_by` 102, `updated_at` 103, `updated_by`
  104, each only where the record has it and it is not in the primary
  key.

Name a field by what it holds: `_on` for a date as an epoch day, `_at`
for an instant in milliseconds, `_by` for an `Actor`, and
`failure_reason` for why a record ended in a failed status or outcome.
`last_error` is the latest error on something still being retried, and
`error` belongs to the envelope or response carrying a record rather
than to the record.

Declare a field `required` wherever every write gives it a value, a
zero, `false` or empty string included, and `optional` only where a
record legitimately lacks it: a transition that has not happened, a
term a version does not offer. Give no field an explicit
`[default = …]`.

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
- Number the primary key's fields first in key order, the record's own
  fields from 1 to 50, its transitions from 51 to 99 as `_at` and `_by`
  pairs with the `_at` odd, and `idempotency_key`, `created_at`,
  `created_by`, `updated_at` and `updated_by` at 100 to 104.
- Name a date `_on`, an instant `_at`, an actor `_by`, and the reason a
  record failed `failure_reason`.
- Declare a field `required` wherever every write gives it a value.
- Change the folder's `.avsc.json` files with its protos.

**MUST NOT:**

- Give a field an explicit `[default = …]`.
- Write `reserved` for an audit or transition number a record does not
  use.
- Comment a record or field with how something elsewhere uses it.

**MAY:**

- Leave a transition's `_by` unused where nobody performs it.

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

A record's `_by` fields are what makes it attributable: see
[ADR-0046](../../adr/0046-who-did-what-is-recorded-on-the-record.md).

## References

- [schema-evolution](schema-evolution.md) — changing a record that
  already holds data.
- [code-generation](code-generation.md) — the build that writes a set
  required field.
- [ADR-0046](../../adr/0046-who-did-what-is-recorded-on-the-record.md) —
  who did what is recorded on the record.
