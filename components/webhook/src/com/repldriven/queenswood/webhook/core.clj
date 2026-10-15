(ns com.repldriven.queenswood.webhook.core
  (:require
    [com.repldriven.queenswood.webhook.components :as components]
    [com.repldriven.queenswood.webhook.domain :as domain]
    [com.repldriven.queenswood.webhook.signing :as signing]
    [com.repldriven.queenswood.webhook.store :as store]

    [com.repldriven.queenswood.audit.interface :as audit]
    [com.repldriven.queenswood.policy.interface :as policy]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility])
  (:import
    (java.net InetAddress)
    (java.security SecureRandom)
    (java.util Base64)))

(def ^:private secret-bytes
  "How much entropy a webhook secret carries. Standard Webhooks keys an
  HMAC-SHA256 with it, so a secret shorter than the digest would be the
  weak half of the pair."
  32)

(def ^:private default-previous-secret-ttl-ms
  "How long a rotated-away secret stays accepted, so a tenant that has
  not yet picked the new one up keeps verifying — a day."
  86400000)

(def ^:private ^SecureRandom random (SecureRandom.))

(defn- mint-secret
  "A fresh signing secret. Encoded without padding: `=` is outside the
  alphabet `WebhookSigningSecret` declares, so a padded secret fails
  the registration response's own schema."
  []
  (let [bytes (byte-array secret-bytes)]
    (.nextBytes random bytes)
    (str signing/secret-prefix
         (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes))))

(defn resolved
  "The host's addresses as the platform sees them now, as a vector of
  textual addresses. A host that resolves to nothing — or that cannot
  be resolved at all — answers with an empty vector, which the address
  rule refuses: an unreachable address is the tenant's to hear about,
  not an infrastructure anomaly to surface.

  The delivery runner re-resolves through this immediately before each
  request, so an address whose DNS has moved into a range no tenant may
  be reached on is refused at send time as it would have been at
  registration."
  [address]
  (let [result (error/try-nom
                :webhook-endpoint/resolve
                "Failed to resolve webhook address"
                (mapv #(.getHostAddress ^InetAddress %)
                      (InetAddress/getAllByName (domain/host-of address))))]
    (if (error/anomaly? result) [] result)))

(defn- get-policies
  [txn bank-id opts]
  (or (:policies opts)
      (policy/get-effective-policies txn {:bank-id bank-id})))

(defn- or-already-registered
  "On a uniqueness violation — a retried registration carrying an
  already-seen idempotency-key — read the existing endpoint back and
  return it, so the caller gets the endpoint it already created rather
  than a second one. Any other value passes through unchanged."
  [txn bank-id data result]
  (if (and (store/uniqueness-violation? result)
           (:idempotency-key data))
    (let-nom> [existing (store/find-endpoint-by-idempotency-key
                         txn
                         bank-id
                         (:idempotency-key data))]
      (or existing result))
    result))

(defn- load-endpoint
  [txn bank-id endpoint-id]
  (let-nom> [endpoint (store/find-endpoint txn bank-id endpoint-id)]
    (domain/ensure-found endpoint bank-id endpoint-id)))

(defn- audited
  "Records an act by `actor` taking an endpoint from `before` to `after`
  in the bank's audit log, naming the request `opts` carries."
  [txn kind before after actor opts]
  (audit/record txn
                {:bank-id (:bank-id after)
                 :kind kind
                 :subject-kind :audit-subject-kind-webhook-endpoint
                 :subject-id (:endpoint-id after)
                 :request-id (:request-id opts)
                 :before before
                 :after after
                 :performed-by actor}))

(def ^:private batch-size
  "How many deliveries one transaction of a re-send or a backfill
  writes, so a long window commits in many transactions rather than
  failing as one too large to."
  500)

