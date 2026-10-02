(ns com.repldriven.queenswood.idv.domain
  (:require
    [com.repldriven.queenswood.idv-query.interface :as idv-query]
    [com.repldriven.queenswood.party-query.interface :as party-query]
    [com.repldriven.queenswood.policy.interface :as policy]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str]))

(defn- required?
  [policies criterion]
  (some? (idv-query/outstanding-reason policies criterion)))

(defn required-criteria
  [policies]
  (filterv (fn [criterion] (required? policies criterion)) idv-query/criteria))

(defn unmet-criteria
  [policies declaration]
  (let [{:keys [verifies screens]} declaration
        declared (set (concat verifies screens))]
    (into []
          (comp (remove (fn [criterion]
                          (declared (idv-query/criterion-name criterion))))
                (map (fn [criterion]
                       (or (:verification criterion) (:screening criterion)))))
          (required-criteria policies))))

(defn check-criteria
  [policies declaration]
  (let [unmet (unmet-criteria policies declaration)]
    (when (seq unmet)
      (error/reject
       :idv/unsupported-criteria
       {:message (str "The identity provider cannot establish "
                      (str/join ", "
                                (map (fn [v]
                                       (str/replace
                                        (name v)
                                        #"^idv-(verification|screening)-"
                                        ""))
                                     unmet)))
        :unmet unmet}))))

(defn- guard-source-status
  [idv message allowed]
  (when-not (contains? allowed (:status idv))
    (error/reject :idv/invalid-status
                  {:message message
                   :verification-id (:verification-id idv)
                   :status (:status idv)
                   :allowed allowed})))

(defn new-idv
  [data]
  (let [{:keys [bank-id party-id]} data
        now (utility/now)]
    {:bank-id bank-id
     :party-id party-id
     :verification-id (utility/generate-id "idv")
     :status :idv-status-pending
     :created-at now
     :updated-at now}))

