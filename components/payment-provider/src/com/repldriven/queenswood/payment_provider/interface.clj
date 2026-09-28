(ns com.repldriven.queenswood.payment-provider.interface
  "The payment providers an installation offers, and what each one's
  adapter can carry — `schemes`, `addresses`, `balances`, `payee-check`,
  `inbound`, `returns` and `screening` — with the check an adapter makes
  at start-up that its provider covers it. A key a declaration leaves
  out reads as the value a provider holding the money has: `inbound`
  notified, no `returns`, and `screening` by the provider.

  The `payment-provider/declaration` component kind holds one provider's
  declaration, a file under `system/payment-providers/` included into a
  system's `payment-provider` group under the provider's key; its
  instance is the declaration with its left-out keys read as their
  defaults. An adapter refers to its own, `payment-provider.<key>`.

  The `payment-provider/providers` component kind names the default and,
  per provider, its `declaration` and the channels that reach its
  adapter, `payment-command-channel` and `account-command-channel`, and
  refuses to start where the default names no provider. Every component
  that routes to a provider or reads its declaration refers to
  `payment-provider.providers`."
  (:require
    [com.repldriven.queenswood.payment-provider.system]

    [com.repldriven.queenswood.payment-provider.core :as core]))

(def ^{:doc "The value each key reads as where the declaration leaves it out."}
     defaults
  core/defaults)

(defn declared
  "The declaration with every key it leaves out read as its default.

  Args:
  - declaration: the parsed `payment-provider.yml`, keyword keys."
  [declaration]
  (core/declared declaration))

(defn check
  "Nil where the adapter carries everything the declaration asks, its
  left-out keys read as their defaults; otherwise a
  `:payment/unsupported-declaration` failure whose `:uncovered` maps
  each key to the values asked and not carried.

  Args:
  - declaration: the parsed `payment-provider.yml`, keyword keys.
  - carries: map of declaration key to the set of values the adapter
    carries; a key it does not name is not checked."
  [declaration carries]
  (core/check declaration carries))

(def ^{:doc "The kind a bank records its payment provider under."} kind
  core/kind)

(defn providers
  "The instance of a `payment-provider/providers` component: `:kind`,
  `\"payment\"`, `:default`, the default provider's key, and `:providers`,
  each provider's entry by key, every entry carrying its key as
  `:provider`. A `:payment-provider/unknown-default` failure where the
  default names no provider.

  Args:
  - config: `{:default :providers}`, the default's key and a map of key
    to entry — `:declaration`, `:payment-command-channel`,
    `:account-command-channel`."
  [config]
  (core/providers config))

(defn default
  "The default provider's entry.

  Args:
  - instance: a `payment-provider/providers` instance."
  [instance]
  (core/default instance))

(defn entries
  "Every provider's entry.

  Args:
  - instance: a `payment-provider/providers` instance."
  [instance]
  (core/entries instance))

(defn for-bank
  "The entry of the provider `bank` records under `kind`, the default's
  where it records none, or a `:payment-provider/unknown` rejection
  where the provider it records is not offered.

  Args:
  - instance: a `payment-provider/providers` instance.
  - bank: a bank map, whose `:providers` is a sequence of `{:kind
    :provider}`."
  [instance bank]
  (core/for-bank instance bank))
