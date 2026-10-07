(ns com.repldriven.queenswood.migrator.system
  "Bare-require bundle for the migrator: applies FDB record metadata
  (via `fdb/meta-store` with `migrate: true`, gated on FDB_MIGRATE),
  clears the personal data the platform no longer keeps (via
  `personal-data/clearance`, ADR-0045), and declares the Pulsar
  topology (tenants/namespaces/topics/schemas).
  No domain seeding — the bank-bootstrap-service Job runs after this
  one and seeds the policy records and product templates."
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.personal-data.interface]
    [com.repldriven.queenswood.schema.interface]

    [com.repldriven.mono.avro.interface]
    [com.repldriven.mono.kafka.interface]
    [com.repldriven.mono.telemetry.interface]))
