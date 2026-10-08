(ns com.repldriven.queenswood.modulr-relay.outbound.accounts
  (:require
    [com.repldriven.queenswood.modulr-relay.outbound.shared :as shared]

    [com.repldriven.queenswood.modulr-relay.modulr :as modulr]
    [com.repldriven.queenswood.modulr-relay.store :as store]

    [com.repldriven.queenswood.intent-poller.interface :as intent-poller]

    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.utility.interface :as utility]))

(defn- scan
  [{:keys [id identifiers]}]
  (let [{:keys [sortCode accountNumber]}
        (or (some (fn [i] (when (= "SCAN" (:type i)) i)) identifiers)
            (first identifiers))]
    {:id id
     :addresses [{:scheme "scan"
                  :sort-code sortCode
                  :account-number accountNumber}]}))

(defn- account-event
  [intent event-name data]
  {:event-name event-name
   :dedup-key (str (:idempotency-key intent) ":" event-name)
   :data data})

(defn- open-body
  [intent currency]
  (let [{:keys! [account-id]} (shared/context intent)]
    (utility/assoc-some {:externalReference (modulr/->reference account-id)}
                        :currency
                        currency
                        :productCode
                        (not-empty (get (json/read-str (:request intent))
                                        "productCode")))))

(defn- open-path
  [config]
  (str "/customers/" (:customer-id config) "/accounts"))

(defn- account-refused
  [intent reason]
  (let [{:keys! [bank-id account-id]} (shared/context intent)]
    (account-event intent
                   "payment-account-refused"
                   {:bank-id bank-id :account-id account-id :reason reason})))

(defn- held
  "Settled with `event`, recording in the same transaction that
  `account-id`'s money is now held in `provider-account-id`."
  [account-id provider-account-id event]
  {:status :outbound-intent-status-settled
   :event event
   :also (fn [txn] (store/hold txn account-id provider-account-id))})

(defn- open
  [config _now intent]
  (shared/answer (shared/call config
                              intent
                              {:method :post
                               :path (open-path config)
                               :raw-body (:request intent)})))

(defn- opened
  [_config _now intent result]
  (let [{:keys! [bank-id account-id]} (shared/context intent)
        {:keys [id addresses]} (scan result)]
    (held account-id
          id
          (account-event intent
                         "payment-account-opened"
                         {:bank-id bank-id
                          :account-id account-id
                          :provider-account-id id
                          :addresses addresses}))))

(defn- open-failed
  [_config _now intent failure reason]
  {:status :outbound-intent-status-failed
   :event (account-refused intent (shared/undelivered failure reason))})

(defn- close-refused
  [intent reason]
  (let [{:keys! [bank-id account-id]} (shared/context intent)]
    (account-event intent
                   "payment-account-close-refused"
                   {:bank-id bank-id :account-id account-id :reason reason})))

(defn- close
  [config _now intent]
  (let [{:keys! [account-id provider-account-id]} (shared/context intent)]
    (shared/answer (shared/call config
                                intent
                                {:method :post
                                 :path (str "/accounts/"
                                            (shared/held-at config
                                                            account-id
                                                            provider-account-id)
                                            "/close")}))))

(defn- closed
  [_config _now intent _result]
  (let [{:keys! [bank-id account-id]} (shared/context intent)]
    {:status :outbound-intent-status-settled
     :event (account-event intent
                           "payment-account-closed"
                           {:bank-id bank-id :account-id account-id})}))

(defn- close-failed
  [_config _now intent failure reason]
  {:status :outbound-intent-status-failed
   :event (close-refused intent (shared/undelivered failure reason))})

(defn- reissue-step
  "The call for the reissue's current step, and how its result advances
  the context: block the old account, read what it holds, open the new
  one, move the balance across, close the old one."
  [config intent ctx]
  (let [{:keys! [step]
         :keys [provider-account-id balance currency new-account]}
        ctx
        old provider-account-id]
    (case step
      "block"
      [{:method :post :path (str "/accounts/" old "/block")}
       (fn [_] (assoc ctx :step "read"))]

      "read"
      [{:method :get :path (str "/accounts/" old)}
       (fn [{:keys [balance currency]}]
         (assoc ctx
                :step "open"
                :balance (str balance)
                :currency currency))]

      "open"
      [{:method :post
        :path (open-path config)
        :body (open-body intent currency)}
       (fn [result] (assoc ctx :step "move" :new-account (scan result)))]

      "move"
      (if (pos? (.signum (bigdec (or (not-empty balance) "0"))))
        [{:method :post
          :path "/payments"
          :body {:sourceAccountId old
                 :destination {:type "ACCOUNT" :id (:id new-account)}
                 :amount (bigdec balance)
                 :currency currency
                 :reference "Address reissue"
                 :externalReference (str "move-" (:intent-id intent))}}
         (fn [_] (assoc ctx :step "close"))]
        [nil (fn [_] (assoc ctx :step "close"))])

      "close"
      [{:method :post :path (str "/accounts/" old "/close")}
       (fn [_] (assoc ctx :step "done"))]

      "unblock"
      [{:method :post :path (str "/accounts/" old "/unblock")}
       (fn [_] (assoc ctx :step "failed"))])))

