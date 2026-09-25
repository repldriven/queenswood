(ns com.repldriven.queenswood.webhook.catalogue
  (:require
    [com.repldriven.queenswood.cash-account-api.interface :as cash-account-api]
    [com.repldriven.queenswood.cash-account-query.interface :as
     cash-account-query]
    [com.repldriven.queenswood.party-api.interface :as party-api]
    [com.repldriven.queenswood.party-query.interface :as party-query]
    [com.repldriven.queenswood.payment-api.interface :as payment-api]
    [com.repldriven.queenswood.payment-query.interface :as payment-query]
    [com.repldriven.queenswood.reward-api.interface :as reward-api]
    [com.repldriven.queenswood.reward-query.interface :as reward-query]

    [com.repldriven.mono.utility.interface :as utility]))

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

(def ^:private party-status-name
  (enum-name-fn (party-api/party-status-enum-schema)))

(def ^:private outbound-payment-status-name
  (enum-name-fn (payment-api/outbound-payment-status-enum-schema)))

(def ^:private inbound-payment-status-name
  (enum-name-fn (payment-api/inbound-payment-status-enum-schema)))

(def ^:private internal-payment-status-name
  "An internal payment carries no status on its record, so no read
  route spells one: its settle entry's one status is spelled here."
  {:internal-payment-status-settled "settled"})

(def ^:private reward-status-name
  (enum-name-fn (reward-api/reward-status-enum-schema)))

(defn- cash-account-entry
  "One `cash-account.*` kind per change kind the cash-account brick
  writes. An opening and a closing each write two entries, and only the
  one landing on `terminal-status` is told; a rotation and a migration
  leave the status where it was, so they name none and are told on
  whatever status the account holds."
  ([kind change-kind published]
   (cash-account-entry kind change-kind published nil))
  ([kind change-kind published terminal-status]
   (utility/assoc-some {:kind kind
                        :event "cash-account-status-changed"
                        :change-kind change-kind
                        :published-change-kind published
                        :resource-type "CashAccount"
                        :resource-id-key :account-id
                        :status-name cash-account-status-name
                        :load cash-account-query/find-account
                        :project cash-account-api/->wire-body}
                       :terminal-status
                       terminal-status)))

(defn- party-entry
  "One `party.*` kind per transition the party brick writes. Its event
  carries no change kind, so a kind is told by the status the transition
  lands on, and by the one it left where two transitions land on the
  same status. A creation is not told: the caller holds that answer."
  ([kind published terminal-status]
   (party-entry kind published nil terminal-status))
  ([kind published statuses-before terminal-status]
   (utility/assoc-some {:kind kind
                        :event "party-status-changed"
                        :published-change-kind published
                        :terminal-status terminal-status
                        :resource-type "Party"
                        :resource-id-key :party-id
                        :status-name party-status-name
                        :load party-query/get-party
                        :project party-api/->wire-body}
                       :statuses-before
                       statuses-before)))

