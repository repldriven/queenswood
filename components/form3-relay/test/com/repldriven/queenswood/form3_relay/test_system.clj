(ns com.repldriven.queenswood.form3-relay.test-system
  "Bare-require bundle for the tests that boot
  `form3-relay/application-test.yml`."
  (:require
    [com.repldriven.queenswood.changelog-relay.interface]
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.mono.avro.interface]
    [com.repldriven.mono.message-bus.interface]))
