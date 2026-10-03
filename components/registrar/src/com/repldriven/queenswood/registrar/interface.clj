(ns com.repldriven.queenswood.registrar.interface
  "An external adapter's subscriptions to its provider's notifications,
  kept by a registrar. An adapter registers how to read and make its
  subscriptions with `defsubscriptions`; the registrar asks the provider
  which it holds, makes each one missing, and marks the adapter ready
  once all are held. It does so through the circuit breaker on
  `adapter:<adapter>` (ADR-0034), asking every `:retry-ms` until the
  provider holds them all or while the breaker is not closed, and every
  `:check-ms` otherwise. It never gives up, so a provider down at
  start-up is subscribed to when it returns, and one that drops a
  subscription is told again. Since it asks whether or not anything
  else is sent, it is the adapter's breaker's probe.

  A registrar config carries the FDB `:record-db` and `:record-store`
  the breaker is held in, the adapter's `:delivery-policy`,
  `:retry-ms`, `:check-ms`, the `:readiness` atom it sets, and whatever
  the adapter's own functions read."
  (:require
    [com.repldriven.queenswood.registrar.core :as core]
    [com.repldriven.queenswood.registrar.subscriptions :as subscriptions]))

(def
  ^{:doc
    "The schema a registrar config's `:delivery-policy`, `:retry-ms` and
  `:check-ms` are checked against, for a registrar's
  `:system/config-schema`."}
  config-schema
  core/config-schema)

(defmacro defsubscriptions
  "Register how `adapter` reads and makes its subscriptions, a map of
  three functions:
  - `:wanted` — `(fn [config])`, the subscriptions the adapter needs,
    each a value comparable with those `:held` returns.
  - `:held` — `(fn [config])`, asks the provider which it holds, and
    returns `[:answered subscriptions]` with them as a set,
    `[:refused reason]`, or `[:retry reason]` where the provider did not
    answer.
  - `:subscribe` — `(fn [config subscription])`, makes one, returning
    `[:answered result]`, `[:refused reason]` or `[:retry reason]`.

  Usage:
    (defsubscriptions :adapter {:wanted wanted :held held :subscribe subscribe})"
  [adapter subscription-map]
  `(subscriptions/defsubscriptions ~adapter ~subscription-map))

(defn ensure-subscribed
  "Make each subscription `adapter` needs that its provider does not
  hold, once, and mark the adapter ready where it then holds them all.
  Returns `:held`, `:missing` where the provider refused one, `:failed`
  where it did not answer, or an anomaly for an adapter with nothing
  registered.

  Args:
  - adapter: the adapter's keyword, as registered.
  - config: a registrar config."
  [adapter config]
  (core/ensure-subscribed adapter config))

(defn start
  "Start `adapter`'s registrar, a daemon loop. Returns `{:stop fn}`.

  Args:
  - adapter: the adapter's keyword, as registered.
  - config: a registrar config."
  [adapter config]
  (core/start adapter config))
