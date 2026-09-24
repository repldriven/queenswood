(ns com.repldriven.queenswood.api.examples
  (:require
    [com.repldriven.queenswood.api-schema.interface :refer
     [examples-registry]]))

(def BadRequest
  {:value {:title "REJECTED"
           :type "server/bad-request"
           :status 400
           :detail "Bad Request"}})

(def Unauthorized
  {:value {:title "UNAUTHORIZED"
           :type "auth/unauthenticated"
           :status 401
           :detail "Missing or invalid token"}})

(def Forbidden
  {:value {:title "FORBIDDEN"
           :type "auth/forbidden"
           :status 403
           :detail "Insufficient privileges"}})

(def PolicyDenied
  {:value {:title "UNAUTHORIZED"
           :type ":policy/denied"
           :status 403
           :detail "No matching allow capability"}})

(def PolicyLimitExceeded
  {:value {:title "REJECTED"
           :type ":policy/limit-exceeded"
           :status 429
           :detail "Limit exceeded for this bank"}})

(def BadResponse
  {:value {:title "FAILED"
           :type "server/bad-response"
           :status 500
           :detail "Bad Response"}})

(def InternalServerError
  {:value {:title "FAILED"
           :type "mono/internal-server-error"
           :status 500
           :detail "Internal server error"}})

(def MissingIdempotencyKey
  {:value {:title "REJECTED"
           :type "server/missing-idempotency-key"
           :status 400
           :detail "Missing Idempotency-Key header"}})

(def InvalidIdempotencyKey
  {:value {:title "REJECTED"
           :type "server/invalid-idempotency-key"
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
  (examples-registry
   [#'BadRequest #'Unauthorized #'Forbidden #'BadResponse #'InternalServerError
    #'Contention #'Timeout #'MissingIdempotencyKey #'InvalidIdempotencyKey
    #'IdempotentRequestInFlight #'IdempotencyKeyReused
    #'IdempotencyCacheUnavailable #'PolicyDenied #'PolicyLimitExceeded]))
