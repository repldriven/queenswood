(ns com.repldriven.queenswood.form3-relay.interface
  "The Form3 adapter's two outboxes. Notification handlers persist an
  event with `save-event`, co-committed to the outbox store's changelog,
  and the shared `changelog-relay/envelope-handler` publishes it at least
  once. Commands become intents with `save-intent`, and the outbound
  runner makes each call outside any FDB transaction, signed with the
  adapter's key and made again safely, since every resource it creates
  carries an id the adapter chose: a payment created then submitted, or
  an account registered under a number the adapter issues, closed or
  reissued. A payment submitted but not settled by a notification within
  `reconcile-after-ms` is read back, and what Form3 reports is written
  under the dedup key its notification would carry."
  (:require
    [com.repldriven.queenswood.form3-relay.system]

    [com.repldriven.queenswood.form3-relay.form3 :as form3]
    [com.repldriven.queenswood.form3-relay.outcomes :as outcomes]
    [com.repldriven.queenswood.form3-relay.store :as store]))

(defn save-event
  "Persist an outbox event and append it to the changelog in one FDB
  transaction. Returns the event, or a `:form3-outbox/save` anomaly — a
  uniqueness violation when the `dedup-key` was already recorded.

  Args:
  - txn: an open FDB transaction or `{:record-db :record-store}` config.
  - event: a map with `:outbox-id`, `:dedup-key`, `:event-name`,
    `:payload` (Avro-serialised bytes), `:correlation-id`,
    `:causation-id`, `:created-at`.
  - settles: optional; the dedup key of a sent intent the event reports
    the outcome of, settled in the same transaction."
  ([txn event]
   (store/save-event txn event))
  ([txn event settles]
   (store/save-event txn event settles)))

(defn uniqueness-violation?
  "True if a `save-event` or `save-intent` result is a duplicate
  `dedup-key`: the notification or command was already recorded.

  Args:
  - result: the value `save-event` or `save-intent` returned."
  [result]
  (store/uniqueness-violation? result))

(defn save-intent
  "Persist a pending intent in one FDB transaction. Returns the intent or
  a `:form3-outbound/save` anomaly, a uniqueness violation when the
  `idempotency-key` was already enqueued.

  Args:
  - txn: an open FDB transaction or `{:record-db :record-store}` config.
  - intent: a map with `:intent-id`, `:idempotency-key`, `:kind` (`payment`,
    `return`, `open-account`, `close-account` or `reissue-address`),
    `:request` (a payment's or a return's attributes as JSON),
    `:provider-payment-id` (the id the payment is created under, or the
    inbound a return sends back), `:status` (\"pending\"), `:attempt-count`,
    `:created-at` and `:context`, EDN of what the runner needs to make
    the call and report its outcome."
  [txn intent]
  (store/save-intent txn intent))

(defn find-intent
  "The intent enqueued under `idempotency-key`, or nil.

  Args:
  - txn: an open FDB transaction or `{:record-db :record-store}` config.
  - idempotency-key: the end-to-end id or account call key."
  [txn idempotency-key]
  (store/find-intent txn idempotency-key))

(defn request
  "Make one call to Form3, signed with the adapter's key. Returns the
  response map, or a `:payment/unavailable` failure where Form3 could not
  be reached.

  Args:
  - config: `{:form3-url :credentials}`, the URL Form3's API is served
    under and the adapter's `form3-webhook/credentials`.
  - request: `{:method :path :query :body}`, the path under the URL, the
    query a map, and the body a map written as JSON."
  [config request]
  (form3/request config request))

(defn classify
  "`[:ok body]`, `[:exists message]` for a create Form3 already holds,
  `[:refused message]` or `[:retry message]` for a call's result.

  Args:
  - res: the value `request` returned."
  [res]
  (form3/classify res))

(defn ->major-units
  "Minor units as the two-place decimal string Form3 takes.

  Args:
  - minor-units: a whole number of minor units."
  [minor-units]
  (form3/->major-units minor-units))

(defn reason-code
  "The ISO 20022 reason code for one of Form3's status reasons, `NARR`
  for any with no equivalent.

  Args:
  - reason: Form3's reason, such as `account_closed`."
  [reason]
  (outcomes/reason-code reason))

(defn admission-reason
  "Form3's admission reason for the ISO 20022 code the platform refused
  an inbound with.

  Args:
  - code: the platform's reason code, such as `AC04`."
  [code]
  (outcomes/admission-reason code))

(defn payment-outcome
  "The scheme event descriptor, `{:event-name :dedup-key :data}`, an
  outbound's final submission status reports, or nil while it is not
  final. The dedup key is Form3's payment id and the outcome, whichever
  of a notification and a reconciliation records it.

  Args:
  - payment: `{:provider-payment-id :end-to-end-id :amount :currency
    :status :status-reason :at}`, the amount in minor units and `:at`
    epoch millis."
  [payment]
  (outcomes/payment payment))
