(ns com.repldriven.queenswood.modulr-relay.outbound.transfers
  (:require
    [com.repldriven.queenswood.modulr-relay.outbound.shared :as shared]

    [com.repldriven.queenswood.modulr-relay.modulr :as modulr]
    [com.repldriven.queenswood.modulr-relay.outcomes :as outcomes]

    [com.repldriven.queenswood.intent-poller.interface :as intent-poller]

    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.log.interface :as log]))

(defn- transfer-failed
  [intent reason now]
  (let [{:keys! [bank-id]} (shared/context intent)]
    {:event-name "transfer-failed"
     :dedup-key (str (:dedup-key intent) ":failed")
     :data {:transfer-id (:dedup-key intent)
            :bank-id bank-id
            :reason reason
            :timestamp-failed now}}))

(defn- transfer-completed
  [intent now]
  (let [{:keys! [bank-id]} (shared/context intent)]
    {:event-name "transfer-completed"
     :dedup-key (str (:dedup-key intent) ":completed")
     :data {:transfer-id (:dedup-key intent)
            :bank-id bank-id
            :timestamp-completed now}}))

(def ^:private sandbox-payer
  "Who the sandbox credit says paid, since Modulr requires a payer."
  {:name "Sandbox funding"
   :identifier {:type "SCAN" :sortCode "000000" :accountNumber "00000000"}})

(defn- transfer-body
  [config intent]
  (let [{:keys! [amount currency]
         :keys [debtor-account-id creditor-account-id
                debtor-provider-account-id creditor-provider-account-id]}
        (shared/context intent)
        debtor
        (shared/held-at config debtor-account-id debtor-provider-account-id)
        creditor (shared/held-at config
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

(defn- transfer
  "A transfer between provider accounts, or a credit into one. One whose
  provider account is not open yet waits for it."
  [config _now intent]
  (if-let [body (transfer-body config intent)]
    (shared/answer (shared/call config
                                intent
                                {:method :post
                                 :path (if (= "credit" (:kind intent))
                                         "/credit"
                                         "/payments")
                                 :raw-body body}))
    [:retry "No provider account holds it yet"]))

(defn- transferred
  "A credit is complete once Modulr takes it; a transfer is sent, to be
  reconciled if no webhook settles it first."
  [config now intent result]
  (if (= "credit" (:kind intent))
    {:status "settled" :event (transfer-completed intent now)}
    (shared/sent config now result)))

(defn- transfer-not-made
  [_config now intent _failure reason]
  {:status "failed" :event (transfer-failed intent reason now)})

(defn- reconcile-transfer
  "Ask Modulr what became of a sent transfer no webhook has settled, and
  record what it reports under the dedup key its webhook would carry."
  [config now intent]
  (let [{:keys [intent-id dedup-key provider-payment-id]} intent
        {:keys! [bank-id]} (shared/context intent)
        {:keys [status]} (shared/lookup config provider-payment-id)
        descriptor (outcomes/transfer {:provider-payment-id provider-payment-id
                                       :transfer-id dedup-key
                                       :bank-id bank-id
                                       :status status
                                       :at now})]
    (if descriptor
      (do (log/info "Reconciled a Modulr transfer"
                    {:intent-id intent-id :status status})
          {:status "settled" :event descriptor})
      (shared/wait config now))))

(intent-poller/defoperations
 :modulr
 {"transfer" {:call transfer
              :answered transferred
              :failed transfer-not-made
              :reconcile reconcile-transfer}
  "credit" {:call transfer :answered transferred :failed transfer-not-made}})
