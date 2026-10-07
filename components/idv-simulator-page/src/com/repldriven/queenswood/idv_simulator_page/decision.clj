(ns com.repldriven.queenswood.idv-simulator-page.decision
  (:require
    [clojure.string :as str]))

(def outcomes
  ["match" "other-document" "document-review" "document-failed"
   "liveness-failed" "address-failed" "sanctions-hit"
   "sanctions-possible-match" "pep" "walk-away"])

(def failing-postcode "XX0 0XX")

(def document-prefixes
  "A document number's prefix, and the outcome it produces, in the order
  they are tried."
  [["REVIEW" "document-review"]
   ["FORGED" "document-failed"]
   ["HIT" "sanctions-hit"]
   ["POSSIBLE" "sanctions-possible-match"]
   ["PEP" "pep"]])

(defn- normalised
  [s]
  (str/replace (str/upper-case (str s)) #"\s" ""))

(defn- prefixed
  [document-number prefixes]
  (some (fn [[prefix outcome]]
          (when (str/starts-with? (normalised document-number) prefix)
            outcome))
        prefixes))

(defn- outcome
  [{:keys [left lookedAway documentNumber postcode]}]
  (let [document (prefixed documentNumber (take 2 document-prefixes))
        screening (prefixed documentNumber (drop 2 document-prefixes))]
    (cond
     left
     "walk-away"

     document
     document

     lookedAway
     "liveness-failed"

     (= (normalised failing-postcode) (normalised postcode))
     "address-failed"

     screening
     screening

     :else
     "match")))

(defn decision
  [submission]
  {:outcome (or (:outcome submission) (outcome submission))
   :document (select-keys submission [:givenNames :familyName :dateOfBirth])})

(def Submission
  [:map
   [:outcome {:optional true} [:maybe (into [:enum] outcomes)]]
   [:givenNames {:optional true} [:maybe string?]]
   [:familyName {:optional true} [:maybe string?]]
   [:dateOfBirth {:optional true} [:maybe string?]]
   [:nationality {:optional true} [:maybe string?]]
   [:documentType {:optional true} [:maybe string?]]
   [:issuingCountry {:optional true} [:maybe string?]]
   [:documentNumber {:optional true} [:maybe string?]]
   [:lookedAway {:optional true} [:maybe boolean?]]
   [:addressLine {:optional true} [:maybe string?]]
   [:town {:optional true} [:maybe string?]]
   [:postcode {:optional true} [:maybe string?]]
   [:country {:optional true} [:maybe string?]]
   [:proofType {:optional true} [:maybe string?]]
   [:left {:optional true} [:maybe boolean?]]])
