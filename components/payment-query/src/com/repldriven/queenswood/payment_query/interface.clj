(ns com.repldriven.queenswood.payment-query.interface
  "Read-side (query) surface for payments: load internal, outbound and
  inbound payment records, look them up by idempotency key or held
  end-to-end id, and count/sum by business day. This is the only payment
  brick `bank-api` (and other readers) may require — it exposes no
  writes. Submission and settlement live in `bank-payment` (command and
  event processors), which reuses these reads inside its own
  transactions."
  (:require
    [com.repldriven.queenswood.payment-query.store :as store]))

(defn get-internal-payment
  "Load an internal payment by id.

  Args:
  - txn: FDB handle or open transaction.
  - payment-id: the payment's id.

  Returns the payment map or nil."
  [txn payment-id]
  (store/get-internal-payment txn payment-id))

(defn get-outbound-payment
  "Load an outbound payment by id.

  Args:
  - txn: FDB handle or open transaction.
  - payment-id: the payment's id.

  Returns the payment map or nil."
  [txn payment-id]
  (store/get-outbound-payment txn payment-id))

(defn get-inbound-payment
  "Load an inbound payment by its scheme-transaction-id (the
  unique secondary index).

  Args:
  - txn: FDB handle or open transaction.
  - scheme-transaction-id: scheme-side unique id.

  Returns the payment map or nil."
  [txn scheme-transaction-id]
  (store/get-inbound-payment txn scheme-transaction-id))

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
