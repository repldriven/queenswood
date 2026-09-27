(ns com.repldriven.queenswood.test-scenarios.verification
  "Stands in for the tenant's app and the person when a verb onboards a
  person: opens a verification session for the party, waits for its
  hand-off, and submits a document that matches the party to the
  identity-provider simulator's decision route — the body its hosted
  page posts."
  (:require
    [com.repldriven.queenswood.idv.interface :as idv]
    [com.repldriven.queenswood.idv-query.interface :as idv-query]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str]))

(def ^:private deadline-ms 15000)

(def ^:private poll-interval-ms 50)

(def ^:private idv-provider
  "The provider declaration the scenario rig's adapter runs under."
  {:verifies ["identity" "liveness" "claimed-identity" "address"]
   :screens ["sanctions" "pep"]
   :channels ["web" "mobile"]
   :needs ["email"]})

(defn- poll
  [what f]
  (let [deadline (+ (utility/now) deadline-ms)]
    (loop []
      (let [v (f)]
        (cond
         (error/anomaly? v)
         v

         (some? v)
         v

         (>= (utility/now) deadline)
         (error/fail :scenario/quiescence-timeout
                     {:message (str "Timed out waiting for " what)})

         :else
         (do (Thread/sleep ^long poll-interval-ms) (recur)))))))

(defn- iso-date
  [yyyymmdd]
  (format "%04d-%02d-%02d"
          (quot yyyymmdd 10000)
          (rem (quot yyyymmdd 100) 100)
          (rem yyyymmdd 100)))

(defn verify
  "Verify the person `party-id` of `bank-id` as having shown a document
  that matches `person`, the payload it was created with. Returns the
  ready session or an anomaly.

  `bank` carries the FDB config, the bus and schemas, and
  `:zyphe-simulator-url`."
  [bank bank-id party-id person]
  (let [{:keys [zyphe-simulator-url]} bank
        {:keys [given-name middle-names family-name date-of-birth]} person]
    (let-nom>
      [session (idv/open-session (assoc bank
                                        :idv-command-channel :idv-command
                                        :idv-provider idv-provider)
                                 {:bank-id bank-id
                                  :party-id party-id
                                  :channel "web"
                                  :return-url "https://app.example.test/back"
                                  :email "person@example.test"})
       ready (poll "the session's hand-off"
                   (fn []
                     (let-nom> [s (idv-query/get-session bank
                                                         bank-id
                                                         (:session-id
                                                          session))]
                       (when (= :idv-session-status-ready (:status s)) s))))
       vr (or (some->> (get-in ready [:hand-off :url])
                       (re-find #"[?&]zypheVr=([^&]+)")
                       second)
              (error/fail :scenario/no-hand-off
                          {:message "The ready session carries no run"}))
       res (http/request
            {:method :post
             :url (str zyphe-simulator-url
                       "/simulator/verification-requests/"
                       vr
                       "/decision")
             :headers {"Content-Type" "application/json"}
             :body (json/write-str
                    {:outcome "match"
                     :givenNames (str/join " "
                                           (remove str/blank?
                                                   [given-name middle-names]))
                     :familyName family-name
                     :dateOfBirth (iso-date date-of-birth)})})]
      (if (= 200 (:status res))
        ready
        (error/fail :scenario/decision
                    {:message "The simulator refused the decision"
                     :status (:status res)})))))
