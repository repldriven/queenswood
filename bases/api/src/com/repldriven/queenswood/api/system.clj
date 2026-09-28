(ns com.repldriven.queenswood.api.system
  "Bare-require bundle for the unified banking HTTP surface: storage
  (fdb), serialization (avro, schema), identity (Keycloak,
  identity-provider), command dispatch and messaging, and the HTTP
  server and telemetry.

  The service dispatches every write over a `command/dispatcher` and
  processes none of them, so the domain processors it dispatches to
  are not required here — only the read-side components the handlers
  call."
  (:require
    [com.repldriven.queenswood.balance-query.interface]
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.payee-check.interface]
    [com.repldriven.queenswood.payment-provider.interface]
    [com.repldriven.queenswood.policy.interface]
    [com.repldriven.queenswood.schema.interface]
    [com.repldriven.queenswood.transaction.interface]

    [com.repldriven.mono.avro.interface]
    [com.repldriven.mono.command-processor.interface]
    [com.repldriven.mono.command.interface]
    [com.repldriven.mono.identity-provider.interface]
    [com.repldriven.mono.kafka.interface]
    [com.repldriven.mono.keycloak.interface]
    [com.repldriven.mono.message-bus.interface]
    [com.repldriven.mono.server.interface]
    [com.repldriven.mono.telemetry.interface]))
