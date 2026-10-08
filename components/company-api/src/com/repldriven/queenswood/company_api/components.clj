(ns com.repldriven.queenswood.company-api.components
  (:require
    [com.repldriven.queenswood.company-api.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :refer
     [components-registry]]

    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str]))

(def RegisteredOfficeAddress
  [:map
   [:address-line-1 {:optional true} string?]
   [:locality {:optional true} string?]
   [:postal-code {:optional true} string?]
   [:country {:optional true} string?]])

(def Company
  "A company profile in the Companies House shape, as resolved from a
  registry lookup."
  [:map {:json-schema/example examples/Company}
   [:company-number string?]
   [:registry-id {:optional true} string?]
   [:company-name {:optional true} string?]
   [:company-status {:optional true} string?]
   [:type {:optional true} string?]
   [:jurisdiction {:optional true} string?]
   [:date-of-creation {:optional true} string?]
   [:registered-office-address {:optional true}
    [:ref "RegisteredOfficeAddress"]]])

(defn ->body
  [company]
  (let [{:keys [registry company-number name status company-type jurisdiction
                incorporated-on registered-office-address]}
        company]
    (utility/assoc-some {:company-number company-number
                         :registry-id (str/replace (clojure.core/name registry)
                                                   #"^company-registry-"
                                                   "")
                         :company-name name
                         :company-status status
                         :type company-type}
                        :jurisdiction jurisdiction
                        :date-of-creation (some-> incorporated-on
                                                  utility/epoch-day->iso-date)
                        :registered-office-address registered-office-address)))

(def registry (components-registry [#'RegisteredOfficeAddress #'Company]))
