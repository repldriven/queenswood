(ns com.repldriven.queenswood.clearbank-relay.outbound.core
  (:require
    [com.repldriven.queenswood.clearbank-relay.outbound.accounts]
    [com.repldriven.queenswood.clearbank-relay.outbound.payments]

    [com.repldriven.queenswood.clearbank-relay.store :as store]

    [com.repldriven.queenswood.intent-poller.interface :as intent-poller]))

(defn- poller-config
  [config]
  (assoc config
         :adapter :clearbank
         :store store/spec))

(defn drain-once
  "Make each due pending call once, oldest first, holding a call for an
  account while an earlier one for it is unsent. Reads are
  transactional; the HTTP call and status write per intent are
  separate, so no network I/O happens inside an FDB transaction."
  [config now]
  (intent-poller/drain-once (poller-config config) now))

(defn start-runner
  "Start the daemon poll loop that drains pending outbound intents.
  Returns `{:stop fn}`."
  [config]
  (intent-poller/start (poller-config config)))
