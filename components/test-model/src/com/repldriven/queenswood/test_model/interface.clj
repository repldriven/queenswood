(ns com.repldriven.queenswood.test-model.interface
  "Pure-functional model of the bank's domain rules. Re-implements
  the relevant production logic in plain Clojure data so the
  scenario runner can compare model state against real-system state.
  Imports nothing from production components."
  (:require
    [com.repldriven.queenswood.test-model.balances :as balances]
    [com.repldriven.queenswood.test-model.fees :as fees]
    [com.repldriven.queenswood.test-model.interest :as interest]
    [com.repldriven.queenswood.test-model.parties :as parties]
    [com.repldriven.queenswood.test-model.products :as products]
    [com.repldriven.queenswood.test-model.state :as state]
    [com.repldriven.queenswood.test-model.transfers :as transfers]))

(def ^:private default-freq
  "How often fugato picks a command beside `:create-bank`, which carries
  its own `:freq` of 1 since it is the costliest to run."
  4)

(def
  ^{:doc
    "Fugato-shape model: a map keyed by command keyword. Each entry
  carries `:next-state`, and where it is generated `:run?`, `:args`,
  `:freq` and `:valid?`. `:open-account` and `:fixture/fund-house`
  are never generated; EDN scenarios drive them. A `:fixture/`
  command is a write beneath the domain that sets up a state the
  domain then reacts to."}
  model
  (update-vals {:create-bank balances/create-bank
                :create-customer balances/create-customer
                :open-account balances/open-account
                :close-account balances/close-account
                :create-product products/create-product
                :publish-product products/publish-product
                :open-draft products/open-draft
                :discard-draft products/discard-draft
                :update-product-draft products/update-product-draft
                :create-person-party parties/create-person-party
                :inbound-transfer transfers/inbound-transfer
                :outbound-payment transfers/outbound-payment
                :internal-transfer transfers/internal-transfer
                :fixture/apply-fee fees/apply-fee
                :fixture/fund-house fees/fund-house
                :accrue-interest interest/accrue-interest
                :capitalize-interest interest/capitalize-interest}
               (fn [spec] (merge {:freq default-freq} spec))))

(def
  ^{:doc
    "Empty bank state. The `:policies` map carries the
  production policy set in model shape; counters track the next
  synthetic id for each entity kind. Args:
  - (none) — used as the starting value for a model run."}
  init-state
  state/init-state)

(def
  ^{:doc
    "Synthetic account ids the model knows about, as a
  vector. Args:
  - state: model state map."}
  known-accounts
  state/known-accounts)

(def
  ^{:doc
    "Synthetic bank ids the model knows about, as a vector.
  Args:
  - state: model state map."}
  known-banks
  state/known-banks)

(def
  ^{:doc
    "Available balance for `acct`, or 0 if unknown. Args:
  - state: model state map.
  - acct: synthetic account id keyword."}
  balance
  state/balance)
