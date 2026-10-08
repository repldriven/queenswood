(ns com.repldriven.queenswood.test-scenarios.verbs
  (:require
    [com.repldriven.queenswood.test-scenarios.await :as await]
    [com.repldriven.queenswood.test-scenarios.id-mapping :as id-mapping]
    [com.repldriven.queenswood.test-scenarios.invariants :as invariants]
    [com.repldriven.queenswood.test-scenarios.observer :as observer]
    [com.repldriven.queenswood.test-scenarios.verification :as verification]

    [com.repldriven.queenswood.balance-query.interface :as balances-query]
    [com.repldriven.queenswood.balance.interface :as balances]
    [com.repldriven.queenswood.bank.interface :as banks]
    [com.repldriven.queenswood.cash-account-product.interface :as products]
    [com.repldriven.queenswood.cash-account-query.interface :as
     cash-accounts-query]
    [com.repldriven.queenswood.cash-account.interface :as cash-accounts]
    [com.repldriven.queenswood.circuit-breaker.interface :as circuit-breaker]
    ;; nosemgrep: fdb-outside-store — seeds state below the interfaces
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.interest.interface :as interest]
    [com.repldriven.queenswood.ledger-account.interface :as ledger-accounts]
    [com.repldriven.queenswood.party-query.interface :as party-query]
    [com.repldriven.queenswood.party.interface :as party]
    [com.repldriven.queenswood.payment-query.interface :as payment-query]
    [com.repldriven.queenswood.payment.interface :as payment]
    [com.repldriven.queenswood.policy.interface :as policy]
    [com.repldriven.queenswood.scheduler.interface :as scheduler]
    [com.repldriven.queenswood.test-projections.interface :as projections]
    [com.repldriven.queenswood.transaction.interface :as transactions]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.event.interface :as event]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.test :refer [is]]))

