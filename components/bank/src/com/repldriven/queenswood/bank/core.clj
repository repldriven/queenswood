(ns com.repldriven.queenswood.bank.core
  (:require
    [com.repldriven.queenswood.bank.changelog :as changelog]
    [com.repldriven.queenswood.bank.domain :as domain]
    [com.repldriven.queenswood.bank.store :as store]

    [com.repldriven.queenswood.bank-query.interface :as bank-query]
    [com.repldriven.queenswood.cash-account-product.interface :as products]
    [com.repldriven.queenswood.cash-account.interface :as cash-accounts]
    [com.repldriven.queenswood.idv-provider.interface :as idv-provider]
    [com.repldriven.queenswood.ledger-account.interface :as ledger-accounts]
    [com.repldriven.queenswood.membership-query.interface :as
     membership-query]
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
  [txn bank-id party-id currency policies payment-provider actor]
  (let-nom>
    [version (products/new-product
              txn
              bank-id
              {:name "Bank own funds"
               :currency currency
               :template-id own-funds-template-id
               :effective-from (utility/today)}
              {:policies policies :actor actor})
     _ (products/publish txn
                         bank-id
                         (:product-id version)
                         (:version-id version)
                         {:policies policies
                          :payment-provider payment-provider
                          :actor actor})]
    (cash-accounts/new-account
     txn
     {:bank-id bank-id
      :party-id party-id
      :product-id (:product-id version)
      :currency currency
      :name "Bank own funds"
      :actor actor}
     {:policies policies})))

(defn- new-house-accounts
  [txn bank-id party-id currencies policies payment-provider actor]
  (reduce (fn [_ currency]
            (let [result (new-house-account txn
                                            bank-id
                                            party-id
                                            currency
                                            policies
                                            payment-provider
                                            actor)]
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

(def ^:private owner-invitation-reason
  "The reason recorded on the owner invitation a create writes."
  "Owner of a new bank")

(defn- new-owner-invitation
  [txn bank-id actor owner-invitation]
  (when owner-invitation
    (memberships/invite txn
                        bank-id
                        {:email (:email owner-invitation) :role :role-owner}
                        {:actor actor
                         :reason owner-invitation-reason})))

(defn choose-providers
  [offered requested]
  (domain/choose-providers offered requested))

(defn- earliest
  [k xs]
  (first (sort-by k xs)))

(defn- replay
  "What the create that made `bank` returned: the bank, the creator's
  owner membership where the create asked for one, and the owner
  invitation's id where it sent one."
  [txn bank membership owner-invitation]
  (let [{:keys [bank-id]} bank]
    (let-nom>
      [memberships (when membership
                     (membership-query/list-by-bank txn bank-id))
       invitations (when owner-invitation
                     (membership-query/list-invitations-by-bank txn
                                                                bank-id))]
      {:bank (dissoc bank :created-by :idempotency-key)
       :membership (earliest :membership-id
                             (filter (fn [m]
                                       (and (= (:user-id membership)
                                               (:user-id m))
                                            (= :role-owner (:role m))))
                                     memberships))
       :owner-invitation-id (:invitation-id
                             (earliest :invitation-id
                                       (filter (fn [i]
                                                 (and (= :role-owner (:role i))
                                                      (= owner-invitation-reason
                                                         (:reason i))))
                                               invitations)))})))

(defn- create-bank
  [txn bank-name bank-status tier currencies actor opts]
  (let [{:keys [idv-provider payment-provider company-binding membership
                owner-invitation idempotency-key]}
        opts
        {:keys [user-id role]} membership]
    (let-nom>
      [policies (or (:policies opts)
                    (policy/get-effective-policies txn {}))
       tier-policies (if (some? tier)
                       (policy/get-policies-by-tier txn tier)
                       [])
       bank (error/nom-> (domain/new-bank bank-name
                                          bank-status
                                          tier
                                          company-binding
                                          tier-policies
                                          policies
                                          idv-provider)
                         (assoc :providers (vec (:providers opts))))
       bank-id (:bank-id bank)
       _ (store/create txn
                       (assoc bank
                              :created-by actor
                              :idempotency-key idempotency-key))
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
                             currencies
                             policies
                             payment-provider
                             actor)
       _ (bind-policies txn bank-id tier-policies)
       _ (scheduler/seed-jobs txn bank-id)
       owner (when membership
               (memberships/new-membership txn
                                           {:user-id user-id
                                            :bank-id bank-id
                                            :role role
                                            :actor actor}))
       invitation (new-owner-invitation txn bank-id actor owner-invitation)]
      {:bank bank
       :membership owner
       :owner-invitation-id (:invitation-id invitation)})))

(defn- issue-client
  "Creates the service-account client of a committed `bank`, its client
  id the bank id, with `audience` as the `aud` claim its tokens carry.
  Creating one that exists answers the same, so a create sent again
  issues a client its first attempt did not. No secret is minted:
  callers rotate one after the reply, so no credential crosses the bus."
  [identity-provider bank audience]
  (identity-provider/create-service-account identity-provider
                                            {:bank-id (:bank-id bank)
                                             :name (:name bank)
                                             :audience audience}))

(defn new-bank
  [txn bank-name bank-status tier currencies opts]
  (let [{:keys [identity-provider membership owner-invitation actor
                idempotency-key audience]}
        opts]
    (let-nom>
      [_
       (when-not identity-provider
         (error/reject
          :bank/missing-identity-provider
          {:message
           "A bank requires an identity-provider to issue its service-account client"
           :bank-name bank-name}))
       result
       (store/transact
        txn
        (fn [txn]
          (let-nom>
            [existing (store/find-by-creation txn
                                              (:principal-id actor)
                                              idempotency-key)]
            (if existing
              (replay txn existing membership owner-invitation)
              (create-bank txn
                           bank-name
                           bank-status
                           tier
                           currencies
                           actor
                           opts))))
        :bank/create
        "Failed to create bank")
       ;; After the commit, so no client exists for a bank that did not.
       _ (issue-client identity-provider (:bank result) audience)]
      result)))

(defn change-tier
  [txn bank-id tier opts]
  (store/transact
   txn
   (fn [txn]
     (let-nom>
       [bank (bank-query/get-bank txn bank-id)
        {:keys [declaration]} (idv-provider/for-bank (:idv-providers opts)
                                                     bank)
        new-tier-policies (policy/get-policies-by-tier txn tier)
        policies (policy/get-effective-policies txn {})
        updated (domain/change-tier bank
                                    tier
                                    new-tier-policies
                                    policies
                                    declaration)
        _ (unbind-tier-policies txn bank-id)
        _ (bind-policies txn bank-id new-tier-policies)
        entry (changelog/tier-changed {:bank-id bank-id
                                       :tier-before (:tier bank)
                                       :tier-after tier})
        _ (store/save txn updated entry)]
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
          entry (changelog/status-changed
                 {:bank-id bank-id
                  :status-before (:status bank)
                  :status-after (:status updated)})
          _ (store/save txn updated entry)]
         updated)))
   :bank/change-status
   "Failed to change bank status"))
