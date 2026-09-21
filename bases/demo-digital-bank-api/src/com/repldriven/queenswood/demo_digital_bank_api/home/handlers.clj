(ns com.repldriven.queenswood.demo-digital-bank-api.home.handlers
  (:require
    [com.repldriven.queenswood.demo-digital-bank-api.errors :as errors]

    [com.repldriven.queenswood.demo-digital-bank.interface :as bank]

    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.log.interface :as log]

    [ring.core.protocols :as ring-protocols])
  (:import
    (java.io OutputStream)
    (java.nio.charset StandardCharsets)))

(defn me
  [request]
  (let [{:keys [bank customer]} request]
    (errors/respond 200 (bank/me bank customer))))

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
