(ns com.repldriven.queenswood.transaction.domain
  (:require
    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private type->status
  {:transaction-type-internal-transfer :transaction-status-posted
   :transaction-type-inbound-transfer :transaction-status-posted
   :transaction-type-reward :transaction-status-posted
   :transaction-type-outbound-return :transaction-status-posted
   :transaction-type-inbound-return :transaction-status-posted})

(defn new-transaction
  [data]
  (let [{:keys [bank-id idempotency-key transaction-type currency
                reference]}
        data
        now (utility/now)
        status (get type->status
                    transaction-type
                    :transaction-status-pending)]
    (if (nil? bank-id)
      ;; The record's bank_id is optional on the wire so older records
      ;; still parse, but it heads the idempotency-key index — a
      ;; transaction written without one takes an unscoped entry.
      (error/reject :transaction/missing-bank-id
                    "Transaction must carry a bank-id")
      (utility/assoc-some
       {:transaction-id (utility/generate-id "txn")
        :bank-id bank-id
        :idempotency-key idempotency-key
        :transaction-type transaction-type
        :currency currency
        :status status
        :created-at now
        :updated-at now}
       :reference
       reference))))

(defn- leg-total
  [side legs]
  (->> legs
       (filter #(= side (:side %)))
       (map :amount)
       (reduce + 0)))

(defn validate-legs
  [legs]
  (cond
   (some #(<= (:amount %) 0) legs)
   (error/reject :transaction/invalid-amount
                 "Transaction amount must be positive")

   (not= (leg-total :leg-side-debit legs) (leg-total :leg-side-credit legs))
   (error/reject :transaction/legs-unbalanced
                 "Transaction legs must balance (debits = credits)")))

(defn new-leg
  [leg transaction]
  (let [{:keys [account-id balance-type balance-status side amount
                product-type]}
        leg
        {:keys [transaction-id bank-id currency]} transaction]
    (utility/assoc-some {:leg-id (utility/generate-id "leg")
                         :transaction-id transaction-id
                         :bank-id bank-id
                         :account-id account-id
                         :balance-type balance-type
                         :balance-status balance-status
                         :side side
                         :amount amount
                         :currency currency
                         :created-at (utility/now)}
                        :product-type
                        product-type)))

(defn- ->posted-leg
  [{:keys [account-id balance-type balance-status side amount]}]
  {:account-id account-id
   :balance-type balance-type
   :balance-status balance-status
   :side side
   :amount amount})

(defn posted
  [transaction legs scheme-account-id]
  (let [{:keys [transaction-id bank-id transaction-type currency]} transaction]
    (utility/assoc-some {:bank-id bank-id
                         :transaction-id transaction-id
                         :transaction-type transaction-type
                         :currency currency
                         :legs (mapv ->posted-leg legs)}
                        :scheme-account-id
                        scheme-account-id)))
