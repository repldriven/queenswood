(ns com.repldriven.queenswood.form3-relay.outbound.accounts
  (:require
    [com.repldriven.queenswood.form3-relay.outbound.shared :as shared]

    [com.repldriven.queenswood.form3-relay.store :as store]

    [com.repldriven.queenswood.intent-poller.interface :as intent-poller]

    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.utility.interface :as utility]))

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
  (let [[outcome result] (shared/call config (registration config ctx))]
    (case outcome
      :ok [:ok (:data result)]
      :exists (let [[o r] (shared/call config
                                       {:method :get
                                        :path (account-path (:registration-id
                                                             ctx))})]
                [o (if (= :ok o) (:data r) r)])
      [outcome result])))

(defn- account-refused
  [intent ctx reason]
  (shared/account-event intent
                        "payment-account-open-refused"
                        {:bank-id (:bank-id ctx)
                         :account-id (:account-id ctx)
                         :reason reason}))

(defn- open
  [config _now intent]
  (let-nom> [ctx (with-number config intent (shared/context intent))]
    (shared/answer (register config ctx))))

(defn- opened
  "An account Form3 registered and confirmed is open; one it registered
  without confirming is refused with Form3's reason."
  [_config _now intent account]
  (let [ctx (shared/context intent)
        {:keys! [bank-id account-id]} ctx
        {:keys [status status_reason]} (:attributes account)]
    (if (= "confirmed" status)
      {:status :outbound-intent-status-settled
       :event (shared/account-event intent
                                    "payment-account-opened"
                                    {:bank-id bank-id
                                     :account-id account-id
                                     :provider-account-id (:id account)
                                     :addresses (addresses (:attributes
                                                            account))})}
      (do (log/error "Form3 did not confirm an account registration"
                     {:intent-id (:intent-id intent) :status status})
          {:status :outbound-intent-status-failed
           :event (account-refused intent
                                   ctx
                                   (or status_reason
                                       (str "Registration " status)))}))))

(defn- open-failed
  [_config _now intent failure reason]
  {:status :outbound-intent-status-failed
   :event (account-refused intent
                           (shared/context intent)
                           (shared/undelivered failure reason))})

(defn- close-registration
  "Read the registration for its version, then close it."
  [config provider-account-id]
  (let [[outcome result] (shared/call config
                                      {:method :get
                                       :path (account-path
                                              provider-account-id)})]
    (if (not= :ok outcome)
      [outcome result]
      (shared/call config
                   {:method :patch
                    :path (account-path provider-account-id)
                    :body {:data {:id provider-account-id
                                  :type "accounts"
                                  :version (get-in result [:data :version] 0)
                                  :attributes {:status "closed"}}}}))))

(defn- close
  [config _now intent]
  (let [{:keys! [provider-account-id]} (shared/context intent)]
    (shared/answer (close-registration config provider-account-id))))

(defn- closed
  [_config _now intent _result]
  (let [{:keys! [bank-id account-id]} (shared/context intent)]
    {:status :outbound-intent-status-settled
     :event (shared/account-event intent
                                  "payment-account-closed"
                                  {:bank-id bank-id :account-id account-id})}))

(defn- close-failed
  [_config _now intent failure reason]
  (let [{:keys! [bank-id account-id]} (shared/context intent)]
    {:status :outbound-intent-status-failed
     :event (shared/account-event intent
                                  "payment-account-close-refused"
                                  {:bank-id bank-id
                                   :account-id account-id
                                   :reason (shared/undelivered failure
                                                               reason)})}))

(defn- reissue-step
  "Read the old registration for its holder, register a new number, close
  the old registration."
  [config ctx]
  (let [{:keys! [step provider-account-id]} ctx]
    (case step
      "read"
      (let [[outcome result] (shared/call config
                                          {:method :get
                                           :path (account-path
                                                  provider-account-id)})]
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
    (shared/account-event intent
                          "payment-address-reissued"
                          {:bank-id bank-id
                           :account-id account-id
                           :provider-account-id (:id new-account)
                           :rotation-key rotation-key
                           :addresses (:addresses new-account)})))

(defn- reissue-failed
  [intent ctx reason]
  (let [{:keys! [bank-id account-id rotation-key]} ctx]
    (shared/account-event intent
                          "payment-address-reissue-failed"
                          {:bank-id bank-id
                           :account-id account-id
                           :rotation-key rotation-key
                           :reason reason})))

(defn- reissue-context
  [intent]
  (let [ctx (shared/context intent)]
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
    {:status :outbound-intent-status-settled :event (reissued intent ctx)}))

(defn- reissue-stopped
  "A reissue that stops at closing the old registration is reissued, the
  old one left open; one that stops before it failed."
  [_config _now intent failure {:keys [ctx reason]}]
  (if (= "close" (:step ctx))
    (do (log/error "Form3 did not close the old registration; it stays open"
                   {:intent-id (:intent-id intent)
                    :provider-account-id (:provider-account-id ctx)
                    :reason reason})
        {:status :outbound-intent-status-settled :event (reissued intent ctx)})
    {:status :outbound-intent-status-failed
     :event (reissue-failed intent ctx (shared/undelivered failure reason))}))

(intent-poller/defoperations
 :form3
 {:form3-outbound-intent-kind-open-account
  {:call open :answered opened :failed open-failed}
  :form3-outbound-intent-kind-close-account
  {:call close :answered closed :failed close-failed}
  :form3-outbound-intent-kind-reissue-address
  {:call reissue :answered reissue-advanced :failed reissue-stopped}})
