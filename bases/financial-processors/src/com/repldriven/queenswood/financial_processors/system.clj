(ns com.repldriven.queenswood.financial-processors.system
  "Bare-require bundle for the financial processors service —
  payment, transaction, payee-check — every brick whose
  component-kinds its application.yml instantiates. Loaded by
  main.clj before `system/start`; nothing else lives here. The
  service's composition is the project's application.yml
  (ADR-0019)."
  (:require
    [com.repldriven.queenswood.changelog-relay.interface]
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.payee-check.interface]
    [com.repldriven.queenswood.payment.interface]
    [com.repldriven.queenswood.schema.interface]
    [com.repldriven.queenswood.transaction.interface]

    [com.repldriven.mono.avro.interface]
    [com.repldriven.mono.command-processor.interface]
    [com.repldriven.mono.event-processor.interface]
    [com.repldriven.mono.kafka.interface]
    [com.repldriven.mono.message-bus.interface]
    [com.repldriven.mono.telemetry.interface]))