(defn- outbound-entry
  "One `payment.outbound-*` kind per transition the payment brick
  writes. Submission is not among them: the caller holds that answer,
  and the kind tells what the scheme did next."
  [kind change-kind published terminal-status]
  {:kind kind
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
  "One `payment.inbound-*` kind per transition the payment brick writes:
  money arriving, held, released, parked in suspense, or returned."
  [kind change-kind published terminal-status]
  {:kind kind
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
  and transition that produce it. One kind per transition rather than
  per event: one event carries several transitions, and a tenant
  filtering on kind would otherwise have to inspect a payload to tell a
  close from a suspension.

  - `:kind` — the public name, `<resource>.<change>`.
  - `:event` — the relayed event, under the name `avro-schemas.yml`
    registers its payload schema against.
  - `:change-kind` — the payload's `change_kind` this entry is keyed
    on, in the form Lancaster decodes it to. Absent where the event
    carries none.
  - `:published-change-kind` — the short name the notification carries.
  - `:terminal-status` — the `status_after` that produces the
    notification. A leg landing on any other status is acknowledged and
    writes nothing, which is what makes a two-phase open notify once.
    Absent where the transition leaves the status alone.
  - `:statuses-before` — the `status_before` values the entry accepts,
    where two transitions land on one status. Absent accepts any.
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
  [(cash-account-entry "cash-account.opened" :cash-account-change-kind-open
                       "open" :cash-account-status-opened)
   (cash-account-entry "cash-account.closed" :cash-account-change-kind-close
                       "close" :cash-account-status-closed)
   (cash-account-entry "cash-account.suspended"
                       :cash-account-change-kind-suspend
                       "suspend" :cash-account-status-suspended)
   (cash-account-entry "cash-account.resumed" :cash-account-change-kind-resume
                       "resume" :cash-account-status-opened)
   (cash-account-entry "cash-account.address-rotated"
                       :cash-account-change-kind-rotate-address
                       "rotate-address")
   (cash-account-entry "cash-account.migrated"
                       :cash-account-change-kind-migrate
                       "migrate")
   ;; A party opens when it first becomes active: a person on passing
   ;; identity verification, and a party created without one at once.
   (party-entry "party.opened"
                "open"
                #{nil :party-status-pending}
                :party-status-active)
   (party-entry "party.rejected" "reject" :party-status-rejected)
   (party-entry "party.suspended" "suspend" :party-status-suspended)
   (party-entry "party.resumed"
                "resume"
                #{:party-status-suspended}
                :party-status-active)
   (party-entry "party.closed" "close" :party-status-closed)
   (party-entry "party.merged" "merge" :party-status-merged)
   (outbound-entry "payment.outbound-held" :outbound-payment-change-kind-hold
                   "hold" :outbound-payment-status-held)
   (outbound-entry "payment.outbound-completed"
                   :outbound-payment-change-kind-settle
                   "settle" :outbound-payment-status-completed)
   (outbound-entry "payment.outbound-failed" :outbound-payment-change-kind-fail
                   "fail" :outbound-payment-status-failed)
   (inbound-entry "payment.inbound-settled" :inbound-payment-change-kind-settle
                  "settle" :inbound-payment-status-settled)
   (inbound-entry "payment.inbound-held" :inbound-payment-change-kind-hold
                  "hold" :inbound-payment-status-held)
   (inbound-entry "payment.inbound-released"
                  :inbound-payment-change-kind-release
                  "release" :inbound-payment-status-settled)
   (inbound-entry "payment.inbound-suspended"
                  :inbound-payment-change-kind-suspend
                  "suspend" :inbound-payment-status-suspended)
   (inbound-entry "payment.inbound-returned" :inbound-payment-change-kind-return
                  "return" :inbound-payment-status-returned)
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
    :project payment-api/->internal-wire-body}
   ;; A reward is told once it is paid. A defer is the bank's
   ;; operational problem, read from the run and the row, and not the
   ;; customer's news.
   {:kind "reward.paid"
    :event "reward-status-changed"
    :change-kind :reward-change-kind-pay
    :published-change-kind "pay"
    :terminal-status :reward-status-paid
    :resource-type "Reward"
    :resource-id-key :reward-id
    :status-name reward-status-name
    :load reward-query/find-reward
    :project reward-api/->wire-body}])

(defn covers-event?
  "Whether any entry is produced by this relayed event. Asked before
  the payload is decoded, so an event the bank relays but does not
  publish costs no schema lookup."
  [event]
  (boolean (some (fn [entry] (= event (:event entry))) entries)))

(defn- matches?
  [entry event data]
  (let [{:keys [change-kind status-before status-after]} data]
    (and (= event (:event entry))
         (= (:change-kind entry) change-kind)
         (or (not (contains? entry :terminal-status))
             (= (:terminal-status entry) status-after))
         (or (not (contains? entry :statuses-before))
             (contains? (:statuses-before entry) status-before)))))

(defn find-entry
  "The entry a relayed event's decoded payload produces, or nil. Absence
  is not an error: the bank relays more changes than it publishes — a
  leg landing on an in-flight status, a change kind with no entry — and
  each is acknowledged unread."
  [event data]
  (first (filter (fn [entry] (matches? entry event data)) entries)))
