(ns com.repldriven.queenswood.demo-digital-bank.streams
  (:import
    (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def closed
  "What a subscriber is handed once the registry has closed, so a
  stream held open ends rather than waiting out its keep-alive."
  ::closed)

(defn registry
  "The open event streams, by customer: each a queue the receiver
  offers a customer's notifications to and a stream handler takes
  from."
  []
  {:subscribers (atom {}) :open (atom true)})

(defn subscribe!
  "A queue of the customer's notifications from now on, registered so
  `publish!` reaches it. A registry already closed hands it `closed`
  at once."
  [registry customer-id]
  (let [queue (LinkedBlockingQueue.)]
    (swap! (:subscribers registry) update customer-id (fnil conj #{}) queue)
    (when-not @(:open registry) (.offer queue closed))
    {:customer-id customer-id :queue queue}))

(defn unsubscribe!
  [registry {:keys [customer-id queue]}]
  (swap! (:subscribers registry)
    (fn [subscribers]
      (let [left (disj (get subscribers customer-id #{}) queue)]
        (if (empty? left)
          (dissoc subscribers customer-id)
          (assoc subscribers customer-id left))))))

(defn publish!
  "Offer `event` to every stream the customer holds open. Nothing is
  kept for a customer with none: what they have not been shown is in
  the store, and a stream replays it as it opens."
  [registry customer-id event]
  (doseq [^LinkedBlockingQueue queue (get @(:subscribers registry)
                                          customer-id)]
    (.offer queue event)))

(defn open-count
  "How many streams the customer holds open."
  [registry customer-id]
  (count (get @(:subscribers registry) customer-id)))

(defn next!
  "The subscription's next event, `closed`, or nil once `timeout-ms`
  has passed with nothing."
  [{:keys [^LinkedBlockingQueue queue]} timeout-ms]
  (.poll queue timeout-ms TimeUnit/MILLISECONDS))

(defn close!
  "End every open stream: each subscriber is handed `closed`, and any
  that subscribes afterwards is handed it at once."
  [registry]
  (reset! (:open registry) false)
  (doseq [^LinkedBlockingQueue queue (apply concat
                                            (vals @(:subscribers registry)))]
    (.offer queue closed)))
