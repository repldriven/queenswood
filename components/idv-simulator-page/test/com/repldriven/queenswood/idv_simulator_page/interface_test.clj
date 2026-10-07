(ns com.repldriven.queenswood.idv-simulator-page.interface-test
  "The sandbox values a person enters on the page, each settling the run
  as its outcome, and the page carrying a step for each."
  (:require
    [com.repldriven.queenswood.idv-simulator-page.interface :as SUT]

    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]))

(def ^:private person
  {:givenNames "Arthur"
   :familyName "Dent"
   :dateOfBirth "1952-03-11"
   :documentNumber "123456789"
   :postcode "CT12 4XY"
   :lookedAway false})

(defn- outcome [changes] (:outcome (SUT/decision (merge person changes))))

(deftest decision-test
  (testing "a person entering nothing from the sandbox passes"
    (is (= "match" (outcome {}))))
  (testing "a document number's prefix settles the document or screening"
    (is (= "document-review" (outcome {:documentNumber "REVIEW0001"})))
    (is (= "document-failed" (outcome {:documentNumber "forged 0001"})))
    (is (= "sanctions-hit" (outcome {:documentNumber "HIT0001"})))
    (is (= "sanctions-possible-match"
           (outcome {:documentNumber "POSSIBLE0001"})))
    (is (= "pep" (outcome {:documentNumber "PEP0001"}))))
  (testing "looking away fails liveness"
    (is (= "liveness-failed" (outcome {:lookedAway true}))))
  (testing "the failing postcode fails the address, however it is spaced"
    (is (= "address-failed" (outcome {:postcode "xx00xx"}))))
  (testing "leaving walks away, whatever else was entered"
    (is (= "walk-away" (outcome {:left true :documentNumber "HIT0001"}))))
  (testing "an outcome a test posts is taken as it is"
    (is (= "pep" (outcome {:outcome "pep"}))))
  (testing "the document carries the names and date of birth entered"
    (is (= {:givenNames "Arthur" :familyName "Dent" :dateOfBirth "1952-03-11"}
           (:document (SUT/decision person))))))

(deftest form-test
  (let [html (SUT/form "/flow/[^/]*$" "/decision" "https://tenant/back")]
    (testing "a step for each part of the provider's flow"
      (is (every? (fn [step] (str/includes? html (str "<li>" step "</li>")))
                  ["Details" "Document" "Selfie" "Address"])))
    (testing "leaving is offered, and the sandbox values listed"
      (is (str/includes? html "id=\"leave\""))
      (is (str/includes? html "XX0 0XX")))))
