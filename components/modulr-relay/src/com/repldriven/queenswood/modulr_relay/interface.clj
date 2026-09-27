(ns com.repldriven.queenswood.modulr-relay.interface
  "The Modulr adapter's two outboxes. Webhook handlers persist an event
  with `save-event`, co-committed to the outbox store's changelog, and
  the shared `changelog-relay/envelope-handler` publishes it at least
  once. Commands become intents with `save-intent`, and the outbound
  runner makes each call outside any FDB transaction, signed and retried
  as the same request: a payment, a transfer between accounts, a sandbox
  credit, or an account opened, closed or reissued. A payment or
  transfer Modulr accepted but no webhook settled within
  `reconcile-after-ms` is looked up, and what Modulr reports is written
  under the dedup key its webhook would carry."
  (:require
    [com.repldriven.queenswood.modulr-relay.system]

    [com.repldriven.queenswood.modulr-relay.modulr :as modulr]
    [com.repldriven.queenswood.modulr-relay.outcomes :as outcomes]
    [com.repldriven.queenswood.modulr-relay.store :as store]))

(defn save-event
  "Persist an outbox event and append it to the changelog in one FDB
  transaction. Returns the event, or a `:modulr-outbox/save` anomaly — a
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
  `dedup-key`: the webhook or command was already recorded.

  Args:
  - result: the value `save-event` or `save-intent` returned."
  [result]
  (store/uniqueness-violation? result))

(defn save-intent
  "Persist a pending intent in one FDB transaction. Returns the intent or
  a `:modulr-outbound/save` anomaly, a uniqueness violation when the
  `dedup-key` was already enqueued.

  Args:
  - txn: an open FDB transaction or `{:record-db :record-store}` config.
  - intent: a map with `:intent-id`, `:dedup-key`, `:kind` (`payment`,
    `transfer`, `credit`, `open-account`, `close-account` or
    `reissue-address`), `:request` (the call's JSON body), `:nonce`,
    `:status` (\"pending\"), `:attempts`, `:created-at` and `:context`,
    EDN of what the runner needs to report the outcome."
  [txn intent]
  (store/save-intent txn intent))

(defn find-intent
  "The intent enqueued under `dedup-key`, or nil.

  Args:
  - txn: an open FDB transaction or `{:record-db :record-store}` config.
  - dedup-key: the end-to-end id, transfer id or account call key."
  [txn dedup-key]
  (store/find-intent txn dedup-key))

(defn request
  "Make one signed call to Modulr. Returns the response map, or
  `:payment/unavailable` when Modulr cannot be reached.

  Args:
  - config: `{:modulr-url :credentials}`.
  - request: `{:method :path}` with optional `:query`, `:body` (data,
    written as JSON), `:raw-body` (a string sent as it is), `:nonce` and
    `:retry?`."
  [config request]
  (modulr/request config request))

(defn classify
  "`[:ok body]`, `[:refused message]` or `[:retry message]` for a
  `request` result: a 2xx, a 4xx other than 408 and 429, or anything
  else.

  Args:
  - res: the value `request` returned."
  [res]
  (modulr/classify res))

(defn ->reference
  "`id` as a Modulr external reference, which allows no `.`.

  Args:
  - id: a platform id, `<prefix>.<ulid>`."
  [id]
  (modulr/->reference id))

(defn reference->id
  "The platform id an external reference encodes, or nil.

  Args:
  - reference: a value `->reference` produced."
  [reference]
  (modulr/reference->id reference))

(defn ->major-units
  "Minor units as the decimal amount Modulr takes.

  Args:
  - minor-units: a whole number of minor units."
  [minor-units]
  (modulr/->major-units minor-units))

(defn payment-outcome
  "The scheme event descriptor, `{:event-name :dedup-key :data}`, a
  payment's final Modulr status reports, or nil while it is not final.
  The dedup key is the provider's payment id and the outcome, whichever
  of a webhook and a reconciliation records it.

  Args:
  - payment: `{:provider-payment-id :end-to-end-id :amount :currency
    :status :at}`, the amount in minor units and `:at` epoch millis."
  [payment]
  (outcomes/payment payment))

(defn transfer-outcome
  "The transfer event descriptor a transfer's final Modulr status
  reports, or nil while it is not final.

  Args:
  - transfer: `{:provider-payment-id :transfer-id :bank-id :status
    :at}`."
  [transfer]
  (outcomes/transfer transfer))
