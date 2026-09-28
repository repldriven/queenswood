(ns com.repldriven.queenswood.form3-simulator.accounts.handlers
  (:require
    [com.repldriven.queenswood.form3-simulator.responses :as responses]
    [com.repldriven.queenswood.form3-simulator.signed :as signed]
    [com.repldriven.queenswood.form3-simulator.records :as records]))

(def register
  (signed/verified
   (fn [request]
     (let [{:keys [state parameters sort-code organisation-id]} request
           {:keys [id attributes]} (get-in parameters [:body :data])
           {:keys [bank_id account_number]} attributes
           refused? (records/take-refusal state)
           account
           (records/resource
            organisation-id
            "accounts"
            id
            (assoc attributes :status (if refused? "failed" "confirmed")))]
       (cond
        (not= sort-code bank_id)
        (signed/api-error 400 "bank_id is not one of the organisation's")

        (not (re-matches #"\d{8}" (str account_number)))
        (signed/api-error 400 "account_number must be eight digits")

        (records/get-in-state state :accounts id)
        (signed/api-error 409 "An account with this id already exists")

        refused?
        (responses/ok 201
                      (records/put state
                                   :accounts
                                   (assoc-in account
                                    [:attributes :status_reason]
                                    "The account was declined")))

        (records/register-account state account)
        (responses/ok 201 account)

        :else
        (signed/api-error 409 "The account number is already registered"))))))

(def fetch
  (signed/verified
   (fn [request]
     (let [{:keys [state parameters]} request]
       (responses/found
        (records/get-in-state state :accounts (get-in parameters [:path :id]))
        "account")))))

(def amend
  (signed/verified
   (fn [request]
     (let [{:keys [state parameters]} request
           id (get-in parameters [:path :id])
           status (get-in parameters [:body :data :attributes :status])]
       (cond
        (nil? (records/get-in-state state :accounts id))
        (signed/api-error 404 "The account does not exist")

        (not= "closed" status)
        (signed/api-error 400 "Only an account's closing is amendable here")

        :else
        (responses/ok (records/change state
                                      :accounts
                                      id
                                      (fn [a] (assoc a :status "closed")))))))))
