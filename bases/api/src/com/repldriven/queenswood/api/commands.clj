(ns com.repldriven.queenswood.api.commands
  (:refer-clojure :exclude [send])
  (:require
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.command.interface :as command]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str]))

(defn- reason->kind
  "Parse the stringified keyword reason emitted by a command
  processor back to a qualified keyword (e.g. \":x/y\" -> :x/y)."
  [reason]
  (when (and (string? reason) (str/starts-with? reason ":"))
    (keyword (subs reason 1))))

(defn- rejected-response
  "Build a ring response for a REJECTED command response. Maps the
  rejection reason to an appropriate status via `rejection-kind->status`
  (falls back to 422 when the reason is absent or unparseable)."
  [result]
  (let [status (or (some-> (:reason result)
                           reason->kind
                           errors/rejection-kind->status)
                   422)]
    {:status status
     :body (errors/error-response status "REJECTED" result)}))

(defn- get-schema
  [schemas command-name]
  (or (get schemas command-name)
      (error/fail :api/unknown-command
                  {:message "Unknown command" :command command-name})))

(defn- decode-payload
  [schemas response-schema result]
  (if-let [payload (:payload result)]
    (let [schema (get schemas response-schema)]
      (avro/deserialize-same schema payload))
    {}))

(defn send
  "Dispatch a command and translate the reply into a ring response.

  `opts` takes `:ordering-key` — the identity whose commands must be
  processed in order relative to each other. Declared per route, never
  inferred: a command without one is unkeyed, which is correct while a
  topic has one partition and silently reorders once it does not."
  ([dispatcher request command-name response-schema data]
   (send dispatcher request command-name response-schema data {}))
  ([dispatcher request command-name response-schema data {:keys [ordering-key]}]
   (let [{:keys [avro]} request
         envelope (command/req->command-request request command-name)
         ;; Routes without `require-idempotency-key` carry no client key,
         ;; so the envelope's id/correlation-id are nil. Assign a server
         ;; id so the command and its trace are addressable. Reply
         ;; matching is on the dispatcher's per-send command-id, not
         ;; correlation-id.
         id (or (:id envelope) (str (utility/uuidv7)))
         envelope (assoc envelope
                         :id id
                         :correlation-id (or (:correlation-id envelope) id))
         result (let-nom>
                  [schema (get-schema avro command-name)
                   payload (avro/serialize schema data)]
                  (command/send dispatcher
                                (assoc envelope :payload payload)
                                (utility/assoc-some {} :key ordering-key)))]
     (cond
      (error/anomaly? result)
      (errors/anomaly->response result)

      (= "REJECTED" (:status result))
      (rejected-response result)

      (= "FAILED" (:status result))
      {:status 500 :body (errors/error-response 500 "FAILED" result)}

      :else
      (let [body (decode-payload avro response-schema result)]
        (if (error/anomaly? body)
          (errors/anomaly->response body)
          {:status 200 :body body}))))))

(defn created
  "The 201 of a command that created a resource: its 200 reply, with the
  resource's `Location` built by `uri` from the body. Any other reply
  passes through unchanged."
  [response uri]
  (cond-> response
          (= 200 (:status response))
          (assoc :status 201 :headers {"Location" (uri (:body response))})))
