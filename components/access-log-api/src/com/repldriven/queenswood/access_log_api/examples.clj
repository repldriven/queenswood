(ns com.repldriven.queenswood.access-log-api.examples
  (:require
    [com.repldriven.queenswood.me-api.interface :as me-api]))

(def AccessEntryId "axs.0000000000000181ma6r00000s")

(def AccessEntry
  {:access-entry-id AccessEntryId
   :bank-id me-api/BankId
   :request-id "01a12a36-88ef-7f53-88f1-7973bba7fbde"
   :idempotency-key "suspend-acc-0042"
   :correlation-id "suspend-acc-0042"
   :trace-id "4bf92f3577b34da6a3ce929d0e0e4736"
   :requested-at "2026-06-01T09:00:00Z"
   :requested-by
   {:kind :member :principal-id me-api/UserId :name "Ada Lovelace"}
   :method "POST"
   :route "/v1/cash-accounts/{account-id}/suspend"
   :operation-id "SuspendCashAccount"
   :tag "Cash Accounts"
   :path-ids {:account-id "acc.01kprbmgcj35ptc8npmybhh4sm"}
   :http-status 409
   :problem-type ":cash-account/invalid-status"
   :replayed false
   :client-address "203.0.113.7"
   :duration 41250})

(def AccessEntryList
  {:items [AccessEntry]
   :links {:next (str "/v1/bank/access-log?page[after]="
                      AccessEntryId
                      "&page[size]=20")}})
