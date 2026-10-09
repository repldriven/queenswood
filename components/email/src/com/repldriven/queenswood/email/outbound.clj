(ns com.repldriven.queenswood.email.outbound
  (:require
    [com.repldriven.queenswood.email.domain :as domain]
    [com.repldriven.queenswood.email.message :as message]
    [com.repldriven.queenswood.email.store :as store]

    [com.repldriven.queenswood.bank-query.interface :as bank-query]
    [com.repldriven.queenswood.circuit-breaker.interface :as circuit-breaker]
    [com.repldriven.queenswood.member-query.interface :as members]
    [com.repldriven.queenswood.user.interface :as user]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.command.interface :as command]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.smtp.interface :as smtp]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private destination "smtp")

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
  "The invitation, its bank's name, its inviter's name and whether a
  newer delivery about it was written, read in one transaction. An
  unknown invitation is nil."
  [config delivery]
  (let [{:keys [bank-id kind-id]} delivery]
    (store/transact
     config
     (fn [txn]
       (let [invitation (members/find-invitation txn bank-id kind-id)]
         (if (= :invitation/not-found (error/kind invitation))
           nil
           (let-nom> [invitation invitation
                      bank (bank-query/get-bank txn bank-id)
                      inviter (inviter-name txn (:invited-by invitation))
                      newer? (store/newer-delivery? txn delivery)]
             {:invitation invitation
              :bank-name (:name bank)
              :inviter-name inviter
              :newer? newer?}))))
     :email/read-invitation
     "Failed to read the invitation to email")))

(defn- record-token
  "Send `record-invitation-token` and await the reply. Returns
  `{:accepted true}`, `{:superseded reason}` for a refusal, or
  `{:error message}` for a failure or no reply."
  [config delivery invitation token-hash]
  (let [{:keys [dispatcher schemas]} config
        {:keys [bank-id delivery-id idempotency-key]} delivery
        {:keys [invitation-id expires-at]} invitation
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
                               :causation-id idempotency-key
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
  message}`, with the mail server's `:answered` or `:failed` as
  `:smtp-outcome` where the send was made."
  [config delivery context]
  (let [{:keys [invitation]} context
        {:keys [token token-hash]} (members/new-invitation-token)
        recorded (record-token config delivery invitation token-hash)]
    (if-not (:accepted recorded)
      recorded
      (let [link (message/invitation-link (:console-url config)
                                          (:invitation-id invitation)
                                          token)
            sent (smtp/send (:smtp config)
                            (message/invitation-message
                             (assoc context :link link)))]
        (if (error/anomaly? sent)
          {:error (message-of sent "email not sent") :smtp-outcome :failed}
          {:message-id (:message-id sent) :smtp-outcome :answered})))))

(defn- outcome-of
  [config delivery]
  (let [context (invitation-context config delivery)]
    (cond
     (error/anomaly? context)
     {:error (message-of context "invitation not read")}

     (nil? context)
     {:superseded "invitation not found"}

     :else
     (if-let [reason (domain/supersession (:invitation context)
                                          (:newer? context))]
       {:superseded reason}
       (send-invitation config delivery context)))))

(defn- breaker-policy [config] (get-in config [:delivery-policy :breaker]))

(defn- record-send
  "Record the mail server's `outcome` on its breaker."
  [config outcome now]
  (let [breaker (circuit-breaker/record config
                                        (breaker-policy config)
                                        destination
                                        outcome
                                        now)]
    (cond
     (error/anomaly? breaker)
     (log/error "Circuit breaker not recorded"
                {:destination destination :anomaly breaker})

     (= "open" (:state breaker))
     (log/warn "Circuit breaker open; email deliveries held"
               {:destination destination :retry-at (:retry-at breaker)}))))

(defn deliver-claimed
  "Send one claimed delivery and record what came back, and the mail
  server's outcome on its breaker where the send was made. The command
  and the send sit between the claim's transaction and the outcome's,
  and inside neither. Returns the delivery as the outcome left it, or an
  anomaly."
  [config delivery]
  (let [{:keys [message-id superseded error smtp-outcome]}
        (outcome-of config delivery)
        now (utility/now)
        updated (cond
                 superseded
                 (domain/mark-superseded delivery now)

                 error
                 (domain/record-failure delivery
                                        (circuit-breaker/retry-policy
                                         (:delivery-policy config)
                                         nil)
                                        error
                                        now)

                 :else
                 (domain/mark-sent delivery message-id now))]
    (when smtp-outcome
      (record-send config smtp-outcome now))
    (when superseded
      (log/info "Email delivery superseded"
                {:delivery-id (:delivery-id delivery) :reason superseded}))
    (when error
      (log/warn "Email delivery attempt failed"
                {:delivery-id (:delivery-id delivery) :error error}))
    (let-nom> [_ (store/save-delivery config updated)]
      updated)))

(defn- claim-limit
  "How many deliveries this pass may claim: none while the mail server's
  breaker is open, one as its half-open probe, and the batch while it is
  closed. A breaker that cannot be read lets the batch through."
  [config now]
  (let [decision (circuit-breaker/allow config
                                        (breaker-policy config)
                                        destination
                                        now
                                        (:runner-id config))]
    (cond
     (error/anomaly? decision)
     (do (log/error "Circuit breaker not read; sending as though closed"
                    {:destination destination :anomaly decision})
         (:batch-size config))

     (= :open decision)
     0

     (= :probe decision)
     1

     :else
     (:batch-size config))))

(defn drain-once
  "Claim the deliveries that are due, as many as the mail server's
  breaker allows, and send them alongside each other. The pass ends when
  each has recorded an outcome."
  [config]
  (let [now (utility/now)
        limit (claim-limit config now)
        claimed (if (pos? limit)
                  (store/claim-due-deliveries config
                                              {:now now
                                               :lease-ms (:claim-lease-ms
                                                          config)
                                               :limit limit})
                  [])]
    (if (error/anomaly? claimed)
      (log/error "Failed to claim due email deliveries" {:anomaly claimed})
      (run! deref
            (mapv (fn [delivery]
                    (future
                     (telemetry/with-span-parent
                      "email-delivery"
                      (telemetry/extract-parent-context delivery)
                      (utility/assoc-some {}
                                          "delivery.id"
                                          (:delivery-id delivery))
                      (fn [] (deliver-claimed config delivery)))))
                  claimed)))))

(defn start-runner
  "Start the daemon poll loop that drains due deliveries. Returns
  `{:stop fn}`."
  [config]
  (let [running (atom true)
        poll-ms (:poll-ms config)
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