(defn- tag-leg-product-type
  "Stamp a customer leg with its cash-account's `:product-type` so
  `ledger-accounts/ensure-controls` can check the control it rolls into.
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
  and apply its legs to balances, after checking the control each
  customer sub-ledger leg rolls into, in the same FDB transaction."
  [bank bank-id tx-data]
  (fdb/transact
   bank
   (fn [txn]
     (let [tagged (mapv #(tag-leg-product-type txn bank-id %) (:legs tx-data))
           checked (ledger-accounts/ensure-controls txn
                                                    bank-id
                                                    (:currency tx-data)
                                                    tagged)]
       (if (error/anomaly? checked)
         checked
         (error/let-nom>
           [r (transactions/record-transaction
               txn
               (assoc tx-data :bank-id bank-id :legs checked))
            stored (ledger-accounts/stored-legs txn
                                                bank-id
                                                (:currency tx-data)
                                                (:legs r))]
           (balances/apply-legs txn bank-id stored (:transaction-type r))))))))

(defn- fund-at-provider
  "Credit the provider account behind `bban` as the scheme the rig plays
  would have, once per scheme transaction, so the provider holds what
  the ledger does. The simulator is told nothing it notifies anyone of."
  [{:keys [bank funded] :as ctx} bban amount scheme-transaction-id result]
  (let [url (:payment-simulator-url bank)]
    (if (or (nil? url)
            (error/anomaly? result)
            (contains? funded scheme-transaction-id))
      ctx
      (do (http/request {:method :post
                         :url (str url "/simulate/fund")
                         :headers {"Content-Type" "application/json"}
                         :body (json/write-str
                                {:bban bban
                                 :amount (.movePointLeft (BigDecimal/valueOf
                                                          (long amount))
                                                         2)})})
          (update ctx :funded (fnil conj #{}) scheme-transaction-id)))))

(defn- await-status
  "The account once it reaches `status`, or `:scenario/timed-out`. An
  account opens and closes once its event has asked the payment provider
  and the provider's answer has come back through the adapter's outbox."
  [{:keys [bank] :as ctx} bank-real-id real-acct-id status]
  (await/value ctx
               (str "account " real-acct-id " to reach " (name status))
               (fn []
                 (cash-accounts-query/find-account bank
                                                   bank-real-id
                                                   real-acct-id))
               (fn [account]
                 (and (not (error/anomaly? account))
                      (= status (:account-status account))))))

(defn- await-opened
  [ctx bank-real-id real-acct-id]
  (await-status ctx bank-real-id real-acct-id :cash-account-status-opened))

(defn- await-close-answered
  "The account once the payment provider has answered its close: closed,
  or back where it closed from where the provider refused."
  [{:keys [bank] :as ctx} bank-real-id real-acct-id]
  (await/value ctx
               (str "account " real-acct-id " to leave closing")
               (fn []
                 (cash-accounts-query/find-account bank
                                                   bank-real-id
                                                   real-acct-id))
               (fn [account]
                 (and (not (error/anomaly? account))
                      (not= :cash-account-status-closing
                            (:account-status account))))))

(defn- await-party-active
  [{:keys [bank] :as ctx} bank-real-id party-id]
  (await/value ctx
               (str "party " party-id " to become active")
               (fn [] (party-query/get-party bank bank-real-id party-id))
               (fn [party]
                 (and (not (error/anomaly? party))
                      (= :party-status-active (:status party))))))

(defn- await-outbound
  "The outbound payment once it reaches `status`, or
  `:scenario/timed-out`."
  [{:keys [bank] :as ctx} payment-id status]
  (await/value ctx
               (str "outbound payment " payment-id " to reach " status)
               (fn [] (payment-query/get-outbound-payment bank payment-id))
               (fn [payment]
                 (and (not (error/anomaly? payment))
                      (= status (:payment-status payment))))))

(defn- await-outbound-completed
  "The outbound payment once the provider's settlement has completed it,
  or `:scenario/timed-out`."
  [ctx payment-id]
  (await-outbound ctx payment-id :outbound-payment-status-completed))

(defn- posted-net
  [bank bank-real-id account-id]
  (let [b (balances-query/get-balance bank
                                      bank-real-id
                                      account-id
                                      :balance-type-default
                                      :balance-status-posted)]
    (when-not (error/anomaly? b) (- (:credit b 0) (:debit b 0)))))

(defn- await-credit
  "The creditor's posted net once it reaches `target`, or
  `:scenario/timed-out`. A payment to an internal creditor settles in two
  provider events, the debit completing the payment and the credit
  landing on the creditor."
  [{:keys [bank] :as ctx} bank-real-id account-id target]
  (await/value ctx
               (str "account " account-id " to reach " target)
               (fn [] (posted-net bank bank-real-id account-id))
               (fn [net] (and net (>= net target)))))

(defn- track
  [ctx result]
  (cond
   (await/timed-out? result)
   (-> ctx
       (assoc :last-outcome :timed-out)
       (assoc :last-rejection-kind nil)
       (update :outcomes (fnil conj []) :timed-out)
       (update :runner-errors (fnil conj []) (error/payload result)))

   (error/anomaly? result)
   (-> ctx
       (assoc :last-outcome :denied)
       (assoc :last-rejection-kind (error/kind result))
       (update :outcomes (fnil conj []) :denied))

   :else
   (-> ctx
       (assoc :last-outcome :succeeded)
       (assoc :last-rejection-kind nil)
       (update :outcomes (fnil conj []) :succeeded))))

(defn- first-timed-out
  "The first of `waits` that timed out, else `result`."
  [result & waits]
  (or (first (filter await/timed-out? waits)) result))

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

(defn- end-to-end-id
  "A keyword names an end-to-end id unique to this run, so a scenario can
  refer to one it made up; a string is taken as it is."
  [{:keys [run-id]} e2e-ref]
  (if (keyword? e2e-ref) (str "scen-e2e-" run-id "-" (name e2e-ref)) e2e-ref))

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

(def ^:private idv-provider
  "The identity provider declaration the scenario banks are created
  against, establishing everything the platform policy requires."
  {:verifies ["identity" "liveness" "claimed-identity" "address"]
   :screens ["sanctions" "pep"]})

(def
  ^{:doc
    "The tier every scenario bank is created on, whose policy the
  model binds each bank to as reality does."}
  bank-tier
  "test-scenario")

(defmulti dispatch (fn [_ctx command] (:command command)))

(defn dispatched
  []
  (set (keys (methods dispatch))))

(def ^:private scenario-operator
  {:kind :actor-kind-operator :principal-id "test-scenarios"})

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
                               bank-tier
                               ["GBP"]
                               {:identity-provider identity-provider
                                :idv-provider idv-provider
                                :audience "queenswood-api-test"
                                :actor scenario-operator
                                :idempotency-key (str (utility/uuidv7))})
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
                                             :product-type-sub-ledger-current)
                            {:actor scenario-operator}))
        scenario-product-id (:product-id scenario-product)
        scenario-version-id (:version-id scenario-product)
        _ (when (and scenario-product-id
                     (not (error/anomaly? scenario-product)))
            (products/publish bank
                              real-bank-id
                              scenario-product-id
                              scenario-version-id
                              {:actor scenario-operator}))
        scenario-account (when scenario-product-id
                           (cash-accounts/new-account
                            bank
                            {:bank-id real-bank-id
                             :party-id real-party-id
                             :product-id scenario-product-id
                             :currency "GBP"
                             :name "Scenario Account"}))
        real-acct-id (:account-id scenario-account)
        opened (when real-acct-id (await-opened ctx real-bank-id real-acct-id))
        real-bban (:bban opened)]
    (-> ctx
        (cond-> real-acct-id
                (-> (assoc :id-mapping
                           (id-mapping/add id-mapping model-acct real-acct-id))
                    (assoc-in [:accounts model-acct] {:bank model-bank})))
        (assoc-in [:banks model-bank] {:real-id real-bank-id :currency "GBP"})
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
        (track (first-timed-out (or scenario-account result) opened)))))

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
        result (products/new-product bank
                                     real-id
                                     (product-payload name kind extras)
                                     {:actor scenario-operator})]
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

