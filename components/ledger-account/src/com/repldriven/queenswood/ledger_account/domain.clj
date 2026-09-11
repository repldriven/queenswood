(ns com.repldriven.queenswood.ledger-account.domain
  (:require
    [com.repldriven.queenswood.policy.interface :as policy]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]))

(def product-type->control-code
  "Maps a cash-account product type to the `:gl-account-code` of the control
  ledger account its *default* balance rolls up into. Postings on those
  accounts fan out to the matching control so the control balance is
  always the live roll-up of its sub-ledger. Customer deposits roll into
  the 2100/2200/2300 deposit controls; the bank's own funding account
  rolls into own funds (3100)."
  {:product-type-sub-ledger-current :gl-account-code-customer-deposits-current
   :product-type-sub-ledger-savings :gl-account-code-customer-deposits-savings
   :product-type-sub-ledger-term-deposit :gl-account-code-customer-deposits-term
   :product-type-sub-ledger-own-funds :gl-account-code-own-funds})

(defn gl-account-code->gl-code
  "The chart number, as a string, for a `gl-account-code` role — the
  enum's own integer value (e.g. `:gl-account-code-suspense` -> `\"2500\"`).
  The number is a display/reporting concern; code resolves accounts by
  role, so this is only reconstituted at the API edge."
  [gl-account-code]
  (str (schema/gl-account-code->int gl-account-code)))

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
      (assoc (select-keys row
                          [:gl-account-code :name :gl-account-type
                           :gl-account-class :required])
             :bank-id bank-id
             :currency currency
             :ledger-account-id (utility/generate-id "led")
             :status :ledger-account-status-open
             :created-at now
             :updated-at now))))

(defn opening-balance
  "The single default-posted balance bucket a ledger account opens
  with, tagged `:product-type-general-ledger` so read sites can tell the
  bank's own books from a customer instrument without inferring it from
  an absent product-type."
  [ledger-account]
  (let [{:keys [ledger-account-id :currency]} ledger-account]
    {:account-id ledger-account-id
     :product-type :product-type-general-ledger
     :balance-type :balance-type-default
     :balance-status :balance-status-posted
     :currency currency}))

(defn fans-out?
  "Posted default customer legs roll up into their product-type control
  (2100/2200/2300/3100). Every other bucket and every non-posted status
  is sub-ledger-only: an `interest-accrued` bucket does not fan out,
  because the bank's side of an accrual is posted in aggregate at close
  by the interest brick's `sweep` rather than per leg."
  [leg]
  (and (= :balance-status-posted (:balance-status leg))
       (= :balance-type-default (:balance-type leg))))

(defn debit-normal?
  "True for the debit-normal account families (asset, expense); false
  for the credit-normal ones (liability, equity, income). A trial
  balance places a debit-normal account's balance in the debit column
  and a credit-normal account's in the credit column."
  [gl-account-type]
  (contains? #{:gl-account-type-asset :gl-account-type-expense}
             gl-account-type))

(defn open?
  "True unless `account` has been closed. An absent or unset `:status`
  (pre-existing seeded rows, the proto2 zero-default sentinel) counts
  as open, so no backfill is needed."
  [account]
  (not= :ledger-account-status-closed (:status account)))

(defn ensure-open
  "Return `account` unchanged if open, or `:ledger-account/closed`
  when it has been closed. Used to gate a closed account out of
  posting sites (by-role lookup, control fan-out) so a posting fails
  outright rather than silently skipping a control leg."
  [account]
  (if (open? account)
    account
    (error/reject :ledger-account/closed
                  {:message "Ledger account is closed"
                   :ledger-account-id (:ledger-account-id account)})))

(defn missing-currency-account
  "`:gl/missing-currency-account` — the bank holds no ledger account for
  the `gl-account-code` role in `currency`, so a by-role resolution has
  no row to return. Carries `:bank-id`, `:gl-account-code` and
  `:currency`, the triple that found nothing."
  [bank-id gl-account-code currency]
  (error/reject :gl/missing-currency-account
                {:message (str "Bank has no "
                               (name gl-account-code)
                               " ledger account in "
                               currency)
                 :bank-id bank-id
                 :gl-account-code gl-account-code
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
