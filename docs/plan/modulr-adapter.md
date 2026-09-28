# Plan: the Modulr payment adapter

## Context

[payments.md](../tdd/payments.md) states the contract every payment
adapter meets. Modulr becomes the default provider: `modulr-adapter`,
`modulr-relay`, `modulr-webhook` and `modulr-simulator` are built
beside ClearBank's, the deployed builds move to them, and ClearBank's
stay in the development project on the neutral contract, passing their
tests. This plan maps each part of the contract onto Modulr's API and
lists what only the sandbox can settle. The four bricks are built, the
simulator covers every row below, and the sandbox is requested next.

Sources: the API docs at modulr.readme.io (index at
modulr.readme.io/llms.txt) and the OpenAPI 3.1 document at
modulr.readme.io/openapi/modulr-api.json, which the simulator's routes
and wire schemas follow.

## The declaration

```yaml
schemes: [fps]
addresses: [scan]
balances: per-account
payee-check: [outbound]
```

Modulr answers inbound name checks against its own accounts from the
account name, so `inbound` is absent.

## Coverage

Each row is a contract item, the Modulr call or delivery that meets
it, and whether the docs settle it (**documented**) or the sandbox must
(**confirm**).

- **Signing calls.** `Authorization: Signature
  keyId=…,algorithm="hmac-sha1",headers="date x-mod-nonce",signature=…`
  over `date: <Date>\nx-mod-nonce: <nonce>`, base64 then URL-encoded.
  Documented, with Modulr's samples on GitHub.
- **Retrying as the same request.** The intent stores its
  `x-mod-nonce`; a retry sends it again with `x-mod-retry: true`, and
  Modulr answers the original response for 48 hours. Past 48 hours the
  runner looks the payment up by `externalReference` before sending
  again. Documented.
- **Opening an account.** `POST /customers/{customerId}/accounts` with
  `currency`, `productCode` and `externalReference` (the Queenswood
  account id, which must fit `[\w \-]{1,50}`). The response's
  `identifiers` carry the sort code and account number, and `status`
  must be `ACTIVE`. Documented; confirm whether an account is `ACTIVE`
  in the response or later, and by which notification.
- **Closing an account.** `POST /accounts/{accountId}/close`, refused
  unless the balance is zero. Documented.
- **Reissuing an address.** A Modulr account's sort code and account
  number are fixed, so the runner blocks the old account
  (`POST /accounts/{accountId}/block`), opens a new one, moves the
  balance with a transfer and closes the old one. Documented; confirm
  what a blocked account does with an inbound payment.
- **Whose customer an account sits under.** One Modulr customer per
  Queenswood bank, created on the bank's first account under partner
  access. Confirm: whether partner access is granted in the sandbox,
  and whether a customer per bank is acceptable to Modulr or it wants
  a customer per party.
- **Submitting a payment.** `POST /payments` with `sourceAccountId`,
  `destination {type: SCAN, name, sortCode, accountNumber}`, `amount`
  in major units, `currency`, `reference`, `externalReference` (the
  end-to-end id) and `nameCheck.id` where a check was made. Documented.
- **Transfers between accounts.** `POST /payments` with
  `destination {type: ACCOUNT, id}` and the transfer id as
  `externalReference`. Documented; confirm it reports through PAYOUT
  and PAYIN like a scheme payment, as the simulator assumes, with the
  PAYIN's `SourceExternalReference` naming the transfer, which the
  adapter reads to leave it unreported.
- **Settled or failed outbound.** The PAYOUT webhook at a final status:
  `PROCESSED` is `transaction-settled` (debit), `CANCELLED` and every
  `ER_*` are `transaction-rejected` with `failure_kind` `declined`.
  Documented, except that PAYOUT does not fire for `ER_EXPIRED`, which
  reconciliation covers.
