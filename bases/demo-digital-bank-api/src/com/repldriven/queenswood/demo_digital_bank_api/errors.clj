(ns com.repldriven.queenswood.demo-digital-bank-api.errors
  (:require
    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.log.interface :as log]

    [clojure.string :as str]))

(def ^:private retry-later
  "The kinds that mean the platform or the store, not the request."
  #{"platform" "jdbc" "http-client"})

(defn- platform-status
  "The status the platform refused with, where it is one a caller's
  request earns, so its not-found and conflict stay what they were."
  [anomaly]
  (let [status (:status (error/payload anomaly))]
    (when (and (int? status) (<= 400 status 499) (not= 401 status)) status)))

(defn- status
  [anomaly]
  (let [kind (error/kind anomaly)]
    (cond (error/unauthorized? anomaly)
          401

          (error/rejection? anomaly)
          (cond (= :platform/refused kind)
                (or (platform-status anomaly) 422)

                (str/ends-with? (name kind) "not-found")
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

(def responses
  "The error responses every route declares, by status."
  {400 {:body [:ref "ErrorResponse"]}
   401 {:body [:ref "ErrorResponse"]}
   404 {:body [:ref "ErrorResponse"]}
   409 {:body [:ref "ErrorResponse"]}
   422 {:body [:ref "ErrorResponse"]}
   503 {:body [:ref "ErrorResponse"]}})

(def unauthenticated-response
  "The 401 a request with no live session receives: what the routes gated
  `sessionAuth` carry as `:unauthorized` for `server/require-scopes`, in
  the problem details the bank's own refusal of a session gives."
  (anomaly->response (error/unauthorized
                      :session/invalid
                      {:message "the session is missing or has expired"})))
