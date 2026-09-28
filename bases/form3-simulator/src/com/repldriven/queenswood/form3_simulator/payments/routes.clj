(ns com.repldriven.queenswood.form3-simulator.payments.routes
  (:require
    [com.repldriven.queenswood.form3-simulator.payments.handlers
     :as handlers]))

(def ^:private payment-path [:map [:id string?]])

(def ^:private e2e-filter (keyword "filter[end_to_end_reference]"))

(defn- read-route
  [summary operation-id path-schema handler]
  {:get {:summary summary
         :openapi {:operationId operation-id}
         :parameters {:path path-schema}
         :responses {200 {:body [:ref "ResourceResponse"]}
                     404 {:body [:ref "ApiError"]}}
         :handler handler}})

(defn- write-route
  [summary operation-id path-schema handler]
  {:post {:summary summary
          :openapi {:operationId operation-id}
          :parameters {:path path-schema :body [:ref "ResourceRequest"]}
          :responses {201 {:body [:ref "ResourceResponse"]}
                      400 {:body [:ref "ApiError"]}
                      404 {:body [:ref "ApiError"]}
                      409 {:body [:ref "ApiError"]}}
          :handler handler}})

(def routes
  [["/transaction/payments"
    {:openapi {:tags ["Payments"]}
     :post {:summary "Create a payment"
            :openapi {:operationId "CreatePayment"}
            :parameters {:body [:ref "ResourceRequest"]}
            :responses {201 {:body [:ref "ResourceResponse"]}
                        400 {:body [:ref "ApiError"]}
                        409 {:body [:ref "ApiError"]}}
            :handler handlers/create}
     :get {:summary "List payments, by end-to-end reference"
           :openapi {:operationId "ListPayments"}
           :parameters {:query [:map
                                [e2e-filter {:optional true} string?]]}
           :responses {200 {:body [:ref "ResourceListResponse"]}}
           :handler handlers/search}}]
   ["/transaction/payments/{id}"
    (merge
     {:openapi {:tags ["Payments"]}}
     (read-route "Fetch a payment" "GetPayment" payment-path handlers/fetch))]
   ["/transaction/payments/{id}/submissions"
    (merge {:openapi {:tags ["Payments"]}}
           (write-route "Submit a payment to the scheme"
                        "CreateSubmission"
                        payment-path
                        handlers/submit))]
   ["/transaction/payments/{id}/submissions/{submissionId}"
    (merge {:openapi {:tags ["Payments"]}}
           (read-route "Fetch a submission"
                       "GetSubmission"
                       [:map [:id string?] [:submissionId string?]]
                       handlers/fetch-submission))]
   ["/transaction/payments/{id}/admissions/{admissionId}"
    (merge {:openapi {:tags ["Payments"]}}
           (read-route "Fetch an inbound's admission"
                       "GetAdmission"
                       [:map [:id string?] [:admissionId string?]]
                       handlers/fetch-admission))]
   ["/transaction/payments/{id}/admissions/{admissionId}/tasks/{taskId}"
    (merge {:openapi {:tags ["Payments"]}
            :patch {:summary "Complete an admission task"
                    :openapi {:operationId "PatchAdmissionTask"}
                    :parameters {:path [:map
                                        [:id string?]
                                        [:admissionId string?]
                                        [:taskId string?]]
                                 :body [:ref "ResourceRequest"]}
                    :responses {200 {:body [:ref "ResourceResponse"]}
                                400 {:body [:ref "ApiError"]}
                                404 {:body [:ref "ApiError"]}
                                409 {:body [:ref "ApiError"]}}
                    :handler handlers/complete-task}}
           (read-route "Fetch an admission task"
                       "GetAdmissionTask"
                       [:map
                        [:id string?]
                        [:admissionId string?]
                        [:taskId string?]]
                       handlers/fetch-task))]
   ["/transaction/payments/{id}/returns"
    (merge {:openapi {:tags ["Returns"]}}
           (write-route "Return an inbound payment"
                        "CreateReturn"
                        payment-path
                        handlers/create-return))]
   ["/transaction/payments/{id}/returns/{returnId}"
    (merge {:openapi {:tags ["Returns"]}}
           (read-route "Fetch a return"
                       "GetReturn"
                       [:map [:id string?] [:returnId string?]]
                       handlers/fetch-return))]
   ["/transaction/payments/{id}/returns/{returnId}/submissions"
    (merge {:openapi {:tags ["Returns"]}}
           (write-route "Submit a return to the scheme"
                        "CreateReturnSubmission"
                        [:map [:id string?] [:returnId string?]]
                        handlers/submit-return))]
   ["/transaction/payments/{id}/returns/{returnId}/submissions/{submissionId}"
    (merge {:openapi {:tags ["Returns"]}}
           (read-route "Fetch a return submission"
                       "GetReturnSubmission"
                       [:map
                        [:id string?]
                        [:returnId string?]
                        [:submissionId string?]]
                       handlers/fetch-return-submission))]
   ["/transaction/payments/{id}/returns/{returnId}/admissions/{admissionId}"
    (merge {:openapi {:tags ["Returns"]}}
           (read-route "Fetch a return's admission"
                       "GetReturnAdmission"
                       [:map
                        [:id string?]
                        [:returnId string?]
                        [:admissionId string?]]
                       handlers/fetch-return-admission))]])
