(ns com.repldriven.queenswood.webhook.core
  (:require
    [com.repldriven.queenswood.webhook.components :as components]
    [com.repldriven.queenswood.webhook.domain :as domain]
    [com.repldriven.queenswood.webhook.store :as store]

    [com.repldriven.queenswood.policy.interface :as policy]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str])
  (:import
    (java.net InetAddress)
    (java.security SecureRandom)
    (java.util Base64)))

(def ^:private secret-bytes
  "How much entropy a webhook secret carries. Standard Webhooks keys an
  HMAC-SHA256 with it, so a secret shorter than the digest would be the
  weak half of the pair."
  32)

(def ^:private secret-prefix "whsec_")

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
    (str secret-prefix
         (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes))))

(defn- resolved
  "The host's addresses as the platform sees them now, as a vector of
  textual addresses. A host that resolves to nothing — or that cannot
  be resolved at all — answers with an empty vector, which the address
  rule refuses: an unreachable address is the tenant's to hear about,
  not an infrastructure anomaly to surface."
  [address]
  (let [host (some-> address
                     (str/replace #"^[a-zA-Z]+://" "")
                     (str/split #"[/:?#]" 2)
                     first)
        result (error/try-nom
                :webhook-endpoint/resolve
                "Failed to resolve webhook address"
                (mapv #(.getHostAddress ^InetAddress %)
                      (InetAddress/getAllByName host)))]
    (if (error/anomaly? result) [] result)))

(defn- get-policies
  [txn bank-id opts]
  (or (:policies opts)
      (policy/get-effective-policies txn {:bank-id bank-id})))

(defn- platform-hosts
  [opts]
  (set (:platform-hosts opts)))

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

(defn- transition
  "Load, apply `f` to the loaded endpoint, save. Every lifecycle
  operation but registration has this shape."
  [txn bank-id endpoint-id f]
  (store/transact
   txn
   (fn [txn]
     (let-nom>
       [existing (load-endpoint txn bank-id endpoint-id)
        updated (f txn existing)
        _ (store/save-endpoint txn updated)]
       updated))))

(defn register
  ([txn bank-id data]
   (register txn bank-id data {}))
  ([txn bank-id data opts]
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
                                          addresses
                                          (platform-hosts opts)
                                          existing-count
                                          policies)
            _ (store/save-endpoint txn endpoint)]
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
  ([txn bank-id endpoint-id data]
   (update-endpoint txn bank-id endpoint-id data {}))
  ([txn bank-id endpoint-id data opts]
   (let [addresses (resolved (:address data))]
     (transition txn
                 bank-id
                 endpoint-id
                 (fn [txn existing]
                   (let-nom> [policies (get-policies txn bank-id opts)]
                     (domain/update-endpoint existing
                                             data
                                             addresses
                                             (platform-hosts opts)
                                             policies)))))))

(defn- save-deliveries
  "One pending delivery of each notification to `endpoint`, saved in
  the caller's transaction. Returns the rows written."
  [txn notifications endpoint now]
  (reduce (fn [saved notification]
            (let [row (domain/new-delivery notification endpoint now)
                  res (store/save-delivery txn row)]
              (if (error/anomaly? res) (reduced res) (conj saved row))))
          []
          notifications))

(defn- delivered-notification-ids
  [txn endpoint-id]
  (let-nom> [deliveries (store/find-deliveries-by-endpoint txn endpoint-id)]
    (into #{}
          (comp (filter domain/delivered-delivery?) (map :notification-id))
          deliveries)))

(defn- backfill
  "One pending delivery for every notification from `since` on that the
  endpoint has chosen and has never had delivered. Runs in the
  transaction the enable committed in, so resuming and asking for the
  gap land together or not at all."
  [txn endpoint since now]
  (let-nom>
    [notifications (store/find-notifications-by-bank txn (:bank-id endpoint))
     delivered (delivered-notification-ids txn (:endpoint-id endpoint))]
    (save-deliveries
     txn
     (filterv (fn [notification]
                (and (domain/within-window? notification since nil)
                     (domain/chosen? endpoint (:kind notification))
                     (not (contains? delivered
                                     (:notification-id notification)))))
              notifications)
     endpoint
     now)))

(defn enable
  ([txn bank-id endpoint-id]
   (enable txn bank-id endpoint-id {}))
  ([txn bank-id endpoint-id opts]
   (store/transact
    txn
    (fn [txn]
      (let-nom>
        [existing (load-endpoint txn bank-id endpoint-id)
         policies (get-policies txn bank-id opts)
         updated (domain/enable existing policies)
         _ (store/save-endpoint txn updated)
         _ (when-let [since (:since opts)]
             (backfill txn updated since (utility/now)))]
        updated)))))

(defn disable
  ([txn bank-id endpoint-id]
   (disable txn bank-id endpoint-id {}))
  ([txn bank-id endpoint-id opts]
   (transition txn
               bank-id
               endpoint-id
               (fn [txn existing]
                 (let-nom> [policies (get-policies txn bank-id opts)]
                   (domain/disable existing policies))))))

(defn pause
  "The one transition whose write co-commits a changelog envelope: the
  platform took it, so the tenant's other endpoints are told."
  [txn bank-id endpoint-id]
  (store/transact
   txn
   (fn [txn]
     (let-nom>
       [existing (load-endpoint txn bank-id endpoint-id)
        updated (domain/pause existing)
        _ (store/save-endpoint-status txn updated (:status existing))]
       updated))))

(defn remove-endpoint
  ([txn bank-id endpoint-id]
   (remove-endpoint txn bank-id endpoint-id {}))
  ([txn bank-id endpoint-id opts]
   (transition txn
               bank-id
               endpoint-id
               (fn [txn existing]
                 (let-nom> [policies (get-policies txn bank-id opts)]
                   (domain/remove-endpoint existing policies))))))

(defn rotate-secret
  ([txn bank-id endpoint-id data]
   (rotate-secret txn bank-id endpoint-id data {}))
  ([txn bank-id endpoint-id data opts]
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
                                             policies))))))))

