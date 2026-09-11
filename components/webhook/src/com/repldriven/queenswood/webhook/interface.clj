(ns com.repldriven.queenswood.webhook.interface
  "Webhook endpoints: a tenant's registered destinations for webhook
  notifications, and the lifecycle over them — register, read, update,
  enable, disable, pause, remove and rotate the secret.

  A registration earns no command. It writes one endpoint record, the
  reacting brick is the writing brick, and it arrives on a request
  whose reply the caller reads; the unique `[bank-id,
  idempotency-key]` index removes the idempotency stakes without a
  bus, so `api` calls straight in. There is no `webhook-query` split
  until reads and writes need one.
  See [ADR-0018](../../../../../../docs/adr/0018-command-writes-are-earned.md).

  Every operation takes an FDB transaction or config map first, so a
  caller may thread its own transaction through. Each returns a value
  or an anomaly, never a throw. An endpoint that does not exist is a
  `:webhook-endpoint/not-found` rejection; a transition from the wrong
  state is `:webhook-endpoint/invalid-status`, carrying `:message`,
  `:endpoint-id`, `:status` and `:allowed`; an address the platform
  refuses to call is `:webhook-endpoint/invalid-address`.

  The delivery side — notifications, deliveries and their attempts —
  is persisted by this brick but not yet exposed here."
  (:require
    [com.repldriven.queenswood.webhook.system]

    [com.repldriven.queenswood.webhook.core :as core]
    [com.repldriven.queenswood.webhook.domain :as domain]))

(defn register
  "Register a webhook endpoint for a bank. Mints the endpoint's id and
  its signing secret, refuses the address, the capability and the
  count limit before writing, and returns the endpoint map — secret
  included — or an anomaly.

  A retried registration carrying an idempotency-key already used by
  this bank reads the first endpoint back rather than minting a
  second. Without a key there is nothing to read back on, so each
  request registers its own endpoint.

  Args:
  - txn: FDB transaction or config map.
  - bank-id: owning bank id.
  - data: endpoint fields:
    - `:address` — the HTTPS URL to call (required). Refused when the
      scheme is not HTTPS, when the host is one of the platform's own,
      or when it resolves into a loopback, link-local, private,
      unique-local, carrier-grade-NAT or unspecified range.
    - `:description` — optional free text.
    - `:kinds` — optional collection of notification kinds this
      endpoint has chosen; all kinds when absent.
    - `:idempotency-key` — the registering request's key, unique per
      bank.
  - opts (optional): map; `:policies` overrides policy resolution and
    `:platform-hosts` names the hosts a tenant may not point at."
  ([txn bank-id data]
   (core/register txn bank-id data))
  ([txn bank-id data opts]
   (core/register txn bank-id data opts)))

(defn get-endpoint
  "Load one endpoint. Returns the endpoint map or a
  `:webhook-endpoint/not-found` rejection.

  Args:
  - txn: FDB transaction or config map.
  - bank-id: owning bank id.
  - endpoint-id: endpoint id."
  [txn bank-id endpoint-id]
  (core/get-endpoint txn bank-id endpoint-id))

(defn get-endpoints
  "List a bank's endpoints, in endpoint-id order. Returns
  `{:endpoints [...] :before <cursor> :after <cursor>}` or an anomaly.
  Removed endpoints are listed like any other: removal is a status,
  not a deletion.

  Args:
  - txn: FDB transaction or config map.
  - bank-id: owning bank id.
  - opts (optional): `{:after :before :limit :order}`, as the FDB
    cursor scan takes them."
  ([txn bank-id]
   (core/get-endpoints txn bank-id))
  ([txn bank-id opts]
   (core/get-endpoints txn bank-id opts)))

(defn update-endpoint
  "Replace an endpoint's editable fields — address, description and
  chosen kinds — as an absolute set, re-running the address rule.
  Returns the updated endpoint or an anomaly. Rejects a removed
  endpoint.

  Args:
  - txn: FDB transaction or config map.
  - bank-id: owning bank id.
  - endpoint-id: endpoint id.
  - data: `:address` (required), `:description`, `:kinds`.
  - opts (optional): as `register`."
  ([txn bank-id endpoint-id data]
   (core/update-endpoint txn bank-id endpoint-id data))
  ([txn bank-id endpoint-id data opts]
   (core/update-endpoint txn bank-id endpoint-id data opts)))

