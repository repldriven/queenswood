(ns com.repldriven.queenswood.demo-digital-bank-api.api
  (:require
    [com.repldriven.queenswood.demo-digital-bank-api.auth :as auth]
    [com.repldriven.queenswood.demo-digital-bank-api.components :as components]
    [com.repldriven.queenswood.demo-digital-bank-api.handlers :as handlers]

    [com.repldriven.mono.server.interface :as server]

    [malli.core :as m]
    [malli.json-schema :as mjs]
    [malli.transform :as mt]
    [reitit.coercion.malli :as malli-coercion]
    [reitit.http :as http]
    [reitit.ring :as ring])
  (:import
    (java.io ByteArrayInputStream InputStream)))

(defn- ->provider
  [base-transformer]
  (reify
   malli-coercion/TransformationProvider
     (-transformer [_ {:keys [default-values]}]
       (mt/transformer base-transformer
                       (when default-values
                         (mt/default-value-transformer))))))

(def ^:private schema-registry (merge (m/default-schemas) components/registry))

(def ^:private coercion
  (malli-coercion/create
   {:transformers {:body {:default (->provider (mt/json-transformer))}
                   :string {:default (->provider (mt/string-transformer))}
                   :response {:default (->provider nil)}}
    :strip-extra-keys false
    :options {:registry schema-registry}}))

(def ^:private documented-schemas
  "The JSON Schema of the shapes no route coerces — the stream's events
  — keyed as `components/schemas` keys them.
  Reitit fills that key from the route schemas it transforms and then
  replaces it wholesale, so a schema only a `$ref` in an `:openapi`
  block names reaches the document after its handler has run."
  (delay (into {}
               (map (fn [name]
                      (:definitions (mjs/transform [:ref name]
                                                   {:registry schema-registry
                                                    ::mjs/definitions-path
                                                    "#/components/schemas/"}))))
               ["Notification"])))

(defn- openapi-handler
  "The standard handler, with the documented schemas merged under the
  ones reitit collected, so every `$ref` resolves."
  []
  (let [handler (server/standard-openapi-handler)
        with-documented (fn [response]
                          (update-in response
                                     [:body :components :schemas]
                                     (fn [schemas]
                                       (merge @documented-schemas schemas))))]
    (fn
      ([request] (with-documented (handler request)))
      ([request respond raise]
       (handler request (comp respond with-documented) raise)))))

(def ^:private receiver-path "/webhooks")

(def ^:private raw-body
  "Keeps a delivery's body as it arrived, as `:raw-body`, before
  anything decodes it: the platform's signature covers those bytes and
  no re-encoding of them. First in the router's chain, so it runs ahead
  of the request decoder, and only the receiver's path pays for the
  copy."
  {:name ::raw-body
   :enter (fn [ctx]
            (let [{:keys [uri ^InputStream body]} (:request ctx)]
              (if (and (= receiver-path uri) body)
                (let [bytes (with-open [in body] (.readAllBytes in))]
                  (update ctx
                          :request assoc
                          :raw-body bytes
                          :body (ByteArrayInputStream. bytes)))
                ctx)))})

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
            :handler handlers/sign-in}}]
   [receiver-path
    {:openapi {:tags ["Platform"]}
     :post {:summary "Receive a signed delivery from the platform"
            :description
            (str "The platform's webhook endpoint. A delivery carries the "
                 "Standard Webhooks headers and a notification as the "
                 "platform's API document describes it; one that verifies "
                 "is answered at once and acted on afterwards.")
            :openapi {:operationId "ReceiveDelivery"
                      :requestBody {:required true
                                    :content {"application/json" {}}}}
            :responses (assoc errors 202 {:body [:ref "Received"]})
            :handler handlers/receive}}]])

(def ^:private idempotency-key
  "The header a submission carries so a repeated tap is answered once."
  {:name "Idempotency-Key"
   :in "header"
   :required false
   :schema {:type "string" :maxLength 64}
   :description
   "A key the app mints per submission; the same key answers the same."})

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
           :handler handlers/me}}]
   ["/events"
    {:openapi {:tags ["Home"]}
     :get {:summary "The customer's notifications, as they are told"
           :description
           (str "A server-sent event stream, held open. What the customer "
                "has not been shown arrives first, then each notification "
                "as the platform tells the bank; each is an event named "
                "`notification` whose data is a Notification.")
           ;; The stream's shape is documented here rather than declared
           ;; under `:responses`: a declared body would be coerced, and
           ;; the body is held open rather than answered.
           :openapi {:operationId "StreamEvents"
                     :responses
                     {200 {:description "The stream."
                           :content {"text/event-stream"
                                     {:schema
                                      {:$ref
                                       "#/components/schemas/Notification"}}}}}}
           :responses (assoc errors 200 {:description "The stream."})
           :handler handlers/events}}]
   ["/payee-checks"
    {:openapi {:tags ["Payments"]}
     :post {:summary "Check a payee's name with their bank"
            :openapi {:operationId "CheckPayee"}
            :parameters {:body [:ref "PayeeCheckRequest"]}
            :responses (assoc errors 200 {:body [:ref "PayeeCheck"]})
            :handler handlers/check-payee}}]
   ["/payments"
    {:openapi {:tags ["Payments"]}
     :post {:summary "Pay a payee from one of the customer's accounts"
            :openapi {:operationId "SubmitPayment"
                      :parameters [idempotency-key]}
            :parameters {:body [:ref "PaymentRequest"]}
            :responses (assoc errors 201 {:body [:ref "Payment"]})
            :handler handlers/submit-payment}}]
   ["/transfers"
    {:openapi {:tags ["Payments"]}
     :post {:summary "Move money between two of the customer's accounts"
            :openapi {:operationId "Transfer" :parameters [idempotency-key]}
            :parameters {:body [:ref "TransferRequest"]}
            :responses (assoc errors 201 {:body [:ref "Transfer"]})
            :handler handlers/transfer}}]
   ["/accounts"
    {:openapi {:tags ["Accounts"]}
     :post {:summary "Open an account against one of the bank's products"
            :openapi {:operationId "OpenAccount" :parameters [idempotency-key]}
            :parameters {:body [:ref "OpenAccountRequest"]}
            :responses (assoc errors 201 {:body [:ref "OpenedAccount"]})
            :handler handlers/open-account}}]])

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
                 :handler (openapi-handler)}}]
         (into ["" {:interceptors (vec (:interceptors ctx))}]
               sign-up-routes)
         (into [""
                {:interceptors (conj (vec (:interceptors ctx)) auth/session)
                 :openapi {:security [{"sessionAuth" []}]}}]
               session-routes)]))

(defn app
  [ctx]
  (-> (http/ring-handler (http/router (routes ctx)
                                      (-> server/standard-router-data
                                          (assoc-in [:data :coercion] coercion)
                                          (update-in [:data :interceptors]
                                                     (fn [chain]
                                                       (into [raw-body]
                                                             chain)))))
                         (ring/routes (server/standard-openapi-ui-handler)
                                      (server/standard-default-handler))
                         server/standard-executor)
      (server/wrap-cors (:cors ctx))))
