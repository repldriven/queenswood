(ns com.repldriven.queenswood.bootstrap.system
  "Bare-require bundle for the bootstrap (seeder): writes the platform
  and micro Policy records and the cash-account-product templates
  (current, savings, term-deposit, own-funds) into FDB, and refuses to
  start when the platform policy requires a verification or screening
  the provider declaration does not establish, then exits.
  A user's first login creates their own bank, so nothing
  organization-shaped is seeded. The migrator Job runs first and
  applies FDB record metadata and the Kafka topology; bootstrap
  consumes the persisted metadata read-only."
  (:require
    [com.repldriven.queenswood.cash-account-product.interface]
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.idv.interface]
    [com.repldriven.queenswood.policy.interface]
    [com.repldriven.queenswood.schema.interface]

    [com.repldriven.mono.telemetry.interface]))
