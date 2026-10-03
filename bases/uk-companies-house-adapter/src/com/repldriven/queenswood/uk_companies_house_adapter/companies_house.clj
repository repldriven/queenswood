(ns com.repldriven.queenswood.uk-companies-house-adapter.companies-house
  "The Companies House REST contract: the outbound call and the
  translation from its snake_case JSON into the company record shape.
  Anomalies stay in the vendor-neutral `:company/*` namespace — they
  surface as the API's RFC 9457 `type`, which must not name a provider."
  (:require
    [com.repldriven.queenswood.circuit-breaker.interface :as circuit-breaker]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.utility.interface :refer [assoc-some]]))

(defn- classify
  "Turn a registry response into the parsed body or the anomaly that
  names what went wrong. Separate from the call so it can be tested as
  the pure function it is — stubbing the HTTP layer would mean a global
  redef, which is not safe alongside a parallel test suite.

  The failure a caller can act on is named for the problem, the rest
  for the call site. A 404 is a rejection — the registry has no such
  company. An unreachable registry, a 5xx and a 429 are
  `:company/unavailable` / `:company/rate-limited`, which map to 503:
  the request was fine and retrying later may work. A remaining 4xx
  means our request or credentials are wrong, which no caller can act
  on, so it keeps the call-site name and carries the detail in the
  payload."
  [company-number res]
  (cond
   (error/anomaly? res)
   (error/fail :company/unavailable
               {:message "Companies House unreachable"
                :company-number company-number
                :cause res})

   (= 404 (:status res))
   (error/reject :company/not-found
                 {:message "Company not found"
                  :company-number company-number})

   (= 429 (:status res))
   (error/fail :company/rate-limited
               {:message "Companies House rate limit exceeded"
                :company-number company-number
                :status (:status res)
                :body (:body res)})

   (>= (:status res) 500)
   (error/fail :company/unavailable
               {:message "Companies House unavailable"
                :company-number company-number
                :status (:status res)
                :body (:body res)})

   (>= (:status res) 400)
   (error/fail :company/http
               {:message "Companies House API error"
                :company-number company-number
                :status (:status res)
                :body (:body res)})

   :else
   (let [body (http/res->edn res)]
     (if (error/anomaly? body)
       (error/fail :company/parse
                   {:message "Failed to parse Companies House response"
                    :company-number company-number
                    :cause body})
       body))))

(def ^:private destination "adapter:uk-companies-house")

(defn- outcome
  "Whether Companies House answered: an unreachable registry, a 5xx and
  a 429 did not."
  [res]
  (let [{:keys [status]} res]
    (if (or (error/anomaly? res) (>= status 500) (= 429 status))
      :failed
      :answered)))

(defn fetch-company
  "GET `{companies-house-url}/company/{company-number}`, through the
  breaker on Companies House. Returns the parsed JSON body as a Clojure
  map (snake_case keys, per the Companies House contract) or an
  anomaly, `:company/unavailable` at once while the breaker is open."
  [config company-number]
  (let [{:keys [companies-house-url breaker]} config]
    (classify company-number
              (circuit-breaker/guard
               (select-keys config [:record-db :record-store])
               breaker
               destination
               outcome
               (fn []
                 (http/request {:method :get
                                :url (str companies-house-url
                                          "/company/"
                                          company-number)
                                :headers {"Accept" "application/json"}}))))))

(defn- address->record
  [{:keys [address_line_1 locality postal_code country]}]
  (assoc-some {}
              :address-line-1 address_line_1
              :locality locality
              :postal-code postal_code
              :country country))

(defn body->company
  "Translate a Companies House company profile into the record shape the
  `company` brick persists."
  [{:keys [company_number company_name company_status type jurisdiction
           date_of_creation registered_office_address]}]
  (assoc-some {:company-number company_number}
              :company-name company_name
              :company-status company_status
              :type type
              :jurisdiction jurisdiction
              :date-of-creation date_of_creation
              :registered-office-address
              (when registered_office_address
                (address->record registered_office_address))))
