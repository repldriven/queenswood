(ns com.repldriven.queenswood.payment-query.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]))

;; must match payment.store store-names — same FDB stores
(def ^:private internal-payments-store-name "internal-payments")
(def ^:private outbound-payments-store-name "outbound-payments")
(def ^:private inbound-payments-store-name "inbound-payments")

(def ^:private held :inbound-payment-status-held)

(def transact fdb/transact)

(defn- oldest-first
  [payments]
  (vec (sort-by (juxt :created-at :payment-id) payments)))

(defn- newest-first
  [payments]
  (vec (sort-by :payment-id (fn [a b] (compare b a)) payments)))

(defn find-internal-payment
  [txn bank-id payment-id]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/load-record (fdb/open txn internal-payments-store-name)
                              bank-id
                              payment-id)
             schema/pb->InternalPayment))
   :payment/find-internal-payment
   "Failed to find internal payment"))

(defn get-outbound-payment
  [txn payment-id]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/query-record (fdb/open txn outbound-payments-store-name)
                               "OutboundPayment"
                               "payment_id"
                               payment-id
                               {:index "OutboundPayment_by_payment_id"})
             schema/pb->OutboundPayment))
   :payment/get-outbound-payment
   "Failed to get outbound payment"))

(defn find-outbound-payment
  [txn bank-id payment-id]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/load-record (fdb/open txn outbound-payments-store-name)
                              bank-id
                              payment-id)
             schema/pb->OutboundPayment))
   :payment/find-outbound-payment
   "Failed to find outbound payment"))

(defn find-internal-payment-by-idempotency-key
  [txn bank-id idempotency-key]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/query-record-compound
              (fdb/open txn internal-payments-store-name)
              "InternalPayment"
              [["bank_id" bank-id]
               ["idempotency_key" idempotency-key]]
              {:index "InternalPayment_by_idempotency_key"})
             schema/pb->InternalPayment))
   :payment/find-internal-by-idempotency-key
   "Failed to find internal payment by idempotency key"))

(defn find-outbound-payment-by-idempotency-key
  [txn bank-id idempotency-key]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/query-record-compound
              (fdb/open txn outbound-payments-store-name)
              "OutboundPayment"
              [["bank_id" bank-id]
               ["idempotency_key" idempotency-key]]
              {:index "OutboundPayment_by_idempotency_key"})
             schema/pb->OutboundPayment))
   :payment/find-outbound-by-idempotency-key
   "Failed to find outbound payment by idempotency key"))

(defn get-inbound-payment
  [txn scheme-transaction-id]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/query-record
              (fdb/open txn inbound-payments-store-name)
              "InboundPayment"
              "scheme_transaction_id"
              scheme-transaction-id
              {:index "InboundPayment_by_scheme_transaction_id"})
             schema/pb->InboundPayment))
   :payment/get-inbound-payment
   "Failed to get inbound payment"))

(defn find-inbound-payment
  [txn bank-id payment-id]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/load-record (fdb/open txn inbound-payments-store-name)
                              bank-id
                              payment-id)
             schema/pb->InboundPayment))
   :payment/find-inbound-payment
   "Failed to find inbound payment"))

(defn- by-end-to-end-id
  [txn end-to-end-id]
  (->> (fdb/query-records (fdb/open txn inbound-payments-store-name)
                          "InboundPayment"
                          "end_to_end_id"
                          end-to-end-id
                          {:index "InboundPayment_by_end_to_end_id"})
       (map schema/pb->InboundPayment)
       oldest-first))

(defn- open-with-status
  [txn end-to-end-id status]
  (filterv (fn [payment] (= status (:status payment)))
           (by-end-to-end-id txn end-to-end-id)))

(defn- open-holds
  [txn end-to-end-id]
  (open-with-status txn end-to-end-id held))

(defn- matches-hold?
  [creditor-account-id amount hold]
  (and (= creditor-account-id (:creditor-account-id hold))
       (or (nil? amount) (= amount (:amount hold)))))

(defn find-open-holds
  [txn end-to-end-id]
  (fdb/transact
   txn
   (fn [txn] (open-holds txn end-to-end-id))
   :payment/find-open-holds
   {:message "Failed to find open holds"
    :end-to-end-id end-to-end-id}))

(defn find-open-hold
  [txn end-to-end-id creditor-account-id amount]
  (fdb/transact
   txn
   (fn [txn]
     (->> (open-holds txn end-to-end-id)
          (filter (fn [hold]
                    (matches-hold? creditor-account-id amount hold)))
          first))
   :payment/find-open-hold
   {:message "Failed to find open hold"
    :end-to-end-id end-to-end-id}))

(defn find-matching-inbound
  [txn end-to-end-id creditor-account-id amount]
  (fdb/transact
   txn
   (fn [txn]
     (->> (by-end-to-end-id txn end-to-end-id)
          (filter (fn [payment]
                    (matches-hold? creditor-account-id amount payment)))
          first))
   :payment/find-matching-inbound
   {:message "Failed to find matching inbound"
    :end-to-end-id end-to-end-id}))

(defn find-open-admission
  [txn end-to-end-id creditor-account-id amount]
  (fdb/transact
   txn
   (fn [txn]
     (->> (open-with-status txn
                            end-to-end-id
                            :inbound-payment-status-admitted)
          (filter (fn [admitted]
                    (matches-hold? creditor-account-id amount admitted)))
          first))
   :payment/find-open-admission
   {:message "Failed to find open admission"
    :end-to-end-id end-to-end-id}))

