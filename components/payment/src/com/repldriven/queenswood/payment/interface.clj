(ns com.repldriven.queenswood.payment.interface
  "Internal, inbound, and outbound payment write side: submission and
  settlement. Submission records balance legs and persists the payment
  record; settlement events flip outbound payments to completed and post
  the customer legs for inbound payments. Hold events mark an outbound
  payment held while the scheme screens it; rejection events reverse the
  in-flight legs (1200 → debtor) and flip the payment to failed. Return
  events bring a completed outbound's money back (1100 → debtor) and flip
  it to returned. Returns the payment map or an anomaly. The `payment/outbound-sweep` component
  republishes the scheme command for an outbound payment left pending and
  logs one left pending or held past a day, changing no record.

  Where the payment provider holds a balance for each account, the
  `payment/transaction-event-processor` component mirrors each posted
  transaction at the provider: it records the transaction's provider
  transfers and sends each as `transfer-between-accounts`, and the
  adapter's `transfer-completed` and `transfer-failed` events settle
  them.

  Reads live in `payment-query`; this brick reuses them inside its own
  transactions. `api` requires the query brick, not this one — submissions
  reach the processor as commands, settlements as events."
  (:require
    [com.repldriven.queenswood.payment.system]

    [com.repldriven.queenswood.payment.core :as core]
    [com.repldriven.queenswood.payment.events.inbound :as inbound]
    [com.repldriven.queenswood.payment.events.outbound :as outbound]
    [com.repldriven.queenswood.payment.events.provider-transfer :as
     provider-transfer]))

(defn submit-internal
  "Submit an internal (same-org) payment between two cash accounts.
  Verifies both accounts under the request's `:bank-id`,
  records the transaction, posts the debit/credit legs, and
  persists an InternalPayment.

  Args:
  - config: FDB handle plus :business-day-cutoff (optional).
  - data: submission map (bank-id, debtor-account-id,
    creditor-account-id, currency, amount, reference, ...).

  Returns the payment map or an anomaly. A creditor account that
  isn't in the same org returns `:cash-account/not-found`."
  [config data]
  (core/submit-internal config data))

(defn submit-outbound
  "Submit an outbound payment: verify the debtor, debit the customer
  account, credit the bank's 1200 pending-outbound GL account,
  persist the OutboundPayment as pending, and publish a
  `submit-payment` command for the scheme adapter, carrying the debtor
  BBAN read in the submitting transaction. The bank's 1200 account is
  resolved per-bank from the chart of accounts at runtime.

  A failed publish is logged at ERROR and still returns the committed
  payment. A redelivered submit, whose idempotency key is already
  recorded, returns the existing payment and republishes its scheme
  command when that payment is still pending.

  Args:
  - config: FDB handle plus :bus, :schemas, and :payment-providers,
    whose default provider the payment is checked against and sent to.
  - data: submission map (bank-id, debtor-account-id,
    creditor-bban, currency, amount, reference, ...).

  Returns the payment map or an anomaly."
  [config data]
  (core/submit-outbound config data))

