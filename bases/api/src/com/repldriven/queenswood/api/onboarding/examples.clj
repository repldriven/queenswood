(ns com.repldriven.queenswood.api.onboarding.examples
  (:require
    [com.repldriven.queenswood.api.bank.examples :as bank-examples]
    [com.repldriven.queenswood.api.me.examples :as me-examples]

    [com.repldriven.queenswood.api-schema.interface :refer
     [examples-registry]]))

(def OnboardingRequest {:company-number "SC998137" :bank-name "Galactic Bank"})

(def OnboardingResponse
  {:user me-examples/User
   :bank (assoc bank-examples/Bank
                :client-secret bank-examples/ClientSecret
                :company-binding bank-examples/CompanyBinding)
   :membership me-examples/Membership})

(def AlreadyOnboarded
  {:value {:title "REJECTED"
           :type ":membership/already-exists"
           :status 409
           :detail "User already belongs to a bank"}})

(def CompanyNotActive
  {:value {:title "REJECTED"
           :type ":onboarding/company-not-active"
           :status 422
           :detail "Only an active company can be bound to a bank"}})

(def CompanyNotFound
  {:value {:title "REJECTED"
           :type ":company/not-found"
           :status 404
           :detail "No active company found for that number"}})

(def registry
  (examples-registry [#'OnboardingRequest #'OnboardingResponse
                      #'AlreadyOnboarded #'CompanyNotActive #'CompanyNotFound]))
