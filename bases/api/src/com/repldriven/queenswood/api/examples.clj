(ns com.repldriven.queenswood.api.examples
  (:require
    [com.repldriven.queenswood.api.schema :refer [examples-registry]]))

(def BadRequest
  {:value {:title "REJECTED"
           :type "mono/bad-request"
           :status 400
           :detail "Bad Request"}})

(def Unauthorized
  {:value {:title "UNAUTHORIZED"
           :type "mono/unauthenticated"
           :status 401
           :detail "Missing or invalid API key"}})

(def Forbidden
  {:value {:title "UNAUTHORIZED"
           :type "mono/unauthorized"
           :status 403
           :detail
           "API key does not have sufficient privileges for this operation"}})

(def BadResponse
  {:value {:title "FAILED"
           :type "mono/bad-response"
           :status 500
           :detail "Bad Response"}})

(def InternalServerError
  {:value {:title "FAILED"
           :type "mono/internal-server-error"
           :status 500
           :detail "Internal server error"}})

(def MissingIdempotencyKey
  {:value {:title "REJECTED"
           :type "mono/missing-idempotency-key"
           :status 400
           :detail "Missing Idempotency-Key header"}})

(def InvalidIdempotencyKey
  {:value {:title "REJECTED"
           :type "mono/invalid-idempotency-key"
           :status 400
           :detail "Idempotency-Key must be 16-255 URL-safe ASCII chars"}})

(def IdempotentRequestInFlight
  {:value {:title "REJECTED"
           :type "mono/idempotent-request-in-flight"
           :status 409
           :detail (str "A request with this Idempotency-Key is already "
                        "being processed; please retry in a moment.")}})

(def IdempotencyKeyReused
  {:value {:title "REJECTED"
           :type "mono/idempotency-key-reused"
           :status 422
           :detail (str "This Idempotency-Key was used for a different "
                        "request; use a fresh key.")}})

(def IdempotencyCacheUnavailable
  {:value {:title "FAILED"
           :type "mono/idempotency-cache-unavailable"
           :status 503
           :detail (str "The idempotency cache could not be read; please "
                        "retry in a moment.")}})

(def Contention
  {:value {:title "FAILED"
           :type ":fdb/contention"
           :status 503
           :detail "Failed to save cash account"}})

(def Timeout
  {:value {:title "FAILED"
           :type ":fdb/timeout"
           :status 503
           :detail "Failed to save cash account"}})

(def registry
  (examples-registry [#'BadRequest #'Unauthorized #'Forbidden #'BadResponse
                      #'InternalServerError #'Contention #'Timeout
                      #'MissingIdempotencyKey #'InvalidIdempotencyKey
                      #'IdempotentRequestInFlight #'IdempotencyKeyReused
                      #'IdempotencyCacheUnavailable]))
