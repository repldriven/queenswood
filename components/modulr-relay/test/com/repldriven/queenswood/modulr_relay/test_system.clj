(ns com.repldriven.queenswood.modulr-relay.test-system
  "Bare-require bundle for the tests that boot
  `modulr-relay/application-test.yml`."
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.mono.avro.interface]))
