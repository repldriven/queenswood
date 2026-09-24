(ns com.repldriven.queenswood.api.bank.commands
  (:require
    [com.repldriven.queenswood.api.access.handlers :as access-handlers]
    [com.repldriven.queenswood.api.commands :as commands]
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.bank-query.interface :as banks]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.identity-provider.interface :as identity-provider]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str]))

(defn- dispatcher
  [request]
  (let [{:keys [dispatchers]} request
        {:keys [banks]} dispatchers]
    banks))

(defn bank-uri
  "The URI a bank is retrieved at."
  [bank-id]
  (str "/v1/banks/" bank-id))

(defn send-create-bank
  "Dispatch a create-bank command. `data` is the command payload
  (name/status/tier/currencies plus optional audience,
  company-binding, membership). Returns the `commands/send` ring
  response (200 + flat bank body on success)."
  [request data]
  (commands/send (dispatcher request) request "create-bank" "bank" data))

(defn bank-with-secret
  "Mint a fresh client secret for the bank — the command reply carries
  no credential, so it never sits on the bus — and load the bank
  enriched with its party and accounts. Returns the rich bank map with
  `:client-secret`, or an anomaly.

  A rotation that answers no secret is an error rather than a
  rejection: the request was well formed, and nothing the caller does
  will change the outcome, so a status that invites a retry would
  mislead. The bank stays created — its command has already committed
  — so the message names it, and an operator regenerates the
  credential by hand."
  [request bank-id]
  (let [{:keys [record-db record-store identity-provider]} request
        txn {:record-db record-db :record-store record-store}]
    (let-nom>
      [{:keys [client-secret]} (identity-provider/rotate-secret
                                identity-provider
                                bank-id)
       _ (when (str/blank? client-secret)
           (error/fail :bank/credential-not-issued
                       {:message (str "Bank "
                                      bank-id
                                      " was created, but no client credential"
                                      " was issued for it; regenerate the"
                                      " credential before the bank is used")
                        :bank-id bank-id}))
       bank (banks/get-bank-view txn bank-id)]
      (assoc bank :client-secret client-secret))))

(defn- owner-invitation
  "The owner invitation the create wrote, loaded by the id its reply
  names. Nil when the create wrote none."
  [request bank-id invitation-id]
  (when invitation-id
    (let [{:keys [record-db record-store]} request
          txn {:record-db record-db :record-store record-store}]
      (access-handlers/named-invitation txn bank-id invitation-id))))

(defn create-bank-data
  "The create-bank command payload for an operator's request: the body
  with its status's audience and the principal as an operator actor, and
  for an `owner-email` the owner invitation."
  [request]
  (let [{:keys [auth parameters audiences-by-status]} request
        {:keys [body]} parameters
        {:keys [status owner-email]} body]
    ;; `audiences-by-status` is bank-api deployment config (sits in
    ;; server/interceptors next to `expected-audiences`). The substrate
    ;; IDP brick is naive about audience naming; the handler resolves
    ;; the per-status audience here and forwards it through.
    (utility/assoc-some
     (assoc (dissoc body :owner-email)
            :audience (get audiences-by-status status)
            :actor {:kind :actor-kind-operator
                    :principal-id (:principal-id auth)})
     :owner-invitation
     (when owner-email {:email owner-email}))))

(defn create-bank
  [request]
  (let [result (send-create-bank request (create-bank-data request))]
    (if (not= 200 (:status result))
      result
      (let [{:keys [bank-id owner-invitation-id]} (:body result)
            bank (let-nom>
                   [bank (bank-with-secret request bank-id)
                    invitation (owner-invitation request
                                                 bank-id
                                                 owner-invitation-id)]
                   (utility/assoc-some bank :owner-invitation invitation))]
        (if (error/anomaly? bank)
          (errors/anomaly->response bank)
          {:status 201
           :headers {"Location" (bank-uri bank-id)}
           :body bank})))))

(defn change-bank-tier
  [request]
  (let [{:keys [parameters record-db record-store]} request
        {:keys [path body]} parameters
        {:keys [bank-id]} path
        {:keys [tier]} body
        result (commands/send (dispatcher request)
                              request
                              "change-bank-tier"
                              "bank"
                              {:bank-id bank-id :tier tier})]
    (if (not= 200 (:status result))
      result
      (let [txn {:record-db record-db :record-store record-store}
            bank (banks/get-bank-view txn bank-id)]
        (if (error/anomaly? bank)
          (errors/anomaly->response bank)
          {:status 200 :body bank})))))

(defn change-bank-status
  [request]
  (let [{:keys [parameters record-db record-store audiences-by-status]} request
        {:keys [path body]} parameters
        {:keys [bank-id]} path
        {:keys [status]} body
        ;; Same status->audience resolution as `create-bank`: the
        ;; substrate IDP brick is naive about audience naming, so the
        ;; handler resolves the target status's audience here and
        ;; forwards it through.
        result (commands/send (dispatcher request)
                              request
                              "change-bank-status"
                              "bank"
                              {:bank-id bank-id
                               :status status
                               :audience (get audiences-by-status status)})]
    (if (not= 200 (:status result))
      result
      (let [txn {:record-db record-db :record-store record-store}
            bank (banks/get-bank-view txn bank-id)]
        (if (error/anomaly? bank)
          (errors/anomaly->response bank)
          {:status 200 :body bank})))))
