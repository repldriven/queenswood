(ns com.repldriven.queenswood.party-query.core
  (:require
    [com.repldriven.queenswood.party-query.store :as store]

    [com.repldriven.queenswood.person-identification.interface :as person-id]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(defn get-party
  [txn bank-id party-id]
  (let-nom> [party (store/get-party txn bank-id party-id)]
    (or party
        (error/reject :party/not-found
                      {:message "Party not found"
                       :bank-id bank-id
                       :party-id party-id}))))

(defn get-party-detail
  [txn bank-id party-id {:keys [person-identification]}]
  ;; Only the summary party read is load-bearing (not-found ⇒ 404). The
  ;; names are opt-in through `embed` and best-effort: a failure there
  ;; must never break the party read the rest of the system (and the
  ;; IDV-status poll) depends on.
  (let-nom> [party (get-party txn bank-id party-id)]
    (let [pi (when person-identification
               (let [r (error/try-nom
                        :party/person-identification-read
                        "Failed to load person identification"
                        (person-id/get-person-identification txn party-id))]
                 (when-not (error/anomaly? r) r)))]
      (cond-> party
              pi
              (merge (select-keys pi
                                  [:given-name :middle-names
                                   :family-name]))))))
