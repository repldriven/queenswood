(ns com.repldriven.queenswood.api.party.commands
  (:require
    [com.repldriven.queenswood.api.commands :as commands]
    [com.repldriven.queenswood.api.party.queries :as queries]
    [com.repldriven.queenswood.api.shared.actor :as actor]

    [com.repldriven.queenswood.party-api.interface :as party-api]
    [com.repldriven.queenswood.schema.interface :as schema]))

(defn- dispatcher
  [request]
  (let [{:keys [dispatchers]} request
        {:keys [parties]} dispatchers]
    parties))

(defn- party-reply
  "A party command's reply, the party it committed as the API shows a
  party."
  [response]
  (commands/reply-body response
                       (fn [{:keys [record]}]
                         (party-api/->body (schema/pb->Party record)))))

(defn create-party
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [body]} parameters]
    (commands/created (party-reply
                       (commands/send (dispatcher request)
                                      request
                                      "create-party"
                                      "party-command-reply"
                                      (assoc body
                                             :bank-id bank-id
                                             :actor (actor/actor
                                                     auth))))
                      #(str "/v1/parties/" (:party-id %)))))

(defn- send-lifecycle
  [request command]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path]} parameters
        {:keys [party-id]} path]
    (party-reply (commands/send (dispatcher request)
                                request
                                command
                                "party-command-reply"
                                {:bank-id bank-id
                                 :party-id party-id
                                 :actor (actor/actor auth)}
                                {:ordering-key party-id}))))

(defn suspend-party
  [request]
  (send-lifecycle request "suspend-party"))

(defn resume-party
  [request]
  (send-lifecycle request "resume-party"))

(defn close-party
  [request]
  (send-lifecycle request "close-party"))

(defn merge-party
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path body]} parameters
        {:keys [party-id]} path
        {:keys [into-party-id]} body]
    (party-reply (commands/send (dispatcher request)
                                request
                                "merge-party"
                                "party-command-reply"
                                {:bank-id bank-id
                                 :party-id party-id
                                 :into-party-id into-party-id
                                 :actor (actor/actor auth)}
                                {:ordering-key party-id}))))

(def ^:private channel-names
  {:idv-session-channel-web "web" :idv-session-channel-mobile "mobile"})

(defn open-verification-session
  [request]
  (let [{:keys [auth parameters dispatchers]} request
        {:keys [bank-id]} auth
        {:keys [path body]} parameters
        {:keys [party-id]} path
        {:keys [channel return-url email]} body
        response (commands/send (:idvs dispatchers)
                                request
                                "open-idv-session"
                                "idv-command-reply"
                                {:bank-id bank-id
                                 :party-id party-id
                                 :channel (channel-names channel)
                                 :return-url return-url
                                 :email email
                                 :actor (actor/actor auth)}
                                {:ordering-key party-id})]
    (if (= 200 (:status response))
      (let [session (schema/pb->IdvSession (:record (:body response)))]
        {:status 202
         :headers {"Location" (str "/v1/parties/"
                                   party-id
                                   "/verification-sessions/"
                                   (:session-id session))}
         :body (queries/session-body session)})
      response)))
