(ns com.repldriven.queenswood.modulr-adapter.subscriptions
  (:require
    [com.repldriven.queenswood.modulr-adapter.webhook.routes :as webhook]

    [com.repldriven.queenswood.modulr-relay.interface :as relay]
    [com.repldriven.queenswood.registrar.interface :as registrar]))

(defn- call
  [config request]
  (let [[outcome result] (relay/classify
                          (relay/request (select-keys config
                                                      [:modulr-url
                                                       :credentials])
                                         request))]
    [(if (= :ok outcome) :answered outcome) result]))

(defn- subscriptions-path
  [{:keys [customer-id]}]
  (str "/customers/" customer-id "/integration-notifications"))

(defn- wanted
  [{:keys [webhook-url]}]
  (map (fn [[type path]] [type (str webhook-url path)]) webhook/paths))

(defn- held
  [config]
  (let [[outcome result] (call config
                               {:method :get
                                :path (subscriptions-path config)})]
    (if (= :answered outcome)
      [:answered (set (map (juxt :type :url) (:content result)))]
      [outcome result])))

(defn- subscribe
  [config [type url]]
  (let [{:keys [webhook-credentials]} config]
    (call config
          {:method :post
           :path (subscriptions-path config)
           :body {:type type
                  :url url
                  :retry true
                  :secret (:secret webhook-credentials)
                  :hmacAlgorithm (:algorithm webhook-credentials)}})))

(registrar/defsubscriptions :modulr
                            {:wanted wanted :held held :subscribe subscribe})
