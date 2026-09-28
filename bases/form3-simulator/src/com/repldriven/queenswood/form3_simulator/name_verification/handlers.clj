(ns com.repldriven.queenswood.form3-simulator.name-verification.handlers
  (:require
    [com.repldriven.queenswood.form3-simulator.responses :as responses]
    [com.repldriven.queenswood.form3-simulator.signed :as signed]
    [com.repldriven.queenswood.form3-simulator.records :as records]

    [clojure.string :as str]))

(defn- answer
  "The check's answer. The test values every payment simulator shares
  decide it: a name holding `COP_NOMATCH`, `COP_CLOSEMATCH` or
  `COP_UNAVAILABLE`; any other name matches."
  [name]
  (cond
   (str/includes? name "COP_NOMATCH")
   {:answer "rejected" :reason_code "ANNM"}

   (str/includes? name "COP_CLOSEMATCH")
   {:answer "rejected"
    :reason_code "MBAM"
    :actual_name (str/trim (str/replace name "COP_CLOSEMATCH" ""))}

   (str/includes? name "COP_UNAVAILABLE")
   {:answer "rejected" :reason_code "ACNS"}

   :else
   {:answer "confirmed"}))

(def check
  (signed/verified
   (fn [request]
     (let [{:keys [state parameters organisation-id]} request
           {:keys [id attributes]} (get-in parameters [:body :data])
           submission (records/resource
                       organisation-id
                       "name_verification_submissions"
                       (records/new-id)
                       (merge {:status "delivery_confirmed"}
                              (answer (str/join " " (:name attributes)))))]
       (responses/ok 201
                     (records/put state
                                  :name-verifications
                                  (records/resource
                                   organisation-id
                                   "name_verifications"
                                   id
                                   attributes
                                   {:name_verification_submission
                                    {:data [(records/public
                                             submission)]}})))))))

(def fetch
  (signed/verified (fn [request]
                     (responses/found (records/get-in-state
                                       (:state request)
                                       :name-verifications
                                       (get-in request [:parameters :path :id]))
                                      "name verification"))))
