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
    [com.repldriven.queenswood.clearbank-adapter.interface]
    [com.repldriven.queenswood.clearbank-simulator.interface]
    [com.repldriven.queenswood.clearbank-webhook.interface]
    [com.repldriven.queenswood.email.interface]
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.idv.interface]
    [com.repldriven.queenswood.idv.system]
    [com.repldriven.queenswood.interest.interface]
    [com.repldriven.queenswood.membership.interface]
    [com.repldriven.queenswood.onfido-adapter.interface]
    [com.repldriven.queenswood.onfido-simulator.interface]
    [com.repldriven.queenswood.party.interface]
    [com.repldriven.queenswood.party.system]
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

(system/defcomponents :test-api-scenarios {:span-dump span-dump})
