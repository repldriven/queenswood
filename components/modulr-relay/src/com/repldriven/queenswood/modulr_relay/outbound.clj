(ns com.repldriven.queenswood.modulr-relay.outbound
  (:require
    [com.repldriven.queenswood.modulr-relay.modulr :as modulr]
    [com.repldriven.queenswood.modulr-relay.outcomes :as outcomes]
    [com.repldriven.queenswood.modulr-relay.store :as store]

    [com.repldriven.queenswood.intent-poller.interface :as intent-poller]
    [com.repldriven.queenswood.modulr-webhook.interface :as modulr-webhook]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.edn :as edn]))

(def ^:private default-reconcile-after-ms 300000)

(def ^:private
     ^{:doc "Operations that wait for an account's calls to settle."}
     settles-first
  #{"close-account" "reissue-address"})

(defn- reconcile-at
  [config now]
  (+ now (or (:reconcile-after-ms config) default-reconcile-after-ms)))

(defn- context
  [intent]
  (or (some-> (not-empty (:context intent))
              edn/read-string)
      {}))

(defn- held-at
  "The provider account holding `account-id`'s money as this adapter
  last opened or reissued it, or `fallback`, the one the command named,
  where it opened none."
  [config account-id fallback]
  (let [held (when account-id (store/provider-account config account-id))]
    (if (or (nil? held) (error/anomaly? held)) fallback held)))

(defn- call
  "Make one call for `intent`, retrying as the same request: a retry
  sends the nonce the first attempt was signed with, and `x-mod-retry`."
  [config intent request]
  (let [{:keys [post-fn]} config
        {:keys [nonce attempts]} intent
        request (assoc request
                       :nonce (not-empty nonce)
                       :retry? (pos? (or attempts 0)))]
    (modulr/classify ((or post-fn modulr/request) config request))))

(defn- answer
  "A Modulr outcome as the poller reads one."
  [[outcome result]]
  [(if (= :ok outcome) :answered outcome) result])

(defn- undelivered
  [failure reason]
  (if (= :undelivered failure) (str "Undelivered: " reason) reason))

(defn- next-step
  "Keep `ctx` for the intent's next step, signed with a fresh nonce."
  [ctx]
  {:advance ctx :changes {:nonce (modulr-webhook/nonce)}})

;; ---- payments and transfers

(defn- rejected
  [intent failure-kind reason now]
  {:event-name "transaction-rejected"
   :dedup-key (str (:dedup-key intent) ":submission-rejected")
   :data {:end-to-end-id (:dedup-key intent)
          :scheme "fps"
          :debit-credit-code :debit-credit-code-debit
          :cancellation-code "NARR"
          :failure-kind failure-kind
          :reason-code "NARR"
          :cancellation-reason reason
          :is-return false
          :timestamp-rejected now}})

(defn- transfer-failed
  [intent reason now]
  (let [{:keys! [bank-id]} (context intent)]
    {:event-name "transfer-failed"
     :dedup-key (str (:dedup-key intent) ":failed")
     :data {:transfer-id (:dedup-key intent)
            :bank-id bank-id
            :reason reason
            :timestamp-failed now}}))

(defn- transfer-completed
  [intent now]
  (let [{:keys! [bank-id]} (context intent)]
    {:event-name "transfer-completed"
     :dedup-key (str (:dedup-key intent) ":completed")
     :data {:transfer-id (:dedup-key intent)
            :bank-id bank-id
            :timestamp-completed now}}))

(defn- failure-event
  [intent failure-kind reason now]
  (if (= "payment" (:kind intent))
    (rejected intent failure-kind reason now)
    (transfer-failed intent reason now)))

(def ^:private sandbox-payer
  "Who the sandbox credit says paid, since Modulr requires a payer."
  {:name "Sandbox funding"
   :identifier {:type "SCAN" :sortCode "000000" :accountNumber "00000000"}})

(defn- payment-body
  [config intent]
  ;; nosemgrep: unchecked-intent-data — optional in a payment's context
  (let [{:keys [debtor-account-id]} (context intent)
        {:keys [request]} intent
        named (get (json/read-str request) "sourceAccountId")
        held (held-at config debtor-account-id named)]
    (if (= named held)
      request
      (json/write-str (assoc (json/read-str request) "sourceAccountId" held)))))

(defn- transfer-body
  [config intent]
  (let [{:keys! [amount currency]
         :keys [debtor-account-id creditor-account-id
                debtor-provider-account-id creditor-provider-account-id]}
        (context intent)
        debtor (held-at config debtor-account-id debtor-provider-account-id)
        creditor (held-at config
                          creditor-account-id
                          creditor-provider-account-id)
        reference (modulr/->reference (:dedup-key intent))]
    (cond
     (not (or creditor-account-id creditor-provider-account-id))
     (:request intent)

     (nil? creditor)
     nil

     (= "credit" (:kind intent))
     (json/write-str {:accountId creditor
                      :amount (modulr/->major-units amount)
                      :description reference
                      :type "PI_FAST"
                      :payerDetail sandbox-payer})

     debtor
     (json/write-str {:sourceAccountId debtor
                      :destination {:type "ACCOUNT" :id creditor}
                      :amount (modulr/->major-units amount)
                      :currency currency
                      :reference "Ledger transfer"
                      :externalReference reference}))))

(defn- request-body
  [config intent]
  (if (= "payment" (:kind intent))
    (payment-body config intent)
    (transfer-body config intent)))

(defn- pay
  [config _now intent]
  (if-let [body (request-body config intent)]
    (answer (call config
                  intent
                  {:method :post
                   :path (if (= "credit" (:kind intent)) "/credit" "/payments")
                   :raw-body body}))
    [:retry "No provider account holds it yet"]))

(defn- paid
  "A credit is complete once Modulr takes it; a payment or transfer is
  sent, to be reconciled if no webhook settles it first."
  [config now intent result]
  (if (= "credit" (:kind intent))
    {:status "settled" :event (transfer-completed intent now)}
    {:status "sent"
     :changes (utility/assoc-some {:next-attempt-at (reconcile-at config now)}
                                  :provider-payment-id
                                  (:id result))}))

(defn- payment-failed
  [_config now intent failure reason]
  {:status "failed"
   :event (failure-event intent
                         (if (= :refused failure)
                           :failure-kind-refused
                           :failure-kind-undelivered)
                         reason
                         now)})

(defn- lookup
  [config provider-payment-id]
  (let [{:keys [post-fn]} config
        [outcome result] (modulr/classify ((or post-fn modulr/request)
                                           config
                                           {:method :get
                                            :path "/payments"
                                            :query {"id"
                                                    provider-payment-id}}))]
    (when (= :ok outcome) (first (:content result)))))

(defn- reconcile
  "Ask Modulr what became of a sent payment or transfer no webhook has
  settled, and record what it reports under the dedup key its webhook
  would carry, so a late webhook finds it already there."
  [config now intent]
  (let [{:keys [intent-id kind dedup-key provider-payment-id]} intent
        {:keys! [amount currency] :keys [bank-id]} (context intent)
        {:keys [status]} (lookup config provider-payment-id)
        descriptor (if (= "payment" kind)
                     (outcomes/payment {:provider-payment-id provider-payment-id
                                        :end-to-end-id dedup-key
                                        :amount amount
                                        :currency currency
                                        :status status
                                        :at now})
                     (outcomes/transfer {:provider-payment-id
                                         provider-payment-id
                                         :transfer-id dedup-key
                                         :bank-id bank-id
                                         :status status
                                         :at now}))]
    (if descriptor
      (do (log/info "Reconciled a Modulr payment"
                    {:intent-id intent-id :status status})
          {:status "settled" :event descriptor})
      {:status "sent" :changes {:next-attempt-at (reconcile-at config now)}})))

;; ---- accounts

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
   :dedup-key (str (:dedup-key intent) ":" event-name)
   :data data})