(defn find-outbound-payments-by-status
  [txn status {:keys [created-by limit]}]
  (fdb/transact
   txn
   (fn [txn]
     (->> (fdb/scan-index-records (fdb/open txn outbound-payments-store-name)
                                  "OutboundPayment_by_status_created_at"
                                  [(schema/outbound-payment-status->int status)]
                                  {:limit limit :through [created-by]})
          (mapv schema/pb->OutboundPayment)))
   :payment/find-outbound-payments-by-status
   {:message "Failed to find outbound payments by status"
    :status status}))

(defn list-inbound-payments
  [txn bank-id status]
  (fdb/transact
   txn
   (fn [txn]
     (let [store (fdb/open txn inbound-payments-store-name)]
       (->> (fdb/query-records-compound
             store
             "InboundPayment"
             [["bank_id" bank-id]
              ["status"
               (fdb/enum-value store
                               "InboundPayment"
                               "status"
                               (schema/inbound-payment-status->int status))]]
             {:index "InboundPayment_by_bank_status_created_at"})
            (map schema/pb->InboundPayment)
            newest-first)))
   :payment/list-inbound-payments
   {:message "Failed to list inbound payments"
    :bank-id bank-id
    :status status}))

(defn get-internal-aggregates
  [txn bank-id business-day]
  (fdb/transact
   txn
   (fn [txn]
     {:count (fdb/count-records-snapshot
              (fdb/open txn internal-payments-store-name)
              "InternalPayment_count_by_bank_business_day"
              [bank-id business-day])})
   :payment/get-internal-aggregates
   {:message "Failed to count internal payments by org/day"
    :bank-id bank-id
    :business-day business-day}))

(defn get-outbound-aggregates
  [txn bank-id business-day]
  (fdb/transact
   txn
   (fn [txn]
     (let [[n total] (fdb/aggregate-records-snapshot
                      (fdb/open txn outbound-payments-store-name)
                      [[:count "OutboundPayment_count_by_bank_business_day"
                        [bank-id business-day]]
                       [:sum "OutboundPayment_sum_amount_by_bank_business_day"
                        [bank-id business-day]]])]
       {:count n :sum total}))
   :payment/get-outbound-aggregates
   {:message "Failed to total outbound payments by org/day"
    :bank-id bank-id
    :business-day business-day}))

(defn get-inbound-aggregates
  [txn bank-id business-day product-type currency]
  (fdb/transact
   txn
   (fn [txn]
     (let [type (schema/product-type->int product-type)
           [n by-type total]
           (fdb/aggregate-records-snapshot
            (fdb/open txn inbound-payments-store-name)
            [[:count "InboundPayment_count_by_bank_business_day"
              [bank-id business-day]]
             [:count
              "InboundPayment_count_by_bank_business_day_creditor_product_type"
              [bank-id business-day type]]
             [:sum
              (str "InboundPayment_sum_amount_by_bank_business_day"
                   "_creditor_product_type_currency")
              [bank-id business-day type currency]]])]
       {:count n :product-type-count by-type :product-type-sum total}))
   :payment/get-inbound-aggregates
   {:message "Failed to total inbound payments by org/day"
    :bank-id bank-id
    :business-day business-day}))

(def ^:private counted
  "For each direction, the stores an account's payments are read from:
  the index stem, whether its key ends in a status, and the statuses
  whose payments count. A payment out counts from its submission until
  it fails, and one returned after it settled still counts; a payment in
  counts once it is admitted or settled, and a held one when it is
  released, since its release is checked as a new payment is."
  {:payment-direction-out
   [{:store internal-payments-store-name
     :stem "InternalPayment_%s_by_bank_debtor_business_day"}
    {:store outbound-payments-store-name
     :stem "OutboundPayment_%s_by_bank_debtor_business_day_status"
     :statuses (into #{}
                     (map schema/outbound-payment-status->int)
                     [:outbound-payment-status-pending
                      :outbound-payment-status-held
                      :outbound-payment-status-completed
                      :outbound-payment-status-returned])}]
   :payment-direction-in
   [{:store internal-payments-store-name
     :stem "InternalPayment_%s_by_bank_creditor_business_day"}
    {:store inbound-payments-store-name
     :stem "InboundPayment_%s_by_bank_creditor_business_day_status"
     :statuses (into #{}
                     (map schema/inbound-payment-status->int)
                     [:inbound-payment-status-admitted
                      :inbound-payment-status-settled])}]})

(defn- total
  "The sum of `groups`' values, of those whose key ends in one of
  `statuses` where the index carries a status."
  [groups statuses]
  (transduce (comp (filter (fn [[k]]
                             (or (nil? statuses)
                                 (contains? statuses (peek k)))))
                   (map val))
             +
             0
             groups))

(defn get-account-aggregates
  [txn bank-id account-id direction from-day through-day opts]
  (fdb/transact
   txn
   (fn [txn]
     (let [range (assoc opts :from [from-day] :through [through-day])
           scans (mapv (fn [{:keys [store stem statuses]}]
                         (let [store (fdb/open txn store)
                               scan (fn [kind]
                                      (fdb/sum-groups-later
                                       store
                                       (format stem kind)
                                       [bank-id account-id]
                                       range))]
                           {:count (scan "count")
                            :sum (scan "sum_amount")
                            :statuses statuses}))
                       (counted direction))]
       (reduce (fn [acc {:keys [statuses] :as scan}]
                 (-> acc
                     (update :count + (total ((:count scan)) statuses))
                     (update :sum + (total ((:sum scan)) statuses))))
               {:count 0 :sum 0}
               scans)))
   :payment/get-account-aggregates
   {:message "Failed to total an account's payments"
    :bank-id bank-id
    :account-id account-id
    :direction direction}))
