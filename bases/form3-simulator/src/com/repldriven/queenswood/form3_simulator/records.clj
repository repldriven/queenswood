(ns com.repldriven.queenswood.form3-simulator.records
  "The simulator's resources, held in one atom as Form3 serves them —
  JSON:API resources with snake_case attributes — keyed by kind and id,
  with an index of registered accounts by sort code and account number.
  A decision the scheme waits on, an admission's, is a promise beside
  them."
  (:require
    [com.repldriven.mono.utility.interface :as utility])
  (:import
    (java.time Instant)))

(defn empty-state
  []
  {:accounts {}
   :by-number {}
   :payments {}
   :submissions {}
   :admissions {}
   :tasks {}
   :returns {}
   :return-submissions {}
   :return-admissions {}
   :subscriptions {}
   :name-verifications {}
   :decisions {}
   :refuse-next false
   :refuse-next-close false
   :refuse-next-return false})

(defn timestamp
  []
  (str (Instant/ofEpochMilli (utility/now))))

(defn new-id [] (str (utility/uuidv7)))

(defn resource
  "A resource as Form3 serves one."
  ([organisation-id type id attributes]
   (resource organisation-id type id attributes nil))
  ([organisation-id type id attributes relationships]
   (utility/assoc-some {:id id
                        :type type
                        :organisation_id organisation-id
                        :version 0
                        :created_on (timestamp)
                        :modified_on (timestamp)
                        :attributes attributes}
                       :relationships
                       relationships)))

(defn get-in-state
  [state kind id]
  (get-in @state [kind id]))

(defn put
  [state kind r]
  (swap! state assoc-in [kind (:id r)] r)
  r)

(defn change
  "Apply `f` to the resource's attributes, bumping its version."
  [state kind id f]
  (get-in (swap! state
            update-in
            [kind id]
            (fn [r]
              (-> r
                  (update :attributes f)
                  (update :version inc)
                  (assoc :modified_on (timestamp)))))
          [kind id]))

(defn- number-key
  [sort-code account-number]
  (str sort-code account-number))

(defn account-by-number
  [state sort-code account-number]
  (some->> (get-in @state [:by-number (number-key sort-code account-number)])
           (get-in-state state :accounts)))

(defn register-account
  "Record the account, or nil where one already holds its number."
  [state account]
  (let [{:keys [bank_id account_number]} (:attributes account)
        k (number-key bank_id account_number)]
    (locking state
      (when-not (get-in @state [:by-number k])
        (swap! state
          (fn [s]
            (-> s
                (assoc-in [:accounts (:id account)] account)
                (assoc-in [:by-number k] (:id account)))))
        account))))

(defn take-refusal
  "True, once, after a control route asked for the next registration to
  fail."
  [state]
  (locking state
    (let [refuse (:refuse-next @state)]
      (when refuse (swap! state assoc :refuse-next false))
      refuse)))

(defn take-close-refusal
  "True, once, after a control route asked for the next close to fail."
  [state]
  (locking state
    (let [refuse (:refuse-next-close @state)]
      (when refuse (swap! state assoc :refuse-next-close false))
      refuse)))

(defn take-return-refusal
  "True, once, after a control route asked for the next return to fail."
  [state]
  (locking state
    (let [refuse (:refuse-next-return @state)]
      (when refuse (swap! state assoc :refuse-next-return false))
      refuse)))

(defn find-payments
  [state pred]
  (filter pred (vals (:payments @state))))

(defn related
  "The resources of `kind` related to the payment `payment-id`."
  [state kind payment-id]
  (->> (vals (get @state kind))
       (filter (fn [r] (= payment-id (::payment-id r))))
       (sort-by :created_on)))

(defn decision
  "The promise an admission's decision is delivered to."
  [state admission-id]
  (locking state
    (or (get-in @state [:decisions admission-id])
        (let [p (promise)]
          (swap! state assoc-in [:decisions admission-id] p)
          p))))

(defn subscriptions
  [state record-type event-type]
  (filter (fn [{:keys [attributes]}]
            (and (not (:deactivated attributes))
                 (contains? #{record-type "*"} (:record_type attributes))
                 (contains? #{event-type "*" nil} (:event_type attributes))))
          (vals (:subscriptions @state))))

(defn public
  "A resource as the API serves it, without the simulator's own keys."
  [r]
  (dissoc r ::payment-id ::direction ::return-id))
