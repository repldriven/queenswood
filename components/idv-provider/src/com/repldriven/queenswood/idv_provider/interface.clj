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
  "The instance of an `idv-provider/providers` component: `:default`,
  the default provider's key, and `:providers`, each provider's entry by
  key, every entry carrying its key as `:provider`. An
  `:idv-provider/unknown-default` failure where the default names no
  provider.

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
