(ns com.repldriven.queenswood.user.core
  (:require
    [com.repldriven.queenswood.user.domain :as domain]
    [com.repldriven.queenswood.user.store :as store]

    [com.repldriven.mono.error.interface :refer [let-nom>]]))

(defn upsert-by-sub
  "Idempotent upsert keyed by the OIDC (issuer, sub) pair. On first
  call (unknown pair) creates a User. On subsequent calls, only
  writes when the mutable claim fields (email/name/avatar/identity-
  provider) actually differ from the stored record — keeps re-sign-in
  on every request cheap. Returns the resulting User map."
  [txn {:keys [issuer sub] :as claims}]
  (store/transact
   txn
   (fn [txn]
     (let-nom> [existing (store/find-by-sub txn issuer sub)]
       (cond
        (nil? existing)
        (let [user (domain/new-user claims)]
          (let-nom> [_ (store/save txn user)]
            user))

        (domain/claims-changed? existing claims)
        (let [user (domain/update-user existing claims)]
          (let-nom> [_ (store/save txn user)]
            user))

        :else
        existing)))
   :user/upsert
   "Failed to upsert user"))

(defn find-by-sub
  "Returns the User with the given (issuer, sub) pair or nil if
  absent."
  [txn issuer sub]
  (store/find-by-sub txn issuer sub))

(defn find-by-id
  [txn user-id]
  (store/get-user txn user-id))

(defn find-by-ids
  [txn user-ids]
  (store/get-users txn user-ids))
