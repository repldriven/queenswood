# Plan: a provider's final refusal is heard

Implements the part of
[ADR-0033](../adr/0033-operations-reach-a-provider-in-the-order-they-were-accepted.md)
saying a command the provider refuses for good, or that exhausts its
attempts, ends as a failure the domain hears.

## Context

Each adapter's relay ends a refused or abandoned intent `failed`. A
payment, a transfer and an account opening then publish an event the
domain acts on. Four do not, and the domain waits forever:

- **Close** (Modulr, Form3, ClearBank): the account stays `closing`.
- **Address reissue** (Modulr, Form3, ClearBank): the rotation stays
  pending, and at Modulr the old provider account may already be
  blocked.
- **Inbound return** (Form3, Modulr): the inbound stays suspended with
  nothing saying the return failed.
- **Verification check** (Zyphe, Onfido): the session stays `opening`
  and the party `pending`.

## Decisions

- **Close.** A refused close returns the account to the status it
  closed from, opened or suspended, with the reason as its
  `refusal-reason`. The account records `closing-from` when it closes.
  The customer is told `cash-account.close-refused`.
- **Reissue.** A failed reissue ends the rotation: the account keeps
  its addresses, the pending rotation is cleared, and the reason is its
  `refusal-reason`. The customer is told
  `cash-account.address-rotation-failed`. A Modulr reissue that gives
  up after blocking the old provider account unblocks it before it
  reports, so the addresses the account keeps still take payments.
- **Return.** A failed return leaves the inbound suspended with a
  `return-failure-reason`, so it reads as such.
- **Verification.** A failed check fails the session, a new
  `failed` status with a `failure-reason`; the party stays pending and
  may open another. The customer is told
  `party.verification-session-failed`.
- **Events.** Each is a provider event on the kind's shared event
  channel: `payment-account-close-refused`,
  `payment-address-reissue-failed`, `inbound-return-failed`,
  `idv-session-failed`.
- **Meta-data.** One version bump covers the new fields.

## Slices

1. Close: the event, the three relays, the simulators'
   `/simulate/close-refused`, the domain, the notification, a scenario
   on every payment provider.
2. Reissue, the same shape.
3. Return, the same shape without a notification.
4. Verification, the same shape on every identity provider.
5. Docs: ADR-0033's Harder bullet on the gap, the payments,
   cash-accounts and parties TDDs, and the webhooks catalogue.
