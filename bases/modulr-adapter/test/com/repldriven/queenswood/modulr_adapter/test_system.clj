(ns com.repldriven.queenswood.modulr-adapter.test-system
  "Bare-require bundle for the tests that boot
  `modulr-adapter/application-test.yml`."
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.modulr-webhook.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.mono.avro.interface]
    [com.repldriven.mono.server.interface]))
