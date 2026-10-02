(ns com.repldriven.queenswood.idv.core
  (:refer-clojure :exclude [get])
  (:require
    [com.repldriven.queenswood.idv.domain :as domain]
    [com.repldriven.queenswood.idv.store :as store]

    [com.repldriven.queenswood.bank-activity.interface :as bank-activity]
    [com.repldriven.queenswood.bank-query.interface :as bank-query]
    [com.repldriven.queenswood.idv-provider.interface :as idv-provider]
    [com.repldriven.queenswood.idv-query.interface :as idv-query]
    [com.repldriven.queenswood.party-query.interface :as party-query]
    [com.repldriven.queenswood.person-identification.interface :as
     person-identification]
    [com.repldriven.queenswood.policy.interface :as policy]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.utility.interface :as utility]))

(defn- fdb-config
  [config]
  (select-keys config [:record-db :record-store]))

(defn bank-provider
  [config txn bank-id]
  (when-let [providers (:idv-providers config)]
    (let-nom> [bank (bank-query/find-bank txn bank-id)]
      (idv-provider/for-bank providers bank))))

(defn- check-data
  [session identification criteria]
  (let [{:keys [verification-id party-id session-id channel return-url email]}
        session
        {:keys [given-name middle-names family-name date-of-birth address]}
        identification]
    (utility/assoc-some {:verification-id verification-id
                         :party-id party-id
                         :first-name (or given-name "")
                         :last-name (or family-name "")
                         :session-id session-id
                         :verifications (keep (fn [c]
                                                (when (:verification c)
                                                  (idv-query/criterion-name
                                                   c)))
                                              criteria)
                         :screenings (keep (fn [c]
                                             (when (:screening c)
                                               (idv-query/criterion-name c)))
                                           criteria)}
                        :middle-names middle-names
                        :date-of-birth (when date-of-birth (str date-of-birth))
                        :address address
                        :channel channel
                        :return-url return-url
                        :email email)))

(defn- record-opening
  [txn session identification criteria]
  (let [{:keys [bank-id session-id]} session]
    (bank-activity/record txn
                          {:bank-id bank-id
                           :event-name "idv-session-opening"
                           :data (check-data session identification criteria)
                           :causation-id session-id
                           :dedup-key session-id})))

(defn save-idv
  "Save an IDV, converting a uniqueness-violation result into an
  `:idv/already-exists` rejection."
  [txn idv changelog]
  (let [result (store/save-idv txn idv changelog)]
    (if (store/uniqueness-violation? result)
      (error/reject :idv/already-exists
                    {:message "IDV already exists for party"
                     :party-id (:party-id idv)})
      result)))

(defn get-idv
  "Load an IDV by composite primary key, rejecting with
  `:idv/not-found` if the record is missing."
  [txn bank-id verification-id]
  (let-nom> [idv (idv-query/get-idv txn bank-id verification-id)]
    (or idv
        (error/reject :idv/not-found
                      {:message "IDV not found"
                       :bank-id bank-id
                       :verification-id verification-id}))))

(defn initiate
  [config data]
  (let-nom>
    [idv (domain/new-idv data)]
    (save-idv (fdb-config config)
              idv
              {:verification-id (:verification-id idv)
               :status-after (:status idv)})))

(defn initiate-for-party
  "Open an IDV for a party that has just entered pending. The IDV waits,
  pending, for the tenant to open a verification session.

  Gated on no IDV already existing for the party, and skips silently
  when one does. Event redelivery and replay must be a no-op here, not
  a rejection — a party arriving pending twice is the normal case. A
  session opened between the check and the save creates the IDV itself,
  and the save's refusal is the same skip."
  [config bank-id party-id]
  (let-nom>
    [existing (idv-query/get-idv-by-party (fdb-config config) party-id)]
    (if existing
      (log/info "IDV already exists for party — skipping"
                {:party-id party-id
                 :verification-id (:verification-id existing)
                 :status (:status existing)})
      (let [result (initiate config {:bank-id bank-id :party-id party-id})]
        (if (= :idv/already-exists (error/kind result))
          (log/info "IDV already exists for party — skipping"
                    {:party-id party-id})
          result)))))

(defn- no-verification
  [bank-id party-id]
  (error/reject :idv/not-found
                {:message "No verification for this party"
                 :bank-id bank-id
                 :party-id party-id}))

(defn- initiate-in
  "The IDV for a pending person whose party event has not been handled
  yet, created in `txn`."
  [txn bank-id party-id]
  (let [party (party-query/get-party txn bank-id party-id)]
    (if (and (not (error/anomaly? party))
             (= :party-type-person (:type party))
             (= :party-status-pending (:status party)))
      (let [idv (domain/new-idv {:bank-id bank-id :party-id party-id})]
        (save-idv txn
                  idv
                  {:verification-id (:verification-id idv)
                   :status-after (:status idv)}))
      (no-verification bank-id party-id))))

