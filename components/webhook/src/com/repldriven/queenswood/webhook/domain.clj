(ns com.repldriven.queenswood.webhook.domain
  (:require
    [com.repldriven.queenswood.policy.interface :as policy]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Address validation

(def
  ^{:doc
    "The only scheme a tenant address may carry. A webhook
  body is signed but not encrypted by the signature, so plaintext
  would put every delivered record on the wire."}
  allowed-scheme
  "https")

(def
  ^{:doc
    "Address ranges an endpoint may not resolve into, as
  `[prefix-bits reason]` keyed by the range's network address in
  the same text form a resolved address arrives in. A tenant
  address that lands in one of these reaches the platform's own
  network rather than the tenant's, which is what makes an
  unvalidated webhook target a request-forgery primitive. The
  cloud metadata address 169.254.169.254 needs no entry of its
  own: it sits inside link-local."}
  blocked-ranges
  [["0.0.0.0" 8 "unspecified"]
   ["10.0.0.0" 8 "private"]
   ["100.64.0.0" 10 "carrier-grade NAT"]
   ["127.0.0.0" 8 "loopback"]
   ["169.254.0.0" 16 "link-local"]
   ["172.16.0.0" 12 "private"]
   ["192.168.0.0" 16 "private"]
   ["::" 128 "unspecified"]
   ["::1" 128 "loopback"]
   ["fc00::" 7 "unique-local"]
   ["fe80::" 10 "link-local"]])

(defn- ipv4?
  [address]
  (and (str/includes? address ".") (not (str/includes? address ":"))))

(defn- ipv4->bits
  "The dotted quad as a 32-bit number, or nil when it is not one."
  [address]
  (let [octets (str/split address #"\.")]
    (when (= 4 (count octets))
      (reduce (fn [acc octet]
                (let [n (parse-long octet)]
                  (if (and n (<= 0 n 255))
                    (+ (* acc 256) n)
                    (reduced nil))))
              0
              octets))))

(defn- ipv6-groups
  "The sixteen-bit groups of an IPv6 address, `::` expanded, or nil
  when it is not one."
  [address]
  (let [[head tail more] (str/split address #"::" 3)
        parse (fn [s]
                (when (seq s)
                  (mapv #(Long/parseLong % 16) (str/split s #":"))))]
    (when-not more
      (let [head-groups (or (parse (or head "")) [])
            tail-groups (or (parse (or tail "")) [])]
        (cond
         (str/includes? address "::")
         (let [gap (- 8 (count head-groups) (count tail-groups))]
           (when (pos? gap)
             (vec (concat head-groups (repeat gap 0) tail-groups))))

         (= 8 (count head-groups))
         head-groups)))))

(defn- ipv6->bits
  [address]
  (when-let [groups (ipv6-groups address)]
    (reduce (fn [acc group] (+ (* acc 65536) group)) 0N groups)))

(defn- ->bits
  [address]
  (try
    (if (ipv4? address)
      (some-> (ipv4->bits address)
              bigint)
      (ipv6->bits address))
    (catch NumberFormatException _ nil)))

(defn- in-range?
  [address [network prefix-bits _reason]]
  (let [width (if (ipv4? network) 32 128)
        value (->bits address)
        base (->bits network)
        host-bits (- width prefix-bits)]
    (and value
         base
         (= (ipv4? address) (ipv4? network))
         (= (.shiftRight (biginteger value) host-bits)
            (.shiftRight (biginteger base) host-bits)))))

(defn- blocked-reason
  "The reason `address` may not be reached, or nil. An address that
  parses as neither an IPv4 nor an IPv6 literal is refused rather
  than allowed: it is not a form this rule can clear."
  [address]
  (if (nil? (->bits address))
    "address is not a recognised IP literal"
    (some (fn [[_ _ reason :as range]]
            (when (in-range? address range) reason))
          blocked-ranges)))

(defn- invalid-address
  [address reason]
  (error/reject :webhook-endpoint/invalid-address
                {:message "Webhook address is not a valid destination"
                 :address address
                 :reason reason}))

(defn check-address
  "Refuse an address that is not HTTPS, whose host is one of the
  platform's own, or that resolves into a range no tenant may be
  reached on.

  `resolved-addresses` are the host's addresses as the caller
  already resolved them — resolution is an effect, and keeping it
  outside lets the same rule run again at send time."
  [address resolved-addresses platform-hosts]
  (let [scheme (some-> address
                       (str/split #"://" 2)
                       first
                       str/lower-case)
        host (some-> address
                     (str/replace #"^[a-zA-Z]+://" "")
                     (str/split #"[/:?#]" 2)
                     first
                     str/lower-case)]
    (cond
     (not= allowed-scheme scheme)
     (invalid-address address "scheme is not https")

     (contains? (set platform-hosts) host)
     (invalid-address address "host is the platform's own")

     (empty? resolved-addresses)
     (invalid-address address "host resolves to no address")

     :else
     (when-let [reason (some blocked-reason resolved-addresses)]
       (invalid-address address reason)))))

;; ---------------------------------------------------------------------------
;; Lifecycle guards

(def ^:private enabled :webhook-endpoint-status-enabled)
(def ^:private disabled :webhook-endpoint-status-disabled)
(def ^:private paused :webhook-endpoint-status-paused)
(def ^:private removed :webhook-endpoint-status-removed)

(def ^:private live-statuses #{enabled disabled paused})

(defn- ensure-status
  [endpoint allowed]
  (when-not (contains? allowed (:status endpoint))
    (error/reject :webhook-endpoint/invalid-status
                  {:message "Endpoint is not in a state that allows this"
                   :endpoint-id (:endpoint-id endpoint)
                   :status (:status endpoint)
                   :allowed allowed})))

(defn ensure-found
  "Reject when the store answered with no endpoint. The store returns
  `nil` rather than rejecting, so absence becomes a rejection here."
  [endpoint bank-id endpoint-id]
  (if endpoint
    endpoint
    (error/reject :webhook-endpoint/not-found
                  {:message "Webhook endpoint not found"
                   :bank-id bank-id
                   :endpoint-id endpoint-id})))

;; ---------------------------------------------------------------------------
;; The pause rule

(def
  ^{:doc
    "How long an endpoint may go without a successful delivery
  before a failure pauses it — a day."}
  pause-window-ms
  86400000)

(def
  ^{:doc
    "How many attempts a delivery must have made before its
  failure can pause the endpoint, so a transient failure on a new
  endpoint does not."}
  pause-minimum-attempts
  5)

(defn should-pause?
  "Whether a failing delivery should pause its endpoint: no success
  inside the pause window, across at least the minimum attempts.

  A `nil` `last-success-at` is an endpoint that has never succeeded,
  which is longer ago than any window; the attempt minimum is what
  keeps a newly registered endpoint from pausing on its first
  failures."
  [last-success-at now attempts]
  (and (>= (or attempts 0) pause-minimum-attempts)
       (or (nil? last-success-at)
           (> (- now last-success-at) pause-window-ms))))

;; ---------------------------------------------------------------------------
;; Capability + limit checks

(defn- check-capability
  [action policies]
  (policy/check-capability policies :webhook-endpoint {:action action}))

(defn- check-limit
  [existing-count policies]
  (policy/check-limit policies
                      :webhook-endpoint
                      {:aggregate :count
                       :window :time-window-instant
                       :value (inc existing-count)}))

;; ---------------------------------------------------------------------------
;; Public domain operations

(defn new-endpoint
  "Build a registered endpoint from `data`, refusing first on the
  address, then on the capability, then on the count limit — all
  before the caller writes anything.

  `secret` is minted by the caller: randomness is an effect."
  [bank-id data secret resolved-addresses platform-hosts existing-count
   policies]
  (let [{:keys [address description kinds idempotency-key]} data
        endpoint-id (utility/generate-id "whe")
        now (utility/now)]
    (let-nom>
      [_ (check-address address resolved-addresses platform-hosts)
       _ (check-capability :webhook-endpoint-action-register policies)
       _ (check-limit existing-count policies)]
      (utility/assoc-some
       {:bank-id bank-id
        :endpoint-id endpoint-id
        :address address
        :status enabled
        :secret secret
        ;; Proto2 requires the key, and the unique index over it is what
        ;; makes a retried registration read the first endpoint back. A
        ;; caller that sends none gets the endpoint's own id, which is
        ;; unique per registration and so reads nothing back.
        :idempotency-key (or idempotency-key endpoint-id)
        :created-at now
        :updated-at now}
       :description description
       :kinds (seq kinds)))))

(defn update-endpoint
  "Replace the editable fields — address, description and kinds — as
  an absolute set, re-running the address rule."
  [endpoint data resolved-addresses platform-hosts policies]
  (let [{:keys [address description kinds]} data]
    (let-nom>
      [_ (ensure-status endpoint live-statuses)
       _ (check-address address resolved-addresses platform-hosts)
       _ (check-capability :webhook-endpoint-action-manage policies)]
      (utility/assoc-some
       (assoc endpoint
              :address address
              :updated-at (utility/now))
       :description description
       :kinds (seq kinds)))))

(defn enable
  [endpoint policies]
  (let-nom>
    [_ (ensure-status endpoint #{disabled paused})
     _ (check-capability :webhook-endpoint-action-manage policies)]
    (assoc endpoint :status enabled :updated-at (utility/now))))

(defn disable
  [endpoint policies]
  (let-nom>
    [_ (ensure-status endpoint #{enabled paused})
     _ (check-capability :webhook-endpoint-action-manage policies)]
    (assoc endpoint :status disabled :updated-at (utility/now))))

(defn pause
  "The runner's transition, taken on a failure `should-pause?` admits.
  It takes no capability check: the platform pauses the endpoint, not
  the tenant."
  [endpoint]
  (let-nom>
    [_ (ensure-status endpoint #{enabled})]
    (assoc endpoint :status paused :updated-at (utility/now))))

(defn remove-endpoint
  [endpoint policies]
  (let-nom>
    [_ (ensure-status endpoint live-statuses)
     _ (check-capability :webhook-endpoint-action-manage policies)]
    (assoc endpoint :status removed :updated-at (utility/now))))

(defn rotate-secret
  "Move the current secret to `:previous-secret`, expiring at
  `previous-expires-at`, and take `secret` as the current one. The
  rotation's key is kept on the record, so a retry under it returns
  this same pair rather than minting a third secret."
  [endpoint secret previous-expires-at idempotency-key policies]
  (let-nom>
    [_ (ensure-status endpoint live-statuses)
     _ (check-capability :webhook-endpoint-action-manage policies)]
    (utility/assoc-some
     (assoc endpoint
            :secret secret
            :previous-secret (:secret endpoint)
            :previous-secret-expires-at previous-expires-at
            :updated-at (utility/now))
     :rotation-idempotency-key
     idempotency-key)))
