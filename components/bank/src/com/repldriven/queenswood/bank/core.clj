(ns com.repldriven.queenswood.bank.core
  (:require
    [com.repldriven.queenswood.bank.domain :as domain]
    [com.repldriven.queenswood.bank.store :as store]

    [com.repldriven.queenswood.bank-query.interface :as bank-query]
    [com.repldriven.queenswood.cash-account-product.interface :as products]
    [com.repldriven.queenswood.cash-account.interface :as cash-accounts]
    [com.repldriven.queenswood.ledger-account.interface :as ledger-accounts]
    [com.repldriven.queenswood.membership.interface :as memberships]
    [com.repldriven.queenswood.party.interface :as party]
    [com.repldriven.queenswood.policy.interface :as policy]
    [com.repldriven.queenswood.scheduler.interface :as scheduler]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.identity-provider.interface :as identity-provider]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.edn :as edn]
    [clojure.java.io :as io]))

(def ^:private default-ledger-accounts
  "The default chart of bank-owned ledger accounts every customer bank
  is seeded with, loaded once from the bank-resources classpath."
  (let [path "ledgers/general-ledger.edn"
        url (io/resource path)]
    (when (nil? url)
      ;; nosemgrep: no-raw-throw
      (throw (ex-info "Default ledger-accounts resource missing" {:path path})))
    (edn/read-string (slurp url))))

(defn- new-ledger-accounts
  "Seed the customer bank's default chart of bank-owned ledger
  accounts — one `LedgerAccount` per default row per currency. Unlike
  customer cash accounts these are bank-owned and flat: no party, no
  product. Gated on the `:ledger-account` create capability; we pass
  the bootstrap `policies` (the platform tier, which grants it) so
  seeding is allowed even for a tier that denies the capability
  per-bank. Runs inside `new-bank`'s transaction, so a failed row
  rolls the whole bank creation back."
  [txn bank-id currencies policies]
  (reduce (fn [_ [currency row]]
            (let [result (ledger-accounts/new-account txn
                                                      bank-id
                                                      currency
                                                      row
                                                      {:policies policies})]
              (if (error/anomaly? result) (reduced result) nil)))
          nil
          (for [currency currencies
                row default-ledger-accounts]
            [currency row])))

(def ^:private own-funds-template-id
  "The internal own-funds template seeded at bootstrap (see
  cash-account-product-templates/own-funds.yml); the house product is
  created from it."
  "tpl.00000000000000000000000004")

(defn- new-house-account
  "Create the bank's own-funds product in `currency` and open the
  house cash account under it on the bank's org party. This is the
  bank's own money: it rolls up into the 3100 own-funds control (not a
  customer-deposit control), and the bank pre-funds it so it can pay
  customers from inside the bank (rewards, etc.). An ordinary
  `CashAccount` — BBAN-addressable, transactable — so external funding
  can land in it and internal transfers can move out of it."
  [txn bank-id party-id sort-code currency policies]
  (let-nom>
    [version (products/new-product
              txn
              bank-id
              {:name "Bank own funds"
               :currency currency
               :template-id own-funds-template-id
               :effective-from (utility/today)}
              {:policies policies})
     _ (products/publish txn
                         bank-id
                         (:product-id version)
                         (:version-id version)
                         {:policies policies})]
    (cash-accounts/new-account
     txn
     {:bank-id bank-id
      :party-id party-id
      :product-id (:product-id version)
      :currency currency
      :sort-code sort-code
      :name "Bank own funds"}
     {:policies policies})))

(defn- new-house-accounts
  [txn bank-id party-id sort-code currencies policies]
  (reduce (fn [_ currency]
            (let [result (new-house-account txn
                                            bank-id
                                            party-id
                                            sort-code
                                            currency
                                            policies)]
              (if (error/anomaly? result) (reduced result) nil)))
          nil
          currencies))

(defn- bind-policies
  [txn bank-id policies]
  (reduce (fn [_ {:keys [policy-id]}]
            (let [result (policy/new-binding
                          txn
                          {:policy-id policy-id
                           :target {:kind {:bank {:bank-id bank-id}}}})]
              (if (error/anomaly? result) (reduced result) nil)))
          nil
          policies))

(defn- policies-for-tier
  "The policies labelled `tier=<tier>`, or an empty vector for a
  tierless bank. Resolved before the first write so `domain/new-bank`
  can reject an unmatched tier, and so a read anomaly aborts the
  transaction rather than being bound over as if it were a policy
  list."
  [txn tier]
  (if (some? tier) (policy/get-policies-by-tier txn tier) []))

(defn- tier-labelled-policy?
  [txn policy-id]
  (let [p (policy/get-policy txn policy-id)]
    (if (error/anomaly? p) p (contains? (:labels p) "tier"))))

