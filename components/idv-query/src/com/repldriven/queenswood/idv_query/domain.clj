(ns com.repldriven.queenswood.idv-query.domain
  (:require
    [com.repldriven.queenswood.policy.interface :as policy]

    [com.repldriven.mono.error.interface :as error]

    [clojure.string :as str]))

(def criteria
  [{:verification :idv-verification-identity}
   {:verification :idv-verification-liveness}
   {:verification :idv-verification-claimed-identity}
   {:verification :idv-verification-address}
   {:screening :idv-screening-sanctions} {:screening :idv-screening-pep}])

(defn- set-value
  [v]
  (when (and (keyword? v) (not (str/ends-with? (name v) "-unknown")))
    v))

(defn criterion-value
  [criterion]
  (or (set-value (:verification criterion))
      (set-value (:screening criterion))))

(defn criterion-name
  [criterion]
  (str/replace (name (criterion-value criterion))
               #"^idv-(verification|screening)-"
               ""))

(defn accept-request
  [criterion]
  (let [verification (set-value (:verification criterion))
        screening (set-value (:screening criterion))]
    (cond-> {:action :idv-action-accept :party-type :party-type-person}
            verification
            (assoc :unverified verification)

            screening
            (assoc :unscreened screening))))

(defn outstanding-reason
  [policies criterion]
  (let [result (policy/check-capability policies
                                        :idv
                                        (accept-request criterion))]
    (when (error/anomaly? result)
      (:message (error/payload result)))))

(defn- same-criterion?
  [a b]
  (= (criterion-value a) (criterion-value b)))

(defn- settled-status
  [idv criterion]
  (some (fn [c] (when (same-criterion? c criterion) (:status c)))
        (:criteria idv)))

(defn verification
  [idv policies]
  (let [{:keys [verification-id status]} idv]
    {:verification-id verification-id
     :status status
     :criteria
     (into []
           (keep (fn [criterion]
                   (let [settled (settled-status idv criterion)
                         reason (outstanding-reason policies criterion)]
                     (cond
                      (contains? #{:idv-criterion-status-established
                                   :idv-criterion-status-review
                                   :idv-criterion-status-failed}
                                 settled)
                      (assoc criterion :status settled)

                      (some? reason)
                      (assoc criterion
                             :status :idv-criterion-status-outstanding
                             :reason reason)))))
           criteria)}))

(defn session
  [idv-session now]
  (let [{:keys [status hand-off]} idv-session
        expired? (and (= :idv-session-status-ready status)
                      (<= (or (:expires-at hand-off) 0) now))]
    (cond-> (dissoc idv-session :hand-off)
            expired?
            (assoc :status :idv-session-status-expired)

            (and (= :idv-session-status-ready status) (not expired?))
            (assoc :hand-off hand-off))))
