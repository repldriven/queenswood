(ns com.repldriven.queenswood.test-scenarios.invariants
  "The two standing accounting invariants, asserted after every
  scenario step.

  The trial balance ties — Sigma-debit == Sigma-credit per currency
  across the whole chart of accounts. A failure means a step committed
  an unbalanced or mis-routed set of posted legs (e.g. a posting whose
  offset never reached the GL, or fanned out to the wrong control).
  This is the check that would have caught interest accrual landing off
  the books, and it guards the next class of bug too — a reversal that
  only reverses one leg, a new transaction type that forgets a control.

  Every control account is the live roll-up of its sub-ledger — the sum
  of the `default / posted` buckets of the cash accounts whose product
  type maps to that control equals the control's own `default / posted`
  bucket, per currency. The tie alone cannot catch a posting that
  balanced against the wrong control; this can.

  Both read `default / posted` only, so in-flight buckets (held,
  pending, interest-accrued sub-ledger) don't perturb them. A balance
  read that fails is never treated as zero: it fails an assertion
  naming the account, because an invariant that holds vacuously is
  worse than none.

  `reduce-cash-accounts` is exposed for the scenario-level interest
  reconciliation, which reads the same sub-ledger inside its own
  snapshot."
  (:require
    [com.repldriven.queenswood.balance-query.interface :as balances]
    [com.repldriven.queenswood.cash-account-query.interface :as cash-accounts]
    ;; nosemgrep: fdb-outside-store — asserts against raw stored state
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.ledger-account.interface :as ledger-accounts]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [is]]))

