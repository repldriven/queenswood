(ns com.repldriven.queenswood.interest.versions
  (:require
    [com.repldriven.queenswood.cash-account-product-query.interface :as
     products]
    [com.repldriven.mono.error.interface :as error]))

(defn get-version
  "The account's pinned product version, memoised for the run.

  The map is built from the accounts as they stream rather than from
  the bank's current products: an account pins `product-id` and
  `version-id` when it opens, and a pinned version may be one the bank
  no longer offers, so enumerating what is on offer would miss
  accounts. A bank has dozens of versions in use rather than millions,
  so after the first few accounts this is a hash lookup.

  Scoped to the run rather than a TTL cache, because a pass wants one
  view of the terms it is applying — an entry expiring mid-pass would
  accrue or pay two halves of the same bank on two different terms."
  [config ctx bank-id account]
  (let [{:keys [versions]} ctx
        k [(:product-id account) (:version-id account)]]
    (if-some [hit (get @versions k)]
      hit
      (let [version (products/get-version config
                                          bank-id
                                          (:product-id account)
                                          (:version-id account))]
        (when-not (error/anomaly? version)
          (swap! versions assoc k version))
        version))))
