(ns com.repldriven.queenswood.test-api-scenarios.fault
  "A lost reply, on demand.

  The running system has no protected route that fails after its
  command has committed, and that is exactly the case the idempotency
  cache exists for: the processor does the work, the reply never
  reaches the API, the API answers 5xx, the cache releases its claim,
  and the client retries. Reproducing it needs a send that delivers
  and then reports itself as failed.

  This interceptor supplies one. On `:enter` it rewrites the request's
  `:dispatchers` so each dispatcher's bus sends through a wrapper for
  that dispatcher's own command channel. The wrapper delegates every
  send to the untouched bus and then, for the *first* send of an
  envelope whose `:id` starts with `ik-lost-reply-`, answers an
  anomaly instead of the ack.

  It lives in the test directory, touches no product code, and reads
  three documented keys: `:bus` and `:command-channel` on a
  dispatcher, and `:producers` on a bus."
  (:require
    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.message-bus.interface :as message-bus]

    [clojure.string :as str]))

(def ^:private lost-reply-prefix "ik-lost-reply-")

(defonce ^:private lost (atom #{}))

(defn reset-lost!
  "Forget every id that has already lost its reply.

  `lost` is a `defonce` and `lose?` never removes an id, so without
  this the lost-reply scenarios are one-shot per JVM: a second run in
  the same REPL has its reply delivered, the retry replays instead of
  re-running, and the scenario fails with a message pointing at the
  route rather than at the seam."
  []
  (reset! lost #{}))

(defn- lose?
  "True for the first send of `id` only. `swap-vals!` makes the claim
  atomic, so two concurrent sends cannot both be the first."
  [id]
  (and (string? id)
       (str/starts-with? id lost-reply-prefix)
       (not (contains? (first (swap-vals! lost conj id)) id))))

(defn- send-then-maybe-lose
  [bus channel message opts]
  (let [ack (if opts
              (message-bus/send bus channel message opts)
              (message-bus/send bus channel message))]
    (if (lose? (:id message))
      (error/fail :test/lost-reply
                  {:message "The reply to this command was lost"
                   :id (:id message)})
      ack)))

(defn- losing-producer
  "A producer that sends through `bus` — the one whose producer map is
  about to be replaced, so this reaches the real producer — and loses
  the ack for a marked envelope."
  [bus channel]
  (reify
   message-bus/Producer
     (send [_ message] (send-then-maybe-lose bus channel message nil))
     (send [_ message opts] (send-then-maybe-lose bus channel message opts))))

(defn- wrap-dispatcher
  [{:keys [bus command-channel] :as dispatcher}]
  (if (and bus command-channel)
    (assoc dispatcher
           :bus
           (assoc-in bus
            [:producers command-channel]
            (losing-producer bus command-channel)))
    dispatcher))

(def lose-reply
  "Splice into the server's interceptor list, after the one that puts
  `:dispatchers` on the request."
  {:name ::lose-reply
   :enter (fn [ctx]
            (update-in ctx
                       [:request :dispatchers]
                       (fn [dispatchers]
                         (if (map? dispatchers)
                           (update-vals dispatchers wrap-dispatcher)
                           dispatchers))))})
