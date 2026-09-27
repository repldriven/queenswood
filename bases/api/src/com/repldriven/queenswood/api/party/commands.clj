(ns com.repldriven.queenswood.api.party.commands
  (:require
    [com.repldriven.queenswood.api.commands :as commands]

    [com.repldriven.queenswood.party-api.interface :as party-api]))

(defn- dispatcher
  [request]
  (let [{:keys [dispatchers]} request
        {:keys [parties]} dispatchers]
    parties))

(defn create-party
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [body]} parameters]
    (commands/created (commands/send (dispatcher request)
                                     request
                                     "create-party"
                                     "party"
                                     (assoc body :bank-id bank-id))
                      #(str "/v1/parties/" (:party-id %)))))

(defn- send-lifecycle
  [request command]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path]} parameters
        {:keys [party-id]} path]
    (commands/send (dispatcher request)
                   request
                   command
                   "party"
                   {:bank-id bank-id :party-id party-id})))

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
    (commands/send (dispatcher request)
                   request
                   "merge-party"
                   "party"
                   {:bank-id bank-id
                    :party-id party-id
                    :into-party-id into-party-id})))

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
                                "idv-session"
                                {:bank-id bank-id
                                 :party-id party-id
                                 :channel (channel-names channel)
                                 :return-url return-url
                                 :email email}
                                {:ordering-key party-id})]
    (if (= 200 (:status response))
      (let [session (:body response)]
        {:status 202
         :headers {"Location" (str "/v1/parties/"
                                   party-id
                                   "/verification-sessions/"
                                   (:session-id session))}
         :body (party-api/->session-body session)})
      response)))
