(ns com.repldriven.queenswood.transaction.test-system
  "Bare-require bundle for the tests that boot
  `transaction/application-test.yml`. Named `test-system` rather than
  `system`: the brick's own `system.clj` already holds that namespace."
  (:require
    [com.repldriven.queenswood.transaction.system]

    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.mono.avro.interface]))
