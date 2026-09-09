(ns com.repldriven.queenswood.transaction.domain
  (:require
    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private type->status
  {:transaction-type-internal-transfer :transaction-status-posted
   :transaction-type-inbound-transfer :transaction-status-posted})

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

(defn- side+amount
  [leg]
  [(:side leg) (:amount leg)])

(defn- control-legs-mirror-postings?
  "Every `:control` roll-up leg must duplicate a distinct posting leg
  by side and amount. The flag means 'denormalised mirror of a
  posting' — so a control leg matching no posting would be slipping an
  unbacked amount past the balance check."
  [control postings]
  (let [available (frequencies (map side+amount postings))]
    (every? (fn [[k n]] (<= n (get available k 0)))
            (frequencies (map side+amount control)))))

(defn validate-legs
  [legs]
  ;; A transaction's real double-entry lives in its non-`:control`
  ;; legs (single-currency, no FX). Roll-up `:control` mirrors are
  ;; excluded from the balance, but each must duplicate a posting leg
  ;; so the flag can't smuggle an unbalanced amount through.
  (let [control (filter :control legs)
        postings (remove :control legs)]
    (cond
     (some #(<= (:amount %) 0) legs)
     (error/reject :transaction/invalid-amount
                   "Transaction amount must be positive")

     (not= (leg-total :leg-side-debit postings)
           (leg-total :leg-side-credit postings))
     (error/reject :transaction/legs-unbalanced
                   "Transaction legs must balance (debits = credits)")

     (not (control-legs-mirror-postings? control postings))
     (error/reject :transaction/control-leg-mismatch
                   "Each roll-up leg must mirror a posting leg"))))

(defn new-leg
  [leg transaction-id currency]
  (let [{:keys [account-id balance-type balance-status
                side amount]}
        leg]
    {:leg-id (utility/generate-id "leg")
     :transaction-id transaction-id
     :account-id account-id
     :balance-type balance-type
     :balance-status balance-status
     :side side
     :amount amount
     :currency currency
     :created-at (utility/now)}))
