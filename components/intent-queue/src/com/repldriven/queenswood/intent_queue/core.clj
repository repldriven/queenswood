(ns com.repldriven.queenswood.intent-queue.core)

(defn- due?
  [now intent]
  (<= (or (:next-attempt-at intent) 0) now))

(defn- holds?
  [subjects intent]
  (boolean (some subjects (:subjects intent))))

(defn- block
  [subjects intent]
  (into subjects (:subjects intent)))

(defn drain
  [intents now {:keys [run settles-first?]}]
  (reduce (fn [{:keys [unsent unsettled] :as state} intent]
            (let [waits? (or (holds? unsent intent)
                             (and (settles-first? intent)
                                  (holds? unsettled intent)))]
              (cond
               (= "sent" (:status intent))
               (update state :unsettled block intent)

               (or waits? (not (due? now intent)))
               (-> state
                   (update :unsent block intent)
                   (update :unsettled block intent))

               :else
               (let [status (:status (run intent))]
                 (cond-> (update state :ran conj (:intent-id intent))
                         (not (#{"sent" "settled" "failed"} status))
                         (-> (update :unsent block intent)
                             (update :unsettled block intent))

                         (= "sent" status)
                         (update :unsettled block intent))))))
          {:unsent #{} :unsettled #{} :ran []}
          (sort-by :intent-id intents)))