(def
  ^{:private true
    :doc
    "Cash accounts per page of a sub-ledger walk. Bounds one
  read, not the walk — the walk follows the cursor to the end."}
  page-size
  100)

(defn- net
  "Credit-positive net (credit − debit) of one balance bucket."
  [balance]
  (- (:credit balance 0) (:debit balance 0)))

(defn- default-posted?
  [balance]
  (and (= :balance-type-default (:balance-type balance))
       (= :balance-status-posted (:balance-status balance))))

(defn reduce-cash-accounts
  "Reduce `f` over every cash account in `bank-id`, each already
  carrying its `:balances`, inside the caller's `txn`. Returns the
  accumulator, or the anomaly a page read failed with.

  `cash-account-query/reduce-accounts-with-balances` refills in its own
  transactions, so it cannot be read against the same snapshot as the
  chart; this pages `get-accounts` inside whatever transaction the
  caller opened. Follows the cursor to the last page, so a bank with
  more accounts than one page still reconciles in full.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - f: reducing fn of `[acc account]`.
  - init: initial accumulator."
  [txn bank-id f init]
  (loop [cursor nil
         acc init]
    (let [page (cash-accounts/get-accounts txn
                                           bank-id
                                           (cond-> {:embed-balances true
                                                    :limit page-size}
                                                   cursor
                                                   (assoc :after cursor)))]
      (if (error/anomaly? page)
        page
        (let [acc (reduce f acc (:accounts page))]
          (if-some [after (:after page)]
            (recur after acc)
            acc))))))

(defn- add-control-totals
  "Add one cash account's `default / posted` buckets to the running
  totals, keyed by the control role its product type rolls into and the
  bucket's currency. An account whose product type rolls into no
  control contributes nothing."
  [totals account]
  (if-some [code (ledger-accounts/product-type->control-code
                  (:product-type account))]
    (reduce (fn [acc balance]
              (if (default-posted? balance)
                (update acc
                        [code (:currency balance)]
                        (fnil + 0)
                        (net balance))
                acc))
            totals
            (:balances account))
    totals))

(defn- posted-net
  "The credit-positive posted net of one ledger account, or the anomaly
  its balance read failed with. Never a zero standing in for a failed
  read — `assert-books` turns the anomaly into a failing assertion
  naming the account."
  [txn bank-id account-id]
  (let [bs (balances/get-balances txn bank-id account-id)]
    (if (error/anomaly? bs)
      bs
      (:value (:posted-balance bs)))))

(defn- chart-entry
  "One chart row, as both a trial-balance entry and a reconciliation
  target: `:normal-side` and `:value` for the tie, `:gl-account-code`
  and `:currency` for resolving a control."
  [txn bank-id account]
  {:ledger-account-id (:ledger-account-id account)
   :gl-account-code (:gl-account-code account)
   :currency (:currency account)
   :normal-side (if (ledger-accounts/debit-normal?
                     (:gl-account-type account))
                  :debit
                  :credit)
   :value (posted-net txn bank-id (:ledger-account-id account))})

(defn- books-snapshot
  "Both sides of both invariants for one bank, read in a single FDB
  transaction: the whole chart with each row's posted net, and the
  sub-ledger totals the controls are reconciled against. One snapshot,
  so an async settlement commit landing mid-read (e.g. `settle-outbound`
  posting its 1100/2100 legs on a webhook thread while this runs on the
  scenario thread) can't tear the two sides apart. Returns
  `{:chart :sub-ledger}`, or the anomaly a read failed with."
  [config bank-id]
  (fdb/transact
   config
   (fn [txn]
     (let [accounts (ledger-accounts/list-accounts txn bank-id)]
       (if (error/anomaly? accounts)
         accounts
         (let [sub-ledger (reduce-cash-accounts txn
                                                bank-id
                                                add-control-totals
                                                {})]
           (if (error/anomaly? sub-ledger)
             sub-ledger
             {:chart (mapv (fn [account]
                             (chart-entry txn bank-id account))
                           accounts)
              :sub-ledger sub-ledger})))))
   :scenario/books-snapshot
   "Failed to read the books snapshot"))

(defn- unreadable
  "Why a snapshot can't be asserted over, or nil when it can: the
  anomaly a read failed with, or `:no-snapshot` for a transaction that
  returned nothing at all."
  [snapshot]
  (cond
   (error/anomaly? snapshot)
   snapshot
   (nil? snapshot)
   :no-snapshot))

(defn- failed-reads
  "The `[ledger-account-id anomaly]` pairs of the chart rows whose
  balance read failed."
  [chart]
  (keep (fn [{:keys [ledger-account-id value]}]
          (when (error/anomaly? value)
            [ledger-account-id value]))
        chart))

(defn- assert-trial-balance-ties
  "Assert the trial balance ties for one bank, per currency, over the
  chart rows of a snapshot."
  [bank-id chart]
  (doseq [{:keys [currency debit credit]} (balances/trial-balance chart)]
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

(defn- assert-control-reconciliation
  "Assert every control account holds the live roll-up of its
  sub-ledger, for each control role and each currency the bank's chart
  carries. A control the chart doesn't carry in that currency reads as
  nil, which fails against its sub-ledger total rather than passing
  unnoticed."
  [bank-id {:keys [chart sub-ledger]}]
  (let [control (into {}
                      (map (fn [{:keys [gl-account-code currency value]}]
                             [[gl-account-code currency] value]))
                      chart)]
    (doseq [code (distinct (vals ledger-accounts/product-type->control-code))
            currency (distinct (map :currency chart))]
      (let [expected (get sub-ledger [code currency] 0)
            actual (get control [code currency])]
        (is (= expected actual)
            (str "control must hold its sub-ledger's roll-up — bank "
                 bank-id
                 " "
                 (name code)
                 " "
                 currency
                 " (sub-ledger "
                 expected
                 " / control "
                 actual
                 ")"))))))

(defn assert-books
  "Assert both standing invariants for one bank, off a single snapshot
  of its books. A snapshot that can't be read, or a chart row whose
  balance can't be read, fails an assertion naming what failed instead
  of leaving the invariants to hold over what was readable.

  Args:
  - config: FDB config map (`:record-db` / `:record-store`).
  - bank-id: owning bank id."
  [config bank-id]
  (let [snapshot (books-snapshot config bank-id)
        failure (unreadable snapshot)
        failures (failed-reads (:chart snapshot))]
    (cond
     (some? failure)
     (is (nil? failure)
         (str "books snapshot must be readable — bank "
              bank-id
              " ("
              (pr-str failure)
              ")"))

     (seq failures)
     (doseq [[ledger-account-id anomaly] failures]
       (is (nil? anomaly)
           (str "ledger account balance must be readable — bank "
                bank-id
                " account "
                ledger-account-id
                " ("
                (pr-str anomaly)
                ")")))

     :else
     (do (assert-trial-balance-ties bank-id (:chart snapshot))
         (assert-control-reconciliation bank-id snapshot)))))

(defn verify-books-tie
  "Assert both standing invariants against every bank created so far in
  the run. Returns `ctx` unchanged so it can be threaded through the
  step reducer. `ctx` is the runner context — `:bank` is the FDB config
  and `:banks` holds the per-model `{:real-id ...}` entries."
  [{:keys [bank banks] :as ctx}]
  (doseq [{:keys [real-id]} (vals banks)]
    (when real-id
      (assert-books bank real-id)))
  ctx)