(defmethod dispatch :publish-product
  [{:keys [bank banks products] :as ctx} {[model-prod] :args}]
  (let [product (get products model-prod)
        {model-bank :bank :keys [real-id]} product
        {version-real-id :real-id} (latest-version product)
        bank-real-id (get-in banks [model-bank :real-id])
        result (products/publish bank
                                 bank-real-id
                                 real-id
                                 version-real-id
                                 {:actor scenario-operator})]
    (-> ctx
        (cond-> (not (error/anomaly? result))
                (update-latest-version model-prod
                                       (fn [v] (assoc v :status :published))))
        (update :counter inc)
        (track result))))

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
                                                          next-number))
                                    {:actor scenario-operator})]
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
        result (products/discard-draft bank
                                       bank-real-id
                                       real-id
                                       version-real-id
                                       {:actor scenario-operator})]
    (-> ctx
        (cond-> (not (error/anomaly? result))
                (update-latest-version model-prod
                                       (fn [v] (assoc v :status :discarded))))
        (update :counter inc)
        (track result))))

(defmethod dispatch :create-person-party
  [{:keys [bank counter next-party-id banks] :as ctx}
   {[model-bank reference-marker] :args}]
  (let [model-party (model-id-for-next-party next-party-id)
        {bank-real-id :real-id} (get banks model-bank)
        payload (cond-> {:bank-id bank-real-id
                         :type :party-type-person
                         :display-name (str "Scenario Person " counter)
                         :given-name "Scenario"
                         :family-name (str "Person" counter)}

                        reference-marker
                        (assoc :external-reference (name reference-marker)))
        result (party/new-party bank payload)
        ;; Reality: the party's IDV waits for a session; the person
        ;; shows a matching document through it and the party
        ;; activates. Wait until reality catches up to the model before
        ;; the next verb fires.
        result'
        (if (error/anomaly? result)
          result
          (let [q (error/let-nom> [_ (verification/verify ctx
                                                          bank-real-id
                                                          (:party-id result)
                                                          payload)]
                    (await-party-active ctx bank-real-id (:party-id result)))]
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

(defmethod dispatch :open-account
  [{:keys [bank counter next-model-id id-mapping banks products parties]
    :as ctx} {[model-bank model-party model-prod] :args}]
  (let [model-acct (model-id-for-next-account next-model-id)
        {bank-real-id :real-id :keys [currency]} (get banks model-bank)
        {prod-real-id :real-id} (get products model-prod)
        {party-real-id :real-id} (get parties model-party)
        result (cash-accounts/new-account bank
                                          {:bank-id bank-real-id
                                           :party-id party-real-id
                                           :product-id prod-real-id
                                           :currency currency
                                           :name (str "Scenario Account "
                                                      counter)})
        real-acct-id (:account-id result)
        opened (when real-acct-id (await-opened ctx bank-real-id real-acct-id))]
    (-> ctx
        (cond-> real-acct-id
                (-> (assoc :id-mapping
                           (id-mapping/add id-mapping model-acct real-acct-id))
                    (assoc-in [:accounts model-acct]
                              {:bank model-bank :bban (:bban opened)})))
        (update :next-model-id inc)
        (update :counter inc)
        (track (first-timed-out result opened)))))

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
        {bank-real-id :real-id :keys [currency]} (get banks model-bank)
        prod-result (when create-prod?
                      (products/new-product bank
                                            bank-real-id
                                            (product-payload
                                             (str "Scenario Current Product "
                                                  counter)
                                             :product-type-sub-ledger-current)
                                            {:actor scenario-operator}))
        _
        (when (and create-prod? prod-result (not (error/anomaly? prod-result)))
          (products/publish bank
                            bank-real-id
                            (:product-id prod-result)
                            (:version-id prod-result)
                            {:actor scenario-operator}))
        prod-real-id (or (get-in products [prod-model-id :real-id])
                         (:product-id prod-result))
        ;; Onboard the person party (mirror of :create-person-party).
        model-party (model-id-for-next-party next-party-id)
        party-payload {:bank-id bank-real-id
                       :type :party-type-person
                       :display-name (str "Scenario Customer " counter)
                       :given-name "Scenario"
                       :family-name (str "Customer" counter)}
        party-result (party/new-party bank party-payload)
        party-result (if (error/anomaly? party-result)
                       party-result
                       (let [q (error/let-nom> [_ (verification/verify
                                                   ctx
                                                   bank-real-id
                                                   (:party-id party-result)
                                                   party-payload)]
                                 (await-party-active ctx
                                                     bank-real-id
                                                     (:party-id party-result)))]
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
                        :name (str "Scenario Customer Account " counter)}))
        real-acct-id (:account-id acct-result)
        opened (when real-acct-id (await-opened ctx bank-real-id real-acct-id))
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
                              {:bank model-bank :bban (:bban opened)})
                    (update :next-model-id inc)))
        (update :next-party-id inc)
        (update :counter inc)
        (track (first-timed-out outcome opened)))))

