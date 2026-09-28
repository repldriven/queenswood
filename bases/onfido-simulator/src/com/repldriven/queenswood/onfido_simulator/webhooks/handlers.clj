(ns com.repldriven.queenswood.onfido-simulator.webhooks.handlers
  (:require
    [com.repldriven.mono.utility.interface :refer [uuidv7]]))

(defn list-webhooks
  [_config]
  (fn [request]
    (let [{:keys [state]} request]
      {:status 200 :body {:webhooks (vec (:webhooks @state))}})))

(defn register-webhook
  [_config]
  (fn [request]
    (let [{:keys [state parameters webhook-token]} request
          {:keys [url events]} (:body parameters)
          existing (some (fn [w] (when (= url (:url w)) w))
                         (:webhooks @state))]
      (if existing
        ;; Idempotent on URL. Adapter pods bounce while the
        ;; simulator stays up; without dedup each restart appended
        ;; another entry under a fresh UUID, so callbacks fanned
        ;; out N times per restart against the same Service URL.
        ;; Returning the existing entry with 200 (not 201) makes
        ;; periodic re-register-on-poll a no-op.
        {:status 200 :body existing}
        (let [id (str (uuidv7))
              webhook
              {:id id :url url :token webhook-token :events (vec events)}]
          (swap! state update :webhooks conj webhook)
          {:status 201 :body webhook})))))

(defn deregister-webhook
  [_config]
  (fn [request]
    (let [{:keys [state parameters]} request
          id (get-in parameters [:path :id])
          existing (some (fn [w] (when (= id (:id w)) w))
                         (:webhooks @state))]
      (if-not existing
        {:status 404
         :body {:title "NOT_FOUND"
                :type "webhook/not-found"
                :status 404
                :detail (str "No webhook with id: " id)}}
        (do (swap! state update
              :webhooks
              (fn [ws] (vec (remove (fn [w] (= id (:id w))) ws))))
            {:status 204 :body nil})))))
