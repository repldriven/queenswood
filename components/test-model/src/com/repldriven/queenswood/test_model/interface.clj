(ns com.repldriven.queenswood.test-model.interface
  "Pure-functional model of the bank's domain rules, re-implemented in
  plain Clojure data so the scenario runner can compare model state
  against real-system state. Imports nothing from production
  components. Each command is a fugato spec: `:next-state`, and where
  fugato generates it `:run?`, `:args`, `:freq` and `:valid?`. A
  command reality would refuse changes nothing. A `:fixture/` command is
  a write beneath the domain that sets up a state the domain then reacts
  to."
  (:require
    [com.repldriven.queenswood.test-model.accounts :as accounts]
    [com.repldriven.queenswood.test-model.banks :as banks]
    [com.repldriven.queenswood.test-model.fixtures :as fixtures]
    [com.repldriven.queenswood.test-model.interest :as interest]
    [com.repldriven.queenswood.test-model.parties :as parties]
    [com.repldriven.queenswood.test-model.payments :as payments]
    [com.repldriven.queenswood.test-model.policies :as policies]
    [com.repldriven.queenswood.test-model.products :as products]
    [com.repldriven.queenswood.test-model.state :as state]))

;; ---------------------------------------------------------------------------
;; Banks, parties and accounts

(def
  ^{:doc
    "A bank, bound to the tier policy, with a published current product,
  its organisation party, and an account for that party on that product.
  Generated a quarter as often as any other command. Args: none."}
  create-bank
  banks/create-bank)

(def
  ^{:doc
    "An active person party on `bank`. A national identifier marker
  another party of the bank already holds creates nothing; a fresh one
  is held from then on. Args:
  - bank: model bank id.
  - ni (optional): a national identifier marker, or nil."}
  create-person-party
  parties/create-person-party)

(def
  ^{:doc
    "A person party on `bank` with an account on `prod`, or on the bank's
  first published current product, one created and published where it
  has none. The party is created even where the bank's policies refuse
  the account. Args:
  - bank: model bank id.
  - prod (optional): model product id."}
  create-customer
  accounts/create-customer)

(def
  ^{:doc
    "An account for `party` on `prod` where the party is active, the
  product's latest version is published, both are `bank`'s, and the
  bank's policies permit the open within their account count, which
  counts the house account the bank opened for itself. Takes the next
  account id whether or not it opens, as the runner does. Never
  generated. Args:
  - bank: model bank id.
  - party: model party id.
  - prod: model product id."}
  open-account
  accounts/open-account)

(def
  ^{:doc
    "Closes `acct` where its balance is zero, or where the bank's policies
  permit closing it with a balance. Args:
  - acct: model account id."}
  close-account
  accounts/close-account)

;; ---------------------------------------------------------------------------
;; Products

(def
  ^{:doc
    "A product on `bank` whose first version is a draft. Args:
  - bank: model bank id.
  - type: `:current` or `:savings`.
  - rate-bps: its interest rate in basis points."}
  create-product
  products/create-product)

(def ^{:doc
       "Publishes `prod`'s latest version. Args:
  - prod: model product id."}
     publish-product
  products/publish-product)

(def
  ^{:doc
    "A new draft version of `prod`, numbered after its latest, where the
  latest is not a draft. Args:
  - prod: model product id."}
  open-draft
  products/open-draft)

(def ^{:doc
       "Discards `prod`'s latest version. Args:
  - prod: model product id."}
     discard-draft
  products/discard-draft)

(def
  ^{:doc
    "Rewrites the effective window of `prod`'s latest version where it is
  a draft. Args:
  - prod: model product id.
  - window: `{:effective-from epoch-day :effective-to epoch-day-or-nil}`."}
  update-product-draft
  products/update-product-draft)

;; ---------------------------------------------------------------------------
;; Payments

(def
  ^{:doc
    "A credit of `amount` to `acct` the scheme has settled, under end-to-end
  id `e2e`. One already settled under the same id changes nothing. Where
  `acct` is not open it parks in suspense. Where a hold for `e2e`, `acct`
  and `amount` is held it releases that hold, its checks counting today's
  inbound payments without the hold itself. Otherwise it is recorded and
  counted against the bank's daily inbound count. A release or a
  settlement lands where the bank's policies permit receiving it within
  their daily count, and parks in suspense, the balance untouched,
  where they do not. Args:
  - acct: model account id.
  - amount: minor units.
  - e2e (optional): an end-to-end id marker, the next inbound marker
    where omitted."}
  inbound-transfer
  payments/inbound-transfer)

(def
  ^{:doc
    "An inbound the payment provider holds for screening, recorded `held`
  and counted against the bank's daily inbound count with no money
  moved and no check run. A hold matching a recorded inbound by `e2e`,
  `acct` and `amount`, whatever its status, records nothing, and one for
  an account that is not open is dropped. Either way it is `acct`'s hold
  for `release-inbound`. Never generated. Args:
  - acct: model account id.
  - amount: minor units.
  - e2e (optional): an end-to-end id marker."}
  hold-inbound
  payments/hold-inbound)

(def
  ^{:doc
    "The settlement of `acct`'s last hold, as `inbound-transfer` settles
  under that hold's end-to-end id and amount. Never generated. Args:
  - acct: model account id."}
  release-inbound
  payments/release-inbound)

(def
  ^{:doc
    "A payment of `amount` from `debtor` to a creditor outside the model,
  or to `creditor`, completed at once as the provider settles it. Nothing
  happens where the amount is not positive, the bank's policies do not
  permit sending it within their daily count, the debtor is not open, or
  the available balance rule refuses the debit. A creditor not open
  receives nothing, its credit parked. The debtor carries three legs: the
  reservation, then its clearing credit and the posted debit. Args:
  - debtor: model account id.
  - creditor (optional): model account id.
  - amount: minor units."}
  outbound-payment
  payments/outbound-payment)

(def
  ^{:doc
    "Moves `amount` from `from` to `to`, both open and the same bank's, in
  GBP, where the bank's policies permit submitting it within their daily
  count and the available balance rule allows both sides. Args:
  - from: model account id.
  - to: model account id.
  - amount: minor units.
  - currency (optional): ISO 4217 code, GBP where omitted."}
  internal-transfer
  payments/internal-transfer)

;; ---------------------------------------------------------------------------
;; Interest

(def
  ^{:doc
    "A day's interest on each open customer account of `bank` whose product
  pays it: whole units credited to its accrued interest and the
  remainder carried, with no leg written. Runs where the bank's policies
  permit accruing and count one more run on `date` within their limits.
  Args:
  - bank: model bank id.
  - date: the run's as-of date, YYYYMMDD."}
  accrue-interest
  interest/accrue-interest)

(def
  ^{:doc
    "Moves each open customer account's accrued interest into its
  available balance, in two legs. Runs where the bank's policies permit
  capitalising and count one more run on `date` within their limits.
  Args:
  - bank: model bank id.
  - date: the run's as-of date, YYYYMMDD."}
  capitalize-interest
  interest/capitalize-interest)

;; ---------------------------------------------------------------------------
;; Policies and fixtures

(def
  ^{:doc
    "Binds `policy` to `bank`. The model reads its unfiltered capabilities
  and its count limits. Never generated. Args:
  - bank: model bank id.
  - policy: a policy map, as the `policy` brick takes it."}
  bind-policy
  policies/bind-policy)

(def
  ^{:doc
    "A fee of `amount` debited from `acct`, past the available balance
  rule. Args:
  - acct: model account id.
  - amount: minor units."}
  apply-fee
  fixtures/apply-fee)

(def
  ^{:doc
    "The bank's own money arriving on its house account, which the model
  holds no balance for, so nothing changes. Never generated. Args:
  - bank: model bank id.
  - amount: minor units."}
  fund-house
  fixtures/fund-house)

;; ---------------------------------------------------------------------------
;; The model

(def ^:private default-freq 4)

(def
  ^{:doc
    "Every command, keyed by the verb the runner dispatches. A command
  carrying no `:freq` takes 4, so `:create-bank`, at 1, is generated a
  quarter as often."}
  model
  (update-vals {:create-bank create-bank
                :create-customer create-customer
                :open-account open-account
                :close-account close-account
                :create-product create-product
                :publish-product publish-product
                :open-draft open-draft
                :discard-draft discard-draft
                :update-product-draft update-product-draft
                :create-person-party create-person-party
                :inbound-transfer inbound-transfer
                :hold-inbound hold-inbound
                :release-inbound release-inbound
                :outbound-payment outbound-payment
                :internal-transfer internal-transfer
                :bind-policy bind-policy
                :fixture/apply-fee apply-fee
                :fixture/fund-house fund-house
                :accrue-interest accrue-interest
                :capitalize-interest capitalize-interest}
               (fn [spec] (merge {:freq default-freq} spec))))

(def
  ^{:doc
    "Empty model state, held to no policy: every capability is refused
  until `with-policies` holds it to a platform policy. Counters track the
  next synthetic id of each kind."}
  init-state
  state/init-state)

(def
  ^{:doc
    "`state` held to the `platform` policy, as every bank is, each bank it
  creates bound to the `tier` policy, and the available balance rule the
  platform carries. The runner loads both from the rig's configuration,
  so the model reads the policies reality boots with. Args:
  - state: model state, typically `init-state`.
  - policies: `{:platform policy :tier policy}`."}
  with-policies
  policies/with-policies)

(def
  ^{:doc
    "Synthetic account ids the model knows about, as a vector. Args:
  - state: model state map."}
  known-accounts
  state/known-accounts)

(def
  ^{:doc
    "Synthetic bank ids the model knows about, as a vector. Args:
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
