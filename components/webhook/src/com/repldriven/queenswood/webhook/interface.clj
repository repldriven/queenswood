(ns com.repldriven.queenswood.webhook.interface
  "Webhook endpoints: a tenant's registered destinations for webhook
  notifications, and the lifecycle over them — register, read, update,
  enable, disable, remove and rotate the secret.

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

  The notification the bank publishes is declared here too, as the
  `registry` and `examples` the document is assembled from: an
  envelope around one of the API's own resource components, its `data`
  a `oneOf` discriminated on `resource-type`, so a resource gaining a
  field gains it in its notifications with no second schema to keep.

  The delivery side is exposed here too: a test notification, a
  re-send of one delivery or of every notification in a window, and
  the endpoint's delivery history with the filters the history
  route takes."
  (:require
    [com.repldriven.queenswood.webhook.system]

    [com.repldriven.queenswood.webhook.catalogue :as catalogue]
    [com.repldriven.queenswood.webhook.components :as components]
    [com.repldriven.queenswood.webhook.core :as core]
    [com.repldriven.queenswood.webhook.domain :as domain]
    [com.repldriven.queenswood.webhook.examples :as examples]))

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
  - opts (optional): map; `:policies` overrides policy resolution, and
    `:since` — an epoch-ms instant — asks for the gap as well as the
    resumption: every notification from that instant on that this
    endpoint has chosen and has never had delivered gets a fresh
    pending delivery, written in the transaction the enable commits
    in."
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

(defn test-notification
  "Send a test notification to an enabled endpoint: one notification of
  the `webhook.test` kind carrying the endpoint as its resource, and
  one pending delivery of it. Returns that delivery — its id, its
  status and its endpoint — which the tenant reads back through the
  delivery history to see what the endpoint answered.

  Every call creates a delivery. Repetition is the caller's to bound,
  through the idempotency pair the route declares.

  Args:
  - txn: FDB transaction or config map.
  - bank-id: owning bank id.
  - endpoint-id: endpoint id.
  - opts (optional): map; `:policies` overrides policy resolution."
  ([txn bank-id endpoint-id]
   (core/test-notification txn bank-id endpoint-id))
  ([txn bank-id endpoint-id opts]
   (core/test-notification txn bank-id endpoint-id opts)))

(defn resend
  "Send one delivery's notification again, as a new delivery to the
  same endpoint. The earlier delivery and its attempts are left where
  they are — a re-send is a new journey, not a reset of the old one.
  Returns the new delivery or an anomaly.

  Args:
  - txn: FDB transaction or config map.
  - bank-id: owning bank id.
  - endpoint-id: the endpoint the delivery belongs to; one reached
    under any other is `:webhook-delivery/not-found`.
  - delivery-id: the delivery to send again.
  - opts (optional): map; `:policies` overrides policy resolution."
  ([txn bank-id endpoint-id delivery-id]
   (core/resend txn bank-id endpoint-id delivery-id))
  ([txn bank-id endpoint-id delivery-id opts]
   (core/resend txn bank-id endpoint-id delivery-id opts)))

(defn resend-window
  "Send every notification the endpoint has chosen from a window again,
  one new pending delivery each. Returns `{:deliveries [...]}` or an
  anomaly. Unlike `enable`'s `:since`, this re-sends what was already
  delivered too: it answers a tenant that lost what it received rather
  than one that never received it.

  Args:
  - txn: FDB transaction or config map.
  - bank-id: owning bank id.
  - endpoint-id: endpoint id.
  - data: `:from` (required) and `:to`, epoch-ms bounds over when the
    notification was created. An absent `:to` leaves the window open.
  - opts (optional): map; `:policies` overrides policy resolution."
  ([txn bank-id endpoint-id data]
   (core/resend-window txn bank-id endpoint-id data))
  ([txn bank-id endpoint-id data opts]
   (core/resend-window txn bank-id endpoint-id data opts)))

(defn get-deliveries
  "An endpoint's delivery history, in creation order. Returns
  `{:deliveries [...]}` or an anomaly.

  Args:
  - txn: FDB transaction or config map.
  - bank-id: owning bank id.
  - endpoint-id: endpoint id.
  - filters (optional): `:kind`, `:outcome` — a delivery status
    keyword — and `:from` / `:to` over the delivery's creation. An
    absent filter admits everything."
  ([txn bank-id endpoint-id]
   (core/get-deliveries txn bank-id endpoint-id))
  ([txn bank-id endpoint-id filters]
   (core/get-deliveries txn bank-id endpoint-id filters)))

;; ---
;; the resources as the API publishes them
;; ---

(defn ->body
  "Project a stored endpoint onto the keys `WebhookEndpoint` declares,
  in the shape a read route returns. The secret, the rotated-away
  secret and the two idempotency keys are not among them, so none of
  them can reach a body through it.

  Args:
  - endpoint: an endpoint as this brick hands it back."
  [endpoint]
  (components/->endpoint-body endpoint))

(defn ->delivery-body
  "Project a stored delivery onto the keys `WebhookDelivery` declares.
  The runner's claim — its lease and the replica holding it — is not
  among them.

  Args:
  - delivery: a delivery as this brick hands it back."
  [delivery]
  (components/->delivery-body delivery))

;; ---
;; the notification resource
;; ---

(def
  ^{:doc
    "Malli registry of the notification schemas, keyed by the name
  each appears under in the document's `components/schemas` —
  `WebhookNotification`, the `WebhookNotificationData` union its
  `data` refers to, the notification id, the public kind, and the
  resource type the union is discriminated on. Merged into the
  coercion registry in `api.clj`, so `[:ref \"X\"]` resolves them on
  any route."}
  registry
  components/registry)

(def
  ^{:doc
    "Map of example name to example value for the notification,
  feeding the document's `components/examples` section."}
  examples
  examples/registry)

(def
  ^{:doc
    "Every public kind the bank publishes, as `{:kind :resource-type}`
  in catalogue order. The `api` base lists one entry per kind under the
  exported document's `webhooks` object, so a kind added to the
  catalogue is published in the document by the same edit."}
  published-kinds
  (mapv #(select-keys % [:kind :resource-type]) catalogue/entries))

;; ---
;; rejection examples
;; ---

(def
  ^{:doc
    "RFC 9457 body for an endpoint that does not exist — 404,
  `:webhook-endpoint/not-found`."}
  WebhookEndpointNotFound
  examples/WebhookEndpointNotFound)

(def
  ^{:doc
    "RFC 9457 body for a delivery that does not exist — 404,
  `:webhook-delivery/not-found`."}
  WebhookDeliveryNotFound
  examples/WebhookDeliveryNotFound)

(def
  ^{:doc
    "RFC 9457 body for an address the platform refuses to call — 422,
  `:webhook-endpoint/invalid-address`."}
  WebhookEndpointInvalidAddress
  examples/WebhookEndpointInvalidAddress)

(def
  ^{:doc
    "RFC 9457 body for an endpoint whose status forbids the transition
  — 409, `:webhook-endpoint/invalid-status`."}
  WebhookEndpointInvalidStatus
  examples/WebhookEndpointInvalidStatus)
