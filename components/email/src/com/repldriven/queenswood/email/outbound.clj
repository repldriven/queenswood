(ns com.repldriven.queenswood.email.outbound
  (:require
    [com.repldriven.queenswood.email.domain :as domain]
    [com.repldriven.queenswood.email.message :as message]
    [com.repldriven.queenswood.email.store :as store]

    [com.repldriven.queenswood.bank-query.interface :as bank-query]
    [com.repldriven.queenswood.membership-query.interface :as memberships]
    [com.repldriven.queenswood.user.interface :as user]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.command.interface :as command]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.smtp.interface :as smtp]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private default-poll-ms 500)

(def ^:private default-batch-size 16)

(def ^:private record-token-command "record-invitation-token")

(def ^:private member :actor-kind-member)

(defn- message-of
  [anomaly fallback]
  (or (:message (error/payload anomaly)) fallback))

(defn- inviter-name
  "The inviting member's name, or nil for an operator or a member whose
  user record is gone, which the message names as the platform."
  [txn {:keys [kind principal-id]}]
  (when (= member kind)
    (let [found (user/find-by-id txn principal-id)]
      (cond
       (= :user/not-found (error/kind found))
       nil

       (error/anomaly? found)
       found

       :else
       (:name found)))))

(defn- invitation-context
  "The invitation, its bank's name and its inviter's name, read in one
  transaction. An unknown invitation is nil."
  [config {:keys [bank-id invitation-id]}]
  (store/transact
   config
   (fn [txn]
     (let [invitation (memberships/find-invitation txn bank-id invitation-id)]
       (if (= :invitation/not-found (error/kind invitation))
         nil
         (let-nom> [invitation invitation
                    bank (bank-query/get-bank txn bank-id)
                    inviter (inviter-name txn (:invited-by invitation))]
           {:invitation invitation
            :bank-name (:name bank)
            :inviter-name inviter}))))
   :email/read-invitation
   "Failed to read the invitation to email"))

(defn- record-token
  "Send `record-invitation-token` and await the reply. Returns
  `{:accepted true}`, `{:superseded reason}` for a refusal, or
  `{:error message}` for a failure or no reply."
  [config delivery token-hash]
  (let [{:keys [dispatcher schemas]} config
        {:keys [bank-id invitation-id expires-at delivery-id]} delivery
        id (str (utility/uuidv7))
        reply (let-nom>
                [payload (avro/serialize (get schemas record-token-command)
                                         {:bank-id bank-id
                                          :invitation-id invitation-id
                                          :expires-at expires-at
                                          :token-hash token-hash})]
                (command/send dispatcher
                              {:id id
                               :correlation-id delivery-id
                               :causation-id (:changelog-event-id delivery)
                               :command record-token-command
                               :payload payload
                               :traceparent (telemetry/inject-traceparent)
                               :tracestate nil
                               :reply-to nil}
                              {:key invitation-id}))]
    (cond
     (error/anomaly? reply)
     {:error (message-of reply "invitation token not recorded")}

     (= "ACCEPTED" (:status reply))
     {:accepted true}

     (= "REJECTED" (:status reply))
     {:superseded (str "token refused: " (:reason reply))}

     :else
     {:error (str "invitation token not recorded: " (:reason reply))})))

(defn- send-invitation
  "Mint a token, record its hash and send the message carrying it.
  Returns `{:message-id id}`, `{:superseded reason}` or `{:error
  message}`."
  [config delivery context]
  (let [{:keys [token token-hash]} (memberships/new-invitation-token)
        recorded (record-token config delivery token-hash)]
    (if-not (:accepted recorded)
      recorded
      (let [{:keys [invitation]} context
            link (message/invitation-link (:console-url config)
                                          (:invitation-id invitation)
                                          token)
            sent (smtp/send (:smtp config)
                            (message/invitation-message
                             (assoc context :link link)))]
        (if (error/anomaly? sent)
          {:error (message-of sent "email not sent")}
          {:message-id (:message-id sent)})))))

(defn- outcome-of
  [config delivery]
  (let [context (invitation-context config delivery)]
    (cond
     (error/anomaly? context)
     {:error (message-of context "invitation not read")}

     (nil? context)
     {:superseded "invitation not found"}

     :else
     (if-let [reason (domain/supersession delivery (:invitation context))]
       {:superseded reason}
       (send-invitation config delivery context)))))

(defn deliver-claimed
  "Send one claimed delivery and record what came back. The command and
  the send sit between the claim's transaction and the outcome's, and
  inside neither. Returns the delivery as the outcome left it, or an
  anomaly."
  [config delivery]
  (let [{:keys [message-id superseded error]} (outcome-of config delivery)
        now (utility/now)
        updated (cond
                 superseded
                 (domain/mark-superseded delivery superseded now)

                 error
                 (domain/record-failure delivery error now)

                 :else
                 (domain/mark-sent delivery message-id now))]
    (when error
      (log/warn "Email delivery attempt failed"
                {:delivery-id (:delivery-id delivery) :error error}))
    (let-nom> [_ (store/save-delivery config updated)]
      updated)))

(defn drain-once
  "Claim the deliveries that are due and send them alongside each other.
  The pass ends when each has recorded an outcome."
  [config]
  (let [claimed (store/claim-due-deliveries
                 config
                 {:now (utility/now)
                  :claimed-by (:runner-id config)
                  :lease-ms domain/claim-lease-ms
                  :limit (or (:batch-size config) default-batch-size)})]
    (if (error/anomaly? claimed)
      (log/error "Failed to claim due email deliveries" {:anomaly claimed})
      (run! deref
            (mapv (fn [delivery] (future (deliver-claimed config delivery)))
                  claimed)))))

(defn start-runner
  "Start the daemon poll loop that drains due deliveries. Returns
  `{:stop fn}`."
  [config]
  (let [running (atom true)
        poll-ms (or (:poll-ms config) default-poll-ms)
        config (update config
                       :runner-id
                       (fn [runner-id] (or runner-id (str (utility/uuidv7)))))
        t (doto (Thread.
                 (fn []
                   (while @running
                     (try (drain-once config)
                          (catch Exception e
                            (log/error e "Email drain threw; continuing")))
                     (try (when @running (Thread/sleep poll-ms))
                          (catch InterruptedException _
                            (reset! running false))))))
            (.setDaemon true)
            (.setName "email-outbound-runner")
            (.start))]
    {:stop (fn [] (reset! running false) (.interrupt t))}))
