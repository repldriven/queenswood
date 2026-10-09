(ns com.repldriven.queenswood.balance.domain
  (:require
    [com.repldriven.queenswood.balance-query.interface :as q]
    [com.repldriven.queenswood.policy.interface :as policy]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]))

(defn- ensure-product-type
  [data]
  (when-not (:product-type data)
    (error/reject :balance/missing-product-type
                  (merge {:message "A balance must carry a product-type"}
                         (select-keys data
                                      [:account-id :balance-type
                                       :balance-status])))))

(defn- ensure-new-balance
  [data exists?]
  (when exists?
    (error/reject :balance/already-exists
                  (merge {:message "Balance already exists"}
                         (select-keys data
                                      [:account-id :balance-type
                                       :balance-status])))))

(defn- check-capability
  [action balance-type balance-status policies]
  (policy/check-capability policies
                           :balance
                           {:action action
                            :balance-type balance-type
                            :balance-status balance-status}))

(defn- find-balance-index
  [balances balance-type balance-status]
  (some (fn [[i b]]
          (when (and (= balance-type (:balance-type b))
                     (= balance-status (:balance-status b)))
            i))
        (map-indexed vector balances)))

(defn- update-balance
  [balances balance-type balance-status f]
  (if-let [idx (find-balance-index balances balance-type balance-status)]
    (let-nom> [updated (f (nth balances idx))]
      (assoc balances idx updated))
    (error/reject :balance/not-found
                  {:message "Balance not found"
                   :balance-type balance-type
                   :balance-status balance-status})))

(defn- apply-leg
  [balance leg policies]
  (let [{:keys [balance-type balance-status]} balance
        {:keys [side amount]} leg]
    (let-nom>
      [_ (check-capability :balance-action-apply
                           balance-type
                           balance-status
                           policies)]
      (let [field (if (= :leg-side-debit side) :debit :credit)]
        (update balance field + amount)))))

(defn- new-zero-balance
  "A fresh zero balance for a leg whose bucket doesn't exist yet — posting
  to a (balance-type, balance-status) opens it (e.g. the first time funds
  are reserved into pending-outgoing). The bucket takes the leg's
  product-type, else that of the account's existing `balances`, since a
  control account's balance sums its sub-ledger's buckets by product
  type; an account with neither is a ledger-account (GL) one, matching
  how GL balances are seeded.

  `bank-id` comes from the caller rather than the leg: it heads the
  balance's primary key, and a leg reaching here need not carry one."
  [bank-id balances leg]
  (let [now (utility/now)]
    {:bank-id bank-id
     :account-id (:account-id leg)
     :product-type (or (:product-type leg)
                       (some :product-type balances)
                       :account-product-type-general-ledger)
     :balance-type (:balance-type leg)
     :balance-status (:balance-status leg)
     :credit 0
     :debit 0
     :created-at now
     :updated-at now}))

(defn- apply-leg-to-balances
  [bank-id balances leg policies]
  (let [{:keys [balance-type balance-status]} leg]
    (if (find-balance-index balances balance-type balance-status)
      (update-balance balances
                      balance-type
                      balance-status
                      (fn [balance] (apply-leg balance leg policies)))
      ;; Open the bucket on demand: a posting to a not-yet-existing
      ;; (balance-type, balance-status) creates it, then applies the leg.
      (let-nom> [opened (apply-leg (new-zero-balance bank-id balances leg)
                                   leg
                                   policies)]
        (conj balances opened)))))

(defn- apply-legs-to-balances
  [bank-id balances legs policies]
  (reduce (fn [bs leg]
            (let [result (apply-leg-to-balances bank-id bs leg policies)]
              (if (error/anomaly? result) (reduced result) result)))
          balances
          legs))

(defn- check-available
  [pre post currency transaction-type policies]
  (let [pre-amount (q/available-balance pre currency)
        post-amount (q/available-balance post currency)]
    (policy/check-limit policies
                        :balance
                        {:kind {:computed {:name "available"}}
                         :transaction-type transaction-type
                         :aggregate :amount
                         :window :time-window-instant
                         :pre-value {:value (:value pre-amount)
                                     :currency currency}
                         :value {:value (:value post-amount)
                                 :currency currency}})))

(defn- bucket
  [b]
  [(:balance-type b) (:balance-status b)])

(defn- before-legs
  "`balances` as they stood before `legs`: a derived bucket was read
  after the posting recorded its legs, so its sums already hold them."
  [balances legs]
  (mapv (fn [b]
          (if (q/derived? b)
            (reduce (fn [b leg]
                      (if (= (bucket b) (bucket leg))
                        (update b
                                (if (= :leg-side-debit (:side leg))
                                  :debit
                                  :credit)
                                -
                                (:amount leg))
                        b))
                    b
                    legs)
            b))
        balances))

(defn- changed
  "Rows to save: balances in `new` that were added or modified versus
  `old`, matched by (balance-type, balance-status), so a freshly opened
  bucket is captured. A derived bucket is its legs' sum and its row is
  never rewritten, so it is saved only when it opens, at zero."
  [old new]
  (let [old-by (into {} (map (fn [b] [(bucket b) b])) old)]
    (into []
          (comp (remove (fn [b] (= b (get old-by (bucket b)))))
                (keep (fn [b]
                        (cond (not (q/derived? b))
                              b

                              (contains? old-by (bucket b))
                              nil

                              :else
                              (assoc b :credit 0 :debit 0)))))
          new)))

(defn apply-legs
  [bank-id account-balances legs transaction-type policies]
  (reduce
   (fn [acc [account-id account-legs]]
     (let [pre (before-legs (get account-balances account-id) account-legs)
           post (let-nom>
                  [balances (apply-legs-to-balances bank-id
                                                    pre
                                                    account-legs
                                                    policies)
                   _ (check-available pre
                                      balances
                                      (:currency (first account-legs))
                                      transaction-type
                                      policies)]
                  balances)]
       (if (error/anomaly? post)
         (reduced post)
         (into acc (changed pre post)))))
   []
   (group-by :account-id legs)))

(defn new-balance
  [data exists? policies]
  (let-nom>
    [_ (check-capability :balance-action-create
                         (:balance-type data)
                         (:balance-status data)
                         policies)
     _ (ensure-product-type data)
     _ (ensure-new-balance data exists?)]
    (let [{:keys [bank-id account-id product-type balance-type balance-status]}
          data
          now (utility/now)]
      {:bank-id bank-id
       :account-id account-id
       :product-type product-type
       :balance-type balance-type
       :balance-status balance-status
       :credit 0
       :debit 0
       :created-at now
       :updated-at now})))
