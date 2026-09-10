(ns com.repldriven.queenswood.cash-account-product-query.interface
  "Read-side (query) surface for cash-account products: load a version,
  a product's version history, a bank's product listing, and the
  platform templates products are created from; plus `active-version`,
  the pure resolver account-opening uses. This is the only product brick
  `bank-api` (and other readers) may require — it exposes no writes. The
  product lifecycle (new/open-draft/update/discard/publish) lives in
  `cash-account-product` (commands), which reuses these reads inside its
  own transactions.

  `get-versions`, `count-by-org`, `count-by-org-product-type` and
  `find-version-by-idempotency-key` are read primitives for the write
  sibling's transactions."
  (:require
    [com.repldriven.queenswood.cash-account-product-query.core :as core]
    [com.repldriven.queenswood.cash-account-product-query.domain :as domain]
    [com.repldriven.queenswood.cash-account-product-query.store :as store]))

(defn get-version
  "Load a single version. Returns the version map or a rejection
  anomaly if not found.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - product-id: product id.
  - version-id: version id."
  [txn bank-id product-id version-id]
  (core/get-version txn bank-id product-id version-id))

(defn get-product
  "Return `{:product-id <pid> :versions [...]}` for one product,
  newest version first.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - product-id: product id."
  [txn bank-id product-id]
  (core/get-product txn bank-id product-id))

(defn get-products
  "Return `{:items [{:product-id ... :versions [...]} ...]}` for
  an organization, grouped by product-id in scan order.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - opts (optional): map; `:limit`, `:order`."
  ([txn bank-id] (core/get-products txn bank-id))
  ([txn bank-id opts] (core/get-products txn bank-id opts)))

(defn get-template
  "Load a template by id. Returns the template map or a
  `:cash-account-product/template-not-found` rejection.

  Args:
  - txn: FDB transaction or db handle.
  - template-id: template id."
  [txn template-id]
  (core/get-template txn template-id))

(defn list-templates
  "Return the customer-facing platform templates (internal ones such as
  own-funds excluded), sorted by product-type.

  Args:
  - txn: FDB transaction or db handle."
  [txn]
  (core/list-templates txn))

(defn active-version
  "Return the published version of a product aggregate (as returned by
  `get-product`) effective on epoch-day `as-of`, or nil if none — the
  published version whose `[effective-from, effective-to)` window
  contains `as-of`, choosing the greatest effective-from.

  Args:
  - product: a product aggregate map with a `:versions` vector.
  - as-of: epoch-day (long) to evaluate the window at (e.g.
    `utility/today`)."
  [product as-of]
  (domain/active-version product as-of))

(defn get-versions
  "Return the raw version records for a bank (optionally one product).
  A read primitive for the write sibling's draft/limit logic.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - opts (optional): `:product-id`, `:limit`, `:order`."
  ([txn bank-id] (store/get-versions txn bank-id))
  ([txn bank-id opts] (store/get-versions txn bank-id opts)))

(defn find-version-by-idempotency-key
  "Return the CashAccountProduct version created under
  `idempotency-key`, or nil. A read primitive for the write sibling's
  new-product idempotency read-back.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - idempotency-key: the command's idempotency key."
  [txn bank-id idempotency-key]
  (store/find-version-by-idempotency-key txn bank-id idempotency-key))

(defn count-by-org
  "Count distinct products for a bank. A read primitive for the write
  sibling's limit checks."
  [txn bank-id]
  (store/count-by-org txn bank-id))

(defn count-by-org-product-type
  "Count distinct products of a product-type for a bank. A read
  primitive for the write sibling's limit checks."
  [txn bank-id product-type]
  (store/count-by-org-product-type txn bank-id product-type))
