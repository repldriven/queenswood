(ns com.repldriven.queenswood.test-api-scenarios.invariants
  "The two standing accounting invariants, asserted after every
  scenario step against every bank the run holds a token for.

  The trial-balance tie is the same property the domain runner
  asserts: per currency, across the whole chart, Sigma-debit equals
  Sigma-credit. The control reconciliation is the one the domain
  runner alone used to carry: for each control role a cash-account
  product type rolls up into, the sum of the sub-ledger's posted
  default balances equals the control account's own posted default
  balance, per currency. The tie alone misses a posting that landed
  on the wrong currency's rows — both currencies still balance — so
  the reconciliation is what catches a mis-routed fan-out.

  This runner holds no FDB config and drives the system over HTTP, so
  both sides come off the wire: the per-currency `:trial-balance`
  block and each control's `:posted-balance` from
  `GET /v1/ledger-accounts`, the sub-ledger from a cursor-paged walk
  of `GET /v1/cash-accounts`. Both read `default / posted` only, so an
  in-flight bucket — held, pending, the customer `interest-accrued`
  that rolls into 2400 rather than a deposit control — perturbs
  neither.

  A bank-scoped token is what makes a bank readable at all: both
  routes take their `bank-id` from the caller's token rather than a
  path, so `verbs` mints one per bank it creates and the banks it
  could not mint for are reported as skipped.

  Unlike the domain runner, which reads its whole chart in one FDB
  snapshot, the list endpoint reads each account's balances in its own
  transaction. A processor commit landing between two of them shows up
  as a momentary imbalance, so a disagreeing reading is taken again
  before anything is asserted; the assertion fires on the last one."
  (:require
    [com.repldriven.queenswood.ledger-account.interface :as ledger-accounts]

    [com.repldriven.mono.http-client.interface :as http]

    [clojure.test :refer [is]]))

(def ^:private wire-product-type
  "The API's `ProductType` spelling back to the domain keyword
  `product-type->control-code` is keyed on. The rendering belongs to
  the `api` base's coercion table, which a component may not require,
  so the wire vocabulary is named here; the control role behind each
  one, and its chart number, come from `ledger-account`."
  {"current" :product-type-sub-ledger-current
   "savings" :product-type-sub-ledger-savings
   "term-deposit" :product-type-sub-ledger-term-deposit
   "own-funds" :product-type-sub-ledger-own-funds})

(defn- control-role
  "The `:gl-account-code` role a cash account's posted default balance
  rolls up into, from its wire `:product-type`. Nil for a product type
  that rolls into no control."
  [product-type]
  (get ledger-accounts/product-type->control-code
       (get wire-product-type product-type)))

(def ^:private role-by-gl-code
  "Every control role a sub-ledger rolls into, keyed by the `:gl-code`
  the API renders on that control's ledger account."
  (into {}
        (map (fn [role] [(ledger-accounts/gl-account-code->gl-code role) role]))
        (vals ledger-accounts/product-type->control-code)))

(def ^:private ledger-accounts-path "/v1/ledger-accounts")

(def ^:private embed-balances "embed[balances]=true")

(def ^:private cash-accounts-path (str "/v1/cash-accounts?" embed-balances))

(defn- get-json
  "One bank-scoped GET, as `{:status :body}`."
  [base-url token path]
  (let [res (http/request {:method :get
                           :url (str base-url path)
                           :headers {"Authorization" (str "Bearer " token)}})]
    {:status (:status res) :body (http/res->edn res)}))

(defn- read-ledger
  "Read the bank's chart: the per-currency trial-balance block, and
  each control account's posted balance keyed by `[role currency]`."
  [base-url token]
  (let [{:keys [status body]} (get-json base-url token ledger-accounts-path)
        {:keys [ledger-accounts trial-balance]} body]
    {:ok? (and (= 200 status) (seq ledger-accounts) (some? trial-balance))
     :status status
     :body body
     :trial-balance trial-balance
     :controls (reduce (fn [acc account]
                         (if-let [role (role-by-gl-code (:gl-code account))]
                           (assoc acc
                                  [role (:currency account)]
                                  (:value (:posted-balance account) 0))
                           acc))
                       {}
                       ledger-accounts)}))

(defn- posted-default
  "One account's credit-positive posted net of its `default` bucket,
  as `{currency value}`."
  [balances]
  (reduce (fn [acc {:keys [balance-type balance-status currency credit debit]}]
            (if (and (= "default" balance-type) (= "posted" balance-status))
              (update acc currency (fnil + 0) (- (or credit 0) (or debit 0)))
              acc))
          {}
          balances))

