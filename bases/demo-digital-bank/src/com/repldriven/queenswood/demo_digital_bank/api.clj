(ns com.repldriven.queenswood.demo-digital-bank.api
  (:require
    [com.repldriven.queenswood.demo-digital-bank.auth :as auth]
    [com.repldriven.queenswood.demo-digital-bank.components :as components]
    [com.repldriven.queenswood.demo-digital-bank.handlers :as handlers]

    [com.repldriven.mono.server.interface :as server]

    [malli.core :as m]
    [malli.transform :as mt]
    [reitit.coercion.malli :as malli-coercion]
    [reitit.http :as http]
    [reitit.ring :as ring]))

(defn- ->provider
  [base-transformer]
  (reify
   malli-coercion/TransformationProvider
     (-transformer [_ {:keys [default-values]}]
       (mt/transformer base-transformer
                       (when default-values
                         (mt/default-value-transformer))))))

(def ^:private coercion
  (malli-coercion/create
   {:transformers {:body {:default (->provider (mt/json-transformer))}
                   :string {:default (->provider (mt/string-transformer))}
                   :response {:default (->provider nil)}}
    :strip-extra-keys false
    :options {:registry (merge (m/default-schemas) components/registry)}}))

(def ^:private errors
  {400 {:body [:ref "ErrorResponse"]}
   401 {:body [:ref "ErrorResponse"]}
   404 {:body [:ref "ErrorResponse"]}
   409 {:body [:ref "ErrorResponse"]}
   422 {:body [:ref "ErrorResponse"]}
   503 {:body [:ref "ErrorResponse"]}})

(def ^:private sign-up-routes
  [["/sign-up" {:openapi {:tags ["Sign-up"]}}
    [""
     {:post {:summary "Start a sign-up with a phone number"
             :openapi {:operationId "StartSignUp"}
             :parameters {:body [:ref "StartSignUpRequest"]}
             :responses (assoc errors 201 {:body [:ref "SignUp"]})
             :handler handlers/start-sign-up}}]
    ["/{sign-up-id}" {:parameters {:path {:sign-up-id string?}}}
     ["/code"
      {:post {:summary "Verify the code sent to the phone"
              :openapi {:operationId "VerifySignUpCode"}
              :parameters {:body [:ref "CodeRequest"]}
              :responses (assoc errors 200 {:body [:ref "SignUp"]})
              :handler handlers/verify-code}}]
     ["/details"
      {:post {:summary "Register the person with the platform"
              :openapi {:operationId "RegisterSignUpDetails"}
              :parameters {:body [:ref "DetailsRequest"]}
              :responses (assoc errors 200 {:body [:ref "SignUp"]})
              :handler handlers/register-details}}]
     ["/passcode"
      {:post {:summary "Choose a passcode and open the first session"
              :openapi {:operationId "ChooseSignUpPasscode"}
              :parameters {:body [:ref "PasscodeRequest"]}
              :responses (assoc errors 201 {:body [:ref "Session"]})
              :handler handlers/choose-passcode}}]]]
   ["/sign-in"
    {:openapi {:tags ["Sessions"]}
     :post {:summary "Open a session with a phone number and passcode"
            :openapi {:operationId "SignIn"}
            :parameters {:body [:ref "SignInRequest"]}
            :responses (assoc errors 201 {:body [:ref "Session"]})
            :handler handlers/sign-in}}]])

(def ^:private session-routes
  [["/sign-out"
    {:openapi {:tags ["Sessions"]}
     :post {:summary "End the session"
            :openapi {:operationId "SignOut"}
            :responses (assoc errors 204 {:description "The session ended"})
            :handler handlers/sign-out}}]
   ["/me"
    {:openapi {:tags ["Home"]}
     :get {:summary "Everything the home screen shows"
           :openapi {:operationId "RetrieveMe"}
           :responses (assoc errors 200 {:body [:ref "Me"]})
           :handler handlers/me}}]])

(defn- routes
  [ctx]
  (into (server/health-routes ctx)
        [["/openapi.json"
          {:get {:no-doc true
                 :openapi {:info {:title "Demo digital bank"
                                  :description
                                  "The bank behind the demo digital bank's app"
                                  :version "1.0.0"}
                           :components
                           {:securitySchemes
                            {"sessionAuth" {:type :http
                                            :scheme :bearer
                                            :description
                                            "A session the bank minted"}}}}
                 :handler (server/standard-openapi-handler)}}]
         (into ["" {:interceptors (vec (:interceptors ctx))}]
               sign-up-routes)
         (into [""
                {:interceptors (conj (vec (:interceptors ctx)) auth/session)
                 :openapi {:security [{"sessionAuth" []}]}}]
               session-routes)]))

(defn app
  [ctx]
  (-> (http/ring-handler (http/router (routes ctx)
                                      (assoc-in server/standard-router-data
                                       [:data :coercion]
                                       coercion))
                         (ring/routes (server/standard-openapi-ui-handler)
                                      (server/standard-default-handler))
                         server/standard-executor)
      (server/wrap-cors (:cors ctx))))
