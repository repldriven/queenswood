(ns com.repldriven.queenswood.scheme-simulator.core
  (:require
    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]))

(def ^:private sort-code-length 6)

(defn scheme [] (atom {}))

(defn join
  [scheme sort-code url]
  (swap! scheme assoc sort-code url)
  {:scheme scheme :sort-code sort-code})

(defn leave [{:keys [scheme sort-code]}] (swap! scheme dissoc sort-code))

(defn- sort-code-of
  [bban]
  (when (and bban (<= sort-code-length (count bban)))
    (subs bban 0 sort-code-length)))

(defn send-inbound
  [scheme payment]
  (when-let [url (some-> scheme
                         deref
                         (get (sort-code-of (:bban payment))))]
    (let [res (http/request {:method :post
                             :url (str url "/simulate/inbound-payment")
                             :headers {"Content-Type" "application/json"}
                             :body (json/write-str payment)})]
      (if (error/anomaly? res)
        res
        {:status (:status res) :body (http/res->edn res)}))))
