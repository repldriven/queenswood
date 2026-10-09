(ns com.repldriven.queenswood.circuit-breaker.domain)

(def ^:private closed :circuit-breaker-status-closed)
(def ^:private opened :circuit-breaker-status-open)
(def ^:private half-open :circuit-breaker-status-half-open)

(defn- closed-breaker
  [breaker destination now]
  {:destination destination
   :status closed
   :failure-count 0
   :created-at (or (:created-at breaker) now)
   :updated-at now})

(defn- open
  [breaker now cool-down-ms]
  (assoc breaker
         :status opened
         :opened-at now
         :next-probe-at (+ now cool-down-ms)
         :cool-down-ms cool-down-ms
         :updated-at now))

(defn allow
  "What a call to the breaker's destination may do at `now`, and the
  breaker as the answer leaves it, nil where it is unchanged: `:closed`
  lets the call through, `:open` holds it, and `:probe` lets it through
  as the half-open probe, holding every other call for `lease-ms` by
  moving `next-probe-at` on."
  [breaker now lease-ms]
  (let [{:keys [status next-probe-at]} breaker]
    (cond
     (or (nil? breaker) (= closed status))
     [:closed nil]

     (< now (or next-probe-at 0))
     [:open nil]

     :else
     [:probe
      (assoc breaker
             :status half-open
             :next-probe-at (+ now lease-ms)
             :updated-at now)])))

(defn record
  "The breaker on `destination` as a call's `outcome` leaves it, nil
  where it is unchanged. `:answered`, a refusal included, closes it and
  clears its count; `:failed` counts towards `failure-threshold` while
  it is closed, opening it there for `cool-down-ms`, and reopens a
  half-open breaker for twice its last cool-down, up to
  `max-cool-down-ms`."
  [breaker destination outcome now policy]
  (let [{:keys [failure-threshold cool-down-ms max-cool-down-ms]} policy
        {:keys [status]} breaker
        failures (or (:failure-count breaker) 0)]
    (case outcome
      :answered
      (when (and breaker (or (not= closed status) (pos? failures)))
        (closed-breaker breaker destination now))

      :failed
      (cond
       (= half-open status)
       (open breaker
             now
             (min max-cool-down-ms
                  (* 2 (or (:cool-down-ms breaker) cool-down-ms))))

       (= opened status)
       nil

       :else
       (let [failures (inc failures)
             counted (assoc (or breaker (closed-breaker nil destination now))
                            :failure-count failures
                            :updated-at now)]
         (if (>= failures failure-threshold)
           (open counted now cool-down-ms)
           counted))))))
