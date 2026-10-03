(ns com.repldriven.queenswood.form3-adapter.subscriptions
  (:require
    [com.repldriven.queenswood.form3-adapter.webhook.routes :as webhook]

    [com.repldriven.queenswood.form3-relay.interface :as relay]
    [com.repldriven.queenswood.registrar.interface :as registrar]

    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private subscriptions-path "/v1/notification/subscriptions")

(defn- call
  [config request]
  (let [[outcome result] (relay/classify
                          (relay/request (select-keys config
                                                      [:form3-url :credentials])
                                         request))]
    [(if (= :ok outcome) :answered outcome) result]))

(defn- wanted
  [{:keys [webhook-url]}]
  (map (fn [[record-type event-type]]
         [record-type event-type (str webhook-url webhook/path)])
       webhook/subscriptions))

(defn- held
  [config]
  (let [[outcome result] (call config {:method :get :path subscriptions-path})]
    (if (= :answered outcome)
      [:answered
       (set (map (fn [{:keys [attributes]}]
                   [(:record_type attributes)
                    (:event_type attributes)
                    (:callback_uri attributes)])
                 (:data result)))]
      [outcome result])))

(defn- subscribe
  [config [record-type event-type callback-uri]]
  (call config
        {:method :post
         :path subscriptions-path
         :body {:data {:id (str (utility/uuidv7))
                       :type "subscriptions"
                       :attributes {:callback_transport "http"
                                    :callback_uri callback-uri
                                    :record_type record-type
                                    :event_type event-type}}}}))

(registrar/defsubscriptions :form3
                            {:wanted wanted :held held :subscribe subscribe})
