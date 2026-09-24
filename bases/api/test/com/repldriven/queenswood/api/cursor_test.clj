(ns com.repldriven.queenswood.api.cursor-test
  (:require
    [com.repldriven.queenswood.api.cursor :as SUT]

    [clojure.test :refer [deftest is testing]]))

(deftest a-cursor-round-trips-test
  (testing "a scalar id"
    (is (= "pty.01kprbmgcj35ptc8npmybhh4s9"
           (SUT/decode (SUT/encode "pty.01kprbmgcj35ptc8npmybhh4s9")))))
  (testing "a compound key comes back as its parts"
    (is (= ["txn.01kprbmgcj35ptc8npmybhh4sb" "leg.01kprbmgcj35ptc8npmybhh4sc"]
           (SUT/decode (SUT/encode ["txn.01kprbmgcj35ptc8npmybhh4sb"
                                    "leg.01kprbmgcj35ptc8npmybhh4sc"])))))
  (testing "a cursor that is not one decodes to nil"
    (is (nil? (SUT/decode "not-a-cursor")))
    (is (nil? (SUT/decode nil)))))

(deftest page-links-keep-the-request-query-test
  (testing "every parameter but page is carried"
    (is (=
         "/v1/cash-accounts?embed%5Bbalances%5D=true"
         (SUT/request-path
          {:uri "/v1/cash-accounts"
           :query-string
           "embed%5Bbalances%5D=true&page%5Bafter%5D=abc&page%5Bsize%5D=2"}))))
  (testing "a request with no other parameter keeps its bare path"
    (is (= "/v1/parties"
           (SUT/request-path {:uri "/v1/parties"
                              :query-string "page[after]=abc"})))
    (is (= "/v1/parties" (SUT/request-path {:uri "/v1/parties"})))))

(deftest a-page-body-links-only-the-sides-with-rows-test
  (let [page {:size 2}]
    (testing "a first page links only onward"
      (is (= {:items [:a :b]
              :links {:next (str "/v1/parties?page[after]="
                                 (SUT/encode "b")
                                 "&page[size]=2")}}
             (SUT/page-body "/v1/parties" page [:a :b] {:after "b"}))))
    (testing "a query on the path is followed with an ampersand"
      (is (= (str "/v1/jobs?status=held&page[before]="
                  (SUT/encode "a")
                  "&page[size]=2")
             (get-in
              (SUT/page-body "/v1/jobs?status=held" page [:a] {:before "a"})
              [:links :prev]))))
    (testing "a page alone carries no links"
      (is (= {:items [:a]} (SUT/page-body "/v1/parties" page [:a] {}))))))
