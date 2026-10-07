(ns com.repldriven.queenswood.person-identification.interface
  "Person-identification records — a person's given, middle and family
  names, and nothing else that identifies them (ADR-0045), keyed by
  party-id. Created by `party` when a person party is registered; read
  by `idv` when a verification session opens, and by an IDV adapter to
  grade the name a provider read. The brick exists to break a would-be cycle between
  bank-party and bank-idv: both bricks need this data, and it lives
  here so neither has to require the other."
  (:require
    [com.repldriven.queenswood.person-identification.domain :as domain]
    [com.repldriven.queenswood.person-identification.store :as store]))

(defn new-person-identification
  "Build a person-identification record. Pure data.

  Args:
  - data: source map with `:given-name`, `:middle-names` and
    `:family-name`; any other key is ignored.
  - party-id: party id this identification is linked to."
  [data party-id]
  (domain/new-person-identification data party-id))

(defn save-person-identification
  "Persist a person-identification record. Returns nil on success
  or an `:error/anomaly` on infra failure.

  Args:
  - txn: FDB handle or open transaction.
  - person-identification: the record map."
  [txn person-identification]
  (store/save-person-identification txn person-identification))

(defn get-person-identification
  "Load a person-identification by party-id. Returns the record
  map, `nil` if not found, or an `:error/anomaly` on infra
  failure.

  Args:
  - txn: FDB handle or open transaction.
  - party-id: party id."
  [txn party-id]
  (store/get-person-identification txn party-id))

(defn clear-identity-details
  "Move every person identification stored before ADR-0045 to its names
  alone, deleting the record that held the date of birth, nationality
  and address. Returns how many it moved — none on a rerun — or an
  anomaly.

  Args:
  - config: `{:record-db :record-store}`."
  [config]
  (store/clear-identity-details config))
