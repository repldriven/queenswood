(ns com.repldriven.queenswood.clearbank-adapter.subscriptions
  (:require
    [com.repldriven.queenswood.registrar.interface :as registrar]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]))

(defn- classify
  [res]
  (let [status (:status res)]
    (cond
     (or (error/anomaly? res)
         (not (number? status))
         (<= 500 status)
         (contains? #{408 429} status))
     [:retry (or (:message (error/payload res)) (str "HTTP " status))]

     (<= 400 status)
     [:refused (str "HTTP " status)]

     :else
     [:answered res])))

(defn- wanted
  [{:keys [webhook-url webhooks]}]
  (map (fn [{:keys [type path]}] [type (str webhook-url path)]) webhooks))

(defn- held
  [{:keys [simulator-url]}]
  (let [[outcome result] (classify
                          (http/request {:method :get
                                         :url (str simulator-url "/v1/webhooks")
                                         :headers {"Accept"
                                                   "application/json"}}))]
    (if (= :answered outcome)
      (let [body (error/try-nom :clearbank-adapter/webhooks
                                "ClearBank's webhook list is not JSON"
                                (json/read-str (:body result)))]
        (if (error/anomaly? body)
          [:refused (:message (error/payload body))]
          [:answered
           (set (map (fn [w] [(get w "type") (get w "url")])
                     (get body "webhooks")))]))
      [outcome result])))

(defn- subscribe
  [{:keys [simulator-url]} [type url]]
  (classify (http/request {:method :post
                           :url (str simulator-url "/v1/webhooks")
                           :headers {"Content-Type" "application/json"}
                           :body (json/write-str {:type type :url url})})))

(registrar/defsubscriptions :clearbank
                            {:wanted wanted :held held :subscribe subscribe})