(defn enable
  "Enable a disabled or paused endpoint. Returns the updated endpoint
  or an anomaly.

  Args:
  - txn: FDB transaction or config map.
  - bank-id: owning bank id.
  - endpoint-id: endpoint id.
  - opts (optional): map; `:policies` overrides policy resolution."
  ([txn bank-id endpoint-id]
   (core/enable txn bank-id endpoint-id))
  ([txn bank-id endpoint-id opts]
   (core/enable txn bank-id endpoint-id opts)))

(defn disable
  "Disable an enabled or paused endpoint. Returns the updated endpoint
  or an anomaly.

  Args:
  - txn: FDB transaction or config map.
  - bank-id: owning bank id.
  - endpoint-id: endpoint id.
  - opts (optional): map; `:policies` overrides policy resolution."
  ([txn bank-id endpoint-id]
   (core/disable txn bank-id endpoint-id))
  ([txn bank-id endpoint-id opts]
   (core/disable txn bank-id endpoint-id opts)))

(defn pause
  "Pause an enabled endpoint — the platform's own transition, taken by
  the delivery runner on a failure `should-pause?` admits, so it takes
  no capability check. Returns the updated endpoint or an anomaly.

  Args:
  - txn: FDB transaction or config map.
  - bank-id: owning bank id.
  - endpoint-id: endpoint id."
  [txn bank-id endpoint-id]
  (core/pause txn bank-id endpoint-id))

(defn remove-endpoint
  "Move an endpoint to removed — a terminal status, not a deletion, so
  its deliveries stay readable. Returns the updated endpoint or an
  anomaly.

  Args:
  - txn: FDB transaction or config map.
  - bank-id: owning bank id.
  - endpoint-id: endpoint id.
  - opts (optional): map; `:policies` overrides policy resolution."
  ([txn bank-id endpoint-id]
   (core/remove-endpoint txn bank-id endpoint-id))
  ([txn bank-id endpoint-id opts]
   (core/remove-endpoint txn bank-id endpoint-id opts)))

(defn rotate-secret
  "Mint a new signing secret, keeping the current one as
  `:previous-secret` until `:previous-secret-expires-at` so a tenant
  that has not yet picked the new one up keeps verifying. Returns the
  updated endpoint — both secrets included — or an anomaly.

  The rotation's key is kept on the record, so a retry under the same
  key returns the pair the first rotation minted and mints none.

  Args:
  - txn: FDB transaction or config map.
  - bank-id: owning bank id.
  - endpoint-id: endpoint id.
  - data: `:idempotency-key`, and `:previous-secret-ttl-ms` to
    override how long the rotated-away secret stays accepted.
  - opts (optional): map; `:policies` overrides policy resolution."
  ([txn bank-id endpoint-id data]
   (core/rotate-secret txn bank-id endpoint-id data))
  ([txn bank-id endpoint-id data opts]
   (core/rotate-secret txn bank-id endpoint-id data opts)))

(defn should-pause?
  "Whether a failing delivery should pause its endpoint: no successful
  delivery inside the pause window, across at least the minimum
  attempts. Pure — the delivery runner asks before calling `pause`.

  Args:
  - last-success-at: epoch-ms of the endpoint's last success, or nil
    when it has never succeeded.
  - now: epoch-ms.
  - attempts: how many attempts the failing delivery has made."
  [last-success-at now attempts]
  (domain/should-pause? last-success-at now attempts))

(defn check-address
  "Whether an address may be called: nil when it may, a
  `:webhook-endpoint/invalid-address` rejection when it may not. Pure,
  taking the host's addresses as the caller already resolved them, so
  the delivery runner re-runs the registration-time rule at send time.

  Args:
  - address: the endpoint's URL.
  - resolved-addresses: the host's textual IP addresses.
  - platform-hosts: hosts a tenant may not point at."
  [address resolved-addresses platform-hosts]
  (domain/check-address address resolved-addresses platform-hosts))
