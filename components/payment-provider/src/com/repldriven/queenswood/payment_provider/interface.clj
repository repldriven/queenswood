(ns com.repldriven.queenswood.payment-provider.interface
  "The payment provider declaration, `system/payment-provider.yml`: what
  the deployment's adapter can carry — `schemes`, `addresses`,
  `balances`, `payee-check`, `inbound`, `returns` and `screening` — and
  the check an adapter makes at start-up that its provider covers it. A
  key the file leaves out reads as the value a provider holding the
  money has: `inbound` notified, no `returns`, and `screening` by the
  provider."
  (:require
    [com.repldriven.queenswood.payment-provider.core :as core]))

(def ^{:doc "The value each key reads as where the declaration leaves it out."}
     defaults
  core/defaults)

(defn declared
  "The declaration with every key it leaves out read as its default.

  Args:
  - declaration: the parsed `payment-provider.yml`, keyword keys."
  [declaration]
  (core/declared declaration))

(defn check
  "Nil where the adapter carries everything the declaration asks, its
  left-out keys read as their defaults; otherwise a
  `:payment/unsupported-declaration` failure whose `:uncovered` maps
  each key to the values asked and not carried.

  Args:
  - declaration: the parsed `payment-provider.yml`, keyword keys.
  - carries: map of declaration key to the set of values the adapter
    carries; a key it does not name is not checked."
  [declaration carries]
  (core/check declaration carries))