(defn- reissued
  [intent ctx]
  (let [{:keys! [bank-id account-id rotation-key new-account]} ctx]
    (account-event intent
                   "payment-address-reissued"
                   {:bank-id bank-id
                    :account-id account-id
                    :provider-account-id (:id new-account)
                    :rotation-key rotation-key
                    :addresses (:addresses new-account)})))

(defn- reissue-failed
  [intent ctx]
  (let [{:keys! [bank-id account-id rotation-key failure]} ctx]
    (account-event intent
                   "payment-address-reissue-failed"
                   {:bank-id bank-id
                    :account-id account-id
                    :rotation-key rotation-key
                    :reason failure})))

(defn- reissue-context
  "The reissue's context, starting at blocking the account's provider
  account where one holds it, or at opening a new one where none does."
  [config intent]
  (let [ctx (shared/context intent)
        {:keys! [account-id]} ctx]
    (if (:step ctx)
      ctx
      (let [held (shared/held-at config account-id (:provider-account-id ctx))]
        (utility/assoc-some (assoc ctx :step (if held "block" "open"))
                            :provider-account-id
                            held)))))

(defn- held-reissued
  [intent ctx]
  (held (:account-id ctx)
        (get-in ctx [:new-account :id])
        (reissued intent ctx)))

(defn- reissue
  "The reissue's current step. A finished or failed reissue makes no
  call."
  [config _now intent]
  (let [ctx (reissue-context config intent)]
    (if (contains? #{"done" "failed"} (:step ctx))
      [:answered {:ctx ctx}]
      (let [[request next-ctx] (reissue-step config intent ctx)
            [outcome result]
            (if request (shared/call config intent request) [:ok nil])]
        (case outcome
          :ok [:answered {:ctx ctx :next (next-ctx result)}]
          :refused [:refused {:ctx ctx :reason result}]
          [:retry {:ctx ctx :reason result}])))))

(defn- reissue-advanced
  [_config _now intent {:keys [ctx next]}]
  (cond
   next
   (shared/next-step next)

   (= "done" (:step ctx))
   (held-reissued intent ctx)

   :else
   {:status :outbound-intent-status-failed :event (reissue-failed intent ctx)}))

(defn- reissue-stopped
  "A reissue stopped at closing the old account is reissued, the old one
  left blocked. One stopped after blocking it unblocks it first, then
  fails; one stopped unblocking it, before blocking it, or refused the
  block, fails."
  [_config _now intent failure {:keys [ctx reason]}]
  (let [{:keys! [step] :keys [provider-account-id]} ctx
        reason (shared/undelivered failure reason)
        log-context {:intent-id (:intent-id intent)
                     :step step
                     :provider-account-id provider-account-id
                     :reason reason}]
    (cond
     (= "close" step)
     (do (log/error "Modulr did not close the old account; it stays blocked"
                    log-context)
         (held-reissued intent ctx))

     (= "unblock" step)
     (do (log/error "Modulr did not unblock the old account" log-context)
         {:status :outbound-intent-status-failed
          :event (reissue-failed intent ctx)})

     (and provider-account-id
          (not (and (= "block" step) (= :refused failure))))
     (do (log/error "Modulr address reissue failed; unblocking"
                    (assoc log-context
                           :new-provider-account-id
                           (get-in ctx [:new-account :id])))
         (shared/next-step (assoc ctx :step "unblock" :failure reason)))

     :else
     {:status :outbound-intent-status-failed
      :event (reissue-failed intent (assoc ctx :failure reason))})))

(intent-poller/defoperations
 :modulr
 {:modulr-outbound-intent-kind-open-account
  {:call open :answered opened :failed open-failed}
  :modulr-outbound-intent-kind-close-account
  {:call close :answered closed :failed close-failed}
  :modulr-outbound-intent-kind-reissue-address
  {:call reissue :answered reissue-advanced :failed reissue-stopped}})