(defn- deliveries-audited
  "Records each delivery an act by `actor` asked for in the bank's audit
  log, naming the request `opts` carries. `changes` holds a
  `[before after]` pair per delivery, `before` nil for one the act
  created."
  [txn kind changes actor opts]
  (reduce (fn [_ [before after]]
            (let [res (audit/record txn
                                    {:bank-id (:bank-id after)
                                     :kind kind
                                     :subject-kind
                                     :audit-subject-kind-webhook-delivery
                                     :subject-id (:delivery-id after)
                                     :request-id (:request-id opts)
                                     :before before
                                     :after after
                                     :performed-by actor})]
              (when (error/anomaly? res) (reduced res))))
          nil
          changes))

(defn- transition
  "Load, apply `f` to the loaded endpoint, save, and record the act as
  `kind`. Every lifecycle operation but registration has this shape."
  [txn bank-id endpoint-id f kind actor opts]
  (store/transact
   txn
   (fn [txn]
     (let-nom>
       [existing (load-endpoint txn bank-id endpoint-id)
        updated (f txn existing)
        _ (store/save-endpoint txn updated)
        _ (audited txn kind existing updated actor opts)]
       updated))))

(defn register
  ([txn bank-id data actor]
   (register txn bank-id data actor {}))
  ([txn bank-id data actor opts]
   (let [addresses (resolved (:address data))]
     (or-already-registered
      txn
      bank-id
      data
      (store/transact
       txn
       (fn [txn]
         (let-nom>
           [policies (get-policies txn bank-id opts)
            existing-count (store/count-endpoints txn bank-id)
            endpoint (domain/new-endpoint bank-id
                                          data
                                          (mint-secret)
                                          actor
                                          addresses
                                          (:platform-hosts opts)
                                          (:address-rule opts)
                                          existing-count
                                          policies)
            _ (store/save-endpoint txn endpoint)
            _ (audited txn
                       :audit-kind-webhook-endpoint-registered
                       nil
                       endpoint
                       actor
                       opts)]
           endpoint)))))))

(defn get-endpoint
  [txn bank-id endpoint-id]
  (store/transact txn (fn [txn] (load-endpoint txn bank-id endpoint-id))))

(defn get-endpoints
  ([txn bank-id]
   (store/get-endpoints txn bank-id))
  ([txn bank-id opts]
   (store/get-endpoints txn bank-id opts)))

(defn update-endpoint
  ([txn bank-id endpoint-id data actor]
   (update-endpoint txn bank-id endpoint-id data actor {}))
  ([txn bank-id endpoint-id data actor opts]
   (let [addresses (resolved (:address data))]
     (transition txn
                 bank-id
                 endpoint-id
                 (fn [txn existing]
                   (let-nom> [policies (get-policies txn bank-id opts)]
                     (domain/update-endpoint existing
                                             data
                                             actor
                                             addresses
                                             (:platform-hosts opts)
                                             (:address-rule opts)
                                             policies)))
                 :audit-kind-webhook-endpoint-updated
                 actor
                 opts))))

(defn- backfill-page
  "Those of `notifications` a backfill queues to `endpoint`: of a kind it
  has chosen, and with no delivery to it already."
  [txn endpoint notifications]
  (let [{:keys [bank-id endpoint-id]} endpoint]
    (reduce (fn [missing notification]
              (if-not (domain/backfilled? endpoint notification)
                missing
                (let [held (store/holds-delivery? txn
                                                  bank-id
                                                  endpoint-id
                                                  (:notification-id
                                                   notification))]
                  (cond
                   (error/anomaly? held)
                   (reduced held)

                   held
                   missing

                   :else
                   (conj missing notification)))))
            []
            notifications)))

(defn- backfill-batch
  "Queue a delivery to `endpoint`, asked for by `actor`, of each of the
  next `batch-size` of the bank's notifications from `since` on, past
  `after`, that it has chosen and never had one of, in one transaction.
  Returns `{:after key}`, the key the next batch starts after, nil once
  there are no more."
  [txn endpoint since after actor]
  (store/transact
   txn
   (fn [txn]
     (let [now (utility/now)]
       (let-nom>
         [entries (store/find-notifications-since txn
                                                  (:bank-id endpoint)
                                                  since
                                                  after
                                                  batch-size)
          missing (backfill-page txn endpoint (mapv :notification entries))
          _ (reduce (fn [_ notification]
                      (let [res (store/save-delivery
                                 txn
                                 (domain/new-delivery notification
                                                      endpoint
                                                      now
                                                      actor))]
                        (when (error/anomaly? res) (reduced res))))
                    nil
                    missing)]
         {:after (when (= batch-size (count entries))
                   (:key (peek entries)))})))))

