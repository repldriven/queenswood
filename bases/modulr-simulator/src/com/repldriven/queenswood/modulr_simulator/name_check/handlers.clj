(ns com.repldriven.queenswood.modulr-simulator.name-check.handlers
  (:require
    [com.repldriven.queenswood.modulr-simulator.ledger :as ledger]
    [com.repldriven.queenswood.modulr-simulator.signed :as signed]

    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str]))

(defn- result
  "The name check's answer. The test values every payment simulator
  shares decide it: a name holding `COP_NOMATCH`, `COP_CLOSEMATCH` or
  `COP_UNAVAILABLE`; any other name matches."
  [name]
  (cond
   (str/includes? name "COP_NOMATCH")
   {:code "NOT_MATCHED"}

   (str/includes? name "COP_CLOSEMATCH")
   {:code "CLOSE_MATCH" :name (str/trim (str/replace name "COP_CLOSEMATCH" ""))}

   (str/includes? name "COP_UNAVAILABLE")
   {:code "ACCOUNT_NOT_SUPPORTED"}

   :else
   {:code "MATCHED"}))

(def check
  (signed/verified
   (fn [request]
     (let [{:keys [state parameters]} request
           {:keys [paymentAccountId name]} (:body parameters)
           payer (ledger/account state paymentAccountId)]
       (if (not= "ACTIVE" (:status payer))
         (signed/refusal "BUSINESSRULE" "The paying account is not active")
         {:status 201
          :body {:id (str "C" (utility/uuidv7)) :result (result name)}})))))
