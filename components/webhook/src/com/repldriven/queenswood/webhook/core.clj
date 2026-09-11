(ns com.repldriven.queenswood.webhook.core
  (:require
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
  []
  (let [bytes (byte-array secret-bytes)]
    (.nextBytes random bytes)
    (str secret-prefix (.encodeToString (Base64/getUrlEncoder) bytes))))

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

(defn enable
  ([txn bank-id endpoint-id]
   (enable txn bank-id endpoint-id {}))
  ([txn bank-id endpoint-id opts]
   (transition txn
               bank-id
               endpoint-id
               (fn [txn existing]
                 (let-nom> [policies (get-policies txn bank-id opts)]
                   (domain/enable existing policies))))))

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
  [txn bank-id endpoint-id]
  (transition txn
              bank-id
              endpoint-id
              (fn [_txn existing]
                (domain/pause existing))))

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
