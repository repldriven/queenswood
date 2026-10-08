(ns com.repldriven.queenswood.ledger-account.domain
  (:require
    [com.repldriven.queenswood.policy.interface :as policy]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]))

(def product-type->control-code
  "Maps a cash-account product type to the `:code` of the control
  ledger account its *default* balance rolls up into. The control holds
  no balance of its own: its balance is the sum of its sub-ledger's,
  read from the balances store's indexes. Customer deposits roll into
  the 2100/2200/2300 deposit controls; the bank's own funding account
  rolls into own funds (3100)."
  {:product-type-sub-ledger-current
   :ledger-account-code-customer-deposits-current
   :product-type-sub-ledger-savings
   :ledger-account-code-customer-deposits-savings
   :product-type-sub-ledger-term-deposit
   :ledger-account-code-customer-deposits-term
   :product-type-sub-ledger-own-funds :ledger-account-code-own-funds})

(def derived
  "How each ledger account holding no balance row of its own reads one,
  by `:code`. A deposit or own-funds control sums its
  sub-ledger's posted balances from the legs' indexes, by
  `:product-types` and `:balance-status`; 2400 interest-payable does the
  same with every customer's `:balance-type` interest-accrued bucket;
  1200 pending-outbound with every customer's pending-outgoing balance,
  and `:mirror?`s it, crediting what they debit; 1100
  cash-at-correspondent and 5100 interest-expense, whose movements
  mirror no set of customer balances, sum their own legs from the
  journal (`:journal?`). A ledger account absent from it keeps a stored
  balance. See ADR-0037, ADR-0038, ADR-0039 and ADR-0042."
  (assoc (into {}
               (map (fn [[product-type code]] [code
                                               {:product-types [product-type]
                                                :balance-status
                                                :balance-status-posted}]))
               product-type->control-code)
         :ledger-account-code-interest-payable
         {:product-types (vec (keys product-type->control-code))
          :balance-type :balance-type-interest-accrued
          :balance-status :balance-status-posted}
         :ledger-account-code-pending-outbound
         {:product-types (vec (keys product-type->control-code))
          :balance-status :balance-status-pending-outgoing
          :mirror? true}
         :ledger-account-code-cash-at-correspondent
         {:balance-status :balance-status-posted :journal? true}
         :ledger-account-code-interest-expense
         {:balance-status :balance-status-posted :journal? true}))

(defn posted-to?
  "Whether postings name a derived account in their legs, which stay in
  the journal but write no balance row: 1200, 1100 and 5100, where a
  control is never named."
  [spec]
  (boolean (or (:mirror? spec) (:journal? spec))))

(defn derived-balance
  "The balance of derived `account`, built by `spec` from the summed
  `{:credit :debit}` of the balances it is derived from, in place of a
  stored row."
  [account {:keys [balance-status mirror?]} {:keys [credit debit]}]
  (let [{:keys [bank-id ledger-account-id created-at updated-at]} account]
    {:bank-id bank-id
     :account-id ledger-account-id
     :product-type :product-type-general-ledger
     :balance-type :balance-type-default
     :balance-status balance-status
     :credit (if mirror? debit credit)
     :debit (if mirror? credit debit)
     :created-at created-at
     :updated-at updated-at}))

(defn chart-number
  "The chart number, as a string, for a `code` role — the
  enum's own integer value (e.g. `:ledger-account-code-suspense` -> `\"2500\"`).
  The number is a display/reporting concern; code resolves accounts by
  role, so this is only reconstituted at the API edge."
  [code]
  (str (schema/ledger-account-code->int code)))

(def ^:private class-by-thousand
  "An account's class by the thousand of its chart number."
  {1 :ledger-account-class-asset
   2 :ledger-account-class-liability
   3 :ledger-account-class-equity
   4 :ledger-account-class-income
   5 :ledger-account-class-expense})

(defn account-class
  "The class of the account in a `code` role, from the thousand
  of its chart number: `:ledger-account-class-asset`, `-liability`,
  `-equity`, `-income` or `-expense`."
  [code]
  (class-by-thousand (quot (schema/ledger-account-code->int code)
                           1000)))

(def ^:private control-codes
  "The codes whose account stands for a sub-ledger: the deposit and
  own-funds controls, and 2400 interest-payable."
  (conj (set (vals product-type->control-code))
        :ledger-account-code-interest-payable))