(defn- backfill
  "Queue a delivery to `endpoint` of every notification from `since` on
  that it has chosen and never had one of, a batch to a transaction."
  [txn endpoint since actor]
  (loop [after nil]
    (let [res (backfill-batch txn endpoint since after actor)]
      (cond
       (error/anomaly? res)
       res

       (:after res)
       (recur (:after res))))))

(defn enable
  ([txn bank-id endpoint-id actor]
   (enable txn bank-id endpoint-id actor {}))
  ([txn bank-id endpoint-id actor opts]
   (let-nom>
     [updated (store/transact
               txn
               (fn [txn]
                 (let-nom>
                   [existing (load-endpoint txn bank-id endpoint-id)
                    policies (get-policies txn bank-id opts)
                    updated (domain/enable existing actor policies)
                    _ (store/save-endpoint txn updated)
                    _ (audited txn
                               :audit-kind-webhook-endpoint-enabled
                               existing
                               updated
                               actor
                               opts)]
                   updated)))
      _ (when-let [since (:since opts)]
          (backfill txn updated since actor))]
     updated)))

(defn disable
  ([txn bank-id endpoint-id actor]
   (disable txn bank-id endpoint-id actor {}))
  ([txn bank-id endpoint-id actor opts]
   (transition txn
               bank-id
               endpoint-id
               (fn [txn existing]
                 (let-nom> [policies (get-policies txn bank-id opts)]
                   (domain/disable existing actor policies)))
               :audit-kind-webhook-endpoint-disabled
               actor
               opts)))

(defn remove-endpoint
  ([txn bank-id endpoint-id actor]
   (remove-endpoint txn bank-id endpoint-id actor {}))
  ([txn bank-id endpoint-id actor opts]
   (transition txn
               bank-id
               endpoint-id
               (fn [txn existing]
                 (let-nom> [policies (get-policies txn bank-id opts)]
                   (domain/remove-endpoint existing actor policies)))
               :audit-kind-webhook-endpoint-removed
               actor
               opts)))

(defn rotate-secret
  ([txn bank-id endpoint-id data actor]
   (rotate-secret txn bank-id endpoint-id data actor {}))
  ([txn bank-id endpoint-id data actor opts]
   (let [{:keys [idempotency-key previous-secret-ttl-ms]} data
         expires-at (+ (utility/now)
                       (or previous-secret-ttl-ms
                           default-previous-secret-ttl-ms))]
     (transition txn
                 bank-id
                 endpoint-id
                 (fn [txn existing]
                   (let-nom> [policies (get-policies txn bank-id opts)]
                     (if (and idempotency-key
                              (= idempotency-key
                                 (:rotation-idempotency-key existing)))
                       existing
                       (domain/rotate-secret existing
                                             (mint-secret)
                                             expires-at
                                             idempotency-key
                                             actor
                                             policies))))
                 :audit-kind-webhook-endpoint-secret-rotated
                 actor
                 opts))))

(defn- load-deliverable
  [txn bank-id endpoint-id opts]
  (let-nom>
    [endpoint (load-endpoint txn bank-id endpoint-id)
     policies (get-policies txn bank-id opts)]
    (domain/ensure-deliverable endpoint policies)))