(defn in-review-idv
  [idv]
  (let-nom>
    [_ (guard-source-status idv
                            "IDV is not awaiting a result"
                            #{:idv-status-pending})]
    (assoc idv
           :status :idv-status-in-review
           :updated-at (utility/now))))

(defn accepted-idv
  [idv]
  (let-nom>
    [_ (guard-source-status idv
                            "IDV is not in a status that can be accepted"
                            #{:idv-status-pending :idv-status-in-review})]
    (assoc idv
           :status :idv-status-accepted
           :completed-at (utility/now)
           :updated-at (utility/now))))

(defn rejected-idv
  [idv]
  (let-nom>
    [_ (guard-source-status idv
                            "IDV is not in a status that can be rejected"
                            #{:idv-status-pending :idv-status-in-review})]
    (assoc idv
           :status :idv-status-rejected
           :completed-at (utility/now)
           :updated-at (utility/now))))

(defn failed-idv
  [idv]
  (let-nom>
    [_ (guard-source-status idv
                            "IDV is not in a status that can fail"
                            #{:idv-status-pending :idv-status-in-review})]
    (assoc idv
           :status :idv-status-failed
           :completed-at (utility/now)
           :updated-at (utility/now))))

(def ^:private evidence-sections [:document :liveness :address :screening])

(defn merge-evidence
  [evidence reported]
  (let [merged (reduce (fn [ev k]
                         (if-some [section (get reported k)]
                           (assoc ev k section)
                           ev))
                       (or evidence {})
                       evidence-sections)]
    (cond-> merged
            (:cancelled reported)
            (assoc :cancelled true))))

(defn- present
  [s]
  (when-not (str/blank? s) s))

(defn- iso-date
  [yyyymmdd]
  (when (and yyyymmdd (pos? yyyymmdd))
    (format "%04d-%02d-%02d"
            (quot yyyymmdd 10000)
            (rem (quot yyyymmdd 100) 100)
            (rem yyyymmdd 100))))

(defn- full-name
  [& parts]
  (str/join " " (keep present parts)))

(def ^:private outcome->state
  {:idv-evidence-outcome-passed :idv-criterion-state-established
   :idv-evidence-outcome-review :idv-criterion-state-review
   :idv-evidence-outcome-failed :idv-criterion-state-failed})

(def ^:private sanctions->state
  {:idv-sanctions-outcome-clear :idv-criterion-state-established
   :idv-sanctions-outcome-possible-match :idv-criterion-state-review
   :idv-sanctions-outcome-hit :idv-criterion-state-failed})

(defn- claimed-identity-state
  [document claimed]
  (let [{:keys [given-names family-name date-of-birth]} document
        read-name (full-name given-names family-name)]
    (when (and (present read-name) (present (:family-name claimed)))
      (let [grade (party-query/match-name (full-name (:given-name claimed)
                                                     (:middle-names claimed)
                                                     (:family-name claimed))
                                          read-name)
            same-birth? (= (iso-date (:date-of-birth claimed))
                           (present date-of-birth))]
        (cond
         (or (= :no-match grade) (not same-birth?))
         :idv-criterion-state-failed

         (= :close-match grade)
         :idv-criterion-state-review

         :else
         :idv-criterion-state-established)))))

(defn- settle
  [evidence claimed criterion]
  (let [{:keys [document liveness address screening]} evidence]
    (or (case (or (:verification criterion) (:screening criterion))
          :idv-verification-identity
          (outcome->state (:outcome document))

          :idv-verification-liveness
          (outcome->state (:outcome liveness))

          :idv-verification-claimed-identity
          (claimed-identity-state document claimed)

          :idv-verification-address
          (outcome->state (:outcome address))

          :idv-screening-sanctions
          (sanctions->state (:sanctions screening))

          :idv-screening-pep
          (when screening
            (if (:pep screening)
              :idv-criterion-state-review
              :idv-criterion-state-established)))
        :idv-criterion-state-outstanding)))

(defn- accepted?
  [policies criteria]
  (let [outstanding (filter (fn [c]
                              (= :idv-criterion-state-outstanding (:state c)))
                            criteria)
        requests (if (seq outstanding)
                   (map (fn [c] (idv-query/accept-request (dissoc c :state)))
                        outstanding)
                   [(idv-query/accept-request {})])]
    (every? (fn [request]
              (true? (policy/check-capability policies :idv request)))
            requests)))

(defn decide
  [idv policies claimed]
  (let [{:keys [evidence]} idv
        criteria (mapv (fn [criterion]
                         (assoc criterion
                                :state
                                (settle evidence claimed criterion)))
                       idv-query/criteria)
        states (set (map :state criteria))]
    {:criteria criteria
     :status (cond
              (contains? states :idv-criterion-state-failed)
              :idv-status-rejected

              (:cancelled evidence)
              :idv-status-failed

              (contains? states :idv-criterion-state-review)
              :idv-status-in-review

              (accepted? policies criteria)
              :idv-status-accepted

              :else
              :idv-status-pending)}))

(def ^:private status->transition
  {:idv-status-in-review in-review-idv
   :idv-status-accepted accepted-idv
   :idv-status-rejected rejected-idv
   :idv-status-failed failed-idv})

(defn apply-evidence
  [idv reported policies claimed]
  (let-nom>
    [_ (guard-source-status idv
                            "IDV is not awaiting evidence"
                            #{:idv-status-pending :idv-status-in-review})]
    (let [merged (assoc idv
                        :evidence
                        (merge-evidence (:evidence idv) reported))
          {:keys [criteria status]} (decide merged policies claimed)
          settled (assoc merged :criteria criteria :updated-at (utility/now))
          transition (status->transition status)]
      (if (and transition (not= status (:status idv)))
        (transition settled)
        settled))))

(def ^:private channels
  {"web" :idv-session-channel-web "mobile" :idv-session-channel-mobile})

(defn check-open-session
  [idv declaration data policies opened-today]
  (let [{:keys [channel email]} data
        {:keys [needs]} declaration]
    (let-nom>
      [_ (guard-source-status idv
                              "IDV is not awaiting a verification"
                              #{:idv-status-pending})
       _ (when-not (and (channels channel)
                        (some #{channel} (:channels declaration)))
           (error/reject :idv/unsupported-channel
                         {:message
                          "The identity provider does not offer this channel"
                          :channel channel
                          :allowed (set (:channels declaration))}))
       _ (when (and (some #{"email"} needs) (str/blank? email))
           (error/reject :idv/missing-email
                         {:message
                          "The identity provider needs the person's email"
                          :verification-id (:verification-id idv)}))
       _ (policy/check-capability policies :idv {:action :idv-action-submit})]
      (policy/check-limit policies
                          :idv
                          {:aggregate :count
                           :window :time-window-daily
                           :value (inc opened-today)}))))

(defn new-session
  [idv data]
  (let [{:keys [bank-id verification-id party-id]} idv
        {:keys [channel return-url]} data
        now (utility/now)]
    {:bank-id bank-id
     :session-id (utility/generate-id "ses")
     :verification-id verification-id
     :party-id party-id
     :channel (channels channel)
     :return-url return-url
     :status :idv-session-status-opening
     :opened-day (utility/today)
     :created-at now
     :updated-at now}))

(defn ready-session
  [session url expires-at]
  (when (and (#{:idv-session-status-opening :idv-session-status-ready}
              (:status session))
             (not= {:url url :expires-at expires-at}
                   (select-keys (:hand-off session) [:url :expires-at])))
    (assoc session
           :status :idv-session-status-ready
           :hand-off {:type :idv-hand-off-type-url
                      :url url
                      :expires-at expires-at}
           :updated-at (utility/now))))

(defn failed-session
  [session reason]
  (when (#{:idv-session-status-opening :idv-session-status-ready}
         (:status session))
    (-> session
        (dissoc :hand-off)
        (utility/assoc-some :failure-reason reason)
        (assoc :status :idv-session-status-failed
               :updated-at (utility/now)))))

(defn completed-session
  [session]
  (when-not (= :idv-session-status-completed (:status session))
    (-> session
        (dissoc :hand-off)
        (assoc :status :idv-session-status-completed
               :updated-at (utility/now)))))