(defmethod dispatch :close-account
  [{:keys [bank id-mapping accounts banks] :as ctx} {[model-acct] :args}]
  (let [model-bank (get-in accounts [model-acct :bank])
        bank-real-id (get-in banks [model-bank :real-id])
        real-acct-id (get-in id-mapping [:model->real model-acct])
        result (cash-accounts/close-account bank
                                            {:bank-id bank-real-id
                                             :account-id real-acct-id})
        answered (when-not (error/anomaly? result)
                   (await-close-answered ctx bank-real-id real-acct-id))]
    (-> ctx
        (update :counter inc)
        (track (first-timed-out result answered)))))

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
   {[model-acct amount e2e-ref] :args}]
  (let [bban (get-in accounts [model-acct :bban])
        marker (keyword (str "in-" next-inbound-id))
        stx-id (if e2e-ref
                 (end-to-end-id ctx e2e-ref)
                 (str "scen-in-" run-id "-" (name marker)))
        result (payment/settle-inbound
                bank
                {:scheme-transaction-id stx-id
                 :end-to-end-id stx-id
                 :scheme "fps"
                 :debit-credit-code :debit-credit-code-credit
                 :amount amount
                 :currency "GBP"
                 :creditor-bban bban
                 :debtor-name "Scenario Funder"
                 :reference (str "scenario inbound " (name marker))
                 :timestamp-settled (utility/now)})]
    (-> ctx
        (fund-at-provider bban amount stx-id result)
        (assoc-in [:inbound-stx marker] stx-id)
        (update :next-inbound-id inc)
        (update :counter inc)
        (track result))))

(defmethod dispatch :admit-inbound
  [{:keys [bank accounts] :as ctx} {[model-acct amount e2e-ref] :args}]
  (let [bban (if (string? model-acct)
               model-acct
               (get-in accounts [model-acct :bban]))
        result (payment/admit-inbound bank
                                      {:end-to-end-id (end-to-end-id ctx
                                                                     e2e-ref)
                                       :scheme "fps"
                                       :creditor-bban bban
                                       :amount amount
                                       :currency "GBP"
                                       :debtor-name "Scenario Admitted Sender"
                                       :reference "scenario admission"})]
    (-> ctx
        (assoc :last-admission result)
        (update :counter inc)
        (track result))))

(defmethod dispatch :assert-admission
  [{:keys [last-admission] :as ctx} {[admitted reason-code] :args}]
  (is (= {:admitted admitted :reason-code reason-code}
         (select-keys (merge {:reason-code nil} last-admission)
                      [:admitted :reason-code]))
      "the last admission's decision")
  ctx)

;; Held-inbound lifecycle — reality-only verbs (no model counterpart). The
;; model-eq runner stops tracking after the first of these but still runs the
;; scenario's explicit `:assert-balance` calls.

