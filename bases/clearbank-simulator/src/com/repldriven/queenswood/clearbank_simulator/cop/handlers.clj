(ns com.repldriven.queenswood.clearbank-simulator.cop.handlers
  (:require
    [com.repldriven.queenswood.clearbank-simulator.signed :as signed]
    [com.repldriven.queenswood.clearbank-simulator.webhook
     :as webhook]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.utility.interface :refer [uuidv7]]

    [clojure.string :as str]))

(defn- simulated-result
  [account-holder-name]
  (cond
   (str/includes? account-holder-name "COP_NOMATCH")
   {:matchResult "NoMatch"
    :reasonCode "ANNM"
    :reason "Account name does not match"}

   (str/includes? account-holder-name "COP_CLOSEMATCH")
   {:matchResult "CloseMatch"
    :actualName (str/replace account-holder-name
                             "COP_CLOSEMATCH"
                             "")
    :reasonCode "PANM"
    :reason "Partial name match"}

   (str/includes? account-holder-name "COP_UNAVAILABLE")
   {:matchResult "Unavailable"
    :reasonCode "ACNS"
    :reason "Account does not support CoP"}

   :else
   {:matchResult "Match"}))

(defn- webhook-result
  [config sort-code body]
  (let [request-id (str "cop-" (uuidv7))
        res (webhook/fire-inbound-cop-request config
                                              sort-code
                                              request-id
                                              body)]
    (if (and (map? res)
             (not (error/anomaly? res))
             (= 200 (:status res)))
      (let [{:keys [matchResult actualName reasonCode reason]}
            (http/res->edn res)]
        {:matchResult matchResult
         :actualName actualName
         :reasonCode reasonCode
         :reason reason})
      {:matchResult "Unavailable"
       :reasonCode "ACNS"
       :reason "No response from bank"})))

(defn- has-webhook?
  [webhooks sort-code]
  (some? (get-in @webhooks
                 [sort-code "InboundCopRequestReceived"])))

(defn outbound-cop
  [_config]
  (signed/verified
   (fn [request]
     (let [{:keys [webhooks signing-key parameters]} request
           {:keys [body]} parameters
           {:keys [accountHolderName endToEndIdentification
                   accountDetails]}
           body
           {:keys [sortCode]} accountDetails
           config {:webhooks webhooks :signing-key signing-key}
           result (if (has-webhook? webhooks sortCode)
                    (webhook-result config sortCode body)
                    (simulated-result accountHolderName))]
       {:status 200
        :body (assoc result
                     :endToEndIdentification
                     endToEndIdentification)}))))