(defn- unbind-tier-policies
  "Drop every binding on `bank-id` whose policy carries a `tier` label
  — identified from the policy, not from any tier previously stored on
  the bank, so a bank with no stored tier still transitions cleanly on
  first use."
  [txn bank-id]
  (let-nom>
    [bindings (policy/get-bindings-for-bank txn bank-id)]
    (reduce (fn [_ {:keys [binding-id policy-id]}]
              (let [tier-labelled (tier-labelled-policy? txn policy-id)]
                (cond
                 (error/anomaly? tier-labelled)
                 (reduced tier-labelled)
                 tier-labelled
                 (let [result (policy/remove-binding txn binding-id)]
                   (if (error/anomaly? result) (reduced result) nil))
                 :else
                 nil)))
            nil
            bindings)))

(defn new-bank
  [txn bank-name bank-status tier currencies opts]
  (store/transact
   txn
   (fn [txn]
     (let [{:keys [identity-provider company-binding membership]} opts
           {:keys [user-id role]} membership]
       (let-nom>
         [_
          (when-not identity-provider
            (error/reject
             :bank/missing-identity-provider
             {:message
              "A bank requires an identity-provider to issue its service-account client"
              :bank-name bank-name}))
          ;; Sole-membership check first, so a redelivered onboarding
          ;; command aborts before any write.
          existing (if membership
                     (memberships/list-by-user txn user-id)
                     [])
          _ (domain/check-sole-membership user-id existing)
          policies (or (:policies opts)
                       (policy/get-effective-policies txn {}))
          tier-policies (policies-for-tier txn tier)
          sort-code (store/allocate-sort-code txn)
          bank (domain/new-bank bank-name
                                bank-status
                                sort-code
                                tier
                                tier-policies
                                company-binding
                                policies)
          bank-id (:bank-id bank)

          ;; Issue the service-account client BEFORE the FDB write so an
          ;; identity-provider failure aborts the transaction cleanly.
          ;; Client-id == bank-id (deterministic mapping). `:audience` is
          ;; the JWT `aud` claim the IDP stamps on tokens for this client
          ;; — the bank-api handler picks it from its own status→audience
          ;; config and forwards it here. The secret it mints is
          ;; discarded: callers rotate a fresh one after the reply so no
          ;; credential crosses the bus.
          _ (identity-provider/create-service-account
             identity-provider
             {:bank-id bank-id
              :name bank-name
              :audience (:audience opts)})
          _ (store/create txn bank)
          {:keys [party-id]} (party/new-party
                              txn
                              {:bank-id bank-id
                               :type :party-type-organization
                               :display-name bank-name}
                              {:policies policies})
          _ (new-ledger-accounts txn bank-id currencies policies)
          _ (new-house-accounts txn
                                bank-id
                                party-id
                                sort-code
                                currencies
                                policies)
          _ (bind-policies txn bank-id tier-policies)
          _ (scheduler/seed-jobs txn bank-id)
          owner (when membership
                  (memberships/new-membership txn
                                              {:user-id user-id
                                               :bank-id bank-id
                                               :role role}))]
         {:bank bank :membership owner})))
   :bank/create
   "Failed to create bank"))

(defn change-tier
  [txn bank-id tier]
  (store/transact
   txn
   (fn [txn]
     (let-nom>
       [bank (bank-query/get-bank txn bank-id)
        new-tier-policies (policy/get-policies-by-tier txn tier)
        updated (domain/change-tier bank tier new-tier-policies)
        _ (unbind-tier-policies txn bank-id)
        _ (bind-policies txn bank-id new-tier-policies)
        _ (store/save-tier txn
                           updated
                           {:bank-id bank-id
                            :tier-before (:tier bank)
                            :tier-after (:tier updated)})]
       updated))
   :bank/change-tier
   "Failed to change bank tier"))

(defn change-status
  [txn bank-id new-status opts]
  (store/transact
   txn
   (fn [txn]
     (let [{:keys [identity-provider audience]} opts]
       (let-nom>
         [bank (bank-query/get-bank txn bank-id)
          updated (domain/change-status bank new-status)
          ;; Swap the service-account client's audience BEFORE the FDB
          ;; write, same rationale as `new-bank`'s IDP call: an IDP
          ;; failure aborts the transaction cleanly rather than leaving
          ;; the bank's status ahead of its client's audience.
          _ (identity-provider/update-service-account-audience
             identity-provider
             bank-id
             audience)
          _ (store/save-status txn
                               updated
                               {:bank-id bank-id
                                :status-before (:status bank)
                                :status-after (:status updated)})]
         updated)))
   :bank/change-status
   "Failed to change bank status"))
