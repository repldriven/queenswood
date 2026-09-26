(ns com.repldriven.queenswood.zyphe-simulator.verification-requests.routes
  (:require
    [com.repldriven.queenswood.zyphe-simulator.verification-requests.handlers
     :as handlers]))

(def routes
  [["/sdk/flow/{flow_id}/vr/create"
    {:openapi {:tags ["Verification requests"]}
     :post {:summary "Create or resume a verification request"
            :openapi {:operationId "CreateVerificationRequest"}
            :parameters {:path [:map [:flow_id string?]]
                         :query [:map [:sandbox boolean?]
                                 [:locale {:optional true} string?]]
                         :header [:map [:x-api-key {:optional true} string?]]
                         :body [:ref "CreateVerificationRequest"]}
            :responses {200 {:body [:ref "CreateVerificationRequestResponse"]}
                        400 {:body [:ref "BaxeError"]}
                        401 {:body [:ref "BaxeError"]}}
            :handler (handlers/create-verification-request nil)}}]
   ["/simulator/verification-requests/{id}"
    {:openapi {:tags ["Simulator"]}}
    [""
     {:get {:summary "Read a verification request as the simulator holds it"
            :openapi {:operationId "GetSimulatedVerificationRequest"}
            :parameters {:path [:map [:id string?]]}
            :responses {200 {:body [:ref "VerificationRequest"]}
                        404 {:body [:ref "BaxeError"]}}
            :handler (handlers/get-verification-request nil)}}]
    ["/decision"
     {:post
      {:summary
       "Settle a run as the person and reviewer would, and deliver its event"
       :openapi {:operationId "DecideSimulatedVerificationRequest"}
       :parameters {:path [:map [:id string?]] :body [:ref "Decision"]}
       :responses {200 {:body [:ref "VerificationRequest"]}
                   404 {:body [:ref "BaxeError"]}}
       :handler (handlers/decide nil)}}]]])
