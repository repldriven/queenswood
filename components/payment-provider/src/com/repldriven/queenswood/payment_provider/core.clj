(ns com.repldriven.queenswood.payment-provider.core
  (:require
    [com.repldriven.mono.error.interface :as error]))

(def kind "payment")

(def defaults {:inbound "notified" :returns [] :screening "provider"})

(defn declared
  [declaration]
  (merge defaults declaration))

(defn- values
  [v]
  (cond
   (nil? v)
   []

   (coll? v)
   v

   :else
   [v]))

(defn- uncovered
  [declaration carries]
  (let [declaration (declared declaration)]
    (into {}
          (keep (fn [[k supported]]
                  (let [missing (vec (remove supported
                                             (values (get declaration k))))]
                    (when (seq missing) [k missing]))))
          carries)))

(defn check
  [declaration carries]
  (let [missing (uncovered declaration carries)]
    (when (seq missing)
      (error/fail :payment/unsupported-declaration
                  {:message
                   "The payment provider declaration asks more than it carries"
                   :uncovered missing}))))

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
      (error/fail :payment-provider/unknown-default
                  {:message "The default payment provider is not offered"
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
          (error/reject :payment-provider/unknown
                        {:message "The bank's payment provider is not offered"
                         :provider provider
                         :offered (offered (:providers instance))})))))
