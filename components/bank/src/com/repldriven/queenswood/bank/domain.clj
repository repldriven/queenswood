(ns com.repldriven.queenswood.bank.domain
  (:require
    [com.repldriven.queenswood.policy.interface :as policy]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]))

(defn check-sole-membership
  [user-id existing]
  (when (seq existing)
    (error/reject :membership/already-exists
                  {:message "User already belongs to a bank"
                   :user-id user-id
                   :bank-id (:bank-id (first existing))})))

(defn- check-tier
  "A named tier must resolve to at least one policy, the same rule
  `change-tier` applies. A nil tier is the one exception: a tierless
  bank binds nothing and is governed by the platform policies alone.
  Anything else is a typo, and a bank created under it would be stamped
  with a tier whose policies it is not bound to."
  [tier tier-policies]
  (when (and (some? tier) (empty? tier-policies))
    (error/reject :bank/unknown-tier
                  {:message "No policies found for tier" :tier tier})))

(defn new-bank
  [bank-name bank-status sort-code tier tier-policies company-binding policies]
  (let-nom>
    [_ (policy/check-capability policies
                                :bank
                                {:action :bank-action-create
                                 :status bank-status})
     _ (check-tier tier tier-policies)
     _ (when (and company-binding
                  (not= "active" (:company-status company-binding)))
         (error/reject
          :onboarding/company-not-active
          {:message "Only an active company can be bound to a bank"
           :company-number (:company-number company-binding)
           :company-status (:company-status company-binding)}))]
    (let [now (utility/now)]
      (utility/assoc-some {:bank-id (utility/generate-id "bnk")
                           :name bank-name
                           :status bank-status
                           :sort-code sort-code
                           :created-at now
                           :updated-at now}
                          :tier
                          tier
                          :company-binding
                          company-binding))))

(defn change-tier
  "Rebind a bank onto a new tier's policies. `new-tier-policies` is the
  set of policies whose `tier=<tier>` label matches — pre-fetched by
  the caller since resolving it needs an FDB read. Rejects
  `:bank/invalid-status` unless the bank is test or live, and
  `:bank/unknown-tier` when the tier resolves to no policies (a typo
  must not silently strip all tier bindings)."
  [bank tier new-tier-policies]
  (let-nom>
    [_ (when-not (#{:bank-status-test :bank-status-live} (:status bank))
         (error/reject :bank/invalid-status
                       {:message "Bank is not in a tier-changeable state"
                        :bank-id (:bank-id bank)
                        :status (:status bank)
                        :allowed #{:bank-status-test :bank-status-live}}))
     _ (when (empty? new-tier-policies)
         (error/reject :bank/unknown-tier
                       {:message "No policies found for tier"
                        :bank-id (:bank-id bank)
                        :tier tier}))]
    (assoc bank :tier tier :updated-at (utility/now))))

(defn change-status
  "Flip a bank between `:bank-status-test` and `:bank-status-live`.
  Rejects `:bank/invalid-status` unless the bank is currently test or
  live, and again when `new-status` matches the bank's current
  status (a no-op transition, not a flip)."
  [bank new-status]
  (let-nom>
    [_ (when-not (#{:bank-status-test :bank-status-live} (:status bank))
         (error/reject :bank/invalid-status
                       {:message "Bank is not in a status-changeable state"
                        :bank-id (:bank-id bank)
                        :status (:status bank)
                        :allowed #{:bank-status-test :bank-status-live}}))
     _ (when (= new-status (:status bank))
         (error/reject :bank/invalid-status
                       {:message "Bank is already in the requested status"
                        :bank-id (:bank-id bank)
                        :status (:status bank)
                        :allowed #{new-status}}))]
    (assoc bank :status new-status :updated-at (utility/now))))
