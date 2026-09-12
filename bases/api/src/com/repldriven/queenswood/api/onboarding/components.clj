(ns com.repldriven.queenswood.api.onboarding.components
  (:require
    [com.repldriven.queenswood.api.onboarding.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :as schema :refer
     [components-registry]]))

(def OnboardingRequest
  "Binds the new bank to a confirmed legal entity. `company-number` is
  looked up against the registry of record and must be active;
  `bank-name` is the public-facing name (the console pre-fills the
  registered name)."
  [:map {:closed true :json-schema/example examples/OnboardingRequest}
   [:company-number string?]
   [:bank-name [:ref "Name"]]])

(def OnboardingResponse
  [:map {:json-schema/example examples/OnboardingResponse}
   [:user [:ref "User"]]
   [:bank [:ref "CreateBankResponse"]]
   [:membership [:ref "Membership"]]])

(def registry (components-registry [#'OnboardingRequest #'OnboardingResponse]))