(defn test-notification
  ([txn bank-id endpoint-id actor]
   (test-notification txn bank-id endpoint-id actor {}))
  ([txn bank-id endpoint-id actor opts]
   (store/transact
    txn
    (fn [txn]
      (let [now (utility/now)]
        (let-nom>
          [endpoint (load-deliverable txn bank-id endpoint-id opts)
           policies (get-policies txn bank-id opts)
           sent (store/count-notifications-since txn
                                                 bank-id
                                                 domain/test-notification-kind
                                                 (domain/day-start now))
           notification (domain/test-notification
                         endpoint
                         (components/->wire-endpoint-body endpoint)
                         now
                         actor
                         {:webhook-test-notification
                          {#{:bank-id :kind :created-on} sent}}
                         policies)
           _ (store/save-notification txn notification)
           delivery (domain/new-delivery notification endpoint now actor)
           _ (store/save-delivery txn delivery)
           _ (deliveries-audited txn
                                 :audit-kind-webhook-test-sent
                                 [[nil delivery]]
                                 actor
                                 opts)]
          delivery))))))

(defn resend
  ([txn bank-id endpoint-id delivery-id actor]
   (resend txn bank-id endpoint-id delivery-id actor {}))
  ([txn bank-id endpoint-id delivery-id actor opts]
   (store/transact
    txn
    (fn [txn]
      (let [now (utility/now)]
        (let-nom>
          [found (store/find-delivery txn bank-id delivery-id)
           existing (domain/ensure-delivery-found found
                                                  bank-id
                                                  endpoint-id
                                                  delivery-id)
           endpoint (load-deliverable txn bank-id endpoint-id opts)
           found-notification (store/find-notification
                               txn
                               bank-id
                               (:notification-id existing))
           notification (domain/ensure-notification-found
                         found-notification
                         bank-id
                         (:notification-id existing))
           delivery (domain/new-delivery notification endpoint now actor)
           _ (store/save-delivery txn delivery)
           _ (deliveries-audited txn
                                 :audit-kind-webhook-delivery-resent
                                 [[nil delivery]]
                                 actor
                                 opts)]
          delivery))))))

(defn- requeue-batch
  "Pend again up to `batch-size` of the endpoint's failed deliveries
  created inside `[from, to]`, as `actor` asked, in one transaction.
  Returns how many it pended."
  [txn bank-id endpoint-id from to actor opts]
  (store/transact
   txn
   (fn [txn]
     (let [now (utility/now)]
       (let-nom>
         [_ (load-deliverable txn bank-id endpoint-id opts)
          failed (store/find-failed-deliveries txn
                                               bank-id
                                               endpoint-id
                                               from
                                               to
                                               batch-size)
          changes (reduce (fn [changes delivery]
                            (let [pending (domain/requeue delivery now)
                                  res (store/save-delivery txn pending)]
                              (if (error/anomaly? res)
                                (reduced res)
                                (conj changes [delivery pending]))))
                          []
                          failed)
          _ (deliveries-audited txn
                                :audit-kind-webhook-delivery-resent
                                changes
                                actor
                                opts)]
         (count changes))))))

(defn resend-window
  ([txn bank-id endpoint-id data actor]
   (resend-window txn bank-id endpoint-id data actor {}))
  ([txn bank-id endpoint-id data actor opts]
   (let [{:keys [from to]} data
         to (or to (utility/now))]
     (loop [resent 0]
       (let [n (requeue-batch txn bank-id endpoint-id from to actor opts)]
         (cond
          (error/anomaly? n)
          n

          (< n batch-size)
          {:resent-count (+ resent n)}

          :else
          (recur (+ resent n))))))))

(defn get-deliveries
  ([txn bank-id endpoint-id]
   (get-deliveries txn bank-id endpoint-id {}))
  ([txn bank-id endpoint-id filters]
   (store/transact
    txn
    (fn [txn]
      (let-nom>
        [endpoint (load-endpoint txn bank-id endpoint-id)
         deliveries (store/find-deliveries-by-endpoint txn
                                                       bank-id
                                                       (:endpoint-id endpoint))
         matching (filterv (fn [delivery]
                             (domain/matches-filters? delivery filters))
                           deliveries)]
        {:deliveries matching})))))

(defn get-attempts
  [txn bank-id endpoint-id delivery-id]
  (store/transact
   txn
   (fn [txn]
     (let-nom>
       [_ (load-endpoint txn bank-id endpoint-id)
        found (store/find-delivery txn bank-id delivery-id)
        _ (domain/ensure-delivery-found found bank-id endpoint-id delivery-id)
        attempts (store/find-attempts-by-delivery txn bank-id delivery-id)]
       {:attempts attempts}))))
