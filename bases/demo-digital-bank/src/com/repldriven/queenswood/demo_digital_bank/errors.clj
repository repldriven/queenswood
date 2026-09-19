(ns com.repldriven.queenswood.demo-digital-bank.errors
  (:require
    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.log.interface :as log]

    [clojure.string :as str]))

(def ^:private retry-later
  "The kinds that mean the platform or the store, not the request."
  #{"platform" "jdbc" "http-client"})

(defn- status
  [anomaly]
  (let [kind (error/kind anomaly)]
    (cond (error/unauthorized? anomaly)
          401
          (error/rejection? anomaly)
          (cond (str/ends-with? (name kind) "not-found")
                404
                (= "invalid-status" (name kind))
                409
                :else
                422)
          (contains? retry-later (namespace kind))
          503
          :else
          500)))

(defn- title
  [anomaly]
  (cond (error/unauthorized? anomaly)
        "UNAUTHORIZED"
        (error/rejection? anomaly)
        "REJECTED"
        :else
        "FAILED"))

(defn anomaly->response
  "An RFC 9457 problem details response for an anomaly, logged where
  it is the bank's or the platform's fault rather than the caller's."
  [anomaly]
  (let [status (status anomaly)
        {:keys [message exception]} (error/payload anomaly)]
    (when (<= 500 status)
      (if exception
        (log/error exception (str (error/kind anomaly) ": " message))
        (log/error (str (error/kind anomaly) ": " message))))
    {:status status
     :body (cond-> {:title (title anomaly)
                    :type (str (error/kind anomaly))
                    :status status}
                   message
                   (assoc :detail message))}))

(defn respond
  "A response of `status` carrying `result`, or the problem details of
  the anomaly it is."
  [status result]
  (if (error/anomaly? result)
    (anomaly->response result)
    {:status status :body result}))
