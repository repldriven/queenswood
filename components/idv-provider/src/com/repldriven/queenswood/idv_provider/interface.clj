(ns com.repldriven.queenswood.idv-provider.interface
  "The IDV providers an installation offers, and what each one's adapter
  can establish — `verifies` and `screens` — the `channels` and
  `hand-offs` it offers, and what it `needs` as input.

  The `idv-provider/declaration` component kind holds one provider's
  declaration, a file under `system/idv-providers/` included into a
  system's `idv-provider` group under the provider's key. An adapter
  refers to its own, `idv-provider.<key>`.

  The `idv-provider/providers` component kind names the default and, per
  provider, its `declaration` and the `command-channel` that reaches its
  adapter, and refuses to start where the default names no provider.
  Every component that routes to a provider or reads its declaration
  refers to `idv-provider.providers`."
  (:require
    [com.repldriven.queenswood.idv-provider.system]

    [com.repldriven.queenswood.idv-provider.core :as core]))

(def ^{:doc "The kind a bank records its IDV provider under."} kind core/kind)

(defn providers
  "The instance of an `idv-provider/providers` component: `:kind`,
  `\"idv\"`, `:default`, the default provider's key, and `:providers`,
  each provider's entry by key, every entry carrying its key as
  `:provider`. An `:idv-provider/unknown-default` failure where the
  default names no provider.

  Args:
  - config: `{:default :providers}`, the default's key and a map of key
    to entry — `:declaration`, `:command-channel`."
  [config]
  (core/providers config))

(defn default
  "The default provider's entry.

  Args:
  - instance: an `idv-provider/providers` instance."
  [instance]
  (core/default instance))

(defn entries
  "Every provider's entry.

  Args:
  - instance: an `idv-provider/providers` instance."
  [instance]
  (core/entries instance))

(defn for-bank
  "The entry of the provider `bank` records under `kind`, the default's
  where it records none, or an `:idv-provider/unknown` rejection where
  the provider it records is not offered.

  Args:
  - instance: an `idv-provider/providers` instance.
  - bank: a bank map, whose `:providers` is a sequence of `{:kind
    :provider}`."
  [instance bank]
  (core/for-bank instance bank))

(defn full-name
  "The non-blank `parts` of a name joined by single spaces, or nil where
  none is.

  Args:
  - parts: name parts — given, middle, family — any of them nil."
  [& parts]
  (apply core/full-name parts))

(defn name-match
  "How the name a provider read off a document compares with the name
  the run was started for — `:idv-name-match-match`,
  `:idv-name-match-close-match` or `:idv-name-match-no-match` — or nil
  where either is blank. An adapter reports the grade and never the
  names, so nothing the provider read leaves it (ADR-0045).

  Args:
  - run-name: the name the run was started for, as `party-name` reads
    it.
  - read-name: the name the provider read off the document."
  [run-name read-name]
  (core/name-match run-name read-name))

(defn party-name
  "The legal name a person party was registered with, which a run for it
  is graded against: nil where `party-id` is blank or names no person,
  or an anomaly where it cannot be read.

  Args:
  - txn: an FDB handle or open transaction.
  - party-id: the party the run is for."
  [txn party-id]
  (core/party-name txn party-id))