(defn- get-party-idv
  [txn bank-id party-id]
  (let-nom> [idv (idv-query/get-idv-by-party txn party-id)]
    (cond
     (nil? idv)
     (initiate-in txn bank-id party-id)

     (= bank-id (:bank-id idv))
     idv

     :else
     (no-verification bank-id party-id))))

(defn open-session
  "Open a verification session for a party's pending IDV, recording it
  as the bank's activity so the provider is asked for a hand-off. A pending person whose IDV
  the party event has not created yet gets it here, so a session opened
  straight after the party is not refused. Returns the session, opening,
  or an anomaly."
  [config data]
  (let [{:keys [bank-id party-id]} data]
    (store/transact
     (fdb-config config)
     (fn [txn]
       (let-nom>
         [idv (get-party-idv txn bank-id party-id)
          provider (bank-provider config txn bank-id)
          policies (policy/get-effective-policies txn
                                                  {:bank-id
                                                   bank-id})
          opened-today (idv-query/count-sessions-on
                        txn
                        bank-id
                        (utility/today))
          _ (domain/check-open-session idv
                                       (:declaration provider)
                                       data
                                       policies
                                       opened-today)
          identification
          (person-identification/get-person-identification
           txn
           party-id)
          session (store/save-session txn
                                      (domain/new-session idv data)
                                      nil)
          criteria (domain/required-criteria policies)
          _ (record-opening txn
                            (assoc session
                                   :channel (:channel data)
                                   :email (:email data))
                            identification
                            criteria)]
         session))
     :idv/open-session
     "Failed to open a verification session")))

(defn- complete-sessions
  [txn idv]
  (let-nom> [sessions (idv-query/get-sessions-by-verification
                       txn
                       (:bank-id idv)
                       (:verification-id idv))]
    (reduce (fn [_ session]
              (if-let [completed (domain/completed-session session)]
                (let [result (store/save-session txn
                                                 completed
                                                 (:status session))]
                  (if (error/anomaly? result) (reduced result) nil))
                nil))
            nil
            sessions)))

(defn apply-evidence
  "Merge a provider's reported evidence into its IDV and decide it.
  Skips, returning nil, an IDV that is missing or no longer awaiting
  evidence — delivery is at-least-once and a replay must be a no-op."
  [config data]
  (let [{:keys [bank-id verification-id]} data]
    (store/transact
     (fdb-config config)
     (fn [txn]
       (let-nom>
         [idv (idv-query/get-idv txn bank-id verification-id)]
         (if (nil? idv)
           (log/warn "Evidence for an unknown IDV — skipping"
                     {:verification-id verification-id})
           (let-nom>
             [policies (policy/get-effective-policies txn {:bank-id bank-id})
              claimed (person-identification/get-person-identification
                       txn
                       (:party-id idv))]
             (let [updated (domain/apply-evidence idv data policies claimed)]
               (if (error/anomaly? updated)
                 (log/info "IDV not awaiting evidence — skipping"
                           {:verification-id verification-id
                            :status (:status idv)})
                 (let [changed? (not= (:status idv) (:status updated))]
                   (let-nom>
                     [_ (save-idv txn
                                  updated
                                  (when changed?
                                    {:verification-id verification-id
                                     :status-before (:status idv)
                                     :status-after (:status updated)}))
                      _ (when (and changed?
                                   (= :idv-status-pending (:status idv)))
                          (complete-sessions txn updated))]
                     updated))))))))
     :idv/apply-evidence
     "Failed to apply IDV evidence")))

(defn record-hand-off
  "Make a session ready with the hand-off the adapter reported. Skips,
  returning nil, a session that is missing or already completed, and a
  report of the hand-off the session already holds."
  [config data]
  (let [{:keys [bank-id session-id url expires-at]} data]
    (store/transact
     (fdb-config config)
     (fn [txn]
       (let-nom>
         [session (idv-query/get-session txn bank-id session-id)]
         (if-let [ready (some-> session
                                (domain/ready-session url expires-at))]
           (store/save-session txn ready (:status session))
           (log/info "Hand-off already held or no open session — skipping"
                     {:session-id session-id}))))
     :idv/record-hand-off
     "Failed to record an IDV hand-off")))

(defn fail-session
  "Fail a session whose check the provider could not run, with its
  reason. Skips, returning nil, a session that is missing, completed or
  already failed."
  [config data]
  (let [{:keys [bank-id session-id reason]} data]
    (store/transact
     (fdb-config config)
     (fn [txn]
       (let-nom>
         [session (idv-query/get-session txn bank-id session-id)]
         (if-let [failed (some-> session
                                 (domain/failed-session
                                  (or reason
                                      "The provider could not run the check")))]
           (store/save-session txn failed (:status session))
           (log/info "No open session to fail — skipping"
                     {:session-id session-id}))))
     :idv/fail-session
     "Failed to record an IDV session's failure")))

(defn get
  [txn data]
  (get-idv txn (:bank-id data) (:verification-id data)))