(defn- open-body
  [intent currency]
  (let [{:keys! [account-id]} (context intent)]
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
  (let [{:keys! [bank-id account-id]} (context intent)]
    (account-event intent
                   "payment-account-refused"
                   {:bank-id bank-id :account-id account-id :reason reason})))

(defn- held
  "Settled with `event`, recording in the same transaction that
  `account-id`'s money is now held in `provider-account-id`."
  [account-id provider-account-id event]
  {:status "settled"
   :event event
   :also (fn [txn] (store/hold txn account-id provider-account-id))})

(defn- open
  [config _now intent]
  (answer (call config
                intent
                {:method :post
                 :path (open-path config)
                 :raw-body (:request intent)})))

(defn- opened
  [_config _now intent result]
  (let [{:keys! [bank-id account-id]} (context intent)
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
  {:status "failed"
   :event (account-refused intent (undelivered failure reason))})

(defn- close-refused
  [intent reason]
  (let [{:keys! [bank-id account-id]} (context intent)]
    (account-event intent
                   "payment-account-close-refused"
                   {:bank-id bank-id :account-id account-id :reason reason})))

(defn- close
  [config _now intent]
  (let [{:keys! [account-id provider-account-id]} (context intent)]
    (answer (call config
                  intent
                  {:method :post
                   :path (str "/accounts/"
                              (held-at config account-id provider-account-id)
                              "/close")}))))

(defn- closed
  [_config _now intent _result]
  (let [{:keys! [bank-id account-id]} (context intent)]
    {:status "settled"
     :event (account-event intent
                           "payment-account-closed"
                           {:bank-id bank-id :account-id account-id})}))

(defn- close-failed
  [_config _now intent failure reason]
  {:status "failed" :event (close-refused intent (undelivered failure reason))})

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
  (let [ctx (context intent)
        {:keys! [account-id]} ctx]
    (if (:step ctx)
      ctx
      (let [held (held-at config account-id (:provider-account-id ctx))]
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
            (if request (call config intent request) [:ok nil])]
        (case outcome
          :ok [:answered {:ctx ctx :next (next-ctx result)}]
          :refused [:refused {:ctx ctx :reason result}]
          [:retry {:ctx ctx :reason result}])))))

(defn- reissue-advanced
  [_config _now intent {:keys [ctx next]}]
  (cond
   next
   (next-step next)

   (= "done" (:step ctx))
   (held-reissued intent ctx)

   :else
   {:status "failed" :event (reissue-failed intent ctx)}))

(defn- reissue-stopped
  "A reissue stopped at closing the old account is reissued, the old one
  left blocked. One stopped after blocking it unblocks it first, then
  fails; one stopped unblocking it, before blocking it, or refused the
  block, fails."
  [_config _now intent failure {:keys [ctx reason]}]
  (let [{:keys! [step] :keys [provider-account-id]} ctx
        reason (undelivered failure reason)
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
         {:status "failed" :event (reissue-failed intent ctx)})

     (and provider-account-id
          (not (and (= "block" step) (= :refused failure))))
     (do (log/error "Modulr address reissue failed; unblocking"
                    (assoc log-context
                           :new-provider-account-id
                           (get-in ctx [:new-account :id])))
         (next-step (assoc ctx :step "unblock" :failure reason)))

     :else
     {:status "failed"
      :event (reissue-failed intent (assoc ctx :failure reason))})))

(intent-poller/defoperations
 :modulr
 {"payment"
  {:call pay :answered paid :failed payment-failed :reconcile reconcile}
  "transfer"
  {:call pay :answered paid :failed payment-failed :reconcile reconcile}
  "credit" {:call pay :answered paid :failed payment-failed}
  "open-account" {:call open :answered opened :failed open-failed}
  "close-account" {:call close :answered closed :failed close-failed}
  "reissue-address"
  {:call reissue :answered reissue-advanced :failed reissue-stopped}})

(defn- poller-config
  [config]
  (assoc config
         :adapter :modulr
         :store store/spec
         :settles-first? (fn [intent]
                           (contains? settles-first (:kind intent)))))

(defn drain-once
  "Make each due pending call once, oldest first, holding a call for an
  account while an earlier one for it is unsent, and a close or reissue
  while one is unsettled; then reconcile every due sent payment and
  transfer. Reads are transactional; each call and the write recording
  it are separate, so no network I/O happens inside an FDB transaction."
  [config now]
  (intent-poller/drain-once (poller-config config) now))

(defn start-runner
  [config]
  (intent-poller/start (poller-config config)))
