(ns com.repldriven.queenswood.company-api.examples
  (:require
    [com.repldriven.queenswood.api-schema.interface :refer
     [examples-registry]]))

(def CompanyNotFound
  {:value {:title "REJECTED"
           :type ":company/not-found"
           :status 404
           :detail "No active company found for that number"}})

(def CompanyRegistryUnavailable
  {:value {:title "FAILED"
           :type ":company/unavailable"
           :status 503
           :detail "Companies House unavailable"}})

(def registry
  (examples-registry [#'CompanyNotFound #'CompanyRegistryUnavailable]))

(def Company
  {:registry :uk-companies-house
   :company-number "SC998137"
   :name "SIRIUS CYBERNETICS CORPORATION LTD"
   :status "active"
   :company-type "ltd"
   :jurisdiction "england-wales"
   :incorporated-on "2009-02-11"
   :registered-office-address {:address-line-1 "42 Improbability Way"
                               :locality "London"
                               :postal-code "QZ1 9ZX"
                               :country "United Kingdom"}})
