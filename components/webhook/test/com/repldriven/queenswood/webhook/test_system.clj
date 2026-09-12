(ns com.repldriven.queenswood.webhook.test-system
  "Bare-require bundle for the tests that boot
  `webhook/application-test.yml`. A `defcomponents` registration fires
  only when its namespace loads, and that rig now names kinds from six
  bricks, so each test namespace bare-requires this one rather than
  repeating them.

  Named `test-system` rather than `system`: the brick's own
  `system.clj` already holds that namespace."
  (:require
    [com.repldriven.queenswood.webhook.system]

    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.policy.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.mono.avro.interface]
    [com.repldriven.mono.event-processor.interface]
    [com.repldriven.mono.message-bus.interface]))
