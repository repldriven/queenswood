(ns ^:eftest/synchronized com.repldriven.queenswood.api.onboarding.handlers-test
  "A person who already holds a membership creates another bank through
  onboarding: the handler answers 201 with the second bank, and the
  command it sends names the person as the owner and as the actor
  (AC-11's base half).

  The company lookup, the command send and the bank read are stood in
  for with `with-redefs`, and the identity provider with a `reify`, so
  no system is booted. `bank-with-secret` itself is left real: another
  namespace calls it in parallel, and a redefinition would reach that
  namespace too."
  (:require
    [com.repldriven.queenswood.api.onboarding.handlers :as SUT]

    [com.repldriven.queenswood.api.bank.commands :as bank-commands]
    [com.repldriven.queenswood.api.companies.queries :as companies]

    [com.repldriven.queenswood.bank-query.interface :as banks]

    [com.repldriven.mono.identity-provider.protocol :as protocol]

    [clojure.test :refer [deftest is testing]]))

(def ^:private user-id "usr.01kprbmgcj35ptc8npmybhh4s7")

(def ^:private first-bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7")

(def ^:private second-bank-id "bnk.01kprbqv3z6e0r9d4f1m8nk2yh")

(def ^:private request
  {:auth {:principal-type :user
          :principal-id user-id
          :user {:user-id user-id :email "ada@example.com"}
          :memberships [{:membership-id "mem.01kprbpdwa9q5n2t7vwsx84a3m"
                         :user-id user-id
                         :bank-id first-bank-id
                         :role :role-owner
                         :status :membership-status-active}]}
   :parameters {:body {:company-number "SC998137" :bank-name "Second Bank"}}
   :identity-provider
   #_{:clj-kondo/ignore [:missing-protocol-method]}
   (reify
    protocol/IdentityProvider
      (-rotate-secret [_ id] {:client-id id :client-secret "secret"}))})

(deftest a-member-onboards-a-second-bank-test
  (let [sent (atom nil)
        membership {:membership-id "mem.01kprbpdwa9q5n2t7vwsx84a3n"
                    :user-id user-id
                    :bank-id second-bank-id
                    :role :role-owner}]
    (with-redefs [companies/lookup (fn [_ company-number]
                                     {:status 200
                                      :body {:company-number company-number
                                             :company-name "SECOND LTD"
                                             :registry-id
                                             "gb-companies-house"}})
                  bank-commands/send-create-bank
                  (fn [_ data]
                    (reset! sent data)
                    {:status 200
                     :body {:bank-id second-bank-id :membership membership}})
                  banks/get-bank-view (fn [_ bank-id] {:bank-id bank-id})]
      (let [response (SUT/onboard request)]
        (testing "answers 201 with the second bank rather than 409"
          (is (= 201 (:status response)))
          (is (= {:bank-id second-bank-id :client-secret "secret"}
                 (get-in response [:body :bank])))
          (is (= membership (get-in response [:body :membership]))))
        (testing "sends the person as the owner and as the actor"
          (is (= {:user-id user-id :role :role-owner} (:membership @sent)))
          (is (= {:kind :actor-kind-member :principal-id user-id}
                 (:actor @sent))))))))
