(ns com.repldriven.queenswood.test-api-scenarios.invariants
  "The trial-balance tie, asserted after every scenario step against
  every bank the run holds a token for: per currency, across the whole
  chart, Sigma-debit equals Sigma-credit, as the `:trial-balance` block
  of `GET /v1/ledger-accounts` reports it. The route reads the chart in
  one transaction, so the block is one reading of the books and is
  asserted as read.

  The control reconciliation, each control against the sub-ledger it
  controls, is the domain runner's: it reads both sides in one FDB
  snapshot, where no pair of HTTP reads shares a moment.

  A bank-scoped token is what makes a bank readable at all: the route
  takes its `bank-id` from the caller's token rather than a path, so
  `verbs` mints one per bank it creates and the banks it could not mint
  for are reported as skipped."
  (:require
    [com.repldriven.mono.http-client.interface :as http]

    [clojure.string :as str]
    [clojure.test :refer [is]]))

(def ^:private ledger-accounts-path "/v1/ledger-accounts")

(def ^:private audit-events-path "/v1/bank/audit-events")

(def ^:private audit-page-size
  "How many of a bank's newest audit events the check reads. A step's
  own event is among the newest, since steps run one at a time."
  50)

(defn- read-ledger
  "Read the bank's chart and its per-currency trial-balance block."
  [base-url token]
  (let [res (http/request {:method :get
                           :url (str base-url ledger-accounts-path)
                           :headers {"Authorization" (str "Bearer " token)}})
        status (:status res)
        body (http/res->edn res)
        {:keys [items trial-balance]} body]
    {:ok? (and (= 200 status) (seq items) (some? trial-balance))
     :status status
     :body body
     :trial-balance trial-balance}))

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

(defn- assert-bank
  [base-url {:keys [bank-id token]}]
  (let [{:keys [ok? status body] :as ledger} (read-ledger base-url token)]
    (is ok?
        (str "GET " ledger-accounts-path
             " must answer for bank " bank-id
             " — status " status
             ", body " (pr-str body)))
    (when ok? (assert-ties bank-id ledger))))

(defn verify-books-tie
  "Assert the trial-balance tie for every bank the run holds a token
  for. Returns `ctx` unchanged so it can be threaded through the step
  reducer. `ctx` is the runner context — `:base-url` is the booted
  API and `:banks` holds the `{:bank-id :token}` entries `verbs`
  records as each bank is created."
  [{:keys [base-url banks] :as ctx}]
  (doseq [bank (vals banks)]
    (assert-bank base-url bank))
  ctx)

(defn- audit-request-ids
  [base-url admin-token bank-id]
  (let [res (http/request {:method :get
                           :url (str base-url audit-events-path)
                           :query-params {"page[size]" audit-page-size}
                           :headers {"Authorization" (str "Bearer " admin-token)
                                     "Bank-Id" bank-id}})]
    (into #{} (keep :request-id) (:items (http/res->edn res)))))

(defn verify-audited
  "Assert that the write the step just made, when it was a successful,
  fresh write to the API, left an audit event under its request id in
  one of the banks it may have acted on. Returns `ctx` without the
  pending check. `ctx` is the runner context — `:pending-audit` is set
  by `verbs` on each request step."
  [{:keys [base-url admin-token pending-audit] :as ctx}]
  (when-let [{:keys [request-id method path bank-ids]} pending-audit]
    (is (some (fn [bank-id]
                (contains? (audit-request-ids base-url admin-token bank-id)
                           request-id))
              bank-ids)
        (str (str/upper-case (name method))
             " "
             path
             " left no audit event under its request id "
             request-id
             " in "
             (pr-str bank-ids))))
  (dissoc ctx :pending-audit))
