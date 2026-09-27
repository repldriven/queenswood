(ns com.repldriven.queenswood.changelog-relay.interface
  "The relay tier: one daemon per store that tails its changelog cursor
  and republishes each committed entry to the message bus.

  A store's changelog is its transactional outbox (ADR-0002) — the
  entry is written in the same FDB transaction as the record, so
  \"state changed\" and \"event emitted\" cannot diverge. This component
  drains that cursor and nothing else: it holds no domain logic, and
  the handler it is given decides what to publish.

  Delivery is at-least-once. The cursor advances only when the whole
  pass commits, so a handler that throws leaves the checkpoint in place
  and the entry is redriven on the next poll; downstream consumers
  de-duplicate.

  The runner passes `{:deduplicate? false}`, so every changelog entry
  is relayed rather than only the latest per record-id. A relay carries
  transitions, and collapsing two transitions inside one poll window
  would drop an event.

  The `changelog-relay/envelope-handler` kind publishes each entry on
  its `:event-channel`, or on the channel `:event-channels` maps the
  entry's event name to, so one outbox can feed more than one channel
  without the relay reading a payload.

  A cursor has exactly one owner. Run the hosting service at
  `replicas: 1`; scale the relay tier by sharding stores across
  deployments, not by adding replicas to one."
  (:require
    [com.repldriven.queenswood.changelog-relay.system]

    [com.repldriven.queenswood.changelog-relay.runner :as runner]))

(defn start
  "Start the daemon poll loop for one store's changelog. Returns
  `{:stop fn}`.

  Prefer the `changelog-relay/runner` or `changelog-relay/runners`
  component-kinds over calling this directly; this arity exists for
  tests.

  Args:
  - config: a map with `:record-db`, `:consumer-id`, `:store-name`,
    `:handler` (a 2-arity fn of `[ctx changelog-bytes]`), and an
    optional `:poll-ms` (default 100).

  Reuse an existing `:consumer-id` when taking over a cursor — a fresh
  one starts from no checkpoint and would scan the store's entire
  changelog history in a single FDB transaction."
  [config]
  (runner/start config))
