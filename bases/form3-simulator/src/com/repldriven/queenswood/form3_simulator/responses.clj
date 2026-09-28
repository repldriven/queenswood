(ns com.repldriven.queenswood.form3-simulator.responses
  (:require
    [com.repldriven.queenswood.form3-simulator.signed :as signed]
    [com.repldriven.queenswood.form3-simulator.records :as records]))

(defn ok
  ([r] (ok 200 r))
  ([status r] {:status status :body {:data (records/public r)}}))

(defn found
  "200 with `r`, or Form3's 404 where there is none."
  [r what]
  (if r (ok r) (signed/api-error 404 (str "The " what " does not exist"))))

(defn config
  [request]
  (select-keys request
               [:organisation-id :sort-code :webhook-delay-ms
                :admission-deadline-ms :payment-scheme]))
