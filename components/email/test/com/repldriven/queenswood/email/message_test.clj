(ns com.repldriven.queenswood.email.message-test
  (:require
    [com.repldriven.queenswood.email.message :as SUT]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.smtp.interface :as smtp]

    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]))

(def ^:private link
  (SUT/invitation-link "http://localhost:5173/" "inv.test" "tok_en-123"))

(def ^:private context
  {:invitation {:invitation-id "inv.test"
                :email "Ada@Example.com"
                :role :role-admin
                :expires-at 1790000000000}
   :bank-name "Acme & Sons"
   :inviter-name "Grace Hopper"
   :link link})

(deftest invitation-link-test
  (testing "the id and token sit in the fragment, under one slash"
    (is (= "http://localhost:5173/#/invitations/inv.test?token=tok_en-123"
           link))))

(deftest invitation-message-test
  (let [message (SUT/invitation-message context)]
    (testing "the message goes to the address as typed"
      (is (= "Ada@Example.com" (:to message))))
    (testing "the subject names the inviter and the organisation"
      (is (= "Grace Hopper invited you to Acme & Sons on Queenswood"
             (:subject message))))
    (testing "the text names the role, the link and when it expires"
      (is (str/includes? (:text message) "as admin"))
      (is (str/includes? (:text message) link))
      (is (str/includes? (:text message) "21 September 2026 at 14:13 UTC")))
    (testing "the HTML escapes what it is given"
      (is (str/includes? (:html message) "Acme &amp; Sons"))
      (is (str/includes? (:html message) (str "href=\"" link "\""))))
    (testing "the message renders with a from"
      (let [rendered (smtp/render (assoc message :from "noreply@example.test"))]
        (is (not (error/anomaly? rendered)))
        (is (str/includes? rendered "multipart/alternative")))))
  (testing "an operator's invitation is from the platform"
    (is (= "Queenswood invited you to Acme & Sons on Queenswood"
           (:subject (SUT/invitation-message (dissoc context
                                              :inviter-name)))))))
