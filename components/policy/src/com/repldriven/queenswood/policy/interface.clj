(ns com.repldriven.queenswood.policy.interface
  "Policy and policy-binding store for the platform. Persists
  Policy / PolicyBinding records to FoundationDB and exposes the
  capability and limit matchers used to authorise requests.
  Returns either domain maps or anomalies; never throws."
  (:require
    [com.repldriven.queenswood.policy.system]

    [com.repldriven.queenswood.policy.capability :as capability]
    [com.repldriven.queenswood.policy.core :as core]
    [com.repldriven.queenswood.policy.limit :as limit]))

(defn new-policy
  "Persist a policy. Returns the saved policy map or an anomaly.

  Args:
  - config: FDB config map (`:record-db` / `:record-store`).
  - data: input policy data."
  [config data]
  (core/new-policy config data))

(defn archive-policy
  "Archive a policy — a terminal lifecycle state that excludes it from
  evaluation and blocks new bindings, distinct from the reversible
  `:enabled` pause. Rejects `:policy/still-bound` when the policy still
  has bindings (the operator unbinds first). Returns the archived
  policy map or an anomaly.

  Args:
  - config: FDB config map (`:record-db` / `:record-store`).
  - policy-id: policy id string."
  [config policy-id]
  (core/archive-policy config policy-id))

(defn get-policy
  "Load a policy by id. Returns the policy map or a
  `:policy/not-found` rejection.

  Args:
  - txn: FDB config or open transaction.
  - policy-id: policy id string."
  [txn policy-id]
  (core/get-policy txn policy-id))

(defn get-policies
  "List policies, paginated. Returns
  `{:items [...] :before id|nil :after id|nil}` or an anomaly.

  Args:
  - txn: FDB config or open transaction.
  - opts (optional): map with `:after`, `:before`, `:limit`,
    `:order` (`:desc` default — newest-first)."
  ([txn]
   (core/get-policies txn))
  ([txn opts]
   (core/get-policies txn opts)))

(defn new-binding
  "Persist a policy binding tying a policy to a target. Returns the
  saved binding map or an anomaly.

  Args:
  - config: FDB config map.
  - data: input binding data — `:policy-id`, `:target`, optional
    `:reason`."
  [config data]
  (core/new-binding config data))

(defn remove-binding
  "Remove a policy binding by id — the unbind step that lets a bound
  policy be archived. Returns the removed binding map or a
  `:policy-binding/not-found` rejection.

  Args:
  - config: FDB config map (`:record-db` / `:record-store`).
  - binding-id: binding id string."
  [config binding-id]
  (core/remove-binding config binding-id))

(defn get-binding
  "Load a binding by id. Returns the binding map or a
  `:policy-binding/not-found` rejection.

  Args:
  - txn: FDB config or open transaction.
  - binding-id: binding id string."
  [txn binding-id]
  (core/get-binding txn binding-id))

(defn get-bindings-for-bank
  "List policy bindings targeting the given bank. Returns a vector of
  binding maps or an anomaly.

  Args:
  - txn: FDB config or open transaction.
  - bank-id: bank id string."
  [txn bank-id]
  (core/get-bindings-for-bank txn bank-id))

(defn get-bindings
  "List policy bindings, paginated. Returns
  `{:items [...] :before id|nil :after id|nil}` or an anomaly.

  Args:
  - txn: FDB config or open transaction.
  - opts (optional): map with `:after`, `:before`, `:limit`,
    `:order` (`:desc` default — newest-first)."
  ([txn]
   (core/get-bindings txn))
  ([txn opts]
   (core/get-bindings txn opts)))

(defn check-capability
  "Check whether `policies` allow the requested capability. Returns
  `true` on allow, or a `:policy/denied` unauthorized anomaly.

  Args:
  - policies: collection of policy maps to evaluate.
  - kind: capability kind keyword.
  - request: request map matched against capability fields and
    filters."
  [policies kind request]
  (capability/check policies kind request))

(defn check-limit
  "Check whether `policies` impose any violated limit on the
  request. Returns `true` when no limit is breached, or a
  `:rejection/policy-limit-exceeded` anomaly (a limit breach is
  a quota/rate condition, not an authz failure — modelled as a
  rejection so the API surfaces it as HTTP 429).

  Args:
  - policies: collection of policy maps to evaluate.
  - kind: limit kind keyword.
  - request: map of shape
    `{:aggregate :count|:amount
      :window    :time-window-instant|:time-window-daily|
                 :time-window-weekly|:time-window-monthly|
                 :time-window-rolling
      :value     <number-or-amount>
      :pre-value <number-or-amount>}` — `:pre-value` is optional
    and unlocks `:limit-allow-improving` leniency when present."
  [policies kind request]
  (limit/check policies kind request))

(defn get-effective-policies
  "Return the policies effective for the given binding-target
  selectors: the always-on `tier=platform` policies plus any
  policies bound to the selector's target via `PolicyBinding`
  records. Returns a vector of policy maps or an anomaly.

  Args:
  - txn: FDB config or open transaction.
  - selectors: map keyed by target id field
    (e.g. `{:bank-id <id>}`)."
  [txn selectors]
  (core/get-effective-policies txn selectors))

(defn get-effective-policy
  "Return the resolved effective decision for the selector's target:
  the effective policies collapsed the way evaluation would resolve
  them — capabilities deny-wins, limits most-restrictive — as
  `{:capabilities [...] :limits [...]}`. Each survivor carries an
  `:origin {:tier :policy-id :name}` naming the policy that decided
  it. Returns the map or an anomaly.

  Args:
  - txn: FDB config or open transaction.
  - selectors: map keyed by target id field
    (e.g. `{:bank-id <id>}`)."
  [txn selectors]
  (core/get-effective-policy txn selectors))

(defn get-policies-by-tier
  "Return policies whose `tier=<tier>` label matches. Used at
  bank creation time to bind the selected tier's policies to a
  new bank.

  Args:
  - txn: FDB config or open transaction.
  - tier: tier label value string."
  [txn tier]
  (core/get-policies-by-tier txn tier))

(defn get-tiers
  "Return the distinct set of tier label values across all
  policies as `[{:tier <name> :description <description>} ...]`.
  Description is taken from the first policy carrying the label.

  Args:
  - txn: FDB config or open transaction."
  [txn]
  (core/get-tiers txn))
