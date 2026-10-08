(ns com.repldriven.queenswood.clearbank-adapter.commands
  (:require
    [com.repldriven.queenswood.clearbank-adapter.clearbank :as clearbank]

    [com.repldriven.queenswood.clearbank-relay.interface :as relay]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.processor.interface :as processor]
    [com.repldriven.mono.utility.interface :as utility]))

(defn- save-intent
  [config intent]
  (let [res (relay/save-intent (select-keys config [:record-store :record-db])
                               (assoc intent
                                      :intent-id (str (utility/uuidv7))
                                      :status :outbound-intent-status-pending
                                      :attempts 0
                                      :created-at (utility/now)))]
    (cond
     (not (error/anomaly? res))
     {:status "ACCEPTED"}

     (relay/uniqueness-violation? res)
     {:status "ACCEPTED"}

     :else
     res)))

(defn- submit-payment-intent
  [config data]
  (save-intent config
               {:dedup-key (:end-to-end-id data)
                :kind :clearbank-outbound-intent-kind-payment
                :subjects (vec (keep identity [(:debtor-account-id data)]))
                :request (clearbank/->fps-body data)}))

(defn- fdb
  [config]
  (select-keys config [:record-store :record-db]))

(defn- open-account-intent
  [config data]
  (let [{:keys [bank-id account-id holder-name currency]} data]
    (let-nom> [account-number (relay/allocate-account-number (fdb config))]
      (save-intent config
                   {:dedup-key (str "open:" account-id)
                    :kind :clearbank-outbound-intent-kind-open-account
                    :subjects [account-id]
                    :request (clearbank/->virtual-account-body
                              (:sort-code config)
                              account-number
                              holder-name
                              currency
                              account-id)
                    :context (pr-str {:bank-id bank-id
                                      :account-id account-id})}))))

(defn- close-account-intent
  [config data]
  (let [{:keys [bank-id account-id provider-account-id]} data]
    (save-intent config
                 {:dedup-key (str "close:" account-id)
                  :kind :clearbank-outbound-intent-kind-close-account
                  :subjects [account-id]
                  :request "{}"
                  :context (pr-str {:bank-id bank-id
                                    :account-id account-id
                                    :provider-account-id
                                    provider-account-id})})))

(defn- reissue-address-intent
  [config data]
  (let [{:keys [bank-id account-id provider-account-id rotation-key]} data]
    (let-nom> [account-number (relay/allocate-account-number (fdb config))]
      (save-intent config
                   {:dedup-key (str "reissue:" account-id ":" rotation-key)
                    :kind :clearbank-outbound-intent-kind-reissue-address
                    :subjects [account-id]
                    :request (clearbank/->virtual-account-body
                              (:sort-code config)
                              account-number
                              nil
                              nil
                              account-id)
                    :context (pr-str (utility/assoc-some
                                      {:bank-id bank-id
                                       :account-id account-id
                                       :rotation-key rotation-key}
                                      :provider-account-id
                                      provider-account-id))}))))

(defn- dispatch
  [config message]
  (let [{:keys [command payload]} message
        {:keys [schemas]} config
        schema (get schemas command)]
    (if-not schema
      (do (log/warnf "No schema found for command: %s" command)
          nil)
      (let-nom> [data (avro/deserialize-same schema payload)]
        (case command
          "submit-payment"
          (submit-payment-intent config data)

          "open-payment-account"
          (open-account-intent config data)

          "close-payment-account"
          (close-account-intent config data)

          "reissue-payment-address"
          (reissue-address-intent config data)

          (do (log/warnf "Unknown command: %s" command)
              nil))))))

(defrecord ClearBankCommandProcessor [config]
  processor/Processor
    (process [_ message] (dispatch config message)))
