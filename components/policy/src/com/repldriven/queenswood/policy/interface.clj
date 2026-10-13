(ns com.repldriven.queenswood.policy.interface
  "Policy and policy-binding store for the platform. Persists
  Policy / PolicyBinding records to FoundationDB and exposes the
  capability and limit matchers used to authorise requests.
  Returns either domain maps or anomalies; never throws."
  (:require
    [com.repldriven.queenswood.policy.system]

    [com.repldriven.queenswood.policy.capability :as capability]
    [com.repldriven.queenswood.policy.core :as core]
    [com.repldriven.queenswood.policy.limit :as limit]
    [com.repldriven.queenswood.policy.period :as period]))

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
  `:policy-status-disabled` pause. Rejects `:policy/still-bound` when the policy still
  has bindings (the operator unbinds first). Returns the archived
  policy map or an anomaly.

  Args:
  - config: FDB config map (`:record-db` / `:record-store`).
  - policy-id: policy id string.
  - actor: who archived it, stamped as `updated-by`."
  [config policy-id actor]
  (core/archive-policy config policy-id actor))

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
  - data: input binding data — `:policy-id`, `:target`, the `:actor`
    binding it, and an optional `:reason`."
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
                 :time-window-quarterly|:time-window-annually|
                 :time-window-rolling
      :period    <a period from `periods`>
      :value     <number-or-amount>
      :pre-value <number-or-amount>}` — `:pre-value` is optional
    and unlocks `:limit-allow-improving` leniency when present. A
    request naming a `:period` is checked against the limits counted
    over exactly that period, in place of those of its `:window`."
  [policies kind request]
  (limit/check policies kind request))

(defn periods
  "The periods the live limits in `policies` matching `request` are
  counted over, as a set of `{:window :anchor :starts-month
  :starts-day-of-month}` maps without the keys a limit leaves unset. A
  caller whose limits come as data reads an aggregate for each and
  checks it with the period in its request.

  Args:
  - policies: collection of policy maps to evaluate.
  - kind: limit kind keyword.
  - request: map of the shape `check-limit` takes, without `:window` or
    `:period`; its `:value` need carry only the currency an `:amount`
    limit is matched on."
  [policies kind request]
  (limit/periods policies kind request))

(defn period-start
  "The first epoch day of the period `day` falls in. A period is counted
  from the day the account was opened, `opened-on`, unless it names the
  `:window-anchor-calendar` anchor or no opening day is given, when it
  is counted from the first of January, or from the `:starts-month` and
  `:starts-day-of-month` it names, and a week from a Monday. A month, a
  quarter and a year step by whole months from the anchor, an instant
  and a day are the day itself, and a rolling window reaches back its
  whole days.

  Args:
  - period: a period map, as `periods` returns.
  - day: the epoch day the period is wanted for.
  - opened-on: the epoch day the account was opened, or nil."
  [period day opened-on]
  (period/start period day opened-on))

(defn limits->policy
  "A live policy holding `limits`, for limits that come from somewhere
  other than a stored policy, such as a product version's, so they are
  evaluated beside the policies in force.

  Args:
  - limits: collection of limit maps."
  [limits]
  {:status :policy-status-active :limits (vec limits)})

(defn bound-sides
  "The sides, `:max` and `:min`, that the live limits in `policies`
  matching `request` bound — what a request of that shape could breach,
  whatever its value. A `:range` bounds both.

  Args:
  - policies: collection of policy maps to evaluate.
  - kind: limit kind keyword.
  - request: map of the shape `check-limit` takes; its `:value` need
    carry only the currency an `:amount` limit is matched on."
  [policies kind request]
  (limit/sides-in-force policies kind request))

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

(defn get-effective-policies-cached
  "`get-effective-policies`, kept in `cache` — a mono `cache` the caller
  owns — for as long as no policy or binding has been written. Every
  policy and binding write bumps one stamp, read here at snapshot, and
  a cached set is used only while the stamp it was read under is
  unchanged, so a write takes effect in the next transaction to start
  after it commits. The cache's own expiry is a backstop for a write
  made around this brick. With a nil `cache` this is
  `get-effective-policies`. Not for a transaction that writes policies
  or bindings itself, which cannot read the stamp it bumps.

  Args:
  - txn: FDB config or open transaction.
  - selectors: map keyed by target id field (e.g. `{:bank-id <id>}`).
  - cache: a mono cache atom, or nil."
  [txn selectors cache]
  (core/get-effective-policies-cached txn selectors cache))

(defn platform-policies
  "The `tier=platform` policies among `policies` — what
  `get-effective-policies` returns for a selector naming no bank, such
  as an account's — so a caller already holding a bank's effective
  policies need not read the platform's again.

  Args:
  - policies: policy maps, as `get-effective-policies` returns them."
  [policies]
  (core/platform-policies policies))

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
