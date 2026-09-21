(ns com.repldriven.queenswood.demo-digital-bank-api.platform.handlers
  (:require
    [com.repldriven.queenswood.demo-digital-bank-api.errors :as errors]

    [com.repldriven.queenswood.demo-digital-bank.interface :as bank]))

(defn receive
  [request]
  (let [{:keys [bank headers raw-body]} request]
    (errors/respond 202
                    (bank/receive bank headers (or raw-body (byte-array 0))))))
