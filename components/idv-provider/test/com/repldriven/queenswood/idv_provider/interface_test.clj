(ns com.repldriven.queenswood.idv-provider.interface-test
  (:require
    [com.repldriven.queenswood.idv-provider.interface :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(def ^:private offered
  (SUT/providers {:default "zyphe"
                  :providers {:zyphe {:declaration {:screens ["pep"]}
                                      :command-channel :zyphe-idv-command}
                              :onfido {:declaration {:screens []}
                                       :command-channel :onfido-idv-command}}}))

(deftest providers-test
  (testing "each entry carries its key"
    (is (= "onfido" (get-in offered [:providers :onfido :provider]))))
  (testing "a default naming no provider is refused"
    (let [res (SUT/providers {:default "onfido"
                              :providers {:zyphe {:declaration {}}}})]
      (is (= :idv-provider/unknown-default (error/kind res)))
      (is (= ["zyphe"] (:offered (error/payload res)))))))

(deftest default-test
  (is (= :zyphe-idv-command (:command-channel (SUT/default offered)))))

(deftest entries-test
  (is (= #{"zyphe" "onfido"} (set (map :provider (SUT/entries offered))))))

(deftest for-bank-test
  (testing "a bank recording a provider takes its entry"
    (is (= "onfido"
           (:provider (SUT/for-bank offered
                                    {:providers
                                     [{:kind "payment" :provider "form3"}
                                      {:kind "idv" :provider "onfido"}]})))))
  (testing "a bank recording none takes the default's"
    (is (= "zyphe" (:provider (SUT/for-bank offered {})))))
  (testing "a provider not offered is refused"
    (let [res (SUT/for-bank offered
                            {:providers [{:kind "idv" :provider "other"}]})]
      (is (= :idv-provider/unknown (error/kind res)))
      (is (= "other" (:provider (error/payload res)))))))
