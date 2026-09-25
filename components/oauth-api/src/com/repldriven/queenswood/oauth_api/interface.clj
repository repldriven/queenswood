(ns com.repldriven.queenswood.oauth-api.interface
  "The token endpoint and the documents a client verifies tokens
  with, as the banking API publishes them: the malli components and
  the examples their bodies carry."
  (:require
    [com.repldriven.queenswood.oauth-api.components :as components]
    [com.repldriven.queenswood.oauth-api.examples :as examples]))

;; ---
;; schemas
;; ---

(def
  ^{:doc
    "Malli registry of the OAuth schemas, keyed by the name each
  appears under in the document's `components/schemas`:
  `TokenRequest`, `TokenResponse`, `TokenError`, `Jwk`,
  `JwksResponse`, `DiscoveryDoc`. Merged into the coercion registry
  in `api.clj`, so `[:ref \"X\"]` resolves them on any route."}
  registry
  components/registry)

;; ---
;; examples
;; ---

(def
  ^{:doc
    "Map of example name to example value for the OAuth bodies, feeding
  the document's `components/examples` section."}
  examples
  examples/registry)
