(ns com.repldriven.queenswood.form3-adapter.cop.handlers
  (:require
    [com.repldriven.queenswood.form3-relay.interface :as relay]

    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private unavailable
  {:match-result :match-result-unavailable
   :reason-code "ACNS"
   :reason "Confirmation of Payee unavailable"})

(defn- result
  "The platform's result for Form3's answer and reason code."
  [{:keys [answer reason_code actual_name]}]
  (cond
   (= "confirmed" answer)
   {:match-result :match-result-match}

   (contains? #{"MBAM" "PAMM" "BAMM"} reason_code)
   (utility/assoc-some {:match-result :match-result-close-match
                        :reason-code reason_code
                        :reason "Close name match"}
                       :actual-name
                       actual_name)

   (contains? #{"ANNM" "PANM" "BANM"} reason_code)
   {:match-result :match-result-no-match
    :reason-code reason_code
    :reason "Account name does not match"}

   (= "AC01" reason_code)
   {:match-result :match-result-no-match
    :reason-code "AC01"
    :reason "Account does not exist"}

   :else
   (assoc unavailable
          :reason
          (str "Confirmation of Payee unavailable: " reason_code))))

(defn outbound-cop
  [request]
  (let [{:keys [parameters]} request
        {:keys [creditor-name account account-type]} (:body parameters)
        {:keys [sort-code account-number]} account
        [outcome body]
        (relay/classify
         (relay/request
          (select-keys request [:form3-url :credentials])
          {:method :post
           :path "/v1/organisation/nameverifications"
           :body {:data {:id (str (utility/uuidv7))
                         :type "name_verifications"
                         :attributes {:account_number account-number
                                      :account_number_code "BBAN"
                                      :bank_id sort-code
                                      :bank_id_code "GBDSC"
                                      :name [creditor-name]
                                      :account_classification
                                      (if (= :account-type-business
                                             account-type)
                                        "business"
                                        "personal")}}}}))]
    (log/info "Outbound CoP check" {:creditor-name creditor-name})
    {:status 200
     :body (if (= :ok outcome)
             (result (get-in body
                             [:data :relationships :name_verification_submission
                              :data 0 :attributes]))
             unavailable)}))
