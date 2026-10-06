(ns com.repldriven.queenswood.modulr-relay.outbound.shared
  (:require
    [com.repldriven.queenswood.modulr-relay.modulr :as modulr]
    [com.repldriven.queenswood.modulr-relay.store :as store]

    [com.repldriven.queenswood.modulr-webhook.interface :as modulr-webhook]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.edn :as edn]))

(defn reconcile-at
  [config now]
  (+ now (:reconcile-after-ms config)))

(defn context
  [intent]
  (or (some-> (not-empty (:context intent))
              edn/read-string)
      {}))

(defn held-at
  "The provider account holding `account-id`'s money as this adapter
  last opened or reissued it, or `fallback`, the one the command named,
  where it opened none."
  [config account-id fallback]
  (let [held (when account-id (store/provider-account config account-id))]
    (if (or (nil? held) (error/anomaly? held)) fallback held)))

(defn call
  "Make one call for `intent`, retrying as the same request: a retry
  sends the nonce the first attempt was signed with, and `x-mod-retry`."
  [config intent request]
  (let [{:keys [post-fn]} config
        {:keys [nonce attempts]} intent
        request (assoc request
                       :nonce (not-empty nonce)
                       :retry? (pos? (or attempts 0)))]
    (modulr/classify ((or post-fn modulr/request) config request))))

(defn answer
  "A Modulr outcome as the poller reads one."
  [[outcome result]]
  [(if (= :ok outcome) :answered outcome) result])

(defn undelivered
  [failure reason]
  (if (= :undelivered failure) (str "Undelivered: " reason) reason))

(defn next-step
  "Keep `ctx` for the intent's next step, signed with a fresh nonce."
  [ctx]
  {:advance ctx :changes {:nonce (modulr-webhook/nonce)}})

(defn lookup
  "`[outcome payment]`: Modulr's answer to looking `provider-payment-id`
  up, the payment where it answered, nil otherwise."
  [config provider-payment-id]
  (let [{:keys [post-fn]} config
        [outcome result] (modulr/classify ((or post-fn modulr/request)
                                           config
                                           {:method :get
                                            :path "/payments"
                                            :query {"id"
                                                    provider-payment-id}}))]
    [outcome (when (= :ok outcome) (first (:content result)))]))

(defn sent
  "Sent, as the provider's payment `result`, to be reconciled if no
  webhook settles it first."
  [config now result]
  {:status "sent"
   :changes (utility/assoc-some {:next-attempt-at (reconcile-at config now)}
                                :provider-payment-id
                                (:id result))})

(defn wait
  [config now]
  {:status "sent" :changes {:next-attempt-at (reconcile-at config now)}})
