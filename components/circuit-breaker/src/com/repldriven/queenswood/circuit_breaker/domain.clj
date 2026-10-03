(ns com.repldriven.queenswood.circuit-breaker.domain)

(def ^:private closed "closed")
(def ^:private opened "open")
(def ^:private half-open "half-open")

(defn- closed-breaker
  [destination now]
  {:destination destination
   :state closed
   :consecutive-failures 0
   :updated-at now})

(defn- open
  [breaker now cool-down-ms]
  (-> breaker
      (assoc :state opened
             :opened-at now
             :retry-at (+ now cool-down-ms)
             :cool-down-ms cool-down-ms
             :updated-at now)
      (dissoc :probe-claimed-by :probe-lease-expires-at)))

(defn allow
  "What a call to the breaker's destination may do at `now`, and the
  breaker as the answer leaves it, nil where it is unchanged: `:closed`
  lets the call through, `:open` holds it, and `:probe` lets it through
  as the half-open probe, claimed by `claimant` for `lease-ms`. A probe
  another claimant holds a live lease on holds every other call."
  [breaker now claimant lease-ms]
  (let [{:keys [state retry-at probe-claimed-by probe-lease-expires-at]}
        breaker]
    (cond
     (or (nil? breaker) (= closed state))
     [:closed nil]

     (< now (or retry-at 0))
     [:open nil]

     (and probe-claimed-by (< now (or probe-lease-expires-at 0)))
     [:open nil]

     :else
     [:probe
      (assoc breaker
             :state half-open
             :probe-claimed-by claimant
             :probe-lease-expires-at (+ now lease-ms)
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
        {:keys [state]} breaker
        failures (or (:consecutive-failures breaker) 0)]
    (case outcome
      :answered
      (when (and breaker (or (not= closed state) (pos? failures)))
        (closed-breaker destination now))

      :failed
      (cond
       (= half-open state)
       (open breaker
             now
             (min max-cool-down-ms
                  (* 2 (or (:cool-down-ms breaker) cool-down-ms))))

       (= opened state)
       nil

       :else
       (let [failures (inc failures)
             counted (assoc (or breaker (closed-breaker destination now))
                            :consecutive-failures failures
                            :updated-at now)]
         (if (>= failures failure-threshold)
           (open counted now cool-down-ms)
           counted))))))
