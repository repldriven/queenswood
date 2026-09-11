(ns com.repldriven.queenswood.ledger-account.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]))

(def ^:private store-name "ledger-accounts")

(def transact fdb/transact)

(defn save-account
  [txn account]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/save-record (fdb/open txn store-name)
                      (schema/LedgerAccount->java account)))
   :ledger-account/save
   "Failed to save ledger account"))

(defn find-by-id
  [txn bank-id ledger-account-id]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/load-record (fdb/open txn store-name)
                              bank-id
                              ledger-account-id)
             schema/pb->LedgerAccount))
   :ledger-account/find-by-id
   "Failed to load ledger account"))

(defn find-by-code
  [txn bank-id gl-account-code currency]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/query-record-compound
              (fdb/open txn store-name)
              "LedgerAccount"
              [["bank_id" bank-id]
               ["gl_account_code"
                (schema/gl-account-code->pb-enum
                 gl-account-code)]
               ["currency" currency]]
              {:index "LedgerAccount_by_bank_gl_account_code"})
             schema/pb->LedgerAccount))
   :ledger-account/find-by-code
   "Failed to find ledger account by gl-account-code"))

(defn list-by-bank
  [txn bank-id]
  (fdb/transact
   txn
   (fn [txn]
     (mapv schema/pb->LedgerAccount
           (:records (fdb/scan-records (fdb/open txn store-name)
                                       {:prefix [bank-id] :limit 1000}))))
   :ledger-account/list
   "Failed to list ledger accounts"))
