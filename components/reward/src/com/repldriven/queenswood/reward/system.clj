(ns com.repldriven.queenswood.reward.system
  (:require
    [com.repldriven.queenswood.reward.events :as events]

    [com.repldriven.mono.cache.interface :as cache]
    [com.repldriven.mono.system.interface :as system]))

(def ^:private one-hour-ms 3600000)
(def ^:private thirty-seconds-ms 30000)

(def ^:private event-processor
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance
                       (events/->RewardEventProcessor
                        (assoc config
                               :cache (cache/create (:cache-ttl-ms config))
                               :caches {:ledger-account (cache/create
                                                         (:ledger-cache-ttl-ms
                                                          config))}))))
   :system/config {:record-db system/required-component
                   :record-store system/required-component
                   :schemas system/required-component
                   :cache-ttl-ms one-hour-ms
                   :ledger-cache-ttl-ms thirty-seconds-ms}
   :system/instance-schema some?})

(system/defcomponents :reward {:event-processor event-processor})
