(ns com.repldriven.queenswood.test-api-scenarios.system
  "Bare-require bundle for the api-scenarios test runner. Each
  `defcomponents` registration only fires when its namespace loads,
  so the system config in `application-test.yml` references brick
  component-kinds that need their `system.clj` (or `interface.clj`)
  loaded somewhere on the test classpath. Bundling them here means
  the test namespace only needs a single bare require of this ns."
  (:require
    [com.repldriven.mono.system.interface :as system]

    [com.repldriven.queenswood.bank.interface]
    [com.repldriven.queenswood.cash-account.interface]
    [com.repldriven.queenswood.cash-account.system]
    [com.repldriven.queenswood.changelog-relay.interface]
    [com.repldriven.queenswood.email.interface]
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.form3-relay.interface]
    [com.repldriven.queenswood.form3-webhook.interface]
    [com.repldriven.queenswood.idv.interface]
    [com.repldriven.queenswood.idv.system]
    [com.repldriven.queenswood.membership.interface]
    [com.repldriven.queenswood.party.interface]
    [com.repldriven.queenswood.party.system]
    [com.repldriven.queenswood.payment-provider.interface]
    [com.repldriven.queenswood.payment.interface]
    [com.repldriven.queenswood.policy.interface]
    [com.repldriven.queenswood.schema.interface]
    [com.repldriven.queenswood.testcontainers.interface]
    [com.repldriven.queenswood.transaction.interface]
    [com.repldriven.queenswood.uk-companies-house-adapter.interface]

    [com.repldriven.mono.avro.interface]
    [com.repldriven.mono.command-processor.interface]
    [com.repldriven.mono.event-processor.interface]
    [com.repldriven.mono.command.interface]
    [com.repldriven.mono.identity-provider.interface]
    [com.repldriven.mono.kafka.interface]
    [com.repldriven.mono.keycloak.interface]
    [com.repldriven.mono.message-bus.interface]
    [com.repldriven.mono.server.interface]
    [com.repldriven.mono.smtp.interface]
    [com.repldriven.mono.test-telemetry.interface]))

;; Where the run writes its finished spans, or nil for nowhere. The rig
;; reads the path from `QW_SPAN_DUMP`, so the test itself never does.
(def span-dump
  {:system/start (fn [{:system/keys [config]}] (:path config))
   :system/config {:path nil}
   :system/config-schema [:map [:path {:optional true} [:maybe string?]]]
   :system/instance-schema [:maybe string?]})

;; How the run is paced: how many scenarios run at once, which the rig
;; reads from `TEST_API_SCENARIO_WORKERS` and otherwise takes from the
;; processors the JVM may use; how long a step waits on the system; and
;; the capabilities each provider's adapter does not carry yet, as
;; distinct from what its declaration rules out.
(def settings
  {:system/start (fn [{:system/keys [config]}]
                   (let [{:keys [workers await-timeout-ms unbuilt]} config]
                     {:workers (or (some-> workers
                                           str
                                           parse-long)
                                   (.availableProcessors (Runtime/getRuntime)))
                      :await-timeout-ms await-timeout-ms
                      :unbuilt (update-vals (or unbuilt {})
                                            (fn [tags]
                                              (set (map keyword tags))))}))
   :system/config {:workers nil :await-timeout-ms 15000 :unbuilt {}}
   :system/config-schema [:map
                          [:workers {:optional true}
                           [:maybe [:or string? int?]]]
                          [:await-timeout-ms {:optional true} pos-int?]
                          [:unbuilt {:optional true}
                           [:map-of keyword? [:sequential string?]]]]
   :system/instance-schema [:map
                            [:workers pos-int?]
                            [:await-timeout-ms pos-int?]
                            [:unbuilt [:map-of keyword? [:set keyword?]]]]})

(system/defcomponents :test-api-scenarios
                      {:span-dump span-dump :settings settings})
