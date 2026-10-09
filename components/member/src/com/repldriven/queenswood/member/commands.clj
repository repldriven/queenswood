(ns com.repldriven.queenswood.member.commands
  (:require
    [com.repldriven.queenswood.member.core :as core]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.processor.interface :as processor]
    [com.repldriven.mono.utility.interface :as utility]))

(defn- ->rejection
  "An unauthorized anomaly as a rejection of the same kind and payload.
  mono's command response answers REJECTED or FAILED only for those two
  categories and ACCEPTED for anything else, so an unauthorized anomaly
  would otherwise reach the caller as a success."
  [result]
  (if (error/unauthorized? result)
    (error/reject (error/kind result) (error/payload result))
    result))

(defn- ->response
  [config result]
  (if (error/anomaly? result)
    (->rejection result)
    (let [{:keys [schemas]} config]
      {:status "ACCEPTED"
       :payload (avro/serialize (schemas "member-command-reply")
                                (select-keys result
                                             [:bank-id :member-id
                                              :invitation-id]))})))

(defn- actor
  [{:keys [kind principal-id role]}]
  (utility/assoc-some {:kind kind :principal-id principal-id} :role role))

(defn- proof
  [{:keys [token-hash email email-verified]}]
  {:token-hash token-hash :email email :email-verified? (true? email-verified)})

(defn- people-opts
  [data]
  (utility/assoc-some {:actor (actor (:actor data))} :reason (:reason data)))

(defn- recipient-opts
  [data]
  {:user-id (:user-id data)})

(def ^:private command-handlers
  {"invite-member"
   (fn [config {:keys [bank-id email role] :as data}]
     (core/invite config bank-id {:email email :role role} (people-opts data)))
   "resend-invitation"
   (fn [config {:keys [bank-id invitation-id] :as data}]
     (core/resend config bank-id invitation-id (people-opts data)))
   "withdraw-invitation"
   (fn [config {:keys [bank-id invitation-id] :as data}]
     (core/withdraw config bank-id invitation-id (people-opts data)))
   "accept-invitation" (fn [config {:keys [invitation-id] :as data}]
                         (core/accept config
                                      invitation-id
                                      (proof (:proof data))
                                      (recipient-opts data)))
   "decline-invitation" (fn [config {:keys [invitation-id] :as data}]
                          (core/decline config
                                        invitation-id
                                        (proof (:proof data))
                                        (recipient-opts data)))
   "change-member-role"
   (fn [config {:keys [bank-id member-id role] :as data}]
     (core/change-role config bank-id member-id role (people-opts data)))
   "remove-member"
   (fn [config {:keys [bank-id member-id] :as data}]
     (core/remove-member config bank-id member-id (people-opts data)))
   "leave-bank" (fn [config {:keys [member-id] :as data}]
                  (core/leave config member-id (recipient-opts data)))
   "record-invitation-token"
   (fn [config {:keys [bank-id invitation-id expires-at token-hash]}]
     (core/record-invitation-token config
                                   bank-id
                                   invitation-id
                                   {:expires-at expires-at
                                    :token-hash token-hash}))})

(defn- dispatch
  [config message]
  (let [{:keys [command payload]} message
        handler (get command-handlers command)]
    (if (nil? handler)
      (error/reject :membership/unknown-command
                    (str "Unknown command: " command))
      (let [{:keys [schemas]} config
            schema (get schemas command)]
        (if-not schema
          (error/fail :membership/process-command
                      {:message "No schema found for command"
                       :command command})
          (let-nom> [data (avro/deserialize-same schema payload)]
            (->response config (handler config data))))))))

(defrecord MemberProcessor [config]
  processor/Processor
    (process [_ message] (dispatch config message)))
