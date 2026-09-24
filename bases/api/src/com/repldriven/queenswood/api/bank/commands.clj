(ns com.repldriven.queenswood.api.bank.commands
  (:require
    [com.repldriven.queenswood.api.access.handlers :as access-handlers]
    [com.repldriven.queenswood.api.commands :as commands]
    [com.repldriven.queenswood.api.companies.queries :as companies]
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

(def bank-uri
  "The URI a bank is retrieved at, with its id in the `Bank-Id` header."
  "/v1/bank")

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

(def ^:private default-status :bank-status-test)

(def ^:private default-tier "micro")

(def ^:private default-currencies ["GBP"])

(def ^:private operator-fields
  "What an operator chooses and a person creating their own bank may not."
  [:status :tier :currencies :owner-email])

(defn- office->string
  "Join the non-blank registered-office address lines into one string."
  [{:keys [address-line-1 locality postal-code country]}]
  (->> [address-line-1 locality postal-code country]
       (remove str/blank?)
       (str/join ", ")))

(defn- ->binding
  "Snapshot the confirmed company into the bank's company-binding shape."
  [registry company]
  (let [office (office->string (:registered-office-address company))]
    (utility/assoc-some
     {:registry registry
      :company-number (:company-number company)}
     :company-name (:company-name company)
     :company-status (:company-status company)
     :type (:type company)
     :jurisdiction (:jurisdiction company)
     :date-of-creation (:date-of-creation company)
     :registered-office-address (when-not (str/blank? office) office))))

(defn- operator?
  [auth]
  (contains? (:roles auth) :admin))

(defn- refusal
  "The response a create is refused with before anything is looked up:
  a person naming what only an operator chooses, or naming no company.
  Nil when the request may go ahead."
  [{:keys [auth parameters]}]
  (let [{:keys [body]} parameters]
    (when-not (operator? auth)
      (cond (some #(contains? body %) operator-fields)
            (errors/forbidden-response
             (str "Only an operator chooses a bank's status, tier, "
                  "currencies or owner"))
            (nil? (:company-number body))
            (errors/anomaly->response
             (error/reject :bank/company-required
                           {:message
                            "Name the company the bank is created for"}))))))

(defn create-bank-data
  "The create-bank command payload: the body with the defaults a field
  left out takes, its status's audience, and `company`, the registry's
  answer for the number it names, as its binding.
  An operator is the actor and may name an owner by email; a person is
  the actor and the owner."
  [request company]
  (let [{:keys [auth parameters audiences-by-status]} request
        {:keys [body]} parameters
        {:keys [name status tier currencies owner-email]} body
        status (or status default-status)
        person? (not (operator? auth))]
    ;; `audiences-by-status` is bank-api deployment config (sits in
    ;; server/interceptors next to `expected-audiences`). The substrate
    ;; IDP brick is naive about audience naming; the handler resolves
    ;; the per-status audience here and forwards it through.
    (utility/assoc-some
     {:name name
      :status status
      :tier (or tier default-tier)
      :currencies (or currencies default-currencies)
      :audience (get audiences-by-status status)
      :actor {:kind (if person? :actor-kind-member :actor-kind-operator)
              :principal-id (:principal-id auth)}}
     :company-binding (when company
                        (->binding (:registry-id company) company))
     :membership (when person?
                   {:user-id (:principal-id auth) :role :role-owner})
     :owner-invitation (when owner-email {:email owner-email}))))

(defn- company
  "The company the body names, looked up in the registry: the
  `commands/send` ring response, or nil when the body names none."
  [request]
  (when-some [company-number (get-in request
                                     [:parameters :body :company-number])]
    (companies/lookup request company-number)))

(defn- created-bank
  "The created bank with its fresh client secret, and whichever of the
  owner invitation and the creator's owner membership the create wrote."
  [request {:keys [bank-id owner-invitation-id membership]}]
  (let-nom> [bank (bank-with-secret request bank-id)
             invitation (owner-invitation request bank-id owner-invitation-id)]
    (utility/assoc-some bank
                        :owner-invitation
                        invitation
                        :membership
                        (when membership
                          (access-handlers/founding-membership
                           membership
                           (get-in request [:auth :user])
                           bank)))))

(defn create-bank
  [request]
  (let [refused (refusal request)
        looked-up (when-not refused (company request))]
    (cond refused
          refused
          (and looked-up (not= 200 (:status looked-up)))
          looked-up
          :else
          (let [result (send-create-bank request
                                         (create-bank-data request
                                                           (:body looked-up)))]
            (if (not= 200 (:status result))
              result
              (let [bank (created-bank request (:body result))]
                (if (error/anomaly? bank)
                  (errors/anomaly->response bank)
                  {:status 201
                   :headers {"Location" bank-uri}
                   :body bank})))))))

(defn change-bank-tier
  [request]
  (let [{:keys [auth parameters record-db record-store]} request
        {:keys [body]} parameters
        {:keys [bank-id]} auth
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
  (let [{:keys [auth parameters record-db record-store audiences-by-status]}
        request
        {:keys [body]} parameters
        {:keys [bank-id]} auth
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
