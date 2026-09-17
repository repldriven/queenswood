(ns com.repldriven.queenswood.payment-query.interface
  "Read-side (query) surface for payments: load internal, outbound and
  inbound payment records within a bank, or outbound and inbound
  unscoped for the event processors, look them up by idempotency key,
  match open holds by end-to-end id, list them by status, and count/sum
  by business day.
  `payment-query` is the only payment brick `api` (and other readers) may
  require — it exposes no writes. Submission and settlement live in
  `payment` (command and event processors), which reuses these reads
  inside its own transactions."
  (:require
    [com.repldriven.queenswood.payment-query.store :as store]))

(defn find-internal-payment
  "Load `bank-id`'s internal payment by id. Another bank's payment reads
  as absent.

  Args:
  - txn: FDB handle or open transaction.
  - bank-id: the caller's bank.
  - payment-id: the payment's id.

  Returns the payment map, or nil when there is none or its `:bank-id`
  differs."
  [txn bank-id payment-id]
  (store/find-internal-payment txn bank-id payment-id))

(defn get-outbound-payment
  "Load an outbound payment by id, whatever its bank. For the event
  processors, which receive no bank; a caller acting for a bank uses
  `find-outbound-payment`.

  Args:
  - txn: FDB handle or open transaction.
  - payment-id: the payment's id.

  Returns the payment map or nil."
  [txn payment-id]
  (store/get-outbound-payment txn payment-id))

(defn find-outbound-payment
  "Load `bank-id`'s outbound payment by id. Another bank's payment reads
  as absent.

  Args:
  - txn: FDB handle or open transaction.
  - bank-id: the caller's bank.
  - payment-id: the payment's id.

  Returns the payment map, or nil when there is none or its `:bank-id`
  differs."
  [txn bank-id payment-id]
  (store/find-outbound-payment txn bank-id payment-id))

(defn get-inbound-payment
  "Load an inbound payment by its scheme-transaction-id (the
  unique secondary index), whatever its bank. For the event processors,
  which receive no bank; a caller acting for a bank uses
  `find-inbound-payment`.

  Args:
  - txn: FDB handle or open transaction.
  - scheme-transaction-id: scheme-side unique id.

  Returns the payment map or nil."
  [txn scheme-transaction-id]
  (store/get-inbound-payment txn scheme-transaction-id))

(defn find-inbound-payment
  "Load `bank-id`'s inbound payment by id, in any status. Another bank's
  payment reads as absent.

  Args:
  - txn: FDB handle or open transaction.
  - bank-id: the caller's bank.
  - payment-id: the payment's id.

  Returns the payment map, or nil when there is none or its `:bank-id`
  differs."
  [txn bank-id payment-id]
  (store/find-inbound-payment txn bank-id payment-id))

(defn find-internal-payment-by-idempotency-key
  "Return the InternalPayment `bank-id` previously wrote under
  `idempotency-key`, or nil. A read primitive for the write sibling's
  idempotent submit read-back. The key is unique within a bank, not
  across the platform, so the bank is part of the lookup.

  Args:
  - txn: FDB handle or open transaction.
  - bank-id: the bank the key belongs to.
  - idempotency-key: the command's idempotency key."
  [txn bank-id idempotency-key]
  (store/find-internal-payment-by-idempotency-key txn
                                                  bank-id
                                                  idempotency-key))

(defn find-outbound-payment-by-idempotency-key
  "Return the OutboundPayment `bank-id` previously wrote under
  `idempotency-key`, or nil. A read primitive for the write sibling's
  idempotent submit read-back. The key is unique within a bank, not
  across the platform, so the bank is part of the lookup.

  Args:
  - txn: FDB handle or open transaction.
  - bank-id: the bank the key belongs to.
  - idempotency-key: the command's idempotency key."
  [txn bank-id idempotency-key]
  (store/find-outbound-payment-by-idempotency-key txn
                                                  bank-id
                                                  idempotency-key))

(defn get-held-inbound-by-end-to-end-id
  "Return the open `held` InboundPayment for `end-to-end-id`, or nil. A
  read primitive for the write sibling's hold/settle/return handlers.

  Args:
  - txn: FDB handle or open transaction.
  - end-to-end-id: the scheme's end-to-end identifier."
  [txn end-to-end-id]
  (store/get-held-inbound-by-end-to-end-id txn end-to-end-id))

(defn find-open-holds
  "Return every `held` InboundPayment for `end-to-end-id`, oldest first.
  The end-to-end id is not unique: one can carry holds for several
  creditor accounts or amounts.

  Args:
  - txn: FDB handle or open transaction.
  - end-to-end-id: the scheme's end-to-end identifier.

  Returns a vector of payment maps, empty when none is held."
  [txn end-to-end-id]
  (store/find-open-holds txn end-to-end-id))

(defn find-open-hold
  "Return the oldest `held` InboundPayment for `end-to-end-id` that
  credits `creditor-account-id` and, when `amount` is non-nil, carries
  that amount. A read primitive for the write sibling's hold, settle and
  return handlers.

  Args:
  - txn: FDB handle or open transaction.
  - end-to-end-id: the scheme's end-to-end identifier.
  - creditor-account-id: the account the hold credits.
  - amount: the hold's amount in minor units, or nil to match any.

  Returns the payment map or nil."
  [txn end-to-end-id creditor-account-id amount]
  (store/find-open-hold txn end-to-end-id creditor-account-id amount))

(defn find-outbound-payments-by-status
  "Return every outbound payment in `status`, across banks, oldest first.
  For the outbound sweep, which looks for payments stuck in a status.

  Args:
  - txn: FDB handle or open transaction.
  - status: an OutboundPaymentStatus keyword, such as
    `:outbound-payment-status-pending`.

  Returns a vector of payment maps."
  [txn status]
  (store/find-outbound-payments-by-status txn status))

(defn list-inbound-payments
  "Return every inbound payment of `bank-id` in `status`, newest first by
  payment id. The whole list is read; paging it is the caller's.

  Args:
  - txn: FDB handle or open transaction.
  - bank-id: the caller's bank.
  - status: an InboundPaymentStatus keyword, such as
    `:inbound-payment-status-suspended`.

  Returns a vector of payment maps."
  [txn bank-id status]
  (store/list-inbound-payments txn bank-id status))

(defn count-internal-by-org-business-day
  "Count internal payments for a bank on a business day. A read
  primitive for the write sibling's limit checks."
  [txn bank-id business-day]
  (store/count-internal-by-org-business-day txn bank-id business-day))

(defn count-outbound-by-org-business-day
  "Count outbound payments for a bank on a business day. A read
  primitive for the write sibling's limit checks."
  [txn bank-id business-day]
  (store/count-outbound-by-org-business-day txn bank-id business-day))

(defn sum-outbound-by-org-business-day
  "Sum outbound payment amounts for a bank on a business day. A read
  primitive for the write sibling's limit checks."
  [txn bank-id business-day]
  (store/sum-outbound-by-org-business-day txn bank-id business-day))

(defn count-inbound-by-org-business-day
  "Count inbound payments for a bank on a business day. A read primitive
  for the write sibling's limit checks."
  [txn bank-id business-day]
  (store/count-inbound-by-org-business-day txn bank-id business-day))
