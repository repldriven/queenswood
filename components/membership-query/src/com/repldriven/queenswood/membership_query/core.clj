(ns com.repldriven.queenswood.membership-query.core
  (:require
    [com.repldriven.queenswood.membership-query.domain :as domain]
    [com.repldriven.queenswood.membership-query.store :as store]

    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str])
  (:import
    (java.security MessageDigest SecureRandom)
    (java.util Base64)))

(def ^:private token-bytes 32)

(def ^:private ^SecureRandom random (SecureRandom.))

(defn token-hash
  [token]
  (when (string? token)
    (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                          (.getBytes ^String token "UTF-8"))]
      ;; A Java byte is signed, so mask before formatting or every byte
      ;; over 127 renders as eight f-padded characters.
      (apply str (map #(format "%02x" (bit-and % 0xff)) digest)))))

(defn new-invitation-token
  []
  (let [bytes (byte-array token-bytes)]
    (.nextBytes random bytes)
    (let [token (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                                 bytes)]
      {:token token :token-hash (token-hash token)})))

(defn- clock
  [opts]
  (or (:now opts) (utility/now)))

(defn- as-read
  [invitation now]
  (-> invitation
      (assoc :status (domain/effective-status invitation now))
      (dissoc :token-hash)))

(defn list-by-user
  [txn user-id]
  (store/list-by-user txn user-id))

(defn list-by-bank
  [txn bank-id]
  (store/list-by-bank txn bank-id))

(defn list-active-by-user
  [txn user-id]
  (store/list-active-by-user txn user-id))

(defn list-active-by-bank
  [txn bank-id]
  (store/list-active-by-bank txn bank-id))

(defn find-by-id
  [txn membership-id]
  (let-nom> [membership (store/get-membership txn membership-id)]
    (domain/ensure-found membership membership-id)))

(defn get-invitation-record
  [txn bank-id invitation-id]
  (let-nom> [invitation (store/find-invitation txn bank-id invitation-id)]
    (domain/ensure-invitation-found invitation invitation-id)))

(defn get-invitation-record-for-recipient
  [txn invitation-id {:keys [token-hash email email-verified?] :as proof}]
  (store/transact
   txn
   (fn [txn]
     (let-nom>
       [by-token (when (string? token-hash)
                   (store/find-invitation-by-token-hash txn token-hash))
        by-email (if (and (true? email-verified?) (string? email))
                   (store/list-invitations-by-email txn (str/lower-case email))
                   [])
        invitation (domain/ensure-invitation-found
                    (some #(when (= invitation-id (:invitation-id %)) %)
                          (cons by-token by-email))
                    invitation-id)
        _ (domain/check-recipient invitation proof)]
       invitation))
   :invitation/find-for-recipient
   "Failed to load invitation"))

(defn find-invitation
  [txn bank-id invitation-id opts]
  (let-nom> [invitation (get-invitation-record txn bank-id invitation-id)]
    (as-read invitation (clock opts))))

(defn find-invitation-for-recipient
  [txn invitation-id proof opts]
  (let-nom> [invitation (get-invitation-record-for-recipient txn
                                                             invitation-id
                                                             proof)]
    (as-read invitation (clock opts))))

(defn list-invitations-by-bank
  [txn bank-id opts]
  (let [now (clock opts)]
    (let-nom> [invitations (store/list-invitations-by-bank txn bank-id)]
      (mapv #(as-read % now) invitations))))

(defn list-pending-invitations-by-email
  [txn email opts]
  (if-not (string? email)
    []
    (let [now (clock opts)]
      (let-nom> [invitations (store/list-invitations-by-email
                              txn
                              (str/lower-case email))]
        (into []
              (comp (map #(as-read % now))
                    (filter #(= :invitation-status-pending (:status %))))
              invitations)))))

(defn list-access-events
  [txn bank-id opts]
  (store/scan-access-events txn bank-id opts))
