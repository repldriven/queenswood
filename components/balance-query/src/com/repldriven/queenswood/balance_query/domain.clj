(ns com.repldriven.queenswood.balance-query.domain)

(defn- net
  ^long [balance]
  (if balance (- (long (:credit balance 0)) (long (:debit balance 0))) 0))

(defmulti ^:private posted? (fn [b] [(:balance-type b) (:balance-status b)]))

(defmethod posted? [:balance-type-default :balance-status-posted] [_] true)

(defmethod posted? :default [_] false)

(defmulti ^:private available?
  (fn [b] [(= :account-product-type-general-ledger (:product-type b))
           (:balance-type b) (:balance-status b)]))

(defmethod available? [false :balance-type-default :balance-status-posted]
  [_]
  true)

(defmethod available? [false :balance-type-default
                       :balance-status-pending-outgoing]
  [_]
  true)

(defmethod available? :default [_] false)

(def ^:private derived-types
  #{:balance-type-default :balance-type-interest-accrued})

(defn derived?
  [balance]
  (let [{:keys [balance-type product-type]} balance]
    (and (contains? derived-types balance-type)
         (some? product-type)
         (not= :account-product-type-general-ledger product-type))))

(defn available-delta
  [legs]
  (transduce (comp (filter (fn [leg]
                             (= :balance-type-default (:balance-type leg))))
                   (filter (fn [leg]
                             (contains? #{:balance-status-posted
                                          :balance-status-pending-outgoing}
                                        (:balance-status leg))))
                   (map (fn [{:keys [side amount]}]
                          (let [amount (long amount)]
                            (if (= :leg-side-debit side) (- amount) amount)))))
             +
             0
             legs))

(defn net-balance
  [balances currency pred-fn]
  {:value (transduce (comp (filter pred-fn) (map net)) + 0 balances)
   :currency currency})

(defn posted-balance
  [balances currency]
  (net-balance balances currency posted?))

(defn available-balance
  [balances currency]
  (net-balance balances currency available?))

(defn trial-balance
  [entries]
  (->> entries
       (group-by :currency)
       (mapv (fn [[currency es]]
               (reduce (fn [block {:keys [normal-side value]}]
                         (let [debit? (= :debit normal-side)]
                           (-> block
                               (update :accounts inc)
                               (update (if debit? :debit :credit)
                                       +
                                       (if debit? (- value) value)))))
                       {:currency currency :debit 0 :credit 0 :accounts 0}
                       es)))))
