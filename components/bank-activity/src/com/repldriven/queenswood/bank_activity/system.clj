(ns com.repldriven.queenswood.bank-activity.system
  (:require
    [com.repldriven.queenswood.bank-activity.core :as core]

    [com.repldriven.queenswood.changelog-relay.interface :as changelog-relay]

    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.system.interface :as system]))

(defn- start-relay
  [{:keys [record-db keyspace-prefix handler poll-ms]}]
  (mapv (fn [n]
          (changelog-relay/start {:record-db record-db
                                  :keyspace-prefix keyspace-prefix
                                  :consumer-id (str (core/shard-log n) "-relay")
                                  :store-name (core/shard-log n)
                                  :handler handler
                                  :poll-ms poll-ms}))
        (range core/shard-count)))

(def ^:private relay
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (start-relay config)))
   :system/stop (fn [{:system/keys [instance]}]
                  (log/info "Stopping bank activity relay")
                  (run! (fn [{:keys [stop]}] (stop)) instance))
   :system/config {:record-db system/required-component
                   :handler system/required-component
                   :keyspace-prefix nil
                   :poll-ms nil}
   :system/instance-schema vector?})

(system/defcomponents :bank-activity {:relay relay})
