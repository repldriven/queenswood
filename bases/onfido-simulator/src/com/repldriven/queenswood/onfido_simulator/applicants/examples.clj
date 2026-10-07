(ns com.repldriven.queenswood.onfido-simulator.applicants.examples)

(def Address
  {:building_number "155"
   :street "Country Lane"
   :town "Cottington"
   :postcode "QC1 4XY"
   :country "GBR"})

(def CreateApplicantRequest
  {:first_name "Arthur Phillip"
   :last_name "Dent"
   :dob "1950-07-27"
   :address Address})

(def Applicant
  {:id "9b6e8d8f-5b9a-4f4f-9f4d-1234567890ab"
   :created_at "2026-05-02T12:00:00Z"
   :first_name "Arthur Phillip"
   :last_name "Dent"
   :dob "1950-07-27"
   :address Address})
