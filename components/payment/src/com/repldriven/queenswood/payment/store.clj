(ns com.repldriven.queenswood.payment.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]))

;; must match payment-query.store store-names — same FDB stores
(def ^:private internal-payments-store-name "internal-payments")
(def ^:private outbound-payments-store-name "outbound-payments")
(def ^:private inbound-payments-store-name "inbound-payments")

(def transact fdb/transact)
(def uniqueness-violation? fdb/uniqueness-violation?)

(defn save-internal-payment
  [txn payment]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/save-record
      (fdb/open txn internal-payments-store-name)
      (schema/InternalPayment->java payment)))
   :payment/save-internal-payment
   "Failed to save internal payment"))

(defn save-outbound-payment
  [txn payment]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/save-record
      (fdb/open txn outbound-payments-store-name)
      (schema/OutboundPayment->java payment)))
   :payment/save-outbound-payment
   "Failed to save outbound payment"))

(defn save-inbound-payment
  [txn payment]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/save-record
      (fdb/open txn inbound-payments-store-name)
      (schema/InboundPayment->java payment)))
   :payment/save-inbound-payment
   "Failed to save inbound payment"))
