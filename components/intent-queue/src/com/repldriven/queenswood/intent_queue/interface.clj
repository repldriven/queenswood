(ns com.repldriven.queenswood.intent-queue.interface
  "An adapter's pass over its outbound intents, in the order they were
  accepted, keeping each subject's in that order (ADR-0033). A subject
  is what an intent acts on at the provider, an account for a payment
  provider; an intent names its subjects as `:subjects`, and one naming
  none waits for nothing."
  (:require
    [com.repldriven.queenswood.intent-queue.core :as core]))

(defn drain
  "Run each due pending intent once, oldest first by `:intent-id`, unless
  an earlier intent for one of its subjects holds it. An earlier intent
  holds a later one while it is pending, sent or not; and, where
  `settles-first?` is true of the later one, while it is sent and not
  yet settled or failed. An intent that is held, not yet due, or left
  pending by its run holds the later intents for its subjects in turn.

  Returns `{:ran [intent-id ...]}` with the sets of subjects left held.

  Args:
  - intents: every pending and sent intent, each with `:intent-id`,
    `:status`, `:subjects` and an optional `:next-attempt-at`.
  - now: epoch millis an intent's `:next-attempt-at` is compared with.
  - opts:
    - `:run` — makes an intent's call and returns the intent as it left
      it, its `:status` read to see whether it still holds its subjects;
      an anomaly holds them.
    - `:settles-first?` — true of an intent that waits for the earlier
      intents for its subjects to settle, as a provider refuses to close
      an account money is still moving through."
  [intents now opts]
  (core/drain intents now opts))
