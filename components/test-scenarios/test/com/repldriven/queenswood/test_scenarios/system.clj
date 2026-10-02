(ns com.repldriven.queenswood.test-scenarios.system
  "Bare-require bundle for scenario-runner tests. Each `defcomponents`
  registration only fires when its namespace loads, so the system
  config in `application-test.yml` references brick component-kinds
  that need their `system.clj` (or `interface.clj`) loaded somewhere
  on the test classpath. Bundling them here means individual test
  namespaces only need a single bare require of this ns."
  (:require
    [com.repldriven.queenswood.bank.interface]
    [com.repldriven.queenswood.cash-account.interface]
    [com.repldriven.queenswood.bank-activity.interface]
    [com.repldriven.queenswood.changelog-relay.interface]
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.idv.interface]
    [com.repldriven.queenswood.idv.system]
    [com.repldriven.queenswood.modulr-adapter.interface]
    [com.repldriven.queenswood.modulr-simulator.interface]
    [com.repldriven.queenswood.modulr-webhook.interface]
    [com.repldriven.queenswood.party.interface]
    [com.repldriven.queenswood.party.system]
    [com.repldriven.queenswood.payment-provider.interface]
    [com.repldriven.queenswood.payment.interface]
    [com.repldriven.queenswood.policy.interface]
    [com.repldriven.queenswood.schema.interface]
    [com.repldriven.queenswood.testcontainers.interface]
    [com.repldriven.queenswood.zyphe-adapter.interface]
    [com.repldriven.queenswood.zyphe-simulator.interface]

    [com.repldriven.mono.command-processor.interface]
    [com.repldriven.mono.event-processor.interface]
    [com.repldriven.mono.command.interface]
    [com.repldriven.mono.kafka.interface]
    [com.repldriven.mono.message-bus.interface]
    [com.repldriven.mono.server.interface]))
