(ns com.repldriven.queenswood.form3-adapter.commands
  (:require
    [com.repldriven.queenswood.form3-relay.interface :as relay]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.processor.interface :as processor]
    [com.repldriven.mono.utility.interface :as utility]))

(defn- save-intent
  [config intent]
  (let [res (relay/save-intent (select-keys config [:record-store :record-db])
                               (assoc intent
                                      :intent-id (str (utility/uuidv7))
                                      :status "pending"
                                      :attempts 0
                                      :created-at (utility/now)))]
    (if (or (not (error/anomaly? res)) (relay/uniqueness-violation? res))
      {:status "ACCEPTED"}
      res)))

(defn- party
  [bban name]
  {:account_number (subs bban 6)
   :account_number_code "BBAN"
   :bank_id (subs bban 0 6)
   :bank_id_code "GBDSC"
   :account_name name})

(defn- payment-intent
  [data]
  (let [{:keys [end-to-end-id debtor-bban creditor-bban creditor-name amount
                currency reference]}
        data]
    {:dedup-key end-to-end-id
     :kind "payment"
     :provider-payment-id (str (utility/uuidv7))
     :request (json/write-str
               (utility/assoc-some
                {:amount (relay/->major-units amount)
                 :currency currency
                 :payment_scheme "FPS"
                 :scheme_payment_type "ImmediatePayment"
                 :end_to_end_reference end-to-end-id
                 :debtor_party (party debtor-bban "Account holder")
                 :beneficiary_party (party creditor-bban creditor-name)}
                :reference
                (not-empty reference)))
     :context (pr-str {:amount amount
                       :currency currency
                       :submission-id (str (utility/uuidv7))})}))

(defn- return-intent
  [data]
  (let [{:keys [payment-id end-to-end-id scheme-transaction-id amount currency
                reason-code reason]}
        data]
    {:dedup-key (str "return:" payment-id)
     :kind "return"
     :provider-payment-id scheme-transaction-id
     :request (json/write-str {:amount (relay/->major-units amount)
                               :currency currency
                               :return_code reason-code})
     :context (pr-str {:return-id (str (utility/uuidv7))
                       :submission-id (str (utility/uuidv7))
                       :end-to-end-id end-to-end-id
                       :amount amount
                       :currency currency
                       :reason-code reason-code
                       :reason reason})}))

(defn- open-intent
  [data]
  (let [{:keys [bank-id account-id holder-name currency]} data]
    {:dedup-key (str "open:" account-id)
     :kind "open-account"
     :request "{}"
     :context (pr-str {:bank-id bank-id
                       :account-id account-id
                       :holder-name holder-name
                       :currency currency})}))

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
  [data]
  (let [{:keys [bank-id account-id provider-account-id rotation-key]} data]
    {:dedup-key (str "reissue:" account-id ":" rotation-key)
     :kind "reissue-address"
     :request "{}"
     :context (pr-str {:bank-id bank-id
                       :account-id account-id
                       :provider-account-id provider-account-id
                       :rotation-key rotation-key})}))

(defn- subjects
  [data]
  (vec (keep data [:account-id :debtor-account-id])))

(defn- dispatch
  [config message]
  (let [{:keys [command payload]} message
        schema (get (:schemas config) command)]
    (if-not schema
      (do (log/warnf "No schema found for command: %s" command) nil)
      (let-nom> [data (avro/deserialize-same schema payload)]
        (case command
          "submit-payment"
          (save-intent config
                       (assoc (payment-intent data) :subjects (subjects data)))
          "return-payment"
          (save-intent config
                       (assoc (return-intent data) :subjects (subjects data)))
          "open-payment-account"
          (save-intent config
                       (assoc (open-intent data) :subjects (subjects data)))
          "close-payment-account"
          (save-intent config
                       (assoc (close-intent data) :subjects (subjects data)))
          "reissue-payment-address"
          (save-intent config
                       (assoc (reissue-intent data) :subjects (subjects data)))
          (do (log/warnf "Unsupported command: %s" command) nil))))))

(defrecord Form3CommandProcessor [config]
  processor/Processor
    (process [_ message] (dispatch config message)))
