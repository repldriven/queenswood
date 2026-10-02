(ns com.repldriven.queenswood.payment.store
  (:require
    [com.repldriven.queenswood.payment.changelog :as changelog]

    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :refer [let-nom>]]))

;; must match payment-query.store store-names — same FDB stores
(def ^:private internal-payments-store-name "internal-payments")
(def ^:private outbound-payments-store-name "outbound-payments")
(def ^:private inbound-payments-store-name "inbound-payments")
(def ^:private provider-transfers-store-name "provider-transfers")

(def transact fdb/transact)
(def uniqueness-violation? fdb/uniqueness-violation?)

(defn- with-record
  "The changelog fields the saved record supplies: its ids, the status
  it now carries, and the time the entry's dedup key is drawn from."
  [changelog payment]
  (assoc changelog
         :bank-id (:bank-id payment)
         :payment-id (:payment-id payment)
         :status-after (:payment-status payment)
         :updated-at (or (:updated-at payment) (:created-at payment))))

(defn save-internal-payment
  "Save the payment and co-commit its one entry, `settle`, in one
  transaction: an internal payment carries no status, so the entry is
  the transition its save is."
  [txn payment]
  (fdb/transact
   txn
   (fn [txn]
     (let-nom>
       [_ (fdb/save-record (fdb/open txn internal-payments-store-name)
                           (schema/InternalPayment->java payment))
        entry (changelog/internal-settled
               (assoc (with-record {:change-kind
                                    :internal-payment-change-kind-settle
                                    :status-before nil}
                                   payment)
                      :status-after
                      :internal-payment-status-settled))
        _ (fdb/write-changelog txn
                               internal-payments-store-name
                               (:payment-id payment)
                               entry)]
       nil))
   :payment/save-internal-payment
   "Failed to save internal payment"))

(defn save-outbound-payment
  "Save the payment and co-commit its changelog entry, one transaction.
  `changelog` carries `:change-kind` and `:status-before`; the rest
  comes off the payment."
  [txn payment changelog]
  (fdb/transact
   txn
   (fn [txn]
     (let-nom>
       [_ (fdb/save-record (fdb/open txn outbound-payments-store-name)
                           (schema/OutboundPayment->java payment))
        entry (changelog/outbound-changed (with-record changelog payment))
        _ (fdb/write-changelog txn
                               outbound-payments-store-name
                               (:payment-id payment)
                               entry)]
       nil))
   :payment/save-outbound-payment
   "Failed to save outbound payment"))

(defn save-inbound-payment
  "As `save-outbound-payment`, for an inbound payment."
  [txn payment changelog]
  (fdb/transact
   txn
   (fn [txn]
     (let-nom>
       [_ (fdb/save-record (fdb/open txn inbound-payments-store-name)
                           (schema/InboundPayment->java payment))
        entry (changelog/inbound-changed (with-record changelog payment))
        _ (fdb/write-changelog txn
                               inbound-payments-store-name
                               (:payment-id payment)
                               entry)]
       nil))
   :payment/save-inbound-payment
   "Failed to save inbound payment"))

(defn transfers-for-transaction
  "The provider transfers recorded for `transaction-id`."
  [txn transaction-id]
  (fdb/transact
   txn
   (fn [txn]
     (mapv schema/pb->ProviderTransfer
           (fdb/query-records (fdb/open txn provider-transfers-store-name)
                              "ProviderTransfer"
                              "transaction_id"
                              transaction-id
                              {:index "ProviderTransfer_by_transaction_pair"})))
   :payment/transfers-for-transaction
   "Failed to read a transaction's provider transfers"))

(defn get-transfer
  [txn bank-id transfer-id]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/load-record (fdb/open txn provider-transfers-store-name)
                              bank-id
                              transfer-id)
             schema/pb->ProviderTransfer))
   :payment/get-transfer
   "Failed to read a provider transfer"))

(defn save-transfer
  [txn transfer]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/save-record (fdb/open txn provider-transfers-store-name)
                      (schema/ProviderTransfer->java transfer)))
   :payment/save-transfer
   "Failed to save a provider transfer"))