(defn- load-deliverable
  [txn bank-id endpoint-id opts]
  (let-nom>
    [endpoint (load-endpoint txn bank-id endpoint-id)
     policies (get-policies txn bank-id opts)]
    (domain/ensure-deliverable endpoint policies)))

(defn test-notification
  ([txn bank-id endpoint-id]
   (test-notification txn bank-id endpoint-id {}))
  ([txn bank-id endpoint-id opts]
   (store/transact
    txn
    (fn [txn]
      (let [now (utility/now)]
        (let-nom>
          [endpoint (load-deliverable txn bank-id endpoint-id opts)
           notification (domain/test-notification
                         endpoint
                         (components/->endpoint-body endpoint)
                         now)
           _ (store/save-notification txn notification)
           delivery (domain/new-delivery notification endpoint now)
           _ (store/save-delivery txn delivery)]
          delivery))))))

(defn resend
  ([txn bank-id endpoint-id delivery-id]
   (resend txn bank-id endpoint-id delivery-id {}))
  ([txn bank-id endpoint-id delivery-id opts]
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
           delivery (domain/new-delivery notification endpoint now)
           _ (store/save-delivery txn delivery)]
          delivery))))))

(defn resend-window
  ([txn bank-id endpoint-id data]
   (resend-window txn bank-id endpoint-id data {}))
  ([txn bank-id endpoint-id data opts]
   (let [{:keys [from to]} data]
     (store/transact
      txn
      (fn [txn]
        (let [now (utility/now)]
          (let-nom>
            [endpoint (load-deliverable txn bank-id endpoint-id opts)
             notifications (store/find-notifications-by-bank txn bank-id)
             resent (save-deliveries
                     txn
                     (filterv (fn [notification]
                                (and (domain/within-window? notification
                                                            from
                                                            to)
                                     (domain/chosen? endpoint
                                                     (:kind notification))))
                              notifications)
                     endpoint
                     now)]
            {:deliveries resent})))))))

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
                                                       (:endpoint-id endpoint))]
        {:deliveries (filterv (fn [delivery]
                                (and (= bank-id (:bank-id delivery))
                                     (domain/matches-filters? delivery
                                                              filters)))
                              deliveries)})))))
