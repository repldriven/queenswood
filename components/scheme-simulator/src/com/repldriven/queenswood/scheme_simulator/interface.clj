(ns com.repldriven.queenswood.scheme-simulator.interface
  "The payment scheme the payment simulators share, so a payment one
  sends to an account another holds arrives there, as Faster Payments
  carries a payment between two banks.

  The `scheme-simulator/scheme` component kind is the scheme, one per
  system, which a system includes as its `scheme-simulator` group. The
  `scheme-simulator/member` component kind joins a simulator to it
  under the sort code it issues addresses from, `sort-code`, at the root
  URL its control routes are served from, `url`, and leaves when it
  stops."
  (:require
    [com.repldriven.queenswood.scheme-simulator.system]

    [com.repldriven.queenswood.scheme-simulator.core :as core]))

(defn send-inbound
  "Send `payment` to the member holding the sort code its `bban` opens
  with, as an inbound to its `/simulate/inbound-payment`, and answer
  that member's `{:status :body}`, or a failure where it cannot be
  reached. Nil where no member holds the sort code, or `scheme` is nil.

  Args:
  - scheme: a `scheme-simulator/scheme` instance, or nil.
  - payment: `{:bban :amount :currency :reference :debtor-name}`, the
    amount in major units."
  [scheme payment]
  (core/send-inbound scheme payment))
