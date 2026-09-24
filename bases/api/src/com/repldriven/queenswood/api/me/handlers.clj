(ns com.repldriven.queenswood.api.me.handlers
  (:require
    [com.repldriven.queenswood.api.access.handlers :as access]
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.mono.error.interface :as error]))

(defn get-me
  "Return the authenticated User, their active Memberships and whether
  they are an operator. The auth interceptor upserts the User on every
  authenticated request, so a successful verification guarantees the
  User row exists by the time this handler runs — no 404 path is
  needed. SPAs key their onboarding decision on
  `memberships.length === 0` rather than on status code.

  Each membership is shown as `/v1/me/memberships` shows it, with its
  bank's name, so the SPA can render a bank-context kicker without a
  follow-up lookup."
  [request]
  (let [{:keys [record-db record-store auth]} request
        {:keys [user memberships roles]} auth
        txn {:record-db record-db :record-store record-store}
        named (access/named-memberships txn (or memberships []))]
    (cond (not= :user (:principal-type auth))
          (errors/anomaly->response
           (error/unauthorized :auth/unauthenticated
                               {:message "Only user JWTs may call /v1/me"}))
          (error/anomaly? named)
          (errors/anomaly->response named)
          :else
          {:status 200
           :body {:user user
                  :memberships named
                  :operator (contains? roles :admin)}})))
