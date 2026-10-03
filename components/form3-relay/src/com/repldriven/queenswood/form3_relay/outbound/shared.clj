(ns com.repldriven.queenswood.form3-relay.outbound.shared
  (:require
    [com.repldriven.queenswood.form3-relay.form3 :as form3]

    [com.repldriven.mono.utility.interface :as utility]

    [clojure.edn :as edn]))

(defn reconcile-at
  [config now]
  (+ now (:reconcile-after-ms config)))

(defn context
  [intent]
  (or (some-> (not-empty (:context intent))
              edn/read-string)
      {}))

(defn call
  [config request]
  (form3/classify ((or (:post-fn config) form3/request) config request)))

(defn done?
  "True for a create Form3 answered, or refused as one it already has."
  [outcome]
  (contains? #{:ok :exists} outcome))

(defn steps
  "Make each call in turn while Form3 answers or already has it: the
  first other outcome, or the last answer."
  [config requests]
  (reduce (fn [_ request]
            (let [[outcome :as res] (call config request)]
              (if (done? outcome) res (reduced res))))
          [:ok nil]
          requests))

(defn answer
  "A Form3 outcome as the poller reads one: a create Form3 answered or
  already has is answered."
  [[outcome result]]
  [(if (done? outcome) :answered outcome) result])

(defn undelivered
  [failure reason]
  (if (= :undelivered failure) (str "Undelivered: " reason) reason))

(defn sent
  "Submitted, to be reconciled if no notification settles it first."
  [config now intent]
  {:status "sent"
   :changes (utility/assoc-some {:next-attempt-at (reconcile-at config now)}
                                :provider-payment-id
                                (:provider-payment-id intent))})

(defn wait
  [config now]
  {:status "sent" :changes {:next-attempt-at (reconcile-at config now)}})

(defn payment-path
  [payment-id]
  (str "/v1/transaction/payments/" payment-id))

(defn account-event
  [intent event-name data]
  {:event-name event-name
   :dedup-key (str (:dedup-key intent) ":" event-name)
   :data data})
