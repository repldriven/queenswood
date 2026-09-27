(ns com.repldriven.queenswood.idv-query.interface
  "Reads of the IDV records and verification sessions the `idv` brick
  writes, and the views the API returns of them. A verification lists
  each verification and screening a bank's policies ask of a person, as
  established, in review or failed, and the reason of each deny still
  outstanding; it never carries what the person's document says."
  (:require
    [com.repldriven.queenswood.idv-query.domain :as domain]
    [com.repldriven.queenswood.idv-query.store :as store]))

(defn get-idv
  "Load an IDV by bank and id. Returns the IDV map, nil when there is
  none, or an anomaly.

  Args:
  - txn: FDB handle or open transaction.
  - bank-id: bank owning the IDV.
  - verification-id: IDV id."
  [txn bank-id verification-id]
  (store/get-idv txn bank-id verification-id))

(defn get-idv-by-party
  "Load a party's IDV. Returns the IDV map, nil when the party has none,
  or an anomaly.

  Args:
  - txn: FDB handle or open transaction.
  - party-id: the party's id."
  [txn party-id]
  (store/get-idv-by-party txn party-id))

(defn get-session
  "Load a verification session by bank and id. Returns the session map,
  nil when there is none under that bank, or an anomaly.

  Args:
  - txn: FDB handle or open transaction.
  - bank-id: the caller's bank.
  - session-id: the session's id."
  [txn bank-id session-id]
  (store/get-session txn bank-id session-id))

(defn get-sessions-by-verification
  "Every session opened for an IDV. Returns a vector of session maps or
  an anomaly.

  Args:
  - txn: FDB handle or open transaction.
  - bank-id: bank owning the IDV.
  - verification-id: IDV id."
  [txn bank-id verification-id]
  (store/get-sessions-by-verification txn bank-id verification-id))

(defn count-sessions-on
  "How many sessions `bank-id` opened on the epoch day `day`, for the
  daily limit. Returns a count or an anomaly.

  Args:
  - txn: FDB handle or open transaction.
  - bank-id: the bank.
  - day: the epoch day, as `utility/today` gives it."
  [txn bank-id day]
  (store/count-sessions-on txn bank-id day))

(def
  ^{:doc
    "Every verification and screening a policy can ask of a person, in
  order, each a map setting `:verification` or `:screening`."}
  criteria
  domain/criteria)

(defn criterion-name
  "A criterion's name without its prefix, as a provider declaration
  spells it (`\"address\"`, `\"pep\"`).

  Args:
  - criterion: a map setting `:verification` or `:screening`."
  [criterion]
  (domain/criterion-name criterion))

(defn accept-request
  "The `idv-action-accept` request that checks whether `criterion` may
  still be outstanding for a person.

  Args:
  - criterion: a map setting `:verification` or `:screening`."
  [criterion]
  (domain/accept-request criterion))

(defn outstanding-reason
  "The reason `policies` refuse accepting a verification while
  `criterion` is outstanding, or nil when nothing requires it.

  Args:
  - policies: every policy in effect, the platform tier's included.
  - criterion: a map setting `:verification` or `:screening`."
  [policies criterion]
  (domain/outstanding-reason policies criterion))

(defn verification
  "The verification view of `idv`: its id and status, and each
  criterion settled or required, a required one still outstanding
  carrying the reason its deny gives.

  Args:
  - idv: the IDV map.
  - policies: every policy in effect for the bank."
  [idv policies]
  (domain/verification idv policies))

(defn session
  "The view of a verification session at `now`: a ready session whose
  hand-off has expired reads `:idv-session-status-expired`, and only a
  ready, unexpired one carries its hand-off.

  Args:
  - idv-session: the session map.
  - now: epoch millis."
  [idv-session now]
  (domain/session idv-session now))