(defmethod dispatch :hold-inbound
  [{:keys [bank accounts next-inbound-id run-id] :as ctx}
   {[model-acct amount e2e-ref] :args}]
  (let [bban (get-in accounts [model-acct :bban])
        e2e (if e2e-ref
              (end-to-end-id ctx e2e-ref)
              (str "scen-held-" run-id "-" next-inbound-id))
        result (payment/hold-inbound bank
                                     {:end-to-end-id e2e
                                      :scheme "fps"
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
        stx-id (str "scen-rel-" run-id "-" counter)
        result (payment/settle-inbound bank
                                       {:scheme-transaction-id stx-id
                                        :end-to-end-id e2e
                                        :scheme "fps"
                                        :debit-credit-code
                                        :debit-credit-code-credit
                                        :amount amount
                                        :currency "GBP"
                                        :creditor-bban bban
                                        :debtor-name "Scenario Held Sender"
                                        :timestamp-settled (utility/now)})]
    (-> ctx
        (fund-at-provider bban amount stx-id result)
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

;; Closes a bank's own ledger account, which no route or command does, so
;; a scenario can meet a closed control on a production path.
(defmethod dispatch :close-ledger-account
  [{:keys [bank banks] :as ctx} {[model-bank gl-account-code] :args}]
  (let [{bank-real-id :real-id} (get banks model-bank)
        result (error/let-nom> [account (gl-account-for bank
                                                        bank-real-id
                                                        gl-account-code
                                                        "GBP")]
                 (ledger-accounts/close-account bank
                                                bank-real-id
                                                (:ledger-account-id account)))]
    (-> ctx
        (update :counter inc)
        (track result))))

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

(def ^:private external-creditor-bban
  "A creditor BBAN no bank in the run holds, which the simulator settles."
  "040004000000001")

(def ^:private refused-creditor-bban
  "A creditor BBAN under sort code 999998, which the simulator refuses at
  submission, so the payment is rejected rather than settled."
  "99999800000001")

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
              [a amt external-creditor-bban
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
        creditor-pre-net (when creditor-real-id
                           (posted-net bank bank-real-id creditor-real-id))
        result (payment/submit-outbound
                bank
                {:idempotency-key (str "scen-pay-" run-id "-" counter)
                 :bank-id bank-real-id
                 :debtor-account-id real-acct-id
                 :scheme "fps"
                 :currency "GBP"
                 :amount amount
                 :reference (str "scenario payment " counter)
                 :creditor-bban creditor-bban
                 :creditor-name creditor-name})
        real-pmt-id (:payment-id result)
        ;; The model completes the payment at once, so the step waits for
        ;; the provider's debit to complete it and, paying a known
        ;; account, for its credit to land there too.
        completed (when real-pmt-id (await-outbound-completed ctx real-pmt-id))
        credited (when (and real-pmt-id
                            creditor-real-id
                            creditor-pre-net
                            (not (error/anomaly? completed)))
                   (await-credit ctx
                                 bank-real-id
                                 creditor-real-id
                                 (+ creditor-pre-net amount)))]
    (-> ctx
        (cond-> real-pmt-id
                (assoc-in [:payments model-pmt] {:real-id real-pmt-id}))
        (cond-> real-pmt-id (update :next-payment-id inc))
        (update :counter inc)
        (track (first-timed-out result completed credited)))))

(defn- submit-external-outbound
  [{:keys [bank counter id-mapping banks accounts run-id]} model-acct amount
   creditor-bban]
  (let [model-bank (get-in accounts [model-acct :bank])]
    (payment/submit-outbound
     bank
     {:idempotency-key (str "scen-pay-" run-id "-" counter)
      :bank-id (get-in banks [model-bank :real-id])
      :debtor-account-id (id-mapping/real id-mapping model-acct)
      :scheme "fps"
      :currency "GBP"
      :amount amount
      :reference (str "scenario payment " counter)
      :creditor-bban creditor-bban
      :creditor-name (str "Scenario External Creditor " counter)})))

(defn- record-payment
  [{:keys [next-payment-id] :as ctx} result]
  (let [real-pmt-id (:payment-id result)]
    (cond-> ctx
            real-pmt-id
            (-> (assoc-in [:payments
                           (model-id-for-next-payment next-payment-id)]
                          {:real-id real-pmt-id})
                (update :next-payment-id inc)))))

(defmethod dispatch :outbound-payment-redelivered
  [ctx {[model-acct amount] :args}]
  (let [first-result
        (submit-external-outbound ctx model-acct amount external-creditor-bban)
        result (if (error/anomaly? first-result)
                 first-result
                 (submit-external-outbound ctx
                                           model-acct
                                           amount
                                           external-creditor-bban))
        completed (when-let [payment-id (:payment-id result)]
                    (await-outbound-completed ctx payment-id))]
    (-> ctx
        (record-payment result)
        (update :counter inc)
        (track (first-timed-out result completed)))))

(defmethod dispatch :outbound-payment-refused
  [ctx {[model-acct amount] :args}]
  (let [result
        (submit-external-outbound ctx model-acct amount refused-creditor-bban)
        failed
        (when-let [payment-id (:payment-id result)]
          (await-outbound ctx payment-id :outbound-payment-status-failed))]
    (-> ctx
        (record-payment result)
        (update :counter inc)
        (track (first-timed-out result failed)))))

(defmethod dispatch :provider-outage
  [{:keys [bank] :as ctx} {[down?] :args}]
  (let [res (http/request {:method :post
                           :url (str (:payment-simulator-url bank)
                                     "/simulate/outage")
                           :headers {"Content-Type" "application/json"}
                           :body (json/write-str {:down down?})})
        result (if (and (not (error/anomaly? res)) (= 204 (:status res)))
                 res
                 (error/fail :scenario/provider-outage
                             {:message "The simulator refused the outage"
                              :status (:status res)}))]
    (-> ctx
        (update :counter inc)
        (track result))))

(defmethod dispatch :outbound-payment-submitted
  [ctx {[model-acct amount] :args}]
  (let [result
        (submit-external-outbound ctx model-acct amount external-creditor-bban)]
    (-> ctx
        (record-payment result)
        (update :counter inc)
        (track result))))

(defmethod dispatch :reject-outbound-payment
  [{:keys [bank payments] :as ctx} {[model-pmt] :args}]
  (let [real-pmt-id (get-in payments [model-pmt :real-id])
        result (payment/reject-outbound bank
                                        {:end-to-end-id real-pmt-id
                                         :scheme "fps"
                                         :debit-credit-code
                                         :debit-credit-code-debit
                                         :cancellation-code "SCENARIO_REJECTED"
                                         :timestamp-rejected (utility/now)})]
    (-> ctx
        (update :counter inc)
        (track result))))

(defmethod dispatch :return-outbound-payment
  [{:keys [bank payments] :as ctx} {[model-pmt] :args}]
  (let [real-pmt-id (get-in payments [model-pmt :real-id])
        res (http/request {:method :post
                           :url (str (:payment-simulator-url bank)
                                     "/simulate/outbound-return")
                           :headers {"Content-Type" "application/json"}
                           :body (json/write-str {:end-to-end-id real-pmt-id
                                                  :reason-code "AC04"})})
        result (if (and (not (error/anomaly? res)) (= 202 (:status res)))
                 (await/value
                  ctx
                  (str "outbound payment " real-pmt-id " to be returned")
                  (fn [] (payment-query/get-outbound-payment bank real-pmt-id))
                  (fn [p]
                    (= :outbound-payment-status-returned (:payment-status p))))
                 (error/fail :scenario/return-outbound
                             {:message "The simulator refused the return"
                              :payment-id real-pmt-id
                              :status (:status res)}))]
    (-> ctx
        (update :counter inc)
        (track result))))

(defmethod dispatch :return-outbound-event
  [{:keys [bank payments] :as ctx} {[model-pmt amount] :args}]
  (let [real-pmt-id (get-in payments [model-pmt :real-id])
        result (payment/return-outbound
                bank
                {:end-to-end-id real-pmt-id
                 :scheme "fps"
                 :debit-credit-code :debit-credit-code-debit
                 :scheme-transaction-id (str "scen-ret-" real-pmt-id)
                 :amount amount
                 :currency "GBP"
                 :reason-code "AC04"
                 :timestamp-returned (utility/now)})]
    (-> ctx
        (update :counter inc)
        (track result))))

(defmethod dispatch :settle-inbound-event
  [{:keys [bank accounts] :as ctx} {[model-acct amount stx-id] :args}]
  (let [bban (get-in accounts [model-acct :bban])
        result (payment/settle-inbound
                bank
                {:scheme-transaction-id stx-id
                 :end-to-end-id stx-id
                 :scheme "fps"
                 :debit-credit-code :debit-credit-code-credit
                 :amount amount
                 :currency "GBP"
                 :creditor-bban bban
                 :debtor-name "Scenario Funder"
                 :reference (str "scenario inbound " stx-id)
                 :timestamp-settled (utility/now)})]
    (-> ctx
        (fund-at-provider bban amount stx-id result)
        (update :counter inc)
        (track result))))

(defmethod dispatch :settle-outbound-event
  [{:keys [bank counter payments run-id] :as ctx} {[model-pmt] :args}]
  (let [real-pmt-id (get-in payments [model-pmt :real-id])
        result (payment/settle-outbound
                bank
                {:scheme-transaction-id (str "scen-stl-" run-id "-" counter)
                 :end-to-end-id real-pmt-id
                 :scheme "fps"
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

(defmethod dispatch :publish-scheme-event
  [{:keys [bank] :as ctx} {[event-name data] :args}]
  (let [{:keys [bus schemas]} bank
        data
        (update data :end-to-end-id (fn [e2e-ref] (end-to-end-id ctx e2e-ref)))
        result (error/let-nom> [payload (avro/serialize (get schemas event-name)
                                                        data)]
                 (event/publish
                  bus
                  (assoc (event/envelope event-name nil (str (utility/uuidv7)))
                         :payload
                         payload)
                  {:event-channel :schemes-payments-event}))]
    (-> ctx
        (update :counter inc)
        (track result))))

(defmethod dispatch :fixture/apply-fee
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

(defmethod dispatch :update-product-draft
  [{:keys [bank banks products] :as ctx} {[model-prod data] :args}]
  (let [product (get products model-prod)
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
                (update-latest-version model-prod
                                       (fn [v]
                                         (assoc v
                                                :effective-from
                                                (:effective-from result)
                                                :effective-to
                                                (:effective-to result)))))
        (update :counter inc)
        (track result))))

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

(defmethod dispatch :fixture/fund-house
  [{:keys [bank banks run-id counter] :as ctx} {[model-bank amount] :args}]
  ;; The bank's own money arriving from outside the scheme, as the
  ;; sandbox's simulated inbound posts it: 1100 up and the house account
  ;; credited. Mirroring credits the house's provider account from outside.
  (let [{bank-real-id :real-id} (get banks model-bank)
        cash (gl-account-for bank
                             bank-real-id
                             :gl-account-code-cash-at-correspondent
                             "GBP")
        house (cash-accounts-query/house-account bank bank-real-id "GBP")
        result (if (error/anomaly? house)
                 house
                 (record-and-apply
                  bank
                  bank-real-id
                  (transfer-tx
                   {:transaction-type :transaction-type-inbound-transfer
                    :idempotency-key (str "scen-fund-house-" run-id "-" counter)
                    :reference "Scenario own funds"
                    :gl-leg {:account-id (:ledger-account-id cash)
                             :balance-type :balance-type-default
                             :balance-status :balance-status-posted
                             :side :leg-side-debit
                             :amount amount}
                    :customer-leg {:account-id (:account-id house)
                                   :balance-type :balance-type-default
                                   :balance-status :balance-status-posted
                                   :side :leg-side-credit
                                   :amount amount}})))]
    (-> ctx
        (update :counter inc)
        (track result))))

(defn- simulated-balances
  "Each provider account the simulator holds, by id, in minor units."
  [bank]
  (let [res (http/request {:method :get
                           :url (str (:payment-simulator-url bank)
                                     "/simulate/balances")})]
    (into {}
          (map (fn [{:keys [id balance]}]
                 [id
                  (.longValueExact (.movePointRight (BigDecimal. ^String
                                                                 balance)
                                                    2))]))
          (:accounts (http/res->edn res)))))

(defn- posted
  [bank bank-id account-id]
  (let [balance (balances-query/get-balance bank
                                            bank-id
                                            account-id
                                            :balance-type-default
                                            :balance-status-posted)]
    (if (error/anomaly? balance) 0 (- (:credit balance 0) (:debit balance 0)))))

(defn- provider-check
  "What the provider holds beside what the ledger does, for the bank's
  accounts in the run: each customer account's provider balance against
  its posted balance, and every provider balance of the bank together
  against 1100 cash at correspondent, which is the money the scheme
  moved. The house account's provider balance is the difference: its
  own posted balance and the money the ledger keeps in the bank's GL
  accounts."
  [{:keys [bank banks accounts id-mapping]} model-bank]
  (let [{bank-real-id :real-id} (get banks model-bank)
        real-ids (keep (fn [[model-id {:keys [bank]}]]
                         (when (= model-bank bank)
                           (id-mapping/real id-mapping model-id)))
                       accounts)
        house (cash-accounts-query/house-account bank bank-real-id "GBP")
        provider-of (fn [account-id]
                      (:provider-account-id
                       (cash-accounts-query/find-account bank
                                                         bank-real-id
                                                         account-id)))
        held (simulated-balances bank)
        cash (gl-account-for bank
                             bank-real-id
                             :gl-account-code-cash-at-correspondent
                             "GBP")]
    {:accounts (into {}
                     (map (fn [account-id]
                            [account-id
                             [(get held (provider-of account-id) 0)
                              (posted bank bank-real-id account-id)]]))
                     real-ids)
     :bank [(reduce +
                    0
                    (map (fn [id] (get held (provider-of id) 0))
                         (conj real-ids (:account-id house))))
            (- (:value
                (:posted-balance
                 (ledger-accounts/get-balances bank bank-real-id cash))))]}))

(defn- agrees?
  [{:keys [accounts bank]}]
  (and (every? (fn [[provider ledger]] (= provider ledger)) (vals accounts))
       (apply = bank)))

(defmethod dispatch :assert-provider-balances
  [ctx {[model-bank] :args}]
  (let [{check :value}
        (await/until ctx (fn [] (provider-check ctx model-bank)) agrees?)]
    (doseq [[account-id [provider ledger]] (:accounts check)]
      (is (= ledger provider) (str "provider balance of " account-id)))
    (is (apply = (:bank check))
        (str "provider balances of " model-bank " against 1100"))
    ctx))

(defmethod dispatch :assert-gl-balance
  [{:keys [bank banks] :as ctx}
   {[model-bank gl-account-code currency expected] :args}]
  (let [{bank-real-id :real-id} (get banks model-bank)
        gl (gl-account-for bank bank-real-id gl-account-code currency)
        balances (ledger-accounts/get-balances bank bank-real-id gl)
        actual (:value (:posted-balance balances))]
    (is (= expected actual)
        (str "GL " (name gl-account-code) " balance for " model-bank))
    ctx))

(defn- decode-message
  "Decode `bytes` as an envelope under `envelope-schemas`' `envelope-name`,
  then its payload by the name the envelope carries at `name-key`. Returns
  the payload with that name assoc'd at `name-key`, or an anomaly."
  [envelope-schemas schemas envelope-name name-key bytes]
  (error/let-nom>
    [envelope (avro/deserialize-same (get envelope-schemas envelope-name)
                                     bytes)
     message-name (get envelope name-key)
     data (avro/deserialize-same (get schemas message-name)
                                 (:payload envelope))]
    (assoc data name-key message-name)))

(defn- scheme-command-count
  [{:keys [bank scheme-commands envelope-schemas]} payment-id]
  (->> (observer/records scheme-commands)
       (map (fn [bytes]
              (decode-message envelope-schemas
                              (:schemas bank)
                              "command"
                              :command
                              bytes)))
       (filter (fn [command]
                 (and (= "submit-payment" (:command command))
                      (= payment-id (:end-to-end-id command)))))
       count))

(defmethod dispatch :assert-scheme-commands
  [{:keys [scheme-commands payments] :as ctx} {[model-pmt expected] :args}]
  (let [payment-id (get-in payments [model-pmt :real-id])
        {actual :value} (await/until ctx
                                     (fn []
                                       (scheme-command-count ctx payment-id))
                                     (fn [n] (>= n expected)))]
    (is (some? scheme-commands) "the runner has no scheme command observer")
    (is (= expected actual) (str "submit-payment commands for " model-pmt))
    ctx))

(defn- intent-count
  [bank dedup-key]
  (fdb/transact bank
                (fn [txn]
                  (count (fdb/query-records
                          (fdb/open txn "modulr-outbound-intents")
                          "ModulrOutboundIntent"
                          "dedup_key"
                          dedup-key
                          {:index "ModulrOutboundIntent_by_dedup_key"})))
                :scenario/intents
                "Failed to count outbound intents"))

(defmethod dispatch :assert-intents
  [{:keys [bank payments] :as ctx} {[model-pmt expected] :args}]
  (let [payment-id (get-in payments [model-pmt :real-id])
        {actual :value} (await/until
                         ctx
                         (fn [] (intent-count bank payment-id))
                         (fn [n] (or (error/anomaly? n) (>= n expected))))]
    (is (= expected actual) (str "outbound intents for " model-pmt))
    ctx))

(defmethod dispatch :assert-dead-lettered
  [{:keys [bank dead-letters envelope-schemas] :as ctx} {[e2e-ref] :args}]
  (let [e2e (end-to-end-id ctx e2e-ref)
        dead-lettered (fn []
                        (->> (observer/records dead-letters)
                             (map (fn [bytes]
                                    (decode-message envelope-schemas
                                                    (:schemas bank)
                                                    "event"
                                                    :event
                                                    bytes)))
                             (filter (fn [data] (= e2e (:end-to-end-id data))))
                             count))
        {actual :value} (await/until ctx dead-lettered pos?)]
    (is (some? dead-letters) "the runner has no dead-letter observer")
    (is
     (and (number? actual) (pos? actual))
     (str "no event for end-to-end id " e2e " reached the dead-letter topic"))
    ctx))

(def ^:private inbound-payment-statuses
  [:inbound-payment-status-settled
   :inbound-payment-status-suspended
   :inbound-payment-status-held
   :inbound-payment-status-returned
   :inbound-payment-status-admitted])

(defn- inbound-statuses
  "The statuses of the inbound payments carrying `e2e` in the run's banks,
  narrowed to those crediting `account-id` when it is non-nil. Returns a
  vector, or the first anomaly a read returns."
  [bank banks e2e account-id]
  (reduce
   (fn [statuses [bank-id status]]
     (let [payments (payment-query/list-inbound-payments bank bank-id status)]
       (if (error/anomaly? payments)
         (reduced payments)
         (into statuses
               (comp (filter (fn [p]
                               (and (= e2e (:end-to-end-id p))
                                    (or (nil? account-id)
                                        (= account-id
                                           (:creditor-account-id p))))))
                     (map :payment-status))
               payments))))
   []
   (for [{bank-id :real-id} (vals banks)
         status inbound-payment-statuses]
     [bank-id status])))

(defmethod dispatch :assert-inbound-status
  [{:keys [bank banks id-mapping] :as ctx}
   {[e2e-ref expected model-acct] :args}]
  (let [e2e (end-to-end-id ctx e2e-ref)
        account-id (when model-acct (id-mapping/real id-mapping model-acct))
        actual (inbound-statuses bank banks e2e account-id)]
    (is (= (if expected [expected] []) actual)
        (str "inbound payments for end-to-end id "
             e2e
             (when model-acct (str " crediting " model-acct))))
    ctx))

(defmethod dispatch :assert-outbound-status
  [{:keys [payments] :as ctx} {[model-pmt expected] :args}]
  (let [payment-id (get-in payments [model-pmt :real-id])
        payment (await-outbound ctx payment-id expected)]
    (is (not (await/timed-out? payment))
        (str "outbound payment status for " model-pmt " — expected " expected))
    ctx))

(defmethod dispatch :assert-breaker
  [{:keys [bank] :as ctx} {[destination expected] :args}]
  (let [breaker (await/value ctx
                             (str "breaker " destination " to be " expected)
                             (fn [] (circuit-breaker/breaker bank destination))
                             (fn [b]
                               (and (not (error/anomaly? b))
                                    (= expected (:state b "closed")))))]
    (is (not (await/timed-out? breaker))
        (str "breaker " destination " — expected " expected))
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
     balances (ledger-accounts/get-balances txn bank-id payable)]
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
     (if (= currency (:currency account))
       (reduce (fn [total balance]
                 (if (and (= :balance-type-interest-accrued
                             (:balance-type balance))
                          (= :balance-status-posted
                             (:balance-status balance)))
                   (+ total (- (:credit balance 0) (:debit balance 0)))
                   total))
               total
               (:balances account))
       total))
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
