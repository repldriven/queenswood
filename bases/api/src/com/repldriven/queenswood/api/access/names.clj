(ns com.repldriven.queenswood.api.access.names
  "The name every person an access response refers to is shown by: a
  user's own record, whether or not they are still a member, or the
  platform where no record stands behind a principal id. Each distinct
  id is looked up once per response. A bank's owners, as the bank list
  shows them, are named here too."
  (:require
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str]))

(def platform-name
  "The name an act shows when no user record stands behind its principal
  id: the `queenswood-admin` client, and the `unknown` principal a create
  records when it was given no actor."
  "Queenswood")

(defn- users
  [lookup ids]
  (reduce (fn [found id]
            (let [user (lookup id)]
              (cond (not (error/anomaly? user))
                    (assoc found id user)
                    (= :user/not-found (error/kind user))
                    found
                    :else
                    (reduced user))))
          {}
          (distinct (remove nil? ids))))

(defn user-names
  "A map from each distinct id in `ids` that has a user record to its
  name, or its email when the name is blank, or the first anomaly
  `lookup` answers other than `:user/not-found`. `lookup` is a
  one-argument function from a user id to a `User` or an anomaly."
  [lookup ids]
  (let-nom> [found (users lookup ids)]
    (update-vals found
                 (fn [{:keys [name email]}]
                   (if (str/blank? name) email name)))))

(defn recipient-names
  "The map `user-names` answers, with no email fallback: a user whose
  record has no name maps to that blank name, which `->actor` replaces
  with the name it is given for an unnamed actor."
  [lookup ids]
  (let-nom> [found (users lookup ids)]
    (update-vals found :name)))

(defn ->actor
  "The actor as a response shows it: `kind`, `principal-id` and `name`.
  The name comes from `names`, or is the platform's when the principal id
  has no entry, or `unnamed` when the entry is blank."
  ([actor names] (->actor actor names platform-name))
  ([actor names unnamed]
   (let [{:keys [kind principal-id]} actor
         name (get names principal-id platform-name)]
     {:kind kind
      :principal-id principal-id
      :name (if (str/blank? name) unnamed name)})))

(defn actor-ids
  "The principal ids of `actors`."
  [actors]
  (keep :principal-id actors))

(defn- owner
  [lookup membership]
  (let [{:keys [membership-id user-id]} membership
        found {:membership-id membership-id :user-id user-id}
        user (lookup user-id)]
    (cond (not (error/anomaly? user))
          (let [{:keys [name email]} user]
            (utility/assoc-some found
                                :name (when-not (str/blank? name) name)
                                :email email))
          (= :user/not-found (error/kind user))
          found
          :else
          user)))

(defn owners
  "The bank's owners, one per active membership of role owner: each its
  `membership-id` and `user-id`, with the `name` and `email` its user
  record holds, a blank name left out. An owner with no user record is
  listed without either. Answers a vector, empty when the bank has no
  active owner, or the first anomaly a read answers other than
  `:user/not-found`. `list-active` is a one-argument function from a bank
  id to its active memberships or an anomaly, and `lookup` one from a
  user id to a `User` or an anomaly."
  [list-active lookup bank-id]
  (let-nom> [active (list-active bank-id)]
    (reduce (fn [found membership]
              (let [result (owner lookup membership)]
                (if (error/anomaly? result)
                  (reduced result)
                  (conj found result))))
            []
            (filter (fn [membership] (= :role-owner (:role membership)))
                    active))))
