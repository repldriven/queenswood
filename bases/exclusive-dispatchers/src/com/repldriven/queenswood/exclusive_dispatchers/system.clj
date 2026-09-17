(ns com.repldriven.queenswood.exclusive-dispatchers.system
  "Bare-require bundle for the exclusive dispatchers service — the work
  that must have exactly one dispatcher, whatever else scales. A
  changelog cursor has one owner, so the relay runners that tail every
  store's changelog live here; a cron trigger is registered per JVM, so
  the scheduler does too; and a stuck outbound payment is reported once
  per sweep, so the payment outbound sweep runs here as well. Each would
  double-act on a second replica, which is why they share a service
  pinned to `replicas: 1` rather than pinning a domain group.

  Every brick whose component-kinds its application.yml instantiates.
  Loaded by main.clj before `system/start`; nothing else lives here.
  The service's composition is the project's application.yml
  (ADR-0019)."
  (:require
    [com.repldriven.queenswood.changelog-relay.interface]
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.interest.interface]
    [com.repldriven.queenswood.payment.interface]
    [com.repldriven.queenswood.scheduler.interface]
    [com.repldriven.queenswood.schema.interface]

    [com.repldriven.mono.avro.interface]
    [com.repldriven.mono.kafka.interface]
    [com.repldriven.mono.message-bus.interface]
    [com.repldriven.mono.scheduler.interface]
    [com.repldriven.mono.telemetry.interface]))
