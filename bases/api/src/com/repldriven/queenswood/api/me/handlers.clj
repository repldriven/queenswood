(ns com.repldriven.queenswood.api.me.handlers
  (:require
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.bank-query.interface :as banks]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private membership-keys
  [:membership-id :user-id :bank-id :role :created-at :updated-at])

(defn- enrich-with-bank-name
  "Project each membership onto the keys `Membership` declares and add
  the bank's `:name` as `:bank-name`, so SPAs can render a bank-context
  kicker without a follow-up lookup. A membership whose bank-id doesn't
  resolve is left without one."
  [txn memberships]
  (mapv (fn [m]
          (let [b (banks/get-bank txn (:bank-id m))]
            (utility/assoc-some (select-keys m membership-keys)
                                :bank-name
                                (when-not (error/anomaly? b) (:name b)))))
        memberships))

(defn get-me
  "Return the authenticated User, their active Memberships and whether
  they are an operator. The auth interceptor upserts the User on every
  authenticated request, so a successful verification guarantees the
  User row exists by the time this handler runs — no 404 path is
  needed. SPAs key their onboarding decision on
  `memberships.length === 0` rather than on status code.

  Each membership is enriched with `:bank-name` so the SPA can
  render a bank-context kicker without a follow-up lookup."
  [request]
  (let [{:keys [record-db record-store auth]} request
        {:keys [user memberships roles]} auth
        txn {:record-db record-db :record-store record-store}]
    (if (not= :user (:principal-type auth))
      (errors/anomaly->response
       (error/unauthorized :auth/unauthenticated
                           {:message "Only user JWTs may call /v1/me"}))
      {:status 200
       :body {:user user
              :memberships (enrich-with-bank-name txn (or memberships []))
              :operator (contains? roles :admin)}})))
