(ns com.repldriven.queenswood.payment-query.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]))

;; must match bank-payment.store store-names — same FDB stores
(def ^:private internal-payments-store-name "internal-payments")
(def ^:private outbound-payments-store-name "outbound-payments")
(def ^:private inbound-payments-store-name "inbound-payments")

(def transact fdb/transact)

(defn get-internal-payment
  [txn payment-id]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/load-record (fdb/open txn internal-payments-store-name)
                              payment-id)
             schema/pb->InternalPayment))
   :payment/get-internal-payment
   "Failed to get internal payment"))

(defn get-outbound-payment
  [txn payment-id]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/load-record (fdb/open txn outbound-payments-store-name)
                              payment-id)
             schema/pb->OutboundPayment))
   :payment/get-outbound-payment
   "Failed to get outbound payment"))

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

(defn get-held-inbound-by-end-to-end-id
  "Return the open `held` InboundPayment for `end-to-end-id`, or nil. The
  end-to-end-id index is non-unique (ClearBank doesn't guarantee inbound
  uniqueness), so the status filter is what makes this an open-held lookup."
  [txn end-to-end-id]
  (fdb/transact
   txn
   (fn [txn]
     (let [record (some-> (fdb/query-record
                           (fdb/open txn inbound-payments-store-name)
                           "InboundPayment"
                           "end_to_end_id"
                           end-to-end-id
                           {:index "InboundPayment_by_end_to_end_id"})
                          schema/pb->InboundPayment)]
       (when (= :inbound-payment-status-held (:payment-status record))
         record)))
   :payment/get-held-inbound
   "Failed to get held inbound payment"))

(defn count-internal-by-org-business-day
  [txn bank-id business-day]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/count-records-snapshot (fdb/open txn internal-payments-store-name)
                                 "InternalPayment_count_by_bank_business_day"
                                 [bank-id business-day]))
   :payment/count-internal-by-org-business-day
   {:message "Failed to count internal payments by org/day"
    :bank-id bank-id
    :business-day business-day}))

(defn count-outbound-by-org-business-day
  [txn bank-id business-day]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/count-records-snapshot (fdb/open txn outbound-payments-store-name)
                                 "OutboundPayment_count_by_bank_business_day"
                                 [bank-id business-day]))
   :payment/count-outbound-by-org-business-day
   {:message "Failed to count outbound payments by org/day"
    :bank-id bank-id
    :business-day business-day}))

(defn sum-outbound-by-org-business-day
  [txn bank-id business-day]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/sum-records (fdb/open txn outbound-payments-store-name)
                      "OutboundPayment_sum_amount_by_bank_business_day"
                      [bank-id business-day]))
   :payment/sum-outbound-by-org-business-day
   {:message "Failed to sum outbound payments by org/day"
    :bank-id bank-id
    :business-day business-day}))

(defn count-inbound-by-org-business-day
  [txn bank-id business-day]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/count-records-snapshot (fdb/open txn inbound-payments-store-name)
                                 "InboundPayment_count_by_bank_business_day"
                                 [bank-id business-day]))
   :payment/count-inbound-by-org-business-day
   {:message "Failed to count inbound payments by org/day"
    :bank-id bank-id
    :business-day business-day}))
