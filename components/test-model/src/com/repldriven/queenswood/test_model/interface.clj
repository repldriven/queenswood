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

(def
  ^{:doc
    "Fugato-shape model: a map keyed by command keyword.
  Each entry carries `:run?`, `:args`, `:next-state`, and (where
  helpful) `:valid?`. `:open-account` (open an end-customer
  account) is intentionally absent — its preconditions (active
  person party + published tenant product) are heavy for the
  marginal coverage on top of the settlement account that ships
  with `:create-bank`. EDN scenarios still drive `:open-account`
  explicitly when they need a second account."}
  model
  {:create-bank balances/create-bank
   :create-customer balances/create-customer
   :close-account balances/close-account
   :create-product products/create-product
   :publish-product products/publish-product
   :open-draft products/open-draft
   :discard-draft products/discard-draft
   :update-product-draft products/update-product-draft
   :create-person-party parties/create-person-party
   :activate-party parties/activate-party
   :inbound-transfer transfers/inbound-transfer
   :outbound-transfer transfers/outbound-transfer
   :outbound-payment transfers/outbound-payment
   :settle-outbound-payment transfers/settle-outbound-payment
   :internal-transfer transfers/internal-transfer
   :apply-fee fees/apply-fee
   :accrue-interest interest/accrue-interest
   :capitalize-interest interest/capitalize-interest})

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
