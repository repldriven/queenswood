(ns com.repldriven.queenswood.company-api.components
  (:require
    [com.repldriven.queenswood.company-api.coercion :as coercion]
    [com.repldriven.queenswood.company-api.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :refer
     [components-registry]]))

(def CompanyRegistry
  (coercion/company-registry-enum-schema {:json-schema/example
                                          "uk-companies-house"}))

(def RegisteredOfficeAddress
  [:map
   [:address-line-1 {:optional true} string?]
   [:locality {:optional true} string?]
   [:postal-code {:optional true} string?]
   [:country {:optional true} string?]])

(def Company
  "A company as its registry holds it."
  [:map {:json-schema/example examples/Company}
   [:registry [:ref "CompanyRegistry"]]
   [:company-number string?]
   [:name string?]
   [:status string?]
   [:company-type string?]
   [:jurisdiction {:optional true} string?]
   [:incorporated-on {:optional true} [:ref "BusinessDay"]]
   [:registered-office-address {:optional true}
    [:ref "RegisteredOfficeAddress"]]])

(def ^:private company-keys
  (into [] (comp (filter vector?) (map first)) Company))

(defn ->body
  [company]
  (select-keys company company-keys))

(def registry
  (components-registry [#'CompanyRegistry #'RegisteredOfficeAddress #'Company]))
