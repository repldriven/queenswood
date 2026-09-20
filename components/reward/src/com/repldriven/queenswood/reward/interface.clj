(ns com.repldriven.queenswood.reward.interface
  "Rewards: what a bank pays a customer for opening an account under a
  version that promises an opening reward. `pay-due` is the scheduler's
  reward task, run in process the way the interest passes are. A
  `Reward` row per account and kind records what was paid, or is due
  because the house account could not cover it, and is what keeps a
  reward once-only across runs and across a migration onto another
  rewarding version.

  Reads of the rows, the changelog entry that tells the bank and the
  webhook kind are not here yet. See
  [rewards](../../../../../../docs/tdd/rewards.md)."
  (:require
    [com.repldriven.queenswood.reward.core :as core]))

(defn pay-due
  "Pay the opening reward to every account of the bank that is owed
  one: opened, a customer's, pinned to a version carrying
  `opening-reward`, and with no `Reward` row `paid`. Each is paid in a
  transaction of its own — the `reward` posting from the house account
  for the currency and the row `paid` — so a crash leaves either
  nothing or a paid reward with its posting. Where the posting is
  refused, the house account short of funds being the case that
  matters, the row is written `due` with the anomaly's message and the
  next run tries it again; funding the bank is the remedy.

  Args:
  - config: FDB handle plus the product, account, ledger and
    transaction interfaces.
  - data: map with `:bank-id` and `:as-of-date` (epoch-day).

  Returns `{:bank-id :as-of-date :accounts-processed :accounts-failed}`,
  processed being the accounts paid this run and failed the ones left
  due, or an anomaly where the accounts could not be read at all."
  [config data]
  (core/pay-due config data))
