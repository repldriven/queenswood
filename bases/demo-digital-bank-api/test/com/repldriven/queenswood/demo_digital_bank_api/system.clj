(ns com.repldriven.queenswood.demo-digital-bank-api.system
  "Bare-require bundle for the base's tests: the kinds its test
  configuration names, so one require of this namespace registers them
  all. The platform stand-in is the core brick's, from its test tree."
  (:require
    [com.repldriven.queenswood.demo-digital-bank.interface]
    [com.repldriven.queenswood.demo-digital-bank.platform-stub]

    [com.repldriven.mono.jdbc.interface]
    [com.repldriven.mono.migrator.interface]
    [com.repldriven.mono.server.interface]
    [com.repldriven.mono.telemetry.interface]
    [com.repldriven.mono.test-telemetry.interface]
    [com.repldriven.mono.testcontainers.interface]))