- **Held outbound.** The PAYMENTCOMPLIANCESTATUS notification, `HELD`
  then `RELEASED` or `DECLINED`, which the adapter reads beside a lookup
  of the payment it names. Documented. `PENDING_FOR_FUNDS` is seen only
  by a lookup, and the adapter waits it out.
- **Refused submission.** A 400 carrying `{field, code, errorCode,
  message}` is `refused`. Documented.
- **Inbound payment.** The PAYIN webhook with `Type: PI_FAST` is
  `transaction-settled` (credit), resolving the creditor by `Payee`'s
  sort code and account number. Documented.
- **Held inbound.** PAYMENTCOMPLIANCESTATUS `HELD`, then `RELEASED`
  and the PAYIN, or `RETURNED`. Documented; confirm the PAYIN's
  `PaymentId` is the notification's `PaymentBid`, as the adapter
  matches the hold on it.
- **Returned outbound.** A PAYIN with `Type: PO_REV`, `ReturnReason`
  and `OriginalSchemeId`, matched to the original payment by
  `GET /payments?schemeId=PAYPORT:<OriginalSchemeId>`, whose
  `externalReference` names the platform's payment. One matching none
  is reported as money arriving. Documented; confirm the id a PAYOUT's
  `SchemeInfo` carries is the one a return names.
- **Deduplication.** On `PaymentId` and the outcome; `EventId` changes
  on a resend and `TransactionId` is empty for a failed payment.
  Documented.
- **Authenticating deliveries.** HMAC as for calls, keyed by the
  webhook's id and the 32-character secret set at registration, with
  the algorithm chosen there. Confirm: which headers carry it, and
  what is signed; the simulator signs `Date` and `x-mod-nonce` as a
  call does.
- **Registering webhooks.** `POST
  /customers/{customerId}/integration-notifications` per customer, for
  `PAYIN`, `PAYOUT` and `PAYMENT_COMPLIANCE_STATUS`. Documented.
- **Delivery.** A non-2xx answer is retried five times over about 13
  hours, then dropped, so reconciliation is not optional. Documented.
- **Reconciling.** `GET /payments?id=` or `?externalReference=`.
  Documented.
- **Confirmation of Payee.** `POST /account-name-check` with
  `paymentAccountId` (the payer's provider account), `sortCode`,
  `accountNumber`, `accountType` and `name`; `result.code` maps to
  match, close match, no match or unavailable. The sandbox answers
  `MATCHED` unless `secondaryAccountId` carries `%<RESULT>`. Documented.
- **Funding the sandbox.** `POST /credit` credits a sandbox account,
  which the adapter uses for money that reaches the ledger from outside
  the scheme, with the transfer id as its `description`. Documented;
  confirm its PAYIN carries the description as `PaymentReference`,
  which the adapter reads to leave it unreported.

## Reason codes

Modulr's return reasons and error statuses map onto ISO 20022 codes in
`modulr-adapter`: `BENACCCLOSED` to `AC04`, `BENSCANUNKNOWN` to `AC01`,
`DUPLICATE` to `AM05`, and `NARR` for anything without an equivalent.
The full table is built from the docs' return-reasons page with the
adapter.

## Questions for the sandbox request

1. Partner access, and one customer per bank or one per party.
2. The headers and signed string on an incoming webhook.
3. When a new account becomes `ACTIVE`, and how that is reported.
4. Whether a compliance notification's `PaymentBid` is the `PaymentId`
   of the PAYIN that follows a release.
5. Whether a transfer between two Modulr accounts reports as PAYOUT
   and PAYIN, and which references each carries.
6. Sandbox rate limits, and whether keys can outlive a month for an
   open-source project's CI.
7. Whether a sandbox credit's PAYIN carries its description as
   `PaymentReference`.
8. Whether a PAYIN from another Modulr client always names the Payee,
   which the internal transfer example omits.
9. Whether the id a PAYOUT's `SchemeInfo` carries is the
   `OriginalSchemeId` a `PO_REV` names, which the adapter matches a
   return on.
