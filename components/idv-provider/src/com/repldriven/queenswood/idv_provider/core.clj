(ns com.repldriven.queenswood.idv-provider.core
  (:require
    [com.repldriven.mono.error.interface :as error]))

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
      {:default default :providers entries}
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
