(ns com.repldriven.queenswood.test-scenarios.verbs
  (:require
    [com.repldriven.queenswood.test-scenarios.id-mapping :as id-mapping]
    [com.repldriven.queenswood.test-scenarios.invariants :as invariants]
    [com.repldriven.queenswood.test-scenarios.quiescence :as quiescence]

    [com.repldriven.queenswood.balance-query.interface :as balances-query]
    [com.repldriven.queenswood.balance.interface :as balances]
    [com.repldriven.queenswood.bank-query.interface :as banks-query]
    [com.repldriven.queenswood.bank.interface :as banks]
    [com.repldriven.queenswood.cash-account-migration.interface :as
     migrations]
    [com.repldriven.queenswood.cash-account-product-query.interface :as
     products-query]
    [com.repldriven.queenswood.cash-account-product.interface :as products]
    [com.repldriven.queenswood.cash-account-query.interface :as
     cash-accounts-query]
    [com.repldriven.queenswood.cash-account.interface :as cash-accounts]
    ;; nosemgrep: fdb-outside-store — seeds state below the interfaces
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.idv.interface :as idv]
    [com.repldriven.queenswood.interest.interface :as interest]
    [com.repldriven.queenswood.ledger-account.interface :as ledger-accounts]
    [com.repldriven.queenswood.party-query.interface :as party-query]
    [com.repldriven.queenswood.party.interface :as party]
    [com.repldriven.queenswood.payee-check.interface :as payee-check]
    [com.repldriven.queenswood.payment.interface :as payment]
    [com.repldriven.queenswood.policy.interface :as policy]
    [com.repldriven.queenswood.scheduler.interface :as scheduler]
    [com.repldriven.queenswood.test-projections.interface :as projections]
    [com.repldriven.queenswood.transaction.interface :as transactions]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.test :refer [is]]))

(defn- tag-leg-product-type
  "Stamp a customer leg with its cash-account's `:product-type` so
  `ledger-accounts/add-control-legs` can fan it out to the matching control.
  GL legs (account-id resolves to no cash account) pass through
  untouched."
  [txn bank-id leg]
  (let [account (cash-accounts-query/get-account txn bank-id (:account-id leg))
        product-type (when (and (map? account) (not (error/anomaly? account)))
                       (:product-type account))]
    (cond-> leg
            product-type
            (assoc :product-type product-type))))

