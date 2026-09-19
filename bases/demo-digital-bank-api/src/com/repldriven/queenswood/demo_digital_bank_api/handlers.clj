(ns com.repldriven.queenswood.demo-digital-bank-api.handlers
  (:require
    [com.repldriven.queenswood.demo-digital-bank-api.auth :as auth]
    [com.repldriven.queenswood.demo-digital-bank-api.errors :as errors]

    [com.repldriven.queenswood.demo-digital-bank.interface :as bank]

    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.log.interface :as log]

    [ring.core.protocols :as ring-protocols])
  (:import
    (java.io OutputStream)
    (java.nio.charset StandardCharsets)))

(defn- sign-up-id
  [request]
  (get-in request [:parameters :path :sign-up-id]))

(defn- body [request] (get-in request [:parameters :body]))

(defn- client-key
  "The key the app sent with a submission, or nil."
  [request]
  (get-in request [:headers "idempotency-key"]))

(defn start-sign-up
  [request]
  (let [{:keys [bank]} request]
    (errors/respond 201 (bank/start-sign-up bank (body request)))))

(defn verify-code
  [request]
  (let [{:keys [bank]} request]
    (errors/respond
     200
     (bank/verify-code bank (sign-up-id request) (body request)))))

(defn register-details
  [request]
  (let [{:keys [bank]} request]
    (errors/respond 200
                    (bank/register-details bank
                                           (sign-up-id request)
                                           (body request)))))

(defn choose-passcode
  [request]
  (let [{:keys [bank]} request]
    (errors/respond 201
                    (bank/choose-passcode bank
                                          (sign-up-id request)
                                          (body request)))))

(defn sign-in
  [request]
  (let [{:keys [bank]} request]
    (errors/respond 201 (bank/sign-in bank (body request)))))

(defn sign-out
  [request]
  (let [{:keys [bank]} request
        result (bank/sign-out bank (auth/token request))]
    (if (nil? result) {:status 204} (errors/respond 204 result))))

(defn me
  [request]
  (let [{:keys [bank customer]} request]
    (errors/respond 200 (bank/me bank customer))))

(defn check-payee
  [request]
  (let [{:keys [bank customer]} request]
    (errors/respond 200 (bank/check-payee bank customer (body request)))))

(defn submit-payment
  [request]
  (let [{:keys [bank customer]} request]
    (errors/respond 201
                    (bank/submit-payment bank
                                         customer
                                         (client-key request)
                                         (body request)))))

(defn transfer
  [request]
  (let [{:keys [bank customer]} request]
    (errors/respond
     201
     (bank/transfer bank customer (client-key request) (body request)))))

(defn open-account
  [request]
  (let [{:keys [bank customer]} request]
    (errors/respond
     201
     (bank/open-account bank customer (client-key request) (body request)))))

(defn receive
  [request]
  (let [{:keys [bank headers raw-body]} request]
    (errors/respond 202
                    (bank/receive bank headers (or raw-body (byte-array 0))))))

(defn- frame
  "One server-sent event, or a comment where there is nothing to say
  but that the stream is alive."
  [event]
  (if event
    (str "event: notification\nid: "
         (:id event)
         "\ndata: "
         (json/write-str event)
         "\n\n")
    ": keep-alive\n\n"))

(defn- event-stream
  "A body that holds the response open, writing each notification as
  the bank tells it and returning once the app has gone or the bank has
  stopped."
  [bank customer]
  (reify
   ring-protocols/StreamableResponseBody
     (write-body-to-stream [_ _ out]
       (let [^OutputStream out out]
         (try (bank/events bank
                           customer
                           (fn [event]
                             (.write out
                                     (.getBytes ^String (frame event)
                                                StandardCharsets/UTF_8))
                             (.flush out)))
              (catch java.io.IOException _
                (log/debug "event stream closed by the app")))))))

(defn events
  [request]
  (let [{:keys [bank customer]} request]
    {:status 200
     :headers {"content-type" "text/event-stream; charset=utf-8"
               "cache-control" "no-cache"
               "x-accel-buffering" "no"}
     :body (event-stream bank customer)}))
