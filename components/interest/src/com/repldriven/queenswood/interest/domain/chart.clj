(ns com.repldriven.queenswood.interest.domain.chart
  (:require
    [com.repldriven.queenswood.ledger-account.interface :as ledger-accounts]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(defn- account-id
  "The id of the bank's ledger account for `gl-account-code` in
  `currency`, or a rejection naming the code and the currency it has no
  account for. A chart carries one flat row per role per currency, so a
  role alone does not name an account — a bank booking in two
  currencies has two interest-payable rows and a posting must land on
  the one its own money is in. A bank whose chart cannot take a posting
  the run has to make is a chart-of-accounts problem the run cannot
  work around."
  [chart bank-id gl-account-code currency]
  (if-some [account (first (filter (fn [a]
                                     (and (= gl-account-code
                                             (:gl-account-code a))
                                          (= currency (:currency a))))
                                   chart))]
    (:ledger-account-id account)
    (error/reject :interest/missing-gl-account
                  {:message "Bank has no such account in its chart"
                   :bank-id bank-id
                   :gl-account-code gl-account-code
                   :currency currency})))

(defn- deposit-controls
  "Every product type that rolls up into a control, against the id of
  the control it rolls into in `currency`. Taken from the ledger's own
  mapping rather than a list of the product types that pay interest
  today, so a product that starts paying tomorrow already has somewhere
  to land."
  [chart bank-id currency]
  (reduce (fn [acc [product-type gl-account-code]]
            (let [id (account-id chart bank-id gl-account-code currency)]
              (if (error/anomaly? id)
                (reduced id)
                (assoc acc product-type id))))
          {}
          ledger-accounts/product-type->control-code))

(defn accrual-accounts
  "What an accrual run posts between in `currency`: interest expense
  and the interest-payable control. Two fixed roles — an account's
  product type makes no difference to where the bank's side of an
  accrual lands."
  [chart bank-id currency]
  (let-nom>
    [expense (account-id chart
                         bank-id
                         :gl-account-code-interest-expense
                         currency)
     payable (account-id chart
                         bank-id
                         :gl-account-code-interest-payable
                         currency)]
    {:expense expense :payable payable}))

(defn capitalization-accounts
  "What a capitalisation run posts between in `currency`: the
  interest-payable control it clears, and the deposit control each
  earning product type rolls into. The credit side differs by product
  type, which is why capitalisation groups by it and accrual does not."
  [chart bank-id currency]
  (let-nom>
    [payable (account-id chart
                         bank-id
                         :gl-account-code-interest-payable
                         currency)
     controls (deposit-controls chart bank-id currency)]
    {:payable payable :controls controls}))

(defn by-currency
  "What `accounts-fn` resolves out of `chart`, for every currency the
  chart carries, keyed by currency. Resolving the whole chart up front
  is what lets a run fail before it moves customer money: a currency
  whose rows cannot take the run's ledger side rejects here, with no
  customer posting yet made. A currency the chart carries no row of at
  all is invisible to this and is caught by `accounts-for` at close."
  [chart bank-id accounts-fn]
  (reduce (fn [acc currency]
            (let [resolved (accounts-fn chart bank-id currency)]
              (if (error/anomaly? resolved)
                (reduced resolved)
                (assoc acc currency resolved))))
          {}
          (distinct (keep :currency chart))))

(defn accounts-for
  "The `by-currency` entry for `currency`, or a rejection naming the
  currency the bank holds accounts in and has no chart rows in. Reached
  only by a run that posted in a currency its chart says nothing about,
  which `by-currency` cannot see because it reads the chart rather than
  the accounts."
  [gl bank-id currency]
  (if-some [resolved (get gl currency)]
    resolved
    (error/reject :interest/missing-gl-account
                  {:message "Bank has no chart of accounts in this currency"
                   :bank-id bank-id
                   :currency currency})))
