(ns com.repldriven.queenswood.form3-simulator.name-verification.routes
  (:require
    [com.repldriven.queenswood.form3-simulator.name-verification.handlers
     :as handlers]))

(def routes
  [["/organisation/nameverifications"
    {:openapi {:tags ["Confirmation of Payee"]}
     :post {:summary "Check a payee's name"
            :openapi {:operationId "CreateNameVerification"}
            :parameters {:body [:ref "ResourceRequest"]}
            :responses {201 {:body [:ref "ResourceResponse"]}}
            :handler handlers/check}}]
   ["/organisation/nameverifications/{id}"
    {:openapi {:tags ["Confirmation of Payee"]}
     :get {:summary "Fetch a name check with its answer"
           :openapi {:operationId "GetNameVerification"}
           :parameters {:path [:map [:id string?]]}
           :responses {200 {:body [:ref "ResourceResponse"]}
                       404 {:body [:ref "ApiError"]}}
           :handler handlers/fetch}}]])
