(ns com.repldriven.queenswood.test-scenarios.rig
  "What both system tests boot and hand the runner: the handlers patched
  into the rig's servers, the runner's FDB config, and the Kafka
  observers."
  (:require
    [com.repldriven.queenswood.test-scenarios.system]

    [com.repldriven.queenswood.test-scenarios.interface :as scenarios]

    [com.repldriven.queenswood.modulr-adapter.interface :as modulr-adapter]
    [com.repldriven.queenswood.modulr-simulator.interface :as
     modulr-simulator]
    [com.repldriven.queenswood.zyphe-adapter.interface :as zyphe-adapter]
    [com.repldriven.queenswood.zyphe-simulator.interface :as zyphe-simulator]

    [com.repldriven.mono.system.interface :as system]))

(def config-file "classpath:test-scenarios/application-test.yml")

(defn patch-handlers
  [defs]
  (-> defs
      (assoc-in [:system/defs :modulr-simulator-server :handler]
                modulr-simulator/app)
      (assoc-in [:system/defs :modulr-adapter-server :handler]
                modulr-adapter/app)
      (assoc-in [:system/defs :zyphe-simulator-server :handler]
                zyphe-simulator/app)
      (assoc-in [:system/defs :zyphe-adapter-server :handler]
                zyphe-adapter/app)))

(defn bank
  "The runner's FDB config, carrying the bus, schemas and providers an
  outbound payment is submitted with, and the simulators' URLs."
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])
   :bus (system/instance sys [:message-bus :bus])
   :schemas (system/instance sys [:avro :serde])
   :payment-providers (system/instance sys [:payment-provider :providers])
   :idv-providers (system/instance sys [:idv-provider :providers])
   :zyphe-simulator-url (system/instance sys
                                         [:zyphe-simulator-server :http-url])
   :payment-simulator-url (system/instance sys
                                           [:modulr-simulator-server
                                            :http-url])})

(defn start-observers
  [sys]
  {:scheme-commands (scenarios/start-observer
                     (system/instance sys
                                      [:kafka :consumers
                                       :modulr-payment-command-observer]))
   :dead-letters (scenarios/start-observer
                  (system/instance sys
                                   [:kafka :consumers
                                    :schemes-payments-event-dlq]))
   :envelope-schemas (system/instance sys [:kafka :schemas])})

(defn stop-observers
  [{:keys [scheme-commands dead-letters]}]
  (scenarios/stop-observer scheme-commands)
  (scenarios/stop-observer dead-letters))
