(ns com.repldriven.queenswood.api.me.handlers
  (:require
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.mono.error.interface :as error]))

(def ^:private user-keys
  [:user-id :issuer :sub :email :name :avatar-url :identity-provider :status
   :created-at :updated-at])

(defn get-me
  "Return the authenticated user and whether they are an operator. The
  auth interceptor upserts the user on every authenticated request, so
  a successful verification guarantees the user row exists by the time
  this handler runs — no 404 path is needed."
  [request]
  (let [{:keys [auth]} request
        {:keys [user roles]} auth]
    (if (not= :user (:principal-type auth))
      (errors/anomaly->response
       (error/unauthorized :auth/unauthenticated
                           {:message "Only user JWTs may call /v1/me"}))
      {:status 200
       :body (assoc (select-keys user user-keys)
                    :operator
                    (contains? roles :admin))})))
