(ns com.repldriven.queenswood.external-adapters.system
  "Bare-require bundle for the external adapters service — the
  ClearBank, Onfido and Companies House adapters plus the simulators
  that stand in for those vendors, and the webhook notification
  consumer and delivery runner — every brick whose component-kinds
  its application.yml instantiates. Each composed base is reached
  through its `interface.clj`, which registers that base's own
  component-kinds on load. Loaded by main.clj before `system/start`;
  nothing else lives here. The service's composition is the project's
  application.yml (ADR-0019)."
  (:require
    [com.repldriven.queenswood.cash-account-query.interface]
    [com.repldriven.queenswood.clearbank-adapter.interface]
    [com.repldriven.queenswood.clearbank-relay.interface]
    [com.repldriven.queenswood.clearbank-simulator.interface]
    [com.repldriven.queenswood.clearbank-webhook.interface]
    [com.repldriven.queenswood.company.interface]
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.onfido-adapter.interface]
    [com.repldriven.queenswood.onfido-relay.interface]
    [com.repldriven.queenswood.onfido-simulator.interface]
    [com.repldriven.queenswood.onfido-webhook.interface]
    [com.repldriven.queenswood.schema.interface]
    [com.repldriven.queenswood.uk-companies-house-adapter.interface]
    [com.repldriven.queenswood.uk-companies-house-simulator.interface]
    [com.repldriven.queenswood.webhook.interface]

    [com.repldriven.mono.avro.interface]
    [com.repldriven.mono.command-processor.interface]
    [com.repldriven.mono.event-processor.interface]
    [com.repldriven.mono.kafka.interface]
    [com.repldriven.mono.message-bus.interface]
    [com.repldriven.mono.server.interface]
    [com.repldriven.mono.telemetry.interface]))
