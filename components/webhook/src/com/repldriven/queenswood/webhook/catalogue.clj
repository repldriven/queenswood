(ns com.repldriven.queenswood.webhook.catalogue
  (:require
    [com.repldriven.queenswood.cash-account-api.interface :as cash-account-api]
    [com.repldriven.queenswood.cash-account-query.interface :as
     cash-account-query]))

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
    :project cash-account-api/->wire-body}])

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
