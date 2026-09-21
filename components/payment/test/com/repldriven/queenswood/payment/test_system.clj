(ns com.repldriven.queenswood.payment.test-system
  "Bare-require bundle for the tests that boot
  `payment/application-test.yml`. Named `test-system` rather than
  `system`: the brick's own `system.clj` already holds that namespace."
  (:require
    [com.repldriven.queenswood.payment.system]

    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.policy.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.mono.avro.interface]
    [com.repldriven.mono.message-bus.interface]))
