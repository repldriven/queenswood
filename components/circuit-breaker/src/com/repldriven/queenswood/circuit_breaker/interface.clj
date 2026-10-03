(ns com.repldriven.queenswood.circuit-breaker.interface
  "The circuit breaker on each destination an outbound call goes to, and
  the delivery policy a loop retries and gives up by (ADR-0034). A
  destination is a string naming what fails as a unit, an adapter's API
  as `adapter:<adapter>`. Its breaker is an FDB record every replica
  reads: closed, it lets calls through and counts consecutive failures;
  open, it lets none through until its cool-down ends; half-open, it
  lets one probe through, closing on its success and reopening on its
  failure with the cool-down doubled. A destination with no record is
  closed."
  (:require
    [com.repldriven.queenswood.circuit-breaker.core :as core]
    [com.repldriven.queenswood.circuit-breaker.policy :as policy]))

;; ---------------------------------------------------------------------------
;; The delivery policy

(def
  ^{:doc
    "The schema a loop's `delivery-policy` is checked against at start-up:
  a `:default` retry policy, `:operations` overriding it per operation
  keyword, and the `:breaker`'s `:failure-threshold`, `:cool-down-ms`,
  `:max-cool-down-ms` and `:probe-lease-ms`. A retry policy is
  `:initial-backoff-ms`, `:backoff-growth`, `:max-backoff-ms`,
  `:max-attempts` and `:max-age-ms`."}
  delivery-policy-schema
  policy/delivery-policy-schema)

(def
  ^{:doc
    "The schema a breaker policy is checked against: `:failure-threshold`,
  `:cool-down-ms`, `:max-cool-down-ms` and `:probe-lease-ms`."}
  breaker-schema
  policy/breaker-schema)

(defn retry-policy
  "The retry policy `delivery-policy` gives `operation`: its `:default`
  with the operation's entry, where it has one, merged over it.

  Args:
  - delivery-policy: a loop's delivery policy.
  - operation: an operation name, or nil for the default."
  [delivery-policy operation]
  (policy/retry-policy delivery-policy operation))

(defn backoff-ms
  "How long an item waits after its `attempts`-th failed attempt: the
  initial backoff, grown by `:backoff-growth` for each attempt after the
  first, up to `:max-backoff-ms`.

  Args:
  - retry-policy: from `retry-policy`.
  - attempts: the attempts made, one or more."
  [retry-policy attempts]
  (policy/backoff-ms retry-policy attempts))

(defn give-up?
  "True when an item has made its `:max-attempts`, or is older than its
  `:max-age-ms`.

  Args:
  - retry-policy: from `retry-policy`.
  - attempts: the attempts made.
  - age-ms: how long ago the item was created, or nil where unknown."
  [retry-policy attempts age-ms]
  (policy/give-up? retry-policy attempts age-ms))

;; ---------------------------------------------------------------------------
;; The breaker

(defn allow
  "What a call to `destination` may do at `now`: `:closed` lets it
  through, `:open` holds it, and `:probe` lets it through as the
  half-open probe, now claimed by `claimant` for the policy's
  `:probe-lease-ms`, so no other replica probes while it does. Returns
  the decision, or an anomaly.

  Args:
  - config: `{:record-db :record-store}`.
  - policy: the delivery policy's `:breaker`.
  - destination: the destination's name.
  - now: epoch millis.
  - claimant: the caller's runner id."
  [config policy destination now claimant]
  (core/allow config policy destination now claimant))

(defn record
  "Record a call's outcome on `destination`'s breaker: `:answered`, a
  refusal included, closes it; `:failed` counts towards opening it, or
  reopens it from half-open. Writes only where the breaker changes.
  Returns the breaker as it stands, or an anomaly.

  Args:
  - config: `{:record-db :record-store}`.
  - policy: the delivery policy's `:breaker`.
  - destination: the destination's name.
  - outcome: `:answered` or `:failed`.
  - now: epoch millis."
  [config policy destination outcome now]
  (core/record config policy destination outcome now))

(defn breaker
  "`destination`'s breaker record, or nil where it has none, or an
  anomaly.

  Args:
  - config: `{:record-db :record-store}`.
  - destination: the destination's name."
  [config destination]
  (core/breaker config destination))

(defn guard
  "Make a call a request waits for through `destination`'s breaker: while
  it is open, a `:circuit-breaker/open` anomaly at once, without calling;
  otherwise `(f)`'s result, its outcome recorded as `(outcome-of
  result)` answers, `:answered` or `:failed`. A breaker that cannot be
  read lets the call through.

  Args:
  - config: `{:record-db :record-store}`.
  - policy: the delivery policy's `:breaker`.
  - destination: the destination's name.
  - outcome-of: the call's result to `:answered` or `:failed`.
  - f: makes the call."
  [config policy destination outcome-of f]
  (core/guard config policy destination outcome-of f))

(defn start-probe
  "Start a daemon loop that calls `destination` through `guard` on its
  breaker, sleeping `(interval-ms result)` between calls, so the
  destination is probed whether or not anything else is sent to it. A
  probe that throws is an anomaly, as is a call held by an open breaker.
  Returns `{:stop fn}`.

  Args:
  - config: `{:record-db :record-store}`.
  - policy: the delivery policy's `:breaker`.
  - destination: the destination's name.
  - opts:
    - `:probe` — makes the call.
    - `:outcome-of` — the probe's result to `:answered` or `:failed`.
    - `:interval-ms` — the probe's result, or an anomaly, to the
      milliseconds before the next."
  [config policy destination opts]
  (core/start-probe config policy destination opts))
