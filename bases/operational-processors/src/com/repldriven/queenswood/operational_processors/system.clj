(ns com.repldriven.queenswood.operational-processors.system
  "Bare-require bundle for the operational processors service —
  bank, membership, party, cash-account, idv — every brick whose
  component-kinds its application.yml instantiates.
  Loaded by main.clj before `system/start`; nothing else lives
  here. The service's composition is the project's application.yml
  (ADR-0019)."
  (:require
    [com.repldriven.queenswood.bank.interface]
    [com.repldriven.queenswood.cash-account.interface]
    [com.repldriven.queenswood.changelog-relay.interface]
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.idv.interface]
    [com.repldriven.queenswood.membership.interface]
    [com.repldriven.queenswood.party.interface]
    [com.repldriven.queenswood.schema.interface]

    [com.repldriven.mono.avro.interface]
    [com.repldriven.mono.command-processor.interface]
    [com.repldriven.mono.event-processor.interface]
    [com.repldriven.mono.identity-provider.interface]
    [com.repldriven.mono.kafka.interface]
    [com.repldriven.mono.keycloak.interface]
    [com.repldriven.mono.message-bus.interface]
    [com.repldriven.mono.telemetry.interface]))
