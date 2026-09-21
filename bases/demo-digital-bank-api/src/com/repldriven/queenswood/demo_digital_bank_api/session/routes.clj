(ns com.repldriven.queenswood.demo-digital-bank-api.session.routes
  (:require
    [com.repldriven.queenswood.demo-digital-bank-api.errors :as errors]
    [com.repldriven.queenswood.demo-digital-bank-api.session.handlers :as
     handlers]))

(def routes
  [["/sign-in"
    {:openapi {:tags ["Sessions"]}
     :post {:summary "Open a session with a phone number and passcode"
            :openapi {:operationId "SignIn"}
            :parameters {:body [:ref "SignInRequest"]}
            :responses (assoc errors/responses 201 {:body [:ref "Session"]})
            :handler handlers/sign-in}}]
   ["/sign-out"
    {:openapi {:tags ["Sessions"] :security [{"sessionAuth" []}]}
     :post {:summary "End the session"
            :openapi {:operationId "SignOut"}
            :responses
            (assoc errors/responses 204 {:description "The session ended"})
            :handler handlers/sign-out}}]])