(defn- record-and-apply
  "Record a transaction directly (bypassing the payment processor)
  and apply its legs to balances. Fans out customer sub-ledger legs
  to the matching control GL accounts in the same FDB transaction."
  [bank bank-id tx-data]
  (fdb/transact
   bank
   (fn [txn]
     (let [tagged (mapv #(tag-leg-product-type txn bank-id %) (:legs tx-data))
           expanded (ledger-accounts/add-control-legs txn
                                                      bank-id
                                                      (:currency tx-data)
                                                      tagged)]
       (if (error/anomaly? expanded)
         expanded
         (let [r (transactions/record-transaction
                  txn
                  (assoc tx-data :bank-id bank-id :legs expanded))]
           (balances/apply-legs txn
                                bank-id
                                (:legs r)
                                (:transaction-type r))))))))

(defn- seed-opened
  [bank bank-real-id real-acct-id]
  (cash-accounts/seed-opened-account bank bank-real-id real-acct-id))

(defn- seed-closed
  [bank bank-real-id real-acct-id]
  (cash-accounts/seed-closed-account bank bank-real-id real-acct-id))

(defn- track
  [ctx result]
  (let [denied? (error/anomaly? result)
        outcome (if denied? :denied :succeeded)]
    (-> ctx
        (assoc :last-outcome outcome)
        (assoc :last-rejection-kind (when denied? (error/kind result)))
        (update :outcomes (fnil conj []) outcome))))

(defn- model-id-for-next-account
  [next-model-id]
  (keyword (str "acct-" next-model-id)))

(defn- model-id-for-next-bank
  [next-bank-id]
  (keyword (str "bank-" next-bank-id)))

(defn- model-id-for-next-product
  [next-product-id]
  (keyword (str "prod-" next-product-id)))

(defn- model-id-for-next-party
  [next-party-id]
  (keyword (str "party-" next-party-id)))

(defn- model-id-for-next-payment
  [next-payment-id]
  (keyword (str "pmt-" next-payment-id)))

(def ^:private product-type->template-id
  "Maps the internal product-type kind to the stable id of the platform
  template seeded at bootstrap (see templates/*.yml). Products are
  now created from a template-id; the product-type is snapshotted from
  the template."
  {:product-type-sub-ledger-current "tpl.00000000000000000000000001"
   :product-type-sub-ledger-savings "tpl.00000000000000000000000002"
   :product-type-sub-ledger-term-deposit "tpl.00000000000000000000000003"
   :product-type-sub-ledger-own-funds "tpl.00000000000000000000000004"})

(defn- version-payload
  "Build a flat version input for open-draft/update-draft, optionally
  merging caller-supplied extras such as :interest-rate-bps. Names no
  template: a version inherits its product's, and naming a different
  one is rejected."
  [version-name & [extras]]
  (merge {:name version-name
          :currency "GBP"
          ;; A fixed past effective-from (epoch-day 20089 = 2025-01-01)
          ;; so the published version is always active when accounts
          ;; open during the run.
          :effective-from 20089}
         (or extras {})))

(defn- product-payload
  "Build a flat product input for new-product. The `product-type` kind
  selects the seeded template by id, which only a create names."
  [product-name product-type & [extras]]
  (assoc (version-payload product-name extras)
         :template-id
         (product-type->template-id product-type)))

(defmulti dispatch (fn [_ctx command] (:command command)))

(defmethod dispatch :create-bank
  [{:keys [bank identity-provider counter next-model-id next-bank-id
           next-product-id next-party-id id-mapping]
    :as ctx} _command]
  ;; The model treats `:create-bank` as "bank + one usable account in
  ;; one go". Reality post-CoA seeds 7 GL accounts on the bank's own
  ;; organization-party at provisioning, but none of them are
  ;; scenario-usable: no spendable default-posted bucket the model
  ;; recognises. So we additionally create + publish a scenario
  ;; customer-current product and open a single customer-style account
  ;; on the bank's organization-party — that account is what gets
  ;; tracked as `:acct-0`. The 7 GL accounts stay off-model
  ;; (projections only look at `id-mapping`).
  (let [model-acct (model-id-for-next-account next-model-id)
        model-bank (model-id-for-next-bank next-bank-id)
        model-prod (model-id-for-next-product next-product-id)
        model-party (model-id-for-next-party next-party-id)
        bank-name (str "Scenario Customer " counter)
        ;; The test-scenario tier is a thin, test-owned policy (it adds no
        ;; caps; the always-on platform policy governs). Binding it keeps
        ;; this model-equality suite decoupled from the production micro
        ;; tier, whose limits change for production reasons. The narrower
        ;; micro caps (e.g. one product per type) are exercised by the API
        ;; scenarios; per-scenario `bind-policy` supplies any tight limit a
        ;; case needs.
        result (banks/new-bank bank
                               bank-name
                               :bank-status-test
                               "test-scenario"
                               ["GBP"]
                               {:identity-provider identity-provider
                                :audience "queenswood-api-test"})
        bank-entity (:bank result)
        real-bank-id (:bank-id bank-entity)
        real-party-id (when-not (error/anomaly? result)
                        (-> (party-query/get-parties bank real-bank-id)
                            :parties
                            first
                            :party-id))
        scenario-product (when-not (error/anomaly? result)
                           (products/new-product
                            bank
                            real-bank-id
                            (product-payload "Scenario Current"
                                             :product-type-sub-ledger-current)))
        scenario-product-id (:product-id scenario-product)
        scenario-version-id (:version-id scenario-product)
        _ (when (and scenario-product-id
                     (not (error/anomaly? scenario-product)))
            (products/publish bank
                              real-bank-id
                              scenario-product-id
                              scenario-version-id))
        scenario-account (when scenario-product-id
                           (cash-accounts/new-account
                            bank
                            {:bank-id real-bank-id
                             :party-id real-party-id
                             :product-id scenario-product-id
                             :currency "GBP"
                             :sort-code (:sort-code bank-entity)
                             :name "Scenario Account"}))
        real-acct-id (:account-id scenario-account)
        real-bban (:bban scenario-account)
        _ (when real-acct-id (seed-opened bank real-bank-id real-acct-id))]
    (-> ctx
        (cond-> real-acct-id
                (-> (assoc :id-mapping
                           (id-mapping/add id-mapping model-acct real-acct-id))
                    (assoc-in [:accounts model-acct] {:bank model-bank})))
        (assoc-in [:banks model-bank]
                  {:real-id real-bank-id
                   :currency "GBP"
                   :sort-code (:sort-code bank-entity)})
        ;; The scenario product is born already-published (we publish
        ;; above) so track v1 as :published; matches the model's
        ;; auto-scenario-product semantics.
        (cond-> scenario-product-id
                (assoc-in [:products model-prod]
                 {:real-id scenario-product-id
                  :bank model-bank
                  :product-type :current
                  :versions [{:real-id scenario-version-id
                              :status :published
                              :number 1}]}))
        (assoc-in [:parties model-party]
                  {:real-id real-party-id :bank model-bank})
        (cond-> real-acct-id
                (assoc-in [:accounts model-acct]
                 {:bank model-bank :bban real-bban}))
        (update :next-model-id inc)
        (update :next-bank-id inc)
        (update :next-product-id inc)
        (update :next-party-id inc)
        (update :counter inc)
        (track (or scenario-account result)))))

(defn- record-fresh-product
  [ctx model-prod model-bank product-type result]
  (cond-> ctx
          (not (error/anomaly? result))
          (assoc-in [:products model-prod]
           {:real-id (:product-id result)
            :bank model-bank
            :product-type product-type
            :versions [{:real-id (:version-id result)
                        :status :draft
                        :number 1}]})))

(def ^:private product-type->kind
  {:current :product-type-sub-ledger-current
   :savings :product-type-sub-ledger-savings})

(defmethod dispatch :create-product
  [{:keys [bank counter next-product-id banks] :as ctx}
   {[model-bank type rate-bps] :args}]
  (let [model-prod (model-id-for-next-product next-product-id)
        {:keys [real-id]} (get banks model-bank)
        kind (get product-type->kind type :product-type-sub-ledger-current)
        name
        (str (if (= :savings type) "Savings" "Current") " Product " counter)
        extras (when (and rate-bps (pos? rate-bps))
                 {:interest-rate-bps rate-bps})
        result
        (products/new-product bank real-id (product-payload name kind extras))]
    (-> ctx
        (record-fresh-product model-prod model-bank type result)
        (update :next-product-id inc)
        (update :counter inc)
        (track result))))

(defn- latest-version
  [product]
  (peek (:versions product)))

(defn- update-latest-version
  [ctx model-prod f]
  (update-in ctx
             [:products model-prod :versions]
             (fn [versions] (conj (pop versions) (f (peek versions))))))

(defn- resolve-product-id
  [products product-ref]
  (if (keyword? product-ref)
    (get-in products [product-ref :real-id])
    product-ref))

(defmethod dispatch :publish-product
  [{:keys [bank banks products] :as ctx} {args :args}]
  (case (count args)
    1 (let [[model-prod] args
            product (get products model-prod)
            {model-bank :bank :keys [real-id]} product
            {version-real-id :real-id} (latest-version product)
            bank-real-id (get-in banks [model-bank :real-id])
            result (products/publish bank bank-real-id real-id version-real-id)]
        (-> ctx
            (cond-> (not (error/anomaly? result))
                    (update-latest-version model-prod
                                           (fn [v]
                                             (assoc v :status :published))))
            (update :counter inc)
            (track result)))
    3 (let [[model-bank product-ref version-id] args
            bank-real-id (get-in banks [model-bank :real-id])
            product-id (resolve-product-id products product-ref)
            result (products/publish bank bank-real-id product-id version-id)]
        (-> ctx
            (update :counter inc)
            (track result)))))

(defmethod dispatch :open-draft
  [{:keys [bank banks products] :as ctx} {[model-prod] :args}]
  (let [product (get products model-prod)
        {model-bank :bank :keys [real-id]} product
        bank-real-id (get-in banks [model-bank :real-id])
        next-number (inc (:number (latest-version product)))
        result (products/open-draft bank
                                    bank-real-id
                                    real-id
                                    (version-payload (str "Draft Version "
                                                          next-number)))]
    (-> ctx
        (cond-> (not (error/anomaly? result))
                (update-in [:products model-prod :versions]
                           conj
                           {:real-id (:version-id result)
                            :status :draft
                            :number next-number}))
        (update :counter inc)
        (track result))))

(defmethod dispatch :discard-draft
  [{:keys [bank banks products] :as ctx} {[model-prod] :args}]
  (let [product (get products model-prod)
        {model-bank :bank :keys [real-id]} product
        {version-real-id :real-id} (latest-version product)
        bank-real-id (get-in banks [model-bank :real-id])
        result
        (products/discard-draft bank bank-real-id real-id version-real-id)]
    (-> ctx
        (cond-> (not (error/anomaly? result))
                (update-latest-version model-prod
                                       (fn [v] (assoc v :status :discarded))))
        (update :counter inc)
        (track result))))

(defmethod dispatch :create-person-party
  [{:keys [bank counter next-party-id banks] :as ctx}
   {[model-bank ni-marker] :args}]
  (let [model-party (model-id-for-next-party next-party-id)
        {bank-real-id :real-id} (get banks model-bank)
        ni (when ni-marker
             {:type :identifier-type-national-insurance
              :value (name ni-marker)
              :issuing-country "GB"})
        payload (cond-> {:bank-id bank-real-id
                         :type :party-type-person
                         :display-name (str "Scenario Person " counter)
                         :given-name "Scenario"
                         :family-name (str "Person" counter)
                         :date-of-birth 19700101
                         :nationality "GB"
                         :address {:building-number "155"
                                   :street "Country Lane"
                                   :town "Cottington"
                                   :postcode "CT12 4XY"
                                   :country "GBR"}}

                        ni
                        (assoc :national-identifier ni))
        result (party/new-party bank payload)
        ;; Reality: bank-idv watcher → onfido chain → bank-party
        ;; activates. Wait until reality catches up to the model
        ;; before the next verb fires. "Scenario" given-name routes
        ;; the simulator outcome to clear → ACCEPTED, so every
        ;; non-anomaly party reaches :active.
        result' (if (error/anomaly? result)
                  result
                  (let [q (quiescence/wait-for-party-active bank
                                                            bank-real-id
                                                            (:party-id result))]
                    (if (error/anomaly? q) q result)))]
    (-> ctx
        (cond->
         (not (error/anomaly? result'))
         (assoc-in [:parties model-party]
          {:real-id (:party-id result)
           :bank model-bank}))
        (update :next-party-id inc)
        (update :counter inc)
        (track result'))))

(defmethod dispatch :activate-party
  [{:keys [bank banks parties] :as ctx} {[model-party] :args}]
  ;; The IDV chain auto-activates a "Scenario"-named party, so this
  ;; verb degrades to a wait-and-verify. Kept for EDN scenarios that
  ;; emit it; fugato never selects it because no parties enter
  ;; pending in the model.
  (let [{party-real-id :real-id model-bank :bank} (get parties model-party)
        bank-real-id (get-in banks [model-bank :real-id])
        result
        (quiescence/wait-for-party-active bank bank-real-id party-real-id)]
    (-> ctx
        (update :counter inc)
        (track result))))

(defmethod dispatch :open-account
  [{:keys [bank counter next-model-id id-mapping banks products parties]
    :as ctx} {[model-bank model-party model-prod] :args}]
  (let [model-acct (model-id-for-next-account next-model-id)
        {bank-real-id :real-id sort-code :sort-code :keys [currency]}
        (get banks model-bank)
        {prod-real-id :real-id} (get products model-prod)
        {party-real-id :real-id} (get parties model-party)
        result (cash-accounts/new-account bank
                                          {:bank-id bank-real-id
                                           :party-id party-real-id
                                           :product-id prod-real-id
                                           :currency currency
                                           :sort-code sort-code
                                           :name (str "Scenario Account "
                                                      counter)})
        real-acct-id (:account-id result)
        _ (when real-acct-id (seed-opened bank bank-real-id real-acct-id))]
    (-> ctx
        (cond-> real-acct-id
                (assoc :id-mapping
                       (id-mapping/add id-mapping model-acct real-acct-id)))
        (assoc-in [:accounts model-acct]
                  {:bank model-bank :bban (:bban result)})
        (update :next-model-id inc)
        (update :counter inc)
        (track result))))

(defn- find-current-product
  "Returns the first tracked **published** `:current` product on
  `model-bank`, or nil if none. Draft / discarded products can't
  back an account-open, so they're skipped. Mirrors the model's
  lookup."
  [products model-bank]
  (some (fn [[model-prod entry]]
          (when (and (= model-bank (:bank entry))
                     (= :current (:product-type entry))
                     (= :published
                        (:status (peek (:versions entry)))))
            model-prod))
        products))

(defmethod dispatch :create-customer
  ;; Macro verb: open a customer (person party + cash account on a
  ;; current product) on `model-bank`. Composes the existing
  ;; :create-person-party + :open-account flows and auto-creates a
  ;; current product the first time it's called on a bank.
  ;;
  ;;   Args:
  ;;   - `[model-bank]` — auto-finds or creates a current product.
  ;;   - `[model-bank model-prod]` — uses the given product.
  [{:keys [bank counter next-model-id next-party-id next-product-id id-mapping
           banks products]
    :as ctx} {args :args}]
  (let [[model-bank explicit-prod] args
        existing-prod (find-current-product products model-bank)
        ;; Decide whether to create a product first.
        create-prod? (and (nil? explicit-prod) (nil? existing-prod))
        prod-model-id (cond
                       explicit-prod
                       explicit-prod
                       existing-prod
                       existing-prod
                       :else
                       (model-id-for-next-product next-product-id))
        {bank-real-id :real-id sort-code :sort-code :keys [currency]}
        (get banks model-bank)
        prod-result (when create-prod?
                      (products/new-product bank
                                            bank-real-id
                                            (product-payload
                                             (str "Scenario Current Product "
                                                  counter)
                                             :product-type-sub-ledger-current)))
        _
        (when (and create-prod? prod-result (not (error/anomaly? prod-result)))
          (products/publish bank
                            bank-real-id
                            (:product-id prod-result)
                            (:version-id prod-result)))
        prod-real-id (or (get-in products [prod-model-id :real-id])
                         (:product-id prod-result))
        ;; Onboard the person party (mirror of :create-person-party).
        model-party (model-id-for-next-party next-party-id)
        party-payload {:bank-id bank-real-id
                       :type :party-type-person
                       :display-name (str "Scenario Customer " counter)
                       :given-name "Scenario"
                       :family-name (str "Customer" counter)
                       :date-of-birth 19700101
                       :nationality "GB"
                       :address {:building-number "155"
                                 :street "Country Lane"
                                 :town "Cottington"
                                 :postcode "CT12 4XY"
                                 :country "GBR"}}
        party-result (party/new-party bank party-payload)
        party-result (if (error/anomaly? party-result)
                       party-result
                       (let [q (quiescence/wait-for-party-active
                                bank
                                bank-real-id
                                (:party-id party-result))]
                         (if (error/anomaly? q) q party-result)))
        party-real-id (:party-id party-result)
        ;; Open the customer account.
        model-acct (model-id-for-next-account next-model-id)
        acct-result (when (and party-real-id
                               prod-real-id
                               (not (error/anomaly? party-result)))
                      (cash-accounts/new-account
                       bank
                       {:bank-id bank-real-id
                        :party-id party-real-id
                        :product-id prod-real-id
                        :currency currency
                        :sort-code sort-code
                        :name (str "Scenario Customer Account " counter)}))
        real-acct-id (:account-id acct-result)
        _ (when real-acct-id (seed-opened bank bank-real-id real-acct-id))
        outcome (cond
                 (error/anomaly? party-result)
                 party-result
                 (and acct-result (error/anomaly? acct-result))
                 acct-result
                 :else
                 (or acct-result party-result))]
    (-> ctx
        (cond-> create-prod?
                (-> (assoc-in [:products prod-model-id]
                              {:real-id prod-real-id
                               :bank model-bank
                               :product-type :current
                               :versions [{:real-id (:version-id prod-result)
                                           :status :published
                                           :number 1}]})
                    (update :next-product-id inc)))
        (cond-> (not (error/anomaly? party-result))
                (assoc-in [:parties model-party]
                 {:real-id party-real-id :bank model-bank}))
        (cond-> real-acct-id
                (-> (assoc :id-mapping
                           (id-mapping/add id-mapping model-acct real-acct-id))
                    (assoc-in [:accounts model-acct]
                              {:bank model-bank :bban (:bban acct-result)})
                    (update :next-model-id inc)))
        (update :next-party-id inc)
        (update :counter inc)
        (track outcome))))

(defmethod dispatch :close-account
  [{:keys [bank id-mapping accounts banks] :as ctx} {[model-acct] :args}]
  (let [model-bank (get-in accounts [model-acct :bank])
        bank-real-id (get-in banks [model-bank :real-id])
        real-acct-id (get-in id-mapping [:model->real model-acct])
        result (cash-accounts/close-account bank
                                            {:bank-id bank-real-id
                                             :account-id real-acct-id})
        _ (when-not (error/anomaly? result)
            (seed-closed bank bank-real-id real-acct-id))]
    (-> ctx
        (update :counter inc)
        (track result))))

(defn- transfer-tx
  "Build a balanced 2-leg simulation transaction. `gl-leg` is the
  bank-side leg; `customer-leg` is the sub-ledger leg. Both already
  carry their own balance-type/status; the helper just wraps them
  into the transaction envelope."
  [{:keys [transaction-type idempotency-key reference currency
           gl-leg customer-leg]}]
  {:idempotency-key idempotency-key
   :transaction-type transaction-type
   :currency (or currency "GBP")
   :reference reference
   :legs [gl-leg customer-leg]})

(defn- gl-account-for
  "Look up the bank's GL account by `gl-account-code` role and `currency`
  on its own books."
  [bank bank-id gl-account-code currency]
  (ledger-accounts/find-by-code bank bank-id gl-account-code currency))

(defn- bank-id-for-account
  "Resolve the bank-id that owns `model-acct`."
  [banks accounts model-acct]
  (get-in banks [(get-in accounts [model-acct :bank]) :real-id]))

(defmethod dispatch :inbound-transfer
  [{:keys [bank accounts next-inbound-id run-id] :as ctx}
   {[model-acct amount] :args}]
  (let [bban (get-in accounts [model-acct :bban])
        marker (keyword (str "in-" next-inbound-id))
        stx-id (str "scen-in-" run-id "-" (name marker))
        result (payment/settle-inbound
                bank
                {:scheme-transaction-id stx-id
                 :end-to-end-id stx-id
                 :scheme "FPS"
                 :debit-credit-code :debit-credit-code-credit
                 :amount amount
                 :currency "GBP"
                 :creditor-bban bban
                 :debtor-name "Scenario Funder"
                 :reference (str "scenario inbound " (name marker))
                 :timestamp-settled (utility/now)})]
    (-> ctx
        (update :next-inbound-id inc)
        (update :counter inc)
        (track result))))

;; Held-inbound lifecycle — reality-only verbs (no model counterpart). The
;; model-eq runner stops tracking after the first of these but still runs the
;; scenario's explicit `:assert-balance` calls.

(defmethod dispatch :hold-inbound
  [{:keys [bank accounts next-inbound-id run-id] :as ctx}
   {[model-acct amount] :args}]
  (let [bban (get-in accounts [model-acct :bban])
        e2e (str "scen-held-" run-id "-" next-inbound-id)
        result (payment/hold-inbound bank
                                     {:end-to-end-id e2e
                                      :scheme "FPS"
                                      :debit-credit-code
                                      :debit-credit-code-credit
                                      :amount amount
                                      :currency "GBP"
                                      :creditor-bban bban
                                      :debtor-name "Scenario Held Sender"})]
    (-> ctx
        (assoc-in [:held-inbounds model-acct] {:e2e e2e :amount amount})
        (update :next-inbound-id inc)
        (update :counter inc)
        (track result))))

(defmethod dispatch :release-inbound
  [{:keys [bank accounts held-inbounds run-id counter] :as ctx}
   {[model-acct] :args}]
  (let [{:keys [e2e amount]} (get held-inbounds model-acct)
        bban (get-in accounts [model-acct :bban])
        result (payment/settle-inbound
                bank
                {:scheme-transaction-id (str "scen-rel-" run-id "-" counter)
                 :end-to-end-id e2e
                 :scheme "FPS"
                 :debit-credit-code :debit-credit-code-credit
                 :amount amount
                 :currency "GBP"
                 :creditor-bban bban
                 :debtor-name "Scenario Held Sender"
                 :timestamp-settled (utility/now)})]
    (-> ctx
        (update :counter inc)
        (track result))))

(defmethod dispatch :return-inbound
  [{:keys [bank held-inbounds] :as ctx} {[model-acct] :args}]
  (let [{:keys [e2e]} (get held-inbounds model-acct)
        result (payment/return-inbound bank
                                       {:end-to-end-id e2e
                                        :scheme "FPS"
                                        :debit-credit-code
                                        :debit-credit-code-credit
                                        :cancellation-code "HELD_DECLINED"
                                        :timestamp-rejected (utility/now)})]
    (-> ctx
        (update :counter inc)
        (track result))))

(defmethod dispatch :outbound-transfer
  [{:keys [bank counter id-mapping banks accounts run-id] :as ctx}
   {[model-id amount] :args}]
  ;; Reserve, don't post — the shape `payment/domain.clj`'s
  ;; `outbound-payment->transaction` produces: the customer's funds
  ;; move to their pending-outgoing bucket and the bank's 1200 claim
  ;; is likewise pending, so available drops while posted is
  ;; untouched. Nothing reaches a posted bucket, so the transfer
  ;; disturbs neither standing invariant until the scheme settles.
  (let [real-id (id-mapping/real id-mapping model-id)
        bank-id (bank-id-for-account banks accounts model-id)
        pending-outbound
        (gl-account-for bank bank-id :gl-account-code-pending-outbound "GBP")
        result
        (if (or (nil? pending-outbound) (error/anomaly? pending-outbound))
          (error/reject :scenario/no-pending-outbound-account
                        {:message "Bank has no 1200 pending-outbound account"
                         :bank-id bank-id})
          (record-and-apply
           bank
           bank-id
           (transfer-tx
            {:transaction-type :transaction-type-outbound-transfer
             :idempotency-key (str "scen-out-" run-id "-" counter)
             :reference (str "scenario outbound " counter)
             :gl-leg {:account-id (:ledger-account-id pending-outbound)
                      :balance-type :balance-type-default
                      :balance-status :balance-status-pending-outgoing
                      :side :leg-side-credit
                      :amount amount}
             :customer-leg {:account-id real-id
                            :balance-type :balance-type-default
                            :balance-status :balance-status-pending-outgoing
                            :side :leg-side-debit
                            :amount amount}})))]
    (-> ctx
        (update :counter inc)
        (track result))))

(defmethod dispatch :bind-policy
  [{:keys [bank banks] :as ctx} {[model-bank policy-data] :args}]
  (let [bank-real-id (get-in banks [model-bank :real-id])
        result (let [created (policy/new-policy bank policy-data)]
                 (if (error/anomaly? created)
                   created
                   (policy/new-binding
                    bank
                    {:policy-id (:policy-id created)
                     :target {:kind {:bank {:bank-id bank-real-id}}}
                     :reason "scenario-bound test policy"})))]
    (track ctx result)))

(defmethod dispatch :internal-transfer
  [{:keys [bank counter id-mapping run-id banks accounts] :as ctx}
   {[from-model to-model amount currency] :args}]
  (let [from-real (id-mapping/real id-mapping from-model)
        to-real (id-mapping/real id-mapping to-model)
        model-bank (get-in accounts [from-model :bank])
        bank-real-id (get-in banks [model-bank :real-id])
        result (payment/submit-internal
                bank
                {:idempotency-key (str "scen-int-" run-id "-" counter)
                 :bank-id bank-real-id
                 :debtor-account-id from-real
                 :creditor-account-id to-real
                 :currency (or currency "GBP")
                 :amount amount
                 :reference (str "scenario internal " counter)})]
    (-> ctx
        (update :counter inc)
        (track result))))

(defmethod dispatch :outbound-payment
  [{:keys [bank counter id-mapping banks accounts run-id next-payment-id]
    :as ctx} {args :args}]
  ;; Two-arg `[debtor amount]` pays an external creditor with a
  ;; fixed BBAN. Three-arg `[debtor creditor amount]` pays a known
  ;; model account; we look its BBAN up so the bank-payment
  ;; event-processor recognises the creditor as internal and
  ;; credits it on settlement.
  ;;
  (let [internal-creditor (when (= 3 (count args)) (second args))
        [model-acct amount creditor-bban creditor-name]
        (case (count args)
          2 (let [[a amt] args]
              [a amt "040004000000001"
               (str "Scenario External Creditor " counter)])
          3 (let [[a c amt] args]
              [a amt (get-in accounts [c :bban])
               (str "Scenario Internal Creditor " counter)]))
        real-acct-id (id-mapping/real id-mapping model-acct)
        creditor-real-id (when internal-creditor
                           (id-mapping/real id-mapping internal-creditor))
        model-bank (get-in accounts [model-acct :bank])
        bank-real-id (get-in banks [model-bank :real-id])
        model-pmt (model-id-for-next-payment next-payment-id)
        creditor-pre-net
        (when creditor-real-id
          (let [b (balances-query/get-balance bank
                                              bank-real-id
                                              creditor-real-id
                                              :balance-type-default
                                              "GBP"
                                              :balance-status-posted)]
            (when-not (error/anomaly? b) (- (:credit b 0) (:debit b 0)))))
        result (payment/submit-outbound
                bank
                {:idempotency-key (str "scen-pay-" run-id "-" counter)
                 :bank-id bank-real-id
                 :debtor-account-id real-acct-id
                 :scheme "FPS"
                 :currency "GBP"
                 :amount amount
                 :reference (str "scenario payment " counter)
                 :creditor-bban creditor-bban
                 :creditor-name creditor-name})
        real-pmt-id (:payment-id result)
        ;; ClearBank settles asynchronously; the bank-payment
        ;; event-processor on schemes-payments-event fires both
        ;; settle-outbound (Debit → flip OutboundPayment to
        ;; :completed) and settle-inbound (Credit → credit the
        ;; creditor when its BBAN matches an internal account).
        ;; The model auto-completes on :outbound-payment, so the
        ;; subsequent model-eq check needs reality at the same
        ;; state. Poll the OutboundPayment for :completed (Debit
        ;; hop) — and for the 3-arg form, also poll the creditor's
        ;; balance to reach pre + amount (Credit hop, fired as a
        ;; separate transaction-settled webhook).
        _ (when real-pmt-id
            (quiescence/wait-for-outbound-completed bank real-pmt-id))
        _ (when (and real-pmt-id creditor-real-id creditor-pre-net)
            (quiescence/wait-for-credit bank
                                        bank-real-id
                                        creditor-real-id
                                        "GBP"
                                        (+ creditor-pre-net amount)))]
    (-> ctx
        (cond-> real-pmt-id
                (assoc-in [:payments model-pmt] {:real-id real-pmt-id}))
        (cond-> real-pmt-id (update :next-payment-id inc))
        (update :counter inc)
        (track result))))

(defmethod dispatch :wait
  [ctx {[duration-ms] :args}]
  (Thread/sleep ^long duration-ms)
  (update ctx :counter inc))

(defmethod dispatch :settle-inbound-event
  [{:keys [bank accounts] :as ctx} {[model-acct amount stx-id] :args}]
  (let [bban (get-in accounts [model-acct :bban])
        result (payment/settle-inbound
                bank
                {:scheme-transaction-id stx-id
                 :end-to-end-id stx-id
                 :scheme "FPS"
                 :debit-credit-code :debit-credit-code-credit
                 :amount amount
                 :currency "GBP"
                 :creditor-bban bban
                 :debtor-name "Scenario Funder"
                 :reference (str "scenario inbound " stx-id)
                 :timestamp-settled (utility/now)})]
    (-> ctx
        (update :counter inc)
        (track result))))

(defmethod dispatch :settle-outbound-payment
  [{:keys [bank counter payments run-id] :as ctx} {[model-pmt] :args}]
  (let [real-pmt-id (get-in payments [model-pmt :real-id])
        result (payment/settle-outbound
                bank
                {:scheme-transaction-id (str "scen-stl-" run-id "-" counter)
                 :end-to-end-id real-pmt-id
                 :scheme "FPS"
                 :debit-credit-code :debit-credit-code-debit
                 :amount 0
                 :currency "GBP"
                 :creditor-bban "040004000000001"
                 :debtor-name "Scenario Settler"
                 :reference (str "scenario settlement " counter)
                 :timestamp-settled (utility/now)})]
    (-> ctx
        (update :counter inc)
        (track result))))

(defmethod dispatch :apply-fee
  [{:keys [bank counter id-mapping banks accounts run-id] :as ctx}
   {[model-id amount] :args}]
  ;; Scenario fee: DEBIT customer.default, CREDIT 1100.default
  ;; (the bank takes the fee onto its cash position — placeholder
  ;; until 4100 fee-income lands in a future wave).
  (let [real-id (id-mapping/real id-mapping model-id)
        bank-id (bank-id-for-account banks accounts model-id)
        cash (gl-account-for bank
                             bank-id
                             :gl-account-code-cash-at-correspondent
                             "GBP")
        result
        (if (or (nil? cash) (error/anomaly? cash))
          (error/reject :scenario/no-cash-at-correspondent-account
                        {:message "Bank has no 1100 account" :bank-id bank-id})
          (record-and-apply
           bank
           bank-id
           (transfer-tx {:transaction-type :transaction-type-fee
                         :idempotency-key (str "scen-fee-" run-id "-" counter)
                         :reference (str "scenario fee " counter)
                         :gl-leg {:account-id (:ledger-account-id cash)
                                  :balance-type :balance-type-default
                                  :balance-status :balance-status-posted
                                  :side :leg-side-credit
                                  :amount amount}
                         :customer-leg {:account-id real-id
                                        :balance-type :balance-type-default
                                        :balance-status :balance-status-posted
                                        :side :leg-side-debit
                                        :amount amount}})))]
    (-> ctx
        (update :counter inc)
        (track result))))

(defmethod dispatch :accrue-interest
  [{:keys [bank banks] :as ctx} {[model-bank as-of-date] :args}]
  (let [{bank-real-id :real-id} (get banks model-bank)
        result (interest/accrue-day bank
                                    {:bank-id bank-real-id
                                     :as-of-date as-of-date})]
    (-> ctx
        (update :counter inc)
        (track result))))

(defmethod dispatch :capitalize-interest
  [{:keys [bank banks] :as ctx} {[model-bank as-of-date] :args}]
  (let [{bank-real-id :real-id} (get banks model-bank)
        result (interest/capitalize-accrued bank
                                            {:bank-id bank-real-id
                                             :as-of-date as-of-date})]
    (-> ctx
        (update :counter inc)
        (track result))))

(defmethod dispatch :force-start-job
  [{:keys [bank banks] :as ctx} {[model-bank job-id] :args}]
  (let [{bank-real-id :real-id} (get banks model-bank)
        result (scheduler/force-start bank bank-real-id job-id)]
    (-> ctx
        (update :counter inc)
        (track result))))

(defmethod dispatch :get-product
  [{:keys [bank banks products] :as ctx} {[model-bank product-ref] :args}]
  (let [bank-real-id (get-in banks [model-bank :real-id])
        product-id (resolve-product-id products product-ref)
        result (products-query/get-product bank bank-real-id product-id)]
    (-> ctx
        (update :counter inc)
        (track result))))

(defmethod dispatch :get-product-version
  [{:keys [bank banks products] :as ctx}
   {[model-bank product-ref version-id] :args}]
  (let [bank-real-id (get-in banks [model-bank :real-id])
        product-id (resolve-product-id products product-ref)
        result
        (products-query/get-version bank bank-real-id product-id version-id)]
    (-> ctx
        (update :counter inc)
        (track result))))

(defn- resolve-real-id
  [side-table key-or-literal]
  (if (keyword? key-or-literal)
    (get-in side-table [key-or-literal :real-id])
    key-or-literal))

(defmethod dispatch :get-account
  [{:keys [bank banks id-mapping] :as ctx} {[model-bank account-ref] :args}]
  (let [bank-real-id (get-in banks [model-bank :real-id])
        account-id (if (keyword? account-ref)
                     (get-in id-mapping [:model->real account-ref])
                     account-ref)
        result (cash-accounts-query/get-account bank bank-real-id account-id)]
    (-> ctx
        (update :counter inc)
        (track result))))

(defmethod dispatch :get-party
  [{:keys [bank banks parties] :as ctx} {[model-bank party-ref] :args}]
  (let [bank-real-id (get-in banks [model-bank :real-id])
        party-id (resolve-real-id parties party-ref)
        result (party-query/get-party bank bank-real-id party-id)]
    (-> ctx
        (update :counter inc)
        (track result))))

(defmethod dispatch :get-bank
  [{:keys [bank banks] :as ctx} {[bank-ref] :args}]
  (let [bank-id (resolve-real-id banks bank-ref)
        result (banks-query/get-bank bank bank-id)]
    (-> ctx
        (update :counter inc)
        (track result))))

(defmethod dispatch :get-idv
  [{:keys [bank banks] :as ctx} {[model-bank verification-id] :args}]
  (let [bank-real-id (get-in banks [model-bank :real-id])
        result (idv/get-idv bank bank-real-id verification-id)]
    (-> ctx
        (update :counter inc)
        (track result))))

(defmethod dispatch :get-payee-check
  [{:keys [bank banks] :as ctx} {[model-bank check-id] :args}]
  (let [bank-real-id (get-in banks [model-bank :real-id])
        result (payee-check/get-check bank bank-real-id check-id)]
    (-> ctx
        (update :counter inc)
        (track result))))

(defmethod dispatch :get-policy
  [{:keys [bank] :as ctx} {[policy-id] :args}]
  (let [result (policy/get-policy bank policy-id)]
    (-> ctx
        (update :counter inc)
        (track result))))

(defmethod dispatch :get-policy-binding
  [{:keys [bank] :as ctx} {[binding-id] :args}]
  (let [result (policy/get-binding bank binding-id)]
    (-> ctx
        (update :counter inc)
        (track result))))

(defmethod dispatch :get-balance
  [{:keys [bank banks accounts id-mapping] :as ctx}
   {[account-ref balance-type currency balance-status] :args}]
  (let [account-id (if (keyword? account-ref)
                     (get-in id-mapping [:model->real account-ref])
                     account-ref)
        model-bank (get-in accounts [account-ref :bank])
        bank-real-id (or (get-in banks [model-bank :real-id])
                         ;; A scenario naming a raw account id, or an
                         ;; account seeded by :create-bank, has no
                         ;; entry to look through — every scenario runs
                         ;; one bank, so that is the one it means.
                         (:real-id (first (vals banks))))
        result (balances-query/get-balance bank
                                           bank-real-id
                                           account-id
                                           balance-type
                                           currency
                                           balance-status)]
    (-> ctx
        (update :counter inc)
        (track result))))

(defmethod dispatch :update-product-draft
  [{:keys [bank banks products] :as ctx} {args :args}]
  (case (count args)
    ;; Model-driven: rewrite the tracked latest version of `model-prod`.
    2 (let [[model-prod data] args
            product (get products model-prod)
            {model-bank :bank :keys [real-id]} product
            {version-real-id :real-id :keys [number]} (latest-version product)
            bank-real-id (get-in banks [model-bank :real-id])
            result (products/update-draft
                    bank
                    bank-real-id
                    real-id
                    version-real-id
                    (version-payload (str "Updated Version " number) data))]
        (-> ctx
            (cond-> (not (error/anomaly? result))
                    (update-latest-version
                     model-prod
                     (fn [v]
                       (assoc v
                              :effective-from (:effective-from result)
                              :effective-to (:effective-to result)))))
            (update :counter inc)
            (track result)))
    (let [[model-bank product-ref version-id data] args
          bank-real-id (get-in banks [model-bank :real-id])
          product-id (resolve-product-id products product-ref)
          result (products/update-draft bank
                                        bank-real-id
                                        product-id
                                        version-id
                                        (or data {}))]
      (-> ctx
          (update :counter inc)
          (track result)))))

(defmethod dispatch :assert-balance
  [{:keys [bank banks accounts id-mapping] :as ctx} {[model-id expected] :args}]
  (let [actual
        (get (projections/project-balances
              bank
              (projections/real->bank accounts banks (:model->real id-mapping))
              (:real->model id-mapping))
             model-id)]
    (is (= expected actual) (str "balance for " model-id))
    ctx))

(defmethod dispatch :assert-gl-balance
  [{:keys [bank banks] :as ctx}
   {[model-bank gl-account-code currency expected] :args}]
  (let [{bank-real-id :real-id} (get banks model-bank)
        gl (gl-account-for bank bank-real-id gl-account-code currency)
        balance (balances-query/get-balance bank
                                            bank-real-id
                                            (:ledger-account-id gl)
                                            :balance-type-default
                                            currency
                                            :balance-status-posted)
        actual (- (:credit balance 0) (:debit balance 0))]
    (is (= expected actual)
        (str "GL " (name gl-account-code) " balance for " model-bank))
    ctx))

(defn- interest-payable-net
  "The credit-positive `default / posted` net of the bank's 2400 row in
  `currency`. Resolved from the chart by code and currency rather than
  by role alone, so a multi-currency bank reconciles against the row
  the accrual actually posted to."
  [txn bank-id currency]
  (error/let-nom>
    [accounts (ledger-accounts/list-accounts txn bank-id)
     payable (or (first (filter (fn [account]
                                  (and (= :gl-account-code-interest-payable
                                          (:gl-account-code account))
                                       (= currency (:currency account))))
                                accounts))
                 (error/fail
                  :scenario/no-interest-payable-account
                  {:message
                   "Bank has no 2400 interest-payable account in this currency"
                   :bank-id bank-id
                   :currency currency}))
     balances (balances-query/get-balances txn
                                           bank-id
                                           (:ledger-account-id payable))]
    (:value (:posted-balance balances))))

(defn- accrued-total
  "Sigma of the customer `interest-accrued / posted` buckets in
  `currency` across the bank's cash accounts, credit-positive like the
  control it reconciles to."
  [txn bank-id currency]
  (invariants/reduce-cash-accounts
   txn
   bank-id
   (fn [total account]
     (reduce (fn [total balance]
               (if (and (= :balance-type-interest-accrued
                           (:balance-type balance))
                        (= :balance-status-posted (:balance-status balance))
                        (= currency (:currency balance)))
                 (+ total (- (:credit balance 0) (:debit balance 0)))
                 total))
             total
             (:balances account)))
   0))

(defmethod dispatch :assert-interest-reconciliation
  [{:keys [bank banks] :as ctx} {[model-bank currency] :args}]
  (let [{bank-real-id :real-id} (get banks model-bank)
        totals
        (fdb/transact
         bank
         (fn [txn]
           (error/let-nom> [payable
                            (interest-payable-net txn bank-real-id currency)
                            accrued (accrued-total txn bank-real-id currency)]
             {:payable payable :accrued accrued}))
         :scenario/interest-reconciliation
         "Failed to read the interest reconciliation snapshot")]
    (if (error/anomaly? totals)
      (is (not (error/anomaly? totals))
          (str "interest reconciliation must be readable — bank "
               model-bank
               " "
               currency
               " ("
               (pr-str totals)
               ")"))
      (is (= (:accrued totals) (:payable totals))
          (str "interest payable must hold the accrued roll-up — bank "
               model-bank
               " "
               currency
               " (accrued "
               (:accrued totals)
               " / 2400 "
               (:payable totals)
               ")")))
    ctx))

(defmethod dispatch :assert-interest-run
  [{:keys [bank banks] :as ctx} {[model-bank as-of-date kind expected] :args}]
  (let [{bank-real-id :real-id} (get banks model-bank)
        progress (interest/run-progress bank bank-real-id as-of-date kind)
        actual (select-keys progress (keys expected))]
    (is (= expected actual)
        (str "interest " (name kind) " run for " model-bank " on " as-of-date))
    ctx))

(defmethod dispatch :assert-outcome
  [{:keys [last-outcome] :as ctx} {[expected] :args}]
  (is (= expected last-outcome) (str "last step outcome — expected " expected))
  ctx)

(defmethod dispatch :assert-rejection-kind
  [{:keys [last-rejection-kind] :as ctx} {[expected] :args}]
  (is (= expected last-rejection-kind)
      (str "last step rejection kind — expected " expected
           " but got " last-rejection-kind))
  ctx)

(defmethod dispatch :assert-no-anomaly
  [{:keys [outcomes] :as ctx} _command]
  (is (every? (fn [o] (= :succeeded o)) outcomes)
      (str "expected no anomalies; outcomes were " outcomes))
  ctx)

(defmethod dispatch :create-migration
  [{:keys [bank banks products counter] :as ctx}
   {[model-bank model-source model-target] :args}]
  (let [{bank-real-id :real-id} (get banks model-bank)
        source (get products model-source)
        target (get products model-target)
        {target-version-id :real-id} (latest-version target)
        result (migrations/create-migration
                bank
                {:bank-id bank-real-id
                 :name (str "Migration " counter)
                 :source-product-id (:real-id source)
                 :target-product-id (:real-id target)
                 :target-version-id target-version-id})]
    (-> ctx
        (cond-> (not (error/anomaly? result))
                (assoc-in [:migrations model-source]
                 {:real-id (:migration-id result)
                  :bank model-bank}))
        (update :counter inc)
        (track result))))

(defmethod dispatch :preview-migration
  [{:keys [bank banks migrations] :as ctx} {[model-bank model-source] :args}]
  (let [{bank-real-id :real-id} (get banks model-bank)
        {migration-real-id :real-id} (get migrations model-source)
        result (migrations/preview-migration bank
                                             bank-real-id
                                             migration-real-id
                                             (utility/today))]
    (-> ctx
        (cond-> (not (error/anomaly? result))
                (assoc :last-preview result))
        (track result))))

(defmethod dispatch :assert-migration-preview
  [{:keys [last-preview] :as ctx} {[expected] :args}]
  (is (= expected (select-keys last-preview (keys expected)))
      (str "migration preview — expected " expected))
  ctx)

;; Counts the preview's per-account verdicts by outcome, falling back to
;; the ineligibility reason where there is one, so a scenario can assert
;; why accounts were left rather than only how many.
(defmethod dispatch :assert-migration-verdicts
  [{:keys [bank banks last-preview] :as ctx} {[model-bank expected] :args}]
  (let [{bank-real-id :real-id} (get banks model-bank)
        rows
        (migrations/list-run-accounts bank bank-real-id (:run-id last-preview))
        ;; An ineligible verdict is counted by its reason, which is
        ;; the useful grouping; anything else by its outcome.
        actual (frequencies (map (fn [r] (or (:ineligibility r) (:outcome r)))
                                 rows))]
    (is (= expected (select-keys actual (keys expected)))
        (str "migration verdicts for " model-bank " — expected " expected))
    ctx))
