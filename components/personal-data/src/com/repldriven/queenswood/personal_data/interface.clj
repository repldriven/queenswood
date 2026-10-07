(ns com.repldriven.queenswood.personal-data.interface
  "Clears what the platform held about a person before it kept their
  names and each identity check's outcome alone (ADR-0045): a person
  identification's date of birth, nationality and address, the names
  and date of birth a provider read off a document, every national
  identifier, and the email and names in an IDV relay's settled and
  failed intents and its `idv-evidence` outbox payloads. Each owning
  brick clears its own store; a page at a time, so a large store spans
  many transactions.

  The `personal-data/clearance` component kind runs it once as it
  starts, taking `:record-db` and `:record-store`; the migrator
  includes it, its `:record-store` the meta-store, so it runs once the
  meta-data is saved. Every part is idempotent, so a rerun clears
  nothing."
  (:require
    [com.repldriven.queenswood.personal-data.core :as core]
    [com.repldriven.queenswood.personal-data.system]))

(defn clear
  "Clear every store, returning how many records each part cleared,
  `{:person-identifications :idv-evidence :national-identifiers
  :intents :payloads}`, the last two summed over the IDV relays, or the
  first anomaly, the parts before it having committed.

  Args:
  - config: `{:record-db :record-store}`."
  [config]
  (core/clear config))
