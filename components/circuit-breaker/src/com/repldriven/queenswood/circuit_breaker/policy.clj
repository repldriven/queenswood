(ns com.repldriven.queenswood.circuit-breaker.policy)

(def ^:private retry-schema
  [:map
   [:initial-backoff-ms pos-int?]
   [:backoff-growth pos-int?]
   [:max-backoff-ms pos-int?]
   [:max-attempts pos-int?]
   [:max-age-ms pos-int?]])

(def breaker-schema
  [:map
   [:failure-threshold pos-int?]
   [:cool-down-ms pos-int?]
   [:max-cool-down-ms pos-int?]
   [:probe-lease-ms pos-int?]])

(def delivery-policy-schema
  [:map
   [:default retry-schema]
   [:operations {:optional true}
    [:map-of keyword?
     [:map
      [:initial-backoff-ms {:optional true} pos-int?]
      [:backoff-growth {:optional true} pos-int?]
      [:max-backoff-ms {:optional true} pos-int?]
      [:max-attempts {:optional true} pos-int?]
      [:max-age-ms {:optional true} pos-int?]]]]
   [:breaker breaker-schema]])

(defn retry-policy
  [delivery-policy operation]
  (let [{:keys [default operations]} delivery-policy]
    (merge default
           (get operations
                (some-> operation
                        keyword)))))

(defn backoff-ms
  [retry-policy attempts]
  (let [{:keys [initial-backoff-ms backoff-growth max-backoff-ms]}
        retry-policy]
    (reduce (fn [delay _] (min max-backoff-ms (* backoff-growth delay)))
            (min max-backoff-ms initial-backoff-ms)
            (range (dec (max 1 attempts))))))

(defn give-up?
  [retry-policy attempts age-ms]
  (let [{:keys [max-attempts max-age-ms]} retry-policy]
    (or (>= attempts max-attempts) (> (or age-ms 0) max-age-ms))))
