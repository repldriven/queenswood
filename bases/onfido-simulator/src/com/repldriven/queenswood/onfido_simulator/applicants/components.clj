(ns com.repldriven.queenswood.onfido-simulator.applicants.components
  (:require
    [com.repldriven.queenswood.onfido-simulator.applicants.examples :as
     examples]

    [com.repldriven.queenswood.onfido-simulator.schema :as schema]))

(def Address
  [:map
   {:json-schema/example examples/Address}
   [:flat_number {:optional true} string?]
   [:building_number {:optional true} string?]
   [:building_name {:optional true} string?]
   [:street string?]
   [:sub_street {:optional true} string?]
   [:town string?]
   [:state {:optional true} string?]
   [:postcode string?]
   [:country string?]
   [:start_date {:optional true} string?]])

(def CreateApplicantRequest
  [:map
   {:json-schema/example examples/CreateApplicantRequest}
   [:first_name string?]
   [:last_name string?]
   [:dob {:optional true} [:maybe string?]]
   [:email {:optional true} [:maybe string?]]
   [:address {:optional true} [:ref "Address"]]])

(def Applicant
  [:map
   {:json-schema/example examples/Applicant}
   [:id string?]
   [:created_at string?]
   [:first_name string?]
   [:last_name string?]
   [:dob {:optional true} [:maybe string?]]
   [:email {:optional true} [:maybe string?]]
   [:address {:optional true} [:ref "Address"]]])

(def registry
  (schema/components-registry [#'Address #'CreateApplicantRequest #'Applicant]))