(defn account-type
  "The type of the account in a `code` role: `:ledger-account-type-control`
  for one standing for a sub-ledger, `:ledger-account-type-detail`
  otherwise."
  [code]
  (if (contains? control-codes code)
    :ledger-account-type-control
    :ledger-account-type-detail))

(defn new-ledger-account
  "Build a `LedgerAccount` map for one template `row` in `currency`,
  stamping a fresh `led.` id and timestamps. Gated on the
  `:ledger-account` open capability in `policies` (opening a ledger
  account mirrors opening a cash account), so a tier that denies it
  (e.g. micro) cannot mint ledger accounts; returns the account map or
  the deny anomaly."
  [bank-id currency row policies]
  (let-nom>
    [_ (policy/check-capability policies
                                :ledger-account
                                {:action :ledger-account-action-open})]
    (let [now (utility/now)]
      (assoc (select-keys row [:code :name])
             :bank-id bank-id
             :currency currency
             :ledger-account-id (utility/generate-id "led")
             :status :ledger-account-status-open
             :created-at now
             :updated-at now))))

(defn opening-balance
  "The single default-posted balance bucket a ledger account other than
  a control opens with, tagged `:product-type-general-ledger` so read sites can tell the
  bank's own books from a customer instrument without inferring it from
  an absent product-type."
  [ledger-account]
  {:account-id (:ledger-account-id ledger-account)
   :product-type :product-type-general-ledger
   :balance-type :balance-type-default
   :balance-status :balance-status-posted})

(defn control-code
  "The `:code` of the control a posted customer leg rolls up
  into: its product type's (2100/2200/2300/3100) for the default
  bucket, 2400 for the interest-accrued one. Nil for every other bucket
  and status, which are sub-ledger-only."
  [leg]
  (let [{:keys [balance-type balance-status product-type]} leg]
    (when (= :balance-status-posted balance-status)
      (case balance-type
        :balance-type-default
        (product-type->control-code product-type)

        :balance-type-interest-accrued
        (when (product-type->control-code product-type)
          :ledger-account-code-interest-payable)

        nil))))

(defn debit-normal?
  "True for an account whose class is debit-normal (asset, expense);
  false for a credit-normal one (liability, equity, income). A trial
  balance places a debit-normal account's balance in the debit column
  and a credit-normal account's in the credit column."
  [account]
  (contains? #{:ledger-account-class-asset :ledger-account-class-expense}
             (account-class (:code account))))

(defn open?
  "True unless `account` has been closed."
  [account]
  (= :ledger-account-status-open (:status account)))

(defn ensure-open
  "Return `account` unchanged if open, or `:ledger-account/closed`
  when it has been closed. Used to gate a closed account out of
  posting sites (by-role lookup, the control a leg rolls into) so a
  posting fails outright rather than moving a closed control."
  [account]
  (if (open? account)
    account
    (error/reject :ledger-account/closed
                  {:message "Ledger account is closed"
                   :ledger-account-id (:ledger-account-id account)})))

(defn missing-currency-account
  "`:gl/missing-currency-account` — the bank holds no ledger account for
  the `code` role in `currency`, so a by-role resolution has
  no row to return. Carries `:bank-id`, `:code` and
  `:currency`, the triple that found nothing."
  [bank-id code currency]
  (error/reject :gl/missing-currency-account
                {:message (str "Bank has no "
                               (name code)
                               " ledger account in "
                               currency)
                 :bank-id bank-id
                 :code code
                 :currency currency}))

(defn close
  "Transition `account` to closed. Rejects
  `:ledger-account/invalid-status` if already closed,
  `:gl/non-zero-on-close` unless `balance`'s posted default bucket
  nets to zero, and the `:ledger-account` close capability via
  `policies`. `balance` is the account's default-posted bucket, read
  by the caller inside the same transaction as this guard and the
  save."
  [account balance policies]
  (let-nom>
    [_ (when-not (open? account)
         (error/reject :ledger-account/invalid-status
                       {:message "Account is not in a closeable state"
                        :ledger-account-id (:ledger-account-id account)
                        :status (:status account)
                        :allowed #{:ledger-account-status-open}}))
     _ (let [{:keys [credit debit]} balance]
         (when-not (= (or credit 0) (or debit 0))
           (error/reject :gl/non-zero-on-close
                         {:message "Ledger account has a non-zero balance"
                          :ledger-account-id (:ledger-account-id account)
                          :credit credit
                          :debit debit})))
     _ (policy/check-capability policies
                                :ledger-account
                                {:action :ledger-account-action-close})]
    (assoc account
           :status :ledger-account-status-closed
           :updated-at (utility/now))))
