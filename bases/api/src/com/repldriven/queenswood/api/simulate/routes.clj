(ns com.repldriven.queenswood.api.simulate.routes
  (:require
    [com.repldriven.queenswood.api.examples :as api.examples]
    [com.repldriven.queenswood.api.simulate.examples :refer
     [BalanceNotFound InvalidAmount LedgerAccountClosed
      MissingCurrencyAccount SettlementAccountNotFound ForeignBankSimulation]]
    [com.repldriven.queenswood.api.simulate.handlers :as handlers]

    [com.repldriven.queenswood.api.bank.examples :refer [BankNotFound]]
    [com.repldriven.queenswood.api.shared.idempotency :as shared.idempotency]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.api-schema.interface :refer
     [ErrorExamples ErrorResponse]]
    [com.repldriven.queenswood.idempotency.interface :as bank-idempotency]

    [com.repldriven.mono.server.interface :as server]))

(def routes
  [["/simulate"
    {:openapi {:tags ["Simulate"] :security [{"bearerAuth" ["admin"]}]}}
    ["/banks/{bank-id}"
     {:parameters {:path {:bank-id [:ref "BankId"]}}}
     ["/inbound-transfer"
      ;; Sandbox affordance: a bank tenant funds its own bank from the
      ;; console, so this route alone drops to `org:developer` (accrue and
      ;; capitalize stay admin-only). The handler holds the tenant
      ;; boundary; `admin` joins the level, carrying no bank of its own.
      {:openapi {:security ^:replace
                           [{"bearerAuth" ["org:developer"]}
                            {"bearerAuth" ["admin"]}]}
       :post
       {:summary "Simulate an inbound transfer into the bank's own funds"
        :openapi {:operationId "SimulateInboundTransfer"
                  :description
                  (str "Credits the own-funds account of the bank in the path, "
                       "in the given currency, as money arriving from outside, "
                       "and returns the posted transaction with the account it "
                       "credited. A token for another bank is refused with 403."
                       " A currency the bank has no account in is refused with "
                       "409.")
                  :requestBody {:required true}
                  :parameters ^:replace
                              [shared.parameters/ref-bank-id
                               shared.parameters/ref-bank-id-header
                               shared.parameters/ref-idempotency-key]}
        :parameters {:body [:ref "SimulateInboundTransferRequest"]}
        :interceptors [server/require-idempotency-key
                       bank-idempotency/cache-response]
        :responses (shared.idempotency/with-responses
                    {200 {:description
                          "The posted transaction and the account it credited."
                          :body [:ref
                                 "SimulateInboundTransferResponse"]}
                     403 (ErrorExamples [#'ForeignBankSimulation])
                     404 (ErrorResponse [#'BankNotFound
                                         #'BalanceNotFound])
                     409 (ErrorResponse [#'MissingCurrencyAccount
                                         #'LedgerAccountClosed])
                     422 (ErrorResponse [#'InvalidAmount])})
        :handler handlers/inbound-transfer}}]
     ["/accrue"
      {:post {:summary "Accrue a day of interest"
              :openapi {:operationId "SimulateAccrue"
                        :description
                        (str "Accrues one day's interest on every opened "
                             "customer account of the bank the path names, "
                             "for the given date, and returns how many "
                             "accounts it processed. Repeating a date "
                             "accrues nothing twice.")
                        :requestBody {:required true}
                        :parameters ^:replace
                                    [shared.parameters/ref-bank-id
                                     shared.parameters/ref-idempotency-key]}
              :parameters {:body [:ref "SimulateInterestRequest"]}
              :interceptors [server/require-idempotency-key
                             bank-idempotency/cache-response]
              :responses (shared.idempotency/with-responses
                          {200 {:description "How many accounts were processed."
                                :body [:ref
                                       "SimulateInterestResponse"]}
                           404 (ErrorResponse [#'BankNotFound
                                               #'SettlementAccountNotFound])
                           429 (ErrorResponse
                                [#'api.examples/PolicyLimitExceeded])})
              :handler handlers/accrue}}]
     ["/capitalize"
      {:post
       {:summary "Capitalise accrued interest"
        :openapi {:operationId "SimulateCapitalize"
                  :description
                  (str "Moves the accrued interest of every opened "
                       "customer account of the bank the path names "
                       "into its spendable balance as of the given "
                       "date, and returns how many accounts it "
                       "processed. Repeating a date posts nothing " "twice.")
                  :requestBody {:required true}
                  :parameters ^:replace
                              [shared.parameters/ref-bank-id
                               shared.parameters/ref-idempotency-key]}
        :parameters {:body [:ref "SimulateInterestRequest"]}
        :interceptors [server/require-idempotency-key
                       bank-idempotency/cache-response]
        :responses (shared.idempotency/with-responses
                    {200 {:description "How many accounts were processed."
                          :body [:ref
                                 "SimulateInterestResponse"]}
                     404 (ErrorResponse [#'BankNotFound
                                         #'SettlementAccountNotFound])
                     429 (ErrorResponse [#'api.examples/PolicyLimitExceeded])})
        :handler handlers/capitalize}}]]]])
