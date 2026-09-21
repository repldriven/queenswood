(ns com.repldriven.queenswood.reward-query.interface
  "Reads of the `Reward` rows the `reward` brick writes: what a bank
  paid, or owes, an account for opening it. The row is keyed by bank
  and reward, so another bank's reward reads as absent."
  (:require
    [com.repldriven.queenswood.reward-query.store :as store]))

(defn find-reward
  "Load `bank-id`'s reward by id.

  Args:
  - txn: FDB handle or open transaction.
  - bank-id: the caller's bank.
  - reward-id: the reward's id.

  Returns the reward map, or nil when there is none under that bank."
  [txn bank-id reward-id]
  (store/find-reward txn bank-id reward-id))

(defn find-rewards-by-account
  "Every reward `bank-id` paid, or owes, `account-id`: at most one per
  kind, so a short vector, and empty for an account promised none.

  Args:
  - txn: FDB handle or open transaction.
  - bank-id: the caller's bank.
  - account-id: the account's id."
  [txn bank-id account-id]
  (store/find-rewards-by-account txn bank-id account-id))
