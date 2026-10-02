# Checked keys

<!-- tessl-plugin: idioms -->

## Problem

You're writing a relay call to a payment or identity verification
provider from what the adapter stored when it took the command, and a
key missing from that stored data would reach the provider as `nil`.

## Solution

Data is checked as it enters, by the API's schemas and the bus's Avro,
and again as it leaves for a provider. In between it is read plainly.

Read an intent's stored data in a relay — its `context`, or the command
its `request` holds — with Clojure 1.13's `:keys!`, which throws where
a key is absent. Put a key the adapter writes only sometimes under
`:keys` in the same map:

```clojure
{:keys! [amount currency] :keys [debtor-account-id]} (context intent)
```

A read that is optional throughout carries
`;; nosemgrep: unchecked-intent-data — <reason>` on the line above it.

## Failures

**A relay logs "drain threw" on every poll and calls its provider for
nobody.** An intent's stored data lacks a key the relay reads with
`:keys!`: the log names it, "Missing required key", and the relay's pass
stops at that intent every time. Correct the intent's stored data, or the
read that expects the key.

## Rules

- **MUST:** read an intent's stored data in a relay with `:keys!` for
  every key the adapter always writes, and `:keys` only for a key it
  writes sometimes.
- **MUST NOT:** use `:keys!` anywhere else — data inside the system is
  read with `:keys`, its entry and exit already checked.

## Discussion

We check an intent's stored data at the point it leaves for a
provider, and nowhere else.

An intent outlives a deploy: a context written by one release is read
by the next, and no schema covers it, so a key one release stops
writing, or renames, would otherwise reach a provider as `nil` and
come back as the provider's refusal of a malformed call. `:keys!`
checks that a key is present, not that its value is; the adapters
write every key they know of, `nil` included, so it catches a key
absent from the stored data rather than one with no value.

A missing key throws out of the relay's pass rather than failing one
intent, because nothing between the read and the runner catches it. The
`unchecked-intent-data` semgrep rule refuses a plain `{:keys [...]}`
read of an intent's `context`, `ctx` or `data` in a relay's
`outbound.clj`.

## References

- [tdd/transaction-processing.md](../../tdd/transaction-processing.md) —
  the intent and the relay that drains it.
- [ADR-0033](../../adr/0033-operations-reach-a-provider-in-the-order-they-were-accepted.md)
  — the order a relay takes intents in.