(defn- add-account
  [sums account]
  (if-let [role (control-role (:product-type account))]
    (reduce-kv (fn [acc currency value]
                 (update acc [role currency] (fnil + 0) value))
               sums
               (posted-default (:balances account)))
    sums))

(defn- read-sub-ledgers
  "Walk the bank's cash accounts page by page through the cursor,
  summing each account's posted default balance by `[role currency]`.
  The `:next` link carries the cursor but not the embed, so each page
  after the first re-adds it."
  [base-url token]
  (loop [path cash-accounts-path
         sums {}]
    (let [{:keys [status body]} (get-json base-url token path)
          ok? (and (= 200 status) (contains? body :cash-accounts))
          sums (reduce add-account sums (:cash-accounts body))
          next-path (get-in body [:links :next])]
      (if (and ok? next-path)
        (recur (str next-path "&" embed-balances) sums)
        {:ok? ok? :status status :body body :sums sums}))))

(defn- read-books
  [base-url token]
  {:ledger (read-ledger base-url token)
   :sub-ledgers (read-sub-ledgers base-url token)})

(defn- ties?
  [{:keys [trial-balance]}]
  (every? (fn [{:keys [debit credit]}] (= debit credit)) trial-balance))

(defn- reconciles?
  [{:keys [controls]} {:keys [sums]}]
  (every? (fn [k] (= (get sums k 0) (get controls k 0)))
          (into (set (keys controls)) (keys sums))))

(defn- agree?
  [{:keys [ledger sub-ledgers]}]
  (and (:ok? ledger)
       (:ok? sub-ledgers)
       (ties? ledger)
       (reconciles? ledger sub-ledgers)))

(def ^:private settle-attempts 5)
(def ^:private settle-interval-ms 50)

(defn- settle
  "Read the books until they agree or the attempts run out, returning
  the last reading. A reading that disagrees because a commit landed
  between two of the endpoint's per-account reads agrees on the next
  one; a reading that disagrees because the books are wrong never
  does, and is what gets asserted."
  [base-url token]
  (loop [attempt 1]
    (let [reading (read-books base-url token)]
      (if (or (agree? reading) (>= attempt settle-attempts))
        reading
        (do (Thread/sleep settle-interval-ms)
            (recur (inc attempt)))))))

(defn- assert-reads
  "Assert both reads answered. A read that fails names the bank rather
  than leaving the assertions below to hold vacuously over an empty
  chart."
  [bank-id {:keys [ledger sub-ledgers]}]
  (is (:ok? ledger)
      (str "GET " ledger-accounts-path
           " must answer for bank " bank-id
           " — status " (:status ledger)
           ", body " (pr-str (:body ledger))))
  (is (:ok? sub-ledgers)
      (str "GET " cash-accounts-path
           " must answer for bank " bank-id
           " — status " (:status sub-ledgers)
           ", body " (pr-str (:body sub-ledgers)))))

(defn- assert-ties
  [bank-id {:keys [trial-balance]}]
  (doseq [{:keys [currency debit credit]} trial-balance]
    (is (= debit credit)
        (str "trial balance must tie — bank "
             bank-id
             " "
             currency
             " (Dr "
             debit
             " / Cr "
             credit
             ")"))))

(defn- assert-reconciles
  [bank-id {:keys [controls]} {:keys [sums]}]
  (doseq [[role currency :as k] (sort (into (set (keys controls)) (keys sums)))]
    (let [sub-ledger (get sums k 0)
          control (get controls k 0)]
      (is (= sub-ledger control)
          (str "control must equal its sub-ledger — bank "
               bank-id
               " "
               role
               " ("
               (ledger-accounts/gl-account-code->gl-code role)
               ")"
               " "
               currency
               " (sub-ledger "
               sub-ledger
               " / control "
               control
               ")")))))

(defn- assert-bank
  [base-url {:keys [bank-id token]}]
  (let [{:keys [ledger sub-ledgers] :as reading} (settle base-url token)]
    (assert-reads bank-id reading)
    (when (:ok? ledger)
      (assert-ties bank-id ledger)
      (when (:ok? sub-ledgers)
        (assert-reconciles bank-id ledger sub-ledgers)))))

(defn verify-books-tie
  "Assert both invariants for every bank the run holds a token for.
  Returns `ctx` unchanged so it can be threaded through the step
  reducer. `ctx` is the runner context — `:base-url` is the booted
  API and `:banks` holds the `{:bank-id :token}` entries `verbs`
  records as each bank is created."
  [{:keys [base-url banks] :as ctx}]
  (doseq [bank (vals banks)]
    (assert-bank base-url bank))
  ctx)
