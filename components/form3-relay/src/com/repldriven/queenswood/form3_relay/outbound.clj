(ns com.repldriven.queenswood.form3-relay.outbound
  (:require
    [com.repldriven.queenswood.form3-relay.form3 :as form3]
    [com.repldriven.queenswood.form3-relay.outcomes :as outcomes]
    [com.repldriven.queenswood.form3-relay.store :as store]

    [com.repldriven.queenswood.intent-poller.interface :as intent-poller]

    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.edn :as edn]))

(def ^:private default-reconcile-after-ms 300000)

(defn- reconcile-at
  [config now]
  (+ now (or (:reconcile-after-ms config) default-reconcile-after-ms)))

(defn- context
  [intent]
  (or (some-> (not-empty (:context intent))
              edn/read-string)
      {}))

(defn- call
  [config request]
  (form3/classify ((or (:post-fn config) form3/request) config request)))

(defn- done?
  "True for a create Form3 answered, or refused as one it already has."
  [outcome]
  (contains? #{:ok :exists} outcome))

(defn- steps
  "Make each call in turn while Form3 answers or already has it: the
  first other outcome, or the last answer."
  [config requests]
  (reduce (fn [_ request]
            (let [[outcome :as res] (call config request)]
              (if (done? outcome) res (reduced res))))
          [:ok nil]
          requests))

;; ---- payments

(defn- answer
  "A Form3 outcome as the poller reads one: a create Form3 answered or
  already has is answered."
  [[outcome result]]
  [(if (done? outcome) :answered outcome) result])

(defn- undelivered
  [failure reason]
  (if (= :undelivered failure) (str "Undelivered: " reason) reason))

(defn- sent
  "Submitted, to be reconciled if no notification settles it first."
  [config now intent]
  {:status "sent"
   :changes (utility/assoc-some {:next-attempt-at (reconcile-at config now)}
                                :provider-payment-id
                                (:provider-payment-id intent))})

(defn- wait
  [config now]
  {:status "sent" :changes {:next-attempt-at (reconcile-at config now)}})

;; ---- payments

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

(defn- payment-path
  [payment-id]
  (str "/v1/transaction/payments/" payment-id))

(defn- pay
  [config _now intent]
  (let [{:keys [request provider-payment-id]} intent
        {:keys! [submission-id]} (context intent)]
    (answer (steps config
                   [{:method :post
                     :path "/v1/transaction/payments"
                     :body {:data {:id provider-payment-id
                                   :type "payments"
                                   :attributes
                                   (json/read-str request :key-fn keyword)}}}
                    {:method :post
                     :path (str (payment-path provider-payment-id)
                                "/submissions")
                     :body {:data {:id submission-id
                                   :type "payment_submissions"}}}]))))

(defn- paid
  [config now intent _result]
  (sent config now intent))

(defn- payment-failed
  [_config now intent failure reason]
  {:status "failed"
   :event (rejected intent
                    (if (= :refused failure)
                      :failure-kind-refused
                      :failure-kind-undelivered)
                    reason
                    now)})

(defn- lookup
  [config provider-payment-id submission-id]
  (let [[outcome result] (call config
                               {:method :get
                                :path (str (payment-path provider-payment-id)
                                           "/submissions/"
                                           submission-id)})]
    (when (= :ok outcome) (get-in result [:data :attributes]))))

(defn- reconcile-payment
  "Ask Form3 what became of a submitted payment no notification has
  settled, and record what it reports under the dedup key its
  notification would carry, so a late one finds it already there."
  [config now intent]
  (let [{:keys [intent-id dedup-key provider-payment-id]} intent
        {:keys! [amount currency submission-id]} (context intent)
        {:keys [status status_reason]}
        (lookup config provider-payment-id submission-id)
        descriptor (outcomes/payment {:provider-payment-id provider-payment-id
                                      :end-to-end-id dedup-key
                                      :amount amount
                                      :currency currency
                                      :status status
                                      :status-reason status_reason
                                      :at now})]
    (if descriptor
      (do (log/info "Reconciled a Form3 payment"
                    {:intent-id intent-id :status status})
          {:status "settled" :event descriptor})
      (wait config now))))

;; ---- returns

(defn- return-path
  [provider-payment-id return-id]
  (str (payment-path provider-payment-id) "/returns/" return-id))

(defn- return-failed
  [intent reason]
  (let [{:keys [provider-payment-id]} intent
        {:keys! [end-to-end-id]} (context intent)]
    {:event-name "inbound-return-failed"
     :dedup-key (str provider-payment-id ":return-failed")
     :data {:scheme-transaction-id provider-payment-id
            :end-to-end-id end-to-end-id
            :reason reason}}))

(defn- send-return
  [config _now intent]
  (let [{:keys [request provider-payment-id]} intent
        {:keys! [return-id submission-id]} (context intent)]
    (answer
     (steps config
            [{:method :post
              :path (str (payment-path provider-payment-id) "/returns")
              :body {:data {:id return-id
                            :type "returns"
                            :attributes (json/read-str request
                                                       :key-fn
                                                       keyword)}}}
             {:method :post
              :path (str (return-path provider-payment-id return-id)
                         "/submissions")
              :body {:data {:id submission-id
                            :type "return_submissions"}}}]))))

(defn- returned
  [config now intent _result]
  (sent config now intent))

(defn- return-not-taken
  "Form3 did not take the return, so the inbound stays in suspense."
  [_config _now intent failure reason]
  {:status "failed"
   :event (return-failed intent (undelivered failure reason))})

(defn- reconcile-return
  "Ask Form3 what became of a submitted return, and report a delivered
  one as the inbound it sent back returned."
  [config now intent]
  (let [{:keys [intent-id provider-payment-id]} intent
        {:keys! [return-id submission-id end-to-end-id amount currency
                 reason-code]
         :keys [reason]}
        (context intent)
        [outcome result] (call config
                               {:method :get
                                :path (str (return-path provider-payment-id
                                                        return-id)
                                           "/submissions/"
                                           submission-id)})
        status (when (= :ok outcome)
                 (get-in result [:data :attributes :status]))
        descriptor (outcomes/returned {:provider-payment-id provider-payment-id
                                       :end-to-end-id end-to-end-id
                                       :amount amount
                                       :currency currency
                                       :reason-code reason-code
                                       :reason reason
                                       :status status
                                       :at now})]
    (cond
     descriptor
     (do (log/info "Form3 delivered a return" {:intent-id intent-id})
         {:status "settled" :event descriptor})

     (= :failed (outcomes/outcome status))
     (do (log/error "Form3 did not deliver a return; it stays in suspense"
                    {:intent-id intent-id :status status})
         {:status "failed"
          :event (return-failed intent
                                (or (get-in result
                                            [:data :attributes :status_reason])
                                    (str "Return " status)))})

     :else
     (wait config now))))

;; ---- accounts

(defn- account-event
  [intent event-name data]
  {:event-name event-name
   :dedup-key (str (:dedup-key intent) ":" event-name)
   :data data})

(defn- account-path
  [id]
  (str "/v1/organisation/accounts/" id))

(defn- addresses
  [{:keys [bank_id account_number]}]
  [{:scheme "scan" :sort-code bank_id :account-number account_number}])

(defn- with-number
  "The context with a registration id and an account number issued
  under the configured sort code, issuing them where it has none."
  [config intent ctx]
  (if (:account-number ctx)
    ctx
    (let-nom> [number (store/allocate-account-number config)
               ctx (assoc ctx
                          :registration-id (str (utility/uuidv7))
                          :account-number number)
               _ (store/advance config (:intent-id intent) ctx)]
      ctx)))

(defn- registration
  [config ctx]
  (let [{:keys! [registration-id account-number holder-name] :keys [currency]}
        ctx]
    {:method :post
     :path "/v1/organisation/accounts"
     :body {:data {:id registration-id
                   :type "accounts"
                   :attributes {:bank_id (:sort-code config)
                                :bank_id_code "GBDSC"
                                :account_number account-number
                                :country "GB"
                                :base_currency (or currency "GBP")
                                :name [holder-name]
                                :account_classification "personal"}}}}))

(defn- register
  "Register the context's account, reading it back where Form3 already
  holds it: `[outcome account-or-reason]`."
  [config ctx]
  (let [[outcome result] (call config (registration config ctx))]
    (case outcome
      :ok [:ok (:data result)]
      :exists (let [[o r] (call config
                                {:method :get
                                 :path (account-path (:registration-id ctx))})]
                [o (if (= :ok o) (:data r) r)])
      [outcome result])))

(defn- account-refused
  [intent ctx reason]
  (account-event intent
                 "payment-account-refused"
                 {:bank-id (:bank-id ctx)
                  :account-id (:account-id ctx)
                  :reason reason}))

(defn- open
  [config _now intent]
  (let-nom> [ctx (with-number config intent (context intent))]
    (answer (register config ctx))))

(defn- opened
  "An account Form3 registered and confirmed is open; one it registered
  without confirming is refused with Form3's reason."
  [_config _now intent account]
  (let [ctx (context intent)
        {:keys! [bank-id account-id]} ctx
        {:keys [status status_reason]} (:attributes account)]
    (if (= "confirmed" status)
      {:status "settled"
       :event (account-event intent
                             "payment-account-opened"
                             {:bank-id bank-id
                              :account-id account-id
                              :provider-account-id (:id account)
                              :addresses (addresses (:attributes account))})}
      (do (log/error "Form3 did not confirm an account registration"
                     {:intent-id (:intent-id intent) :status status})
          {:status "failed"
           :event (account-refused intent
                                   ctx
                                   (or status_reason
                                       (str "Registration " status)))}))))

(defn- open-failed
  [_config _now intent failure reason]
  {:status "failed"
   :event (account-refused intent
                           (context intent)
                           (undelivered failure reason))})

(defn- close-registration
  "Read the registration for its version, then close it."
  [config provider-account-id]
  (let [[outcome result] (call config
                               {:method :get
                                :path (account-path provider-account-id)})]
    (if (not= :ok outcome)
      [outcome result]
      (call config
            {:method :patch
             :path (account-path provider-account-id)
             :body {:data {:id provider-account-id
                           :type "accounts"
                           :version (get-in result [:data :version] 0)
                           :attributes {:status "closed"}}}}))))

(defn- close
  [config _now intent]
  (let [{:keys! [provider-account-id]} (context intent)]
    (answer (close-registration config provider-account-id))))

(defn- closed
  [_config _now intent _result]
  (let [{:keys! [bank-id account-id]} (context intent)]
    {:status "settled"
     :event (account-event intent
                           "payment-account-closed"
                           {:bank-id bank-id :account-id account-id})}))

(defn- close-failed
  [_config _now intent failure reason]
  (let [{:keys! [bank-id account-id]} (context intent)]
    {:status "failed"
     :event (account-event intent
                           "payment-account-close-refused"
                           {:bank-id bank-id
                            :account-id account-id
                            :reason (undelivered failure reason)})}))

(defn- reissue-step
  "Read the old registration for its holder, register a new number, close
  the old registration."
  [config ctx]
  (let [{:keys! [step provider-account-id]} ctx]
    (case step
      "read"
      (let [[outcome result] (call config
                                   {:method :get
                                    :path (account-path provider-account-id)})]
        [outcome
         result
         (fn [r]
           (let [{:keys [name base_currency]} (get-in r [:data :attributes])]
             (assoc ctx
                    :step "register"
                    :holder-name (first name)
                    :currency base_currency)))])

      "register"
      (let [[outcome result] (register config ctx)
            {:keys [status status_reason]} (:attributes result)]
        [(if (and (= :ok outcome) (not= "confirmed" status)) :refused outcome)
         (if (and (= :ok outcome) (not= "confirmed" status))
           (or status_reason (str "Registration " status))
           result)
         (fn [account]
           (assoc ctx
                  :step "close"
                  :new-account {:id (:id account)
                                :addresses (addresses (:attributes
                                                       account))}))])

      "close"
      (let [[outcome result] (close-registration config provider-account-id)]
        [outcome result (fn [_] (assoc ctx :step "done"))]))))

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
  [intent ctx reason]
  (let [{:keys! [bank-id account-id rotation-key]} ctx]
    (account-event intent
                   "payment-address-reissue-failed"
                   {:bank-id bank-id
                    :account-id account-id
                    :rotation-key rotation-key
                    :reason reason})))

(defn- reissue-context
  [intent]
  (let [ctx (context intent)]
    (cond-> ctx
            (nil? (:step ctx))
            (assoc :step "read"))))

(defn- reissue
  "The reissue's current step: read the old registration, register the
  new number, close the old registration. A finished reissue makes no
  call."
  [config _now intent]
  (let [ctx (reissue-context intent)]
    (if (= "done" (:step ctx))
      [:answered {:ctx ctx}]
      (let-nom> [ctx (if (= "register" (:step ctx))
                       (with-number config intent ctx)
                       ctx)]
        (let [[outcome result next-ctx] (reissue-step config ctx)]
          (case outcome
            :ok [:answered {:ctx ctx :next (next-ctx result)}]
            :refused [:refused {:ctx ctx :reason result}]
            [:retry {:ctx ctx :reason result}]))))))

(defn- reissue-advanced
  [_config _now intent {:keys [ctx next]}]
  (if next
    {:advance next}
    {:status "settled" :event (reissued intent ctx)}))

(defn- reissue-stopped
  "A reissue that stops at closing the old registration is reissued, the
  old one left open; one that stops before it failed."
  [_config _now intent failure {:keys [ctx reason]}]
  (if (= "close" (:step ctx))
    (do (log/error "Form3 did not close the old registration; it stays open"
                   {:intent-id (:intent-id intent)
                    :provider-account-id (:provider-account-id ctx)
                    :reason reason})
        {:status "settled" :event (reissued intent ctx)})
    {:status "failed"
     :event (reissue-failed intent ctx (undelivered failure reason))}))

(intent-poller/defoperations
 :form3
 {"payment"
  {:call pay :answered paid :failed payment-failed :reconcile reconcile-payment}
  "return" {:call send-return
            :answered returned
            :failed return-not-taken
            :reconcile reconcile-return}
  "open-account" {:call open :answered opened :failed open-failed}
  "close-account" {:call close :answered closed :failed close-failed}
  "reissue-address"
  {:call reissue :answered reissue-advanced :failed reissue-stopped}})

(defn- poller-config
  [config]
  (assoc config :adapter :form3 :store store/spec))

(defn drain-once
  "Make each due pending call once, oldest first, holding a call for an
  account while an earlier one for it is unsent, then reconcile every due
  sent payment and return. Reads are transactional; each call and the
  write recording it are separate, so no network I/O happens inside an
  FDB transaction."
  [config now]
  (intent-poller/drain-once (poller-config config) now))

(defn start-runner
  [config]
  (intent-poller/start (poller-config config)))
