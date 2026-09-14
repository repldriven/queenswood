(ns com.repldriven.queenswood.membership-query.domain
  (:require
    [com.repldriven.mono.error.interface :as error]

    [clojure.string :as str]))

(def ^:private pending :invitation-status-pending)
(def ^:private expired :invitation-status-expired)

(defn effective-status
  [invitation now]
  (let [{:keys [status expires-at]} invitation]
    (if (and (= pending status) expires-at (<= expires-at now))
      expired
      status)))

(defn- membership-not-found
  [membership-id]
  (error/reject :membership/not-found
                {:message "Membership not found"
                 :membership-id membership-id}))

(defn ensure-found
  [membership membership-id]
  (or membership (membership-not-found membership-id)))

(defn- invitation-not-found
  [invitation-id]
  (error/reject :invitation/not-found
                {:message "Invitation not found"
                 :invitation-id invitation-id}))

(defn ensure-invitation-found
  [invitation invitation-id]
  (or invitation (invitation-not-found invitation-id)))

(defn check-recipient
  [invitation {:keys [token-hash email email-verified?]}]
  (when-not (or (and (some? token-hash)
                     (= token-hash (:token-hash invitation)))
                (and (true? email-verified?)
                     (some? email)
                     (= (str/lower-case email) (:email-lower invitation))))
    (invitation-not-found (:invitation-id invitation))))
