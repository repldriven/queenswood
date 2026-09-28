(ns com.repldriven.queenswood.form3-adapter.test-system
  "Bare-require bundle for the tests that boot
  `form3-adapter/application-test.yml`."
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.form3-webhook.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.mono.avro.interface]
    [com.repldriven.mono.server.interface]))
