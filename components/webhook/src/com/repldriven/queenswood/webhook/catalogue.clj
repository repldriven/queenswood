(ns com.repldriven.queenswood.webhook.catalogue
  (:require
    [com.repldriven.queenswood.cash-account-api.interface :as cash-account-api]
    [com.repldriven.queenswood.cash-account-query.interface :as
     cash-account-query]
    [com.repldriven.queenswood.payment-api.interface :as payment-api]
    [com.repldriven.queenswood.payment-query.interface :as payment-query]))

(defn- enum-name-fn
  "The public spelling of an enum value, read off the `:encode/api` the
  resource's own enum schema carries. Taken rather than restated so a
  notification and the read route cannot spell one status two ways. A
  value the schema does not know keeps its own name."
  [enum-schema]
  (let [encode (:encode/api (second enum-schema))]
    (fn [value]
      (some-> value
              encode
              name))))

(def ^:private cash-account-status-name
  (enum-name-fn (cash-account-api/cash-account-status-enum-schema)))

(def ^:private outbound-payment-status-name
  (enum-name-fn (payment-api/outbound-payment-status-enum-schema)))

(def ^:private inbound-payment-status-name
  (enum-name-fn (payment-api/inbound-payment-status-enum-schema)))

(def ^:private internal-payment-status-name
  "An internal payment carries no status on its record, so no read
  route spells one: its settle entry's one status is spelled here."
  {:internal-payment-status-settled "settled"})

(defn- outbound-entry
  "One `payment.outbound-status-changed` entry per transition the
  payment brick writes. Submission is not among them: the caller holds
  that answer, and the kind tells what the scheme did next."
  [change-kind published terminal-status]
  {:kind "payment.outbound-status-changed"
   :event "outbound-payment-status-changed"
   :change-kind change-kind
   :published-change-kind published
   :terminal-status terminal-status
   :resource-type "OutboundPayment"
   :resource-id-key :payment-id
   :status-name outbound-payment-status-name
   :load payment-query/find-outbound-payment
   :project payment-api/->outbound-wire-body})

(defn- inbound-entry
  "One `payment.inbound-status-changed` entry per transition the
  payment brick writes: money arriving, held, released, parked in
  suspense, or returned."
  [change-kind published terminal-status]
  {:kind "payment.inbound-status-changed"
   :event "inbound-payment-status-changed"
   :change-kind change-kind
   :published-change-kind published
   :terminal-status terminal-status
   :resource-type "InboundPayment"
   :resource-id-key :payment-id
   :status-name inbound-payment-status-name
   :load payment-query/find-inbound-payment
   :project payment-api/->inbound-wire-body})

(def
  ^{:doc
    "Every public kind the bank publishes, against the relayed event
  that produces it. One entry per kind rather than per event: one event
  carries several change kinds, and a tenant filtering on kind would
  otherwise have to inspect a payload to tell a close from a freeze.

  - `:kind` — the public name, `<resource>.<change>`.
  - `:event` — the relayed event, under the name `avro-schemas.yml`
    registers its payload schema against.
  - `:change-kind` — the payload's `change_kind` this entry is keyed
    on, in the form Lancaster decodes it to.
  - `:published-change-kind` — the short name the notification carries.
  - `:terminal-status` — the `status_after` that produces the
    notification. A leg landing on any other status is acknowledged and
    writes nothing, which is what makes a two-phase open notify once.
  - `:resource-type` — a key of `components/resource-components`, which
    is what gives the notification's `data` a `oneOf` member to project
    onto.
  - `:resource-id-key` — the payload field holding the resource's id.
  - `:status-name` — the payload's status keyword as the envelope
    publishes it.
  - `:load` — `(fn [txn bank-id resource-id])`, the resource's own
    query brick, called inside the consumer's transaction.
  - `:project` — the resource's `->wire-body`, the projection its read
    route returns, encoded as the route encodes it."}
  entries
  [{:kind "cash-account.opened"
    :event "cash-account-status-changed"
    :change-kind :cash-account-change-kind-open
    :published-change-kind "open"
    :terminal-status :cash-account-status-opened
    :resource-type "CashAccount"
    :resource-id-key :account-id
    :status-name cash-account-status-name
    :load cash-account-query/find-account
    :project cash-account-api/->wire-body}
   (outbound-entry :outbound-payment-change-kind-hold
                   "hold"
                   :outbound-payment-status-held)
   (outbound-entry :outbound-payment-change-kind-settle
                   "settle"
                   :outbound-payment-status-completed)
   (outbound-entry :outbound-payment-change-kind-fail
                   "fail"
                   :outbound-payment-status-failed)
   (inbound-entry :inbound-payment-change-kind-settle
                  "settle"
                  :inbound-payment-status-settled)
   (inbound-entry :inbound-payment-change-kind-hold
                  "hold"
                  :inbound-payment-status-held)
   (inbound-entry :inbound-payment-change-kind-release
                  "release"
                  :inbound-payment-status-settled)
   (inbound-entry :inbound-payment-change-kind-suspend
                  "suspend"
                  :inbound-payment-status-suspended)
   (inbound-entry :inbound-payment-change-kind-return
                  "return"
                  :inbound-payment-status-returned)
   ;; An internal payment is settled as it is saved, and the account it
   ;; credits is not the caller: the one entry its save writes is told.
   {:kind "payment.internal-settled"
    :event "internal-payment-settled"
    :change-kind :internal-payment-change-kind-settle
    :published-change-kind "settle"
    :terminal-status :internal-payment-status-settled
    :resource-type "InternalPayment"
    :resource-id-key :payment-id
    :status-name internal-payment-status-name
    :load payment-query/find-internal-payment
    :project payment-api/->internal-wire-body}])

(defn covers-event?
  "Whether any entry is produced by this relayed event. Asked before
  the payload is decoded, so an event the bank relays but does not
  publish costs no schema lookup."
  [event]
  (boolean (some (fn [entry] (= event (:event entry))) entries)))

(defn find-entry
  "The entry a relayed event and change kind produce, or nil. Absence
  is not an error: the bank relays more changes than it publishes, and
  an event or change kind with no entry is acknowledged unread."
  [event change-kind]
  (first (filter (fn [entry]
                   (and (= event (:event entry))
                        (= change-kind (:change-kind entry))))
                 entries)))
