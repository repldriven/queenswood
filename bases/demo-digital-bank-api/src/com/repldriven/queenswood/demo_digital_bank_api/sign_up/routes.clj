(ns com.repldriven.queenswood.demo-digital-bank-api.sign-up.routes
  (:require
    [com.repldriven.queenswood.demo-digital-bank-api.errors :as errors]
    [com.repldriven.queenswood.demo-digital-bank-api.sign-up.handlers :as
     handlers]))

(def routes
  [["/sign-up" {:openapi {:tags ["Sign-up"]}}
    [""
     {:post {:summary "Start a sign-up with a phone number"
             :openapi {:operationId "StartSignUp"}
             :parameters {:body [:ref "StartSignUpRequest"]}
             :responses (assoc errors/responses 201 {:body [:ref "SignUp"]})
             :handler handlers/start}}]
    ["/{sign-up-id}" {:parameters {:path {:sign-up-id string?}}}
     ["/code"
      {:post {:summary "Verify the code sent to the phone"
              :openapi {:operationId "VerifySignUpCode"}
              :parameters {:body [:ref "CodeRequest"]}
              :responses (assoc errors/responses 200 {:body [:ref "SignUp"]})
              :handler handlers/verify-code}}]
     ["/details"
      {:post {:summary "Register the person with the platform"
              :openapi {:operationId "RegisterSignUpDetails"}
              :parameters {:body [:ref "DetailsRequest"]}
              :responses (assoc errors/responses 200 {:body [:ref "SignUp"]})
              :handler handlers/register-details}}]
     ["/passcode"
      {:post {:summary "Choose a passcode and open the first session"
              :openapi {:operationId "ChooseSignUpPasscode"}
              :parameters {:body [:ref "PasscodeRequest"]}
              :responses (assoc errors/responses 201 {:body [:ref "Session"]})
              :handler handlers/choose-passcode}}]]]])
