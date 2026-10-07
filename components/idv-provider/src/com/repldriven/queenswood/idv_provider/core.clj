(ns com.repldriven.queenswood.idv-provider.core
  (:require
    [com.repldriven.queenswood.party-query.interface :as party-query]
    [com.repldriven.queenswood.person-identification.interface :as
     person-identification]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]

    [clojure.string :as str]))

(def kind "idv")

(defn- offered
  [providers]
  (mapv name (keys providers)))

(defn providers
  [config]
  (let [{:keys [default providers]} config
        entries (into {}
                      (map (fn [[k entry]]
                             [(keyword k) (assoc entry :provider (name k))]))
                      providers)
        default (some-> default
                        name
                        keyword)]
    (if (contains? entries default)
      {:kind kind :default default :providers entries}
      (error/fail :idv-provider/unknown-default
                  {:message "The default IDV provider is not offered"
                   :default (some-> default
                                    name)
                   :offered (offered entries)}))))

(defn default
  [instance]
  (let [{:keys [default providers]} instance]
    (get providers default)))

(defn entries
  [instance]
  (vals (:providers instance)))

(defn- chosen
  [bank]
  (some (fn [{bank-kind :kind :keys [provider]}]
          (when (= kind bank-kind) provider))
        (:providers bank)))

(defn for-bank
  [instance bank]
  (let [provider (chosen bank)]
    (if (nil? provider)
      (default instance)
      (or (get-in instance [:providers (keyword provider)])
          (error/reject :idv-provider/unknown
                        {:message "The bank's IDV provider is not offered"
                         :provider provider
                         :offered (offered (:providers instance))})))))

(defn full-name
  [& parts]
  (not-empty (str/join " " (remove str/blank? parts))))

(def ^:private grade->name-match
  {:match :idv-name-match-match
   :close-match :idv-name-match-close-match
   :no-match :idv-name-match-no-match})

(defn name-match
  [run-name read-name]
  (when-not (or (str/blank? run-name) (str/blank? read-name))
    (grade->name-match (party-query/match-name run-name read-name))))

(defn party-name
  [txn party-id]
  (when-not (str/blank? party-id)
    (let-nom> [identification (person-identification/get-person-identification
                               txn
                               party-id)]
      (let [{:keys [given-name middle-names family-name]} identification]
        (full-name given-name middle-names family-name)))))
