(ns com.repldriven.queenswood.reward.interface
  "Rewards: what a bank pays a customer for opening an account under a
  version that promises an opening reward. The `:reward/event-processor`
  kind consumes the cash-account changelog and pays the reward when an
  account becomes opened, so nothing has to be run or forced. A `Reward`
  row per account and kind records what was paid, or is due because the
  house account could not cover it, and is what keeps a reward
  once-only across redeliveries and across a migration onto another
  rewarding version. See
  [rewards](../../../../../../docs/tdd/rewards.md)."
  (:require
    [com.repldriven.queenswood.reward.core :as core]
    [com.repldriven.queenswood.reward.system]))

(defn pay-opening
  "Pay the opening reward to one account, where it is owed one: opened,
  a customer's, pinned to a version carrying `opening-reward`, and with
  no `Reward` row `paid`. The `reward` posting from the house account
  for the currency and the row `paid` commit together, so a crash
  leaves either nothing or a paid reward with its posting. Where the
  posting is refused, the house account short of funds being the case
  that matters, the row is written `due` with the refusal's message.

  Args:
  - config: FDB handle (`:record-db` and `:record-store`), with an
    optional `:cache` holding each bank's house account and `:caches`
    the ledger accounts' ids.
  - data: map with `:bank-id` and `:account-id`.

  Returns the `Reward` row written, `paid` or `due`; nil where nothing
  is owed or the reward was paid before; or an anomaly where a read or
  the write failed, which the consumer redelivers."
  [config data]
  (core/pay-opening config data))
