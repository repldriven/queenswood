(ns com.repldriven.queenswood.form3-relay.outbound.core
  (:require
    [com.repldriven.queenswood.form3-relay.outbound.accounts]
    [com.repldriven.queenswood.form3-relay.outbound.payments]
    [com.repldriven.queenswood.form3-relay.outbound.returns]

    [com.repldriven.queenswood.form3-relay.store :as store]

    [com.repldriven.queenswood.intent-poller.interface :as intent-poller]))

(defn- poller-config
  [config]
  (assoc config :adapter :form3 :store store/spec))

(defn drain-once
  "Make each due pending call once, oldest first, holding a call for an
  account while an earlier one for it is unsent, then reconcile every due
  sent payment and return. Reads are transactional; each call and the
  write recording it are separate, so no network I/O happens inside an
  FDB transaction."
  [config now]
  (intent-poller/drain-once (poller-config config) now))

(defn start-runner
  [config]
  (intent-poller/start (poller-config config)))
