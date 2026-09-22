(ns com.repldriven.queenswood.demo-digital-bank-api.home.handlers
  (:require
    [com.repldriven.queenswood.demo-digital-bank-api.errors :as errors]

    [com.repldriven.queenswood.demo-digital-bank.interface :as bank]

    [com.repldriven.mono.sse.interface :as sse]))

(defn me
  [request]
  (let [{:keys [bank customer]} request]
    (errors/respond 200 (bank/me bank customer))))

(defn events
  [request]
  (let [{:keys [bank customer]} request]
    (sse/response (fn [emit] (bank/events bank customer emit))
                  {:event "notification"})))
