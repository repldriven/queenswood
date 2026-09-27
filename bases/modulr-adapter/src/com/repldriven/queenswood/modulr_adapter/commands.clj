(ns com.repldriven.queenswood.modulr-adapter.commands
  (:require
    [com.repldriven.queenswood.modulr-relay.interface :as relay]
    [com.repldriven.queenswood.modulr-webhook.interface :as modulr-webhook]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.processor.interface :as processor]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private sandbox-payer
  "Who the sandbox credit says paid, since Modulr requires a payer."
  {:name "Sandbox funding"
   :identifier {:type "SCAN" :sortCode "000000" :accountNumber "00000000"}})

(defn- save-intent
  [config intent]
  (let [res (relay/save-intent (select-keys config [:record-store :record-db])
                               (assoc intent
                                      :intent-id (str (utility/uuidv7))
                                      :nonce (modulr-webhook/nonce)
                                      :status "pending"
                                      :attempts 0
                                      :created-at (utility/now)))]
    (if (or (not (error/anomaly? res)) (relay/uniqueness-violation? res))
      {:status "ACCEPTED"}
      res)))

(defn- scan
  [bban name]
  {:type "SCAN"
   :sortCode (subs bban 0 6)
   :accountNumber (subs bban 6)
   :name name})

(defn- payment-intent
  [data]
  (let [{:keys [end-to-end-id debtor-provider-account-id creditor-bban
                creditor-name amount currency reference]}
        data]
    {:dedup-key end-to-end-id
     :kind "payment"
     :request (json/write-str
               (utility/assoc-some
                {:sourceAccountId debtor-provider-account-id
                 :destination (scan creditor-bban creditor-name)
                 :amount (relay/->major-units amount)
                 :currency currency
                 :externalReference (relay/->reference end-to-end-id)}
                :reference
                (not-empty reference)))
     :context (pr-str {:amount amount :currency currency})}))

(defn- transfer-intent
  [data]
  (let [{:keys [transfer-id bank-id debtor-provider-account-id
                creditor-provider-account-id amount currency]}
        data
        reference (relay/->reference transfer-id)]
    {:dedup-key transfer-id
     :kind (if debtor-provider-account-id "transfer" "credit")
     :request (json/write-str
               (if debtor-provider-account-id
                 {:sourceAccountId debtor-provider-account-id
                  :destination {:type "ACCOUNT"
                                :id creditor-provider-account-id}
                  :amount (relay/->major-units amount)
                  :currency currency
                  :reference "Ledger transfer"
                  :externalReference reference}
                 {:accountId creditor-provider-account-id
                  :amount (relay/->major-units amount)
                  :description reference
                  :type "PI_FAST"
                  :payerDetail sandbox-payer}))
     :context (pr-str {:bank-id bank-id :amount amount :currency currency})}))

(defn- open-intent
  [config data]
  (let [{:keys [bank-id account-id currency]} data]
    {:dedup-key (str "open:" account-id)
     :kind "open-account"
     :request (json/write-str
               (utility/assoc-some {:currency currency
                                    :externalReference (relay/->reference
                                                        account-id)}
                                   :productCode
                                   (:product-code config)))
     :context (pr-str {:bank-id bank-id :account-id account-id})}))

(defn- close-intent
  [data]
  (let [{:keys [bank-id account-id provider-account-id]} data]
    {:dedup-key (str "close:" account-id)
     :kind "close-account"
     :request "{}"
     :context (pr-str {:bank-id bank-id
                       :account-id account-id
                       :provider-account-id provider-account-id})}))

(defn- reissue-intent
  [config data]
  (let [{:keys [bank-id account-id provider-account-id rotation-key]} data]
    {:dedup-key (str "reissue:" account-id ":" rotation-key)
     :kind "reissue-address"
     :request (json/write-str (utility/assoc-some {}
                                                  :productCode
                                                  (:product-code config)))
     :context (pr-str (utility/assoc-some {:bank-id bank-id
                                           :account-id account-id
                                           :rotation-key rotation-key}
                                          :provider-account-id
                                          provider-account-id))}))

(defn- dispatch
  [config message]
  (let [{:keys [command payload]} message
        schema (get (:schemas config) command)]
    (if-not schema
      (do (log/warnf "No schema found for command: %s" command) nil)
      (let-nom> [data (avro/deserialize-same schema payload)]
        (case command
          "submit-payment" (save-intent config (payment-intent data))
          "transfer-between-accounts" (save-intent config
                                                   (transfer-intent data))
          "open-payment-account" (save-intent config (open-intent config data))
          "close-payment-account" (save-intent config (close-intent data))
          "reissue-payment-address" (save-intent config
                                                 (reissue-intent config data))
          (do (log/warnf "Unknown command: %s" command) nil))))))

(defrecord ModulrCommandProcessor [config]
  processor/Processor
    (process [_ message] (dispatch config message)))
