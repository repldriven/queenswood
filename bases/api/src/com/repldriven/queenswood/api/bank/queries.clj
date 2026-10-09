(ns com.repldriven.queenswood.api.bank.queries
  (:require
    [com.repldriven.queenswood.api.access.names :as names]
    [com.repldriven.queenswood.api.cursor :as cursor]
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.bank-query.interface :as banks]
    [com.repldriven.queenswood.cash-account-api.interface :as
     cash-account-api]
    [com.repldriven.queenswood.member-query.interface :as members]
    [com.repldriven.queenswood.party-api.interface :as party-api]
    [com.repldriven.queenswood.user.interface :as users]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(defn- offered
  "The providers instance of each kind the installation offers."
  [request]
  (vals (:providers request)))

(defn bank-body
  "`bank` as every route returns it: `:providers` the key of its provider
  of each kind offered, by kind, the one it records or the default where
  it records none, its party as the party routes return one, and each
  of its accounts as the account routes return one."
  [request bank]
  (let [recorded (into {} (map (juxt :kind :provider)) (:providers bank))]
    (cond-> (assoc bank
                   :providers
                   (into {}
                         (map (fn [{:keys [kind default]}]
                                [(keyword kind)
                                 (get recorded kind (name default))]))
                         (offered request)))
            (contains? bank :party)
            (update :party party-api/->body)

            (contains? bank :accounts)
            (update :accounts
                    (fn [accounts] (mapv cash-account-api/->body accounts))))))

(defn list-providers
  [request]
  {:status 200
   :body {:items (->> (offered request)
                      (sort-by :kind)
                      (mapv (fn [{:keys [kind default providers]}]
                              {:kind kind
                               :providers (vec (sort (map name
                                                          (keys providers))))
                               :default (name default)})))}})

(defn- with-owners
  [found owners-of]
  (reduce (fn [enriched bank]
            (let [owners (owners-of (:bank-id bank))]
              (if (error/anomaly? owners)
                (reduced owners)
                (conj enriched (assoc bank :owners owners)))))
          []
          found))

(defn banks-response
  "The 200 body for one page of banks, `found` as `get-banks` returns it,
  each bank given the owners `owners-of` names for its id and the
  providers `request` offers."
  [request page found owners-of]
  (let [result (let-nom> [{:keys [banks]} found]
                 (with-owners (mapv (fn [bank] (bank-body request bank))
                                    banks)
                              owners-of))]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body (cursor/page-body "/v1/banks" page result found)})))

(defn- owner-lookups
  "The two lookups `names/owners` takes, backed by one read of every
  listed bank's active members and one of their owners' users,
  rather than a read per bank and per owner. Returns
  `{:list-active f :lookup f}` or an anomaly."
  [config found]
  (let-nom> [active (members/list-active-by-banks config
                                                  (map :bank-id found))
             users (users/find-by-ids config
                                      (into #{}
                                            (comp cat
                                                  (filter #(= :role-owner
                                                              (:role %)))
                                                  (map :user-id))
                                            (vals active)))]
    {:list-active (fn [bank-id] (get active bank-id []))
     :lookup (fn [user-id] (get users user-id))}))

(defn get-bank
  [request]
  (let [{:keys [auth record-db record-store]} request
        {:keys [bank-id]} auth
        config {:record-db record-db :record-store record-store}
        result (let-nom>
                 [bank (banks/get-bank-view config bank-id)
                  {:keys [list-active lookup]} (owner-lookups config [bank])
                  owners (names/owners list-active lookup bank-id)]
                 (assoc (bank-body request bank) :owners owners))]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body result})))

(defn list-banks
  [request]
  (let [{:keys [record-db record-store parameters]} request
        {:keys [page]} (:query parameters)
        config {:record-db record-db :record-store record-store}
        found (banks/get-banks config (cursor/page-opts page))
        lookups (if (error/anomaly? found)
                  found
                  (owner-lookups config (:banks found)))]
    (if (error/anomaly? lookups)
      (errors/anomaly->response lookups)
      (let [{:keys [list-active lookup]} lookups]
        (banks-response request
                        page
                        found
                        (fn [bank-id]
                          (names/owners list-active lookup bank-id)))))))
