# Plan: the Form3 payment adapter

## Context

[payments.md](../tdd/payments.md) states the contract every payment
adapter meets, and its Three kinds of provider section places Form3 as
rails: it carries Faster Payments messages for a bank that holds the
money in its own settlement account, asks the bank to admit each
inbound, lets it return one, and screens nothing. This plan maps each
part of the contract onto Form3's API and lists what only a Form3
environment can settle. `form3-webhook`, holding the request signing and
the notification schema, and `form3-simulator` are built from this
plan; `form3-adapter` and `form3-relay` follow.

Sources: the Swagger 2.0 document at
api-docs.form3.tech/assets/swagger/form3-swagger.yaml, from which
Form3's own Go client (github.com/form3tech-oss/go-form3) is generated,
and that client's request signing. The simulator's routes and wire
schemas follow the Swagger document.

## The declaration

```yaml
schemes: [fps]
addresses: [scan]
balances: pooled
payee-check: [outbound]
inbound: admitted
returns: [inbound]
screening: bank
```

Form3 answers an inbound name check itself, from the `name` and
`alternative_names` registered on the account, so `inbound` is absent
from `payee-check`.

## Coverage

Each row is a contract item, the Form3 call or delivery that meets it,
and whether the Swagger document settles it (**documented**) or a Form3
environment must (**confirm**).

- **Resources.** Every body is JSON:API-shaped, `{data: {id, type,
  organisation_id, version, attributes, relationships}}`, with
  client-chosen UUIDs as ids, so a create repeated with the same id is
  refused with 409 rather than done twice. Documented.
- **Signing calls.** `Authorization: Signature keyId="<public key
  id>",algorithm="rsa-sha256",headers="(request-target) host date
  content-type digest content-length",signature="<base64>"`, RSA
  PKCS#1 v1.5 over SHA-256 of the lines `(request-target): <method>
  <path>`, `host`, `date` and, on a write, `content-type`, `digest:
  SHA-256=<base64 of the body's SHA-256>` and `content-length`.
  Documented by the Go client; confirm the `Digest` header carries the
  `SHA-256=` prefix the signed line does.
- **Registering an account.** `POST /organisation/accounts` with
  `bank_id` (the sort code the adapter's configuration names),
  `bank_id_code: GBDSC`, `account_number` (issued by the adapter),
  `country: GB`, `base_currency`, `name` and `account_classification`.
  Its `status` goes `pending` to `confirmed` or `failed`. Documented;
  confirm whether `confirmed` arrives in the response or by a
  notification.
- **Closing an account.** `PATCH /organisation/accounts/{id}` to
  `status: closed`, after which an inbound to it fails admission.
  Documented; confirm whether `DELETE` is preferred.
- **Reissuing an address.** The adapter issues a new account number,
  registers it and closes the old registration; nothing moves, since
  the money is the bank's. Documented.
- **Submitting a payment.** `POST /transaction/payments` with `amount`
  (major units as a string), `currency`, `payment_scheme: FPS`,
  `scheme_payment_type: ImmediatePayment`, `end_to_end_reference` (the
  platform's payment id), `reference`, and `debtor_party` and
  `beneficiary_party` each with `account_number`, `bank_id`,
  `bank_id_code: GBDSC` and `account_name`; then `POST
  /transaction/payments/{id}/submissions`. Documented.
- **Settled or failed outbound.** The submission's `status`:
  `delivery_confirmed` is `transaction-settled` (debit), and
  `delivery_failed`, `limit_check_failed` or `rejected_by_customer` is
  `transaction-rejected`, with `status_reason` mapped to an ISO 20022
  code. Documented; confirm the full set of final statuses.
- **Inbound payment.** A notification with `record_type:
  payment_admissions` and `event_type: created` names the admission;
  its payment carries the beneficiary's `account_number` and
  `bank_id`. Documented.
- **Admitting an inbound.** The admission's task with `assignee:
  customer` (for example `name: account_check`) is completed by `PATCH
  /transaction/payments/{id}/admissions/{admissionId}/tasks/{taskId}`
  with `status: completed` and `output: {outcome: passed}`, or
  `outcome: failed` with a `status_reason` from the admission's reasons
  (`unknown_accountnumber`, `account_closed`, `blocked_account`,
  `transaction_forbidden`). The admission then reaches `confirmed`,
  which is `transaction-settled` (credit), or `failed`. Documented;
  confirm the task names and the deadline for completing one.
- **Returning an inbound.** `POST /transaction/payments/{id}/returns`
  with the original amount, currency and a `return_code`, then `POST
  .../returns/{returnId}/submissions`; the submission reaching
  `delivery_confirmed` is `transaction-returned` (credit). Documented;
  confirm the `return_code` values Form3 accepts for FPS.
- **Returned outbound.** A notification with `record_type:
  return_admissions` for one of our payments: its return's `amount`
  and `return_code` are `transaction-returned` (debit), matched to the
  payment by the path's payment id. Documented.
- **Held payments.** None: Form3 screens nothing, and a submission
  held for a limit breach (`limit_check_pending`) is not a compliance
  hold. Documented.
- **Authenticating deliveries.** A notification is `{id, event_type,
  record_type, data, organisation_id, version, action_time}` and
  carries no signature the Swagger document describes, so the adapter
  records nothing from a delivery until it has read the resource back
  with a signed `GET` by the id the delivery names. Confirm whether
  Form3 signs deliveries.
- **Registering for deliveries.** `POST /notification/subscriptions`
  per `record_type` and `event_type`, `callback_transport: http` and
  `callback_uri` the adapter's public URL. Documented.
- **Deduplication.** On the resource id and its final status; a
  notification's own `id` changes on a resend. Documented.
- **Reconciling.** `GET /transaction/payments/{id}` with its
  submissions, or `GET /transaction/payments?filter[...]`. Documented.
- **Confirmation of Payee.** `POST /organisation/nameverifications`
  with `account_number`, `bank_id`, `bank_id_code: GBDSC`, `name` and
  `account_classification`; the submission's `answer` (`confirmed` or
  `rejected`) and `reason_code` (`ANNM`, `MBAM`, `PANM`, `BANM`, `AC01`
  and the rest) map to match, close match or no match; the answer
  comes in the response, as the `name_verification_submission`
  relationship. Documented.

## Reason codes

Form3's status reasons are words, and map onto ISO 20022 codes in
`form3-adapter`: `unknown_accountnumber` to `AC01`, `account_closed`
and its variants to `AC04`, `blocked_account` to `AC06`,
`transaction_forbidden` to `AG01`, `duplicate_payment` to `AM05`, and
`NARR` for anything without an equivalent. Admission runs the other
way, from the platform's code to Form3's reason.

## Questions for a Form3 environment

1. Whether a test environment is open to an open-source project, and
   on what terms.
2. Whether the `Digest` header carries the `SHA-256=` prefix.
3. Whether Form3 signs notification deliveries.
4. The admission task names assigned to the customer for FPS, and the
   deadline for completing one.
5. The final submission statuses for an outbound FPS payment, and
   which mean the money left.
6. The `return_code` values accepted for an FPS return.
7. Whether an account registration is `confirmed` in the response or
   later, and how that is told.
8. Whether a body sent as `application/json` is accepted as well as
   `application/vnd.api+json`.
