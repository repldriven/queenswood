(ns com.repldriven.queenswood.onfido-simulator.checks.handlers)

(defn list-checks
  [_config]
  (fn [request]
    (let [{:keys [state parameters]} request
          applicant-id (get-in parameters [:query :applicant_id])]
      {:status 200
       :body {:checks (->> (vals (:checks @state))
                           (filter (fn [c] (= applicant-id (:applicant_id c))))
                           (sort-by :created_at)
                           vec)}})))

(defn get-check
  [_config]
  (fn [request]
    (let [{:keys [state parameters]} request
          id (get-in parameters [:path :id])
          check (get-in @state [:checks id])]
      (if check
        {:status 200 :body check}
        {:status 404
         :body {:title "NOT_FOUND"
                :type "check/not-found"
                :status 404
                :detail (str "No check with id: " id)}}))))

(defn list-reports
  [_config]
  (fn [request]
    (let [{:keys [state parameters]} request
          check-id (get-in parameters [:query :check_id])
          {:keys [report_ids]} (get-in @state [:checks check-id])]
      {:status 200
       :body {:reports (mapv (fn [id] (get-in @state [:reports id]))
                             report_ids)}})))
