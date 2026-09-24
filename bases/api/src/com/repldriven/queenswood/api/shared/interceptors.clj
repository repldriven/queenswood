(ns com.repldriven.queenswood.api.shared.interceptors
  "Cross-cutting reitit interceptors for the bank-api router."
  (:require
    [com.repldriven.queenswood.api.errors :as errors]

    [clojure.string :as str]
    [sieppari.context :as sc]))

(defn- nest-bracket-entry
  "If `k` looks like `outer[inner]`, returns `[:outer-kw :inner-kw v]`.
  Otherwise returns `[k v]` unchanged. Keys with more than one
  bracket pair (`a[b][c]`) aren't supported — we treat them as
  opaque and leave them to fail the top-level closed-map check."
  [k v]
  (if-let [[_ outer inner]
           (re-matches #"^([^\[\]]+)\[([^\[\]]+)\]$" (str/trim (name k)))]
    [(keyword outer) (keyword inner) v]
    [(if (keyword? k) k (keyword k)) v]))

(defn- nest-params
  "Walks a flat query-params map like `{\"embed[transactions]\" \"false\"
  \"page[size]\" \"20\"}` into a nested map
  `{:embed {:transactions \"false\"} :page {:size \"20\"}}`. Flat,
  non-bracketed keys are kept at the top level.

  Blank flat values (e.g. `?page=` from schemathesis probing the bare
  deepObject serialization) are dropped — a deepObject parameter with
  no `[inner]` segment has no meaningful value, and treating it as
  \"not provided\" keeps optional params schema-compliant rather than
  forcing a 400 on a string/object type mismatch."
  [params]
  (reduce-kv
   (fn [acc k v]
     (let [entry (nest-bracket-entry k v)]
       (if (= 3 (count entry))
         (let [[outer inner v'] entry]
           (update acc outer (fnil assoc {}) inner v'))
         (let [[k' v'] entry]
           (if (and (string? v') (str/blank? v'))
             acc
             (assoc acc k' v'))))))
   {}
   params))

(def nest-bracket-query-params
  "Reitit interceptor that rewrites flat bracketed query-params (the
  `embed[transactions]` form emitted by clients) into nested maps
  (`{:embed {:transactions …}}`) so malli can model them as canonical
  OpenAPI `deepObject`-styled parameters. Sits between the stock
  `parameters-interceptor` (which parses the raw query string) and
  `coerce-request-interceptor` (which validates the result)."
  {:name ::nest-bracket-query-params
   :enter (fn [ctx]
            (update-in ctx
                       [:request :query-params]
                       (fn [qp] (when qp (nest-params qp)))))})

(def named-bank
  "Refuses with 403 a request that resolves no bank: an operator naming
  none in the `Bank-Id` header. A member's is refused before this, by
  `auth/require-bank`, and a service token always names its own."
  {:name ::named-bank
   :enter (fn [ctx]
            (if (get-in ctx [:request :auth :bank-id])
              ctx
              (sc/terminate ctx
                            (errors/forbidden-response
                             "Name the bank in the Bank-Id header"))))})
