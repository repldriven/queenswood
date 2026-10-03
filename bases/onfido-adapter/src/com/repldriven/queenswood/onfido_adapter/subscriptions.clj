(ns com.repldriven.queenswood.onfido-adapter.subscriptions
  (:require
    [com.repldriven.queenswood.onfido-webhook.interface :as onfido-webhook]
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
  [{:keys [adapter-url]}]
  [(str adapter-url onfido-webhook/path)])

(defn- held
  [{:keys [onfido-url]}]
  (let [[outcome result] (classify
                          (http/request {:method :get
                                         :url (str onfido-url "/v3.6/webhooks")
                                         :headers {"Accept"
                                                   "application/json"}}))]
    (if (= :answered outcome)
      (let [body (error/try-nom :onfido-adapter/webhooks
                                "Onfido's webhook list is not JSON"
                                (json/read-str (:body result)))]
        (if (error/anomaly? body)
          [:refused (:message (error/payload body))]
          [:answered (set (map (fn [w] (get w "url")) (get body "webhooks")))]))
      [outcome result])))

(defn- subscribe
  [{:keys [onfido-url]} url]
  (classify (http/request {:method :post
                           :url (str onfido-url "/v3.6/webhooks")
                           :headers {"Content-Type" "application/json"}
                           :body (json/write-str
                                  {:url url
                                   :events ["workflow_run.completed"]})})))

(registrar/defsubscriptions :onfido
                            {:wanted wanted :held held :subscribe subscribe})