(defn settle-inbound
  "Process an inbound `transaction-settled` event. Looks up the
  creditor account by BBAN, dedupes by scheme-transaction-id,
  records the transaction (DEBIT the bank's 1100 cash-at-correspondent
  GL account / CREDIT the creditor's customer account), posts balance
  legs, and persists an InboundPayment. A settlement matching an open
  hold on its end-to-end id, creditor and amount releases that hold
  instead, and one matching an open admission posts it without checking
  again. Money that has arrived is never refused: a creditor that is
  not opened, or a settlement or release the currency, receive
  capability or daily count checks refuse, is posted DEBIT 1100 /
  CREDIT 2500 suspense and recorded `suspended` with the ISO 20022
  reason it was refused for. Where the provider declares `returns:
  [inbound]`, a suspended inbound is then sent back to its sender with
  `return-payment` on the provider's payment command channel, and again on
  a redelivery of the settlement. A BBAN no account holds fails
  `:payment/unknown-creditor`, since the payment provider issued every
  address an account holds. GL accounts are resolved per-bank from the
  chart of accounts at runtime.

  Args:
  - config: FDB handle, plus :bus, :schemas and :payment-providers to
    return a suspended inbound through the default provider.
  - data: settlement event payload.

  Returns the payment map or an anomaly."
  [config data]
  (inbound/settle-inbound config data))

(defn settle-outbound
  "Process an outbound `transaction-settled` event by flipping the
  matching OutboundPayment from pending or held to completed. Already-
  completed settlements are no-ops returning the existing record. A
  payment in any other status, such as failed, is logged at ERROR and
  returned unchanged with no posting.

  Args:
  - config: FDB handle.
  - data: settlement event payload (end-to-end-id is our
    payment-id).

  Returns the updated payment map or an anomaly."
  [config data]
  (outbound/settle-outbound config data))

(defn hold-outbound
  "Process an outbound `transaction-held` event by flipping the matching
  OutboundPayment from pending to held. No balance move — the money stays
  in the 1200 pending-outbound bucket while the scheme screens it. A
  payment that is not pending is left untouched.

  Args:
  - config: FDB handle.
  - data: held event payload (end-to-end-id is our payment-id).

  Returns the updated payment map or an anomaly."
  [config data]
  (outbound/hold-outbound config data))

(defn reject-outbound
  "Process an outbound `transaction-rejected` event. Reverses the
  in-flight payment (DEBIT the bank's 1200 pending-outbound GL account /
  CREDIT the debtor's customer account) and flips the OutboundPayment to
  failed with the failure the event reports — its kind and ISO 20022
  reason code, a decline coded `NARR` when the event predates both.
  Pending and held payments are reversible; an already-failed payment is
  an idempotent no-op; a settled payment cannot be reversed here.

  Args:
  - config: FDB handle.
  - data: rejection event payload (end-to-end-id is our payment-id).

  Returns the updated payment map or an anomaly."
  [config data]
  (outbound/reject-outbound config data))

(defn return-outbound
  "Process a `transaction-returned` event, which the scheme sends when the
  beneficiary's bank returns a completed outbound. Credits the debtor's
  customer account by the returned amount (DEBIT the bank's 1100
  cash-at-correspondent GL account) as an `outbound-return` transaction
  and flips the OutboundPayment to returned with the event's ISO 20022
  reason code and reason. An already-returned payment is an idempotent
  no-op; a payment that is not completed, or does not exist, is an
  anomaly, so the event is redelivered and then dead-lettered.

  Args:
  - config: FDB handle.
  - data: return event payload (end-to-end-id is our payment-id).

  Returns the updated payment map or an anomaly."
  [config data]
  (outbound/return-outbound config data))

(defn admit-inbound
  "Process an `admit-inbound-payment` command, which a provider asking
  the platform to admit each inbound before it settles sends through its
  adapter. Admits where the BBAN names an opened account and the checks
  a settlement runs pass, recording the `InboundPayment` `admitted` with
  nothing posted; the `transaction-settled` that follows posts it
  without checking again. Rejects otherwise, recording nothing, with the
  ISO 20022 reason: `AC01` for no account, `AC04` for one closed, `AC06`
  for one not opened, `AM03` for another currency, `AG01` for a payment a
  policy refuses. An admission repeated for one already recorded, settled
  or not, answers it again and records nothing.

  Args:
  - config: FDB handle.
  - data: `{:end-to-end-id :scheme :creditor-bban :amount :currency
    :debtor-name :reference}`.

  Returns `{:admitted true :payment-id}`, `{:admitted false :reason-code
  :reason}`, or an anomaly."
  [config data]
  (inbound/admit-inbound config data))

(defn hold-inbound
  "Process an inbound `transaction-held` event. Records the inbound `held`
  (creditor resolved by BBAN) with no balance move — the funds are held at
  ClearBank until released or returned. Idempotent on an open hold for the
  same end-to-end id, creditor and amount.

  Args:
  - config: FDB handle.
  - data: held event payload (end-to-end-id is the scheme's identifier).

  Returns the held InboundPayment map or an anomaly."
  [config data]
  (inbound/hold-inbound config data))

(defn return-inbound
  "Process an inbound `transaction-rejected` event. Transitions the matching
  held InboundPayment to `returned` — the funds went back to the remitter,
  so nothing posts. With a creditor BBAN, the hold matched on end-to-end id
  and that creditor is returned; without one, the only open hold for the
  end-to-end id is, and more than one fails with `:payment/ambiguous-hold`.
  A no-op when no open hold matches.

  Args:
  - config: FDB handle.
  - data: rejection event payload.

  Returns the updated payment map or an anomaly."
  [config data]
  (inbound/return-inbound config data))

(defn mirror-posted
  "Process a `transaction-posted` event where the payment provider holds
  a balance for each account: record, pending, the provider transfers
  that make the provider accounts hold what the transaction left in the
  ledger, and send each as `transfer-between-accounts`. A redelivery
  sends again those still pending. Does nothing where the provider pools
  its balance, and fails `:payment/own-funds-unopened` while a transfer
  needs the bank's own funds and the provider has not opened them.

  Args:
  - config: FDB handle plus :bus, :schemas and :payment-providers,
    whose default provider's balances and channel it follows.
  - data: the transaction-posted payload.

  Returns nil or an anomaly."
  [config data]
  (provider-transfer/mirror-posted config data))

(defn complete-transfer
  "Process a `transfer-completed` event: the pending provider transfer
  becomes completed. A transfer no longer pending is left as it is.

  Args:
  - config: FDB handle.
  - data: the event payload, bank-id and transfer-id.

  Returns the transfer or an anomaly, `:payment/unknown-transfer` where
  no transfer has the id."
  [config data]
  (provider-transfer/complete-transfer config data))

(defn fail-transfer
  "Process a `transfer-failed` event: the pending provider transfer
  becomes failed, with the reason, and the failure is logged at ERROR.
  The ledger is not reversed.

  Args:
  - config: FDB handle.
  - data: the event payload, bank-id, transfer-id and reason.

  Returns the transfer or an anomaly."
  [config data]
  (provider-transfer/fail-transfer config data))
