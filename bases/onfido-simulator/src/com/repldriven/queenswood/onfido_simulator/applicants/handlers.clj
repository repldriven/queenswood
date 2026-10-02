(ns com.repldriven.queenswood.onfido-simulator.applicants.handlers
  (:require
    [com.repldriven.mono.utility.interface :refer [now-rfc3339 uuidv7]]))

(def ^{:private true :doc "The email whose applicant the simulator refuses."}
     refused-email
  "refused@verification.example")

(defn create-applicant
  [_config]
  (fn [request]
    (let [{:keys [state parameters]} request
          {:keys [body]} parameters
          id (str (uuidv7))
          applicant (-> body
                        (select-keys [:first_name :last_name :dob :email
                                      :address])
                        (assoc :id id :created_at (now-rfc3339)))]
      (if (= refused-email (:email body))
        {:status 422
         :body {:title "UNPROCESSABLE_ENTITY"
                :type "validation_error"
                :status 422
                :detail "The applicant was refused"}}
        (do (swap! state assoc-in [:applicants id] applicant)
            {:status 201 :body applicant})))))

(defn get-applicant
  [_config]
  (fn [request]
    (let [{:keys [state parameters]} request
          id (get-in parameters [:path :id])
          applicant (get-in @state [:applicants id])]
      (if applicant
        {:status 200 :body applicant}
        {:status 404
         :body {:title "NOT_FOUND"
                :type "applicant/not-found"
                :status 404
                :detail (str "No applicant with id: " id)}}))))
