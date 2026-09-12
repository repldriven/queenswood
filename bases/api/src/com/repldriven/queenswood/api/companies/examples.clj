(ns com.repldriven.queenswood.api.companies.examples
  (:require
    [com.repldriven.queenswood.api-schema.interface :refer
     [examples-registry]]))

(def CompanyNotFound
  {:value {:title "REJECTED"
           :type ":company/not-found"
           :status 404
           :detail "No active company found for that number"}})

(def registry (examples-registry [#'CompanyNotFound]))

(def Company
  {:company-number "SC998137"
   :company-name "SIRIUS CYBERNETICS CORPORATION LTD"
   :company-status "active"
   :type "ltd"
   :jurisdiction "england-wales"
   :date-of-creation "2009-02-11"
   :registered-office-address {:address-line-1 "42 Improbability Way"
                               :locality "London"
                               :postal-code "QZ1 9ZX"
                               :country "United Kingdom"}})
