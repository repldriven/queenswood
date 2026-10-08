(ns com.repldriven.queenswood.modulr-relay.outbound.core
  (:require
    [com.repldriven.queenswood.modulr-relay.outbound.accounts]
    [com.repldriven.queenswood.modulr-relay.outbound.payments]
    [com.repldriven.queenswood.modulr-relay.outbound.transfers]

    [com.repldriven.queenswood.modulr-relay.store :as store]

    [com.repldriven.queenswood.intent-poller.interface :as intent-poller]))

(def ^:private
     ^{:doc "Operations that wait for an account's calls to settle."}
     settles-first
  #{:modulr-outbound-intent-kind-close-account
    :modulr-outbound-intent-kind-reissue-address})

(defn- poller-config
  [config]
  (assoc config
         :adapter :modulr
         :store store/spec
         :settles-first? (fn [intent]
                           (contains? settles-first (:kind intent)))))

(defn drain-once
  "Make each due pending call once, oldest first, holding a call for an
  account while an earlier one for it is unsent, and a close or reissue
  while one is unsettled; then reconcile every due sent payment and
  transfer. Reads are transactional; each call and the write recording
  it are separate, so no network I/O happens inside an FDB transaction."
  [config now]
  (intent-poller/drain-once (poller-config config) now))

(defn start-runner
  [config]
  (intent-poller/start (poller-config config)))
