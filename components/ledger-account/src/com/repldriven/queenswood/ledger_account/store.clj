(ns com.repldriven.queenswood.ledger-account.store
  (:require
    [com.repldriven.queenswood.balance-query.interface :as balance-query]
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

(defn cache
  [txn]
  (fdb/cache txn :ledger-account))

(defn find-by-ids
  [txn bank-id ledger-account-ids]
  (fdb/transact
   txn
   (fn [txn]
     (zipmap ledger-account-ids
             (map (fn [record]
                    (some-> record
                            schema/pb->LedgerAccount))
                  (fdb/load-records (fdb/open txn store-name)
                                    (mapv (fn [id] [bank-id id])
                                          ledger-account-ids)))))
   :ledger-account/find-by-id
   "Failed to load ledger accounts"))

(defn preload-by-ids
  [txn bank-id ledger-account-ids]
  (fdb/transact txn
                (fn [txn]
                  (fdb/preload-records (fdb/open txn store-name)
                                       (mapv (fn [id] [bank-id id])
                                             ledger-account-ids)))
                :ledger-account/find-by-id
                "Failed to preload ledger accounts"))

(defn find-by-codes
  [txn bank-id gl-account-codes currency]
  (fdb/transact
   txn
   (fn [txn]
     (zipmap gl-account-codes
             (map (fn [record]
                    (some-> record
                            schema/pb->LedgerAccount))
                  (fdb/query-records-compound-one-each
                   (fdb/open txn store-name)
                   "LedgerAccount"
                   (mapv (fn [code]
                           [["bank_id" bank-id]
                            ["gl_account_code"
                             (schema/gl-account-code->pb-enum code)]
                            ["currency" currency]])
                         gl-account-codes)
                   {:index "LedgerAccount_by_bank_gl_account_code"}))))
   :ledger-account/find-by-code
   "Failed to find ledger accounts by gl-account-code"))

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

(defn list-by-bank-with-balances
  "The bank's chart paired with each account's balances, in account-id
  order. One merged scan of the two stores rather than a balance read
  per account: both are keyed `[bank_id, account_id, ...]`, so the
  cursors advance in step. A cash account's balances share the bank
  prefix and pair with no chart row, so they are skipped."
  [config bank-id]
  (fdb/merge-scan
   config
   {:left {:store store-name :prefix [bank-id] :limit 1000}
    :right {:store balance-query/store-name :prefix [bank-id] :limit 5000}}
   (fn [acc {:keys [left right]}]
     (if-let [record (first left)]
       (conj acc
             {:account (schema/pb->LedgerAccount record)
              :balances (mapv schema/pb->AccountBalance right)})
       acc))
   []))
