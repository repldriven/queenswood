(ns com.repldriven.queenswood.cash-account-migration.domain-test
  "Pure-function tests for what authoring a migration checks: the one
  compatibility rule, the target's status, and the ordering of notice
  against the move; and the preview a running one refuses. No FDB."
  (:require
    [com.repldriven.queenswood.cash-account-migration.domain :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(defn- version
  [product-id version-id status product-type]
  {:product-id product-id
   :version-id version-id
   :status status
   :product-type product-type})

(def ^:private savings-v1
  (version "prd.super" "ver.1"
           :version-status-published :account-product-type-sub-ledger-savings))

(def ^:private savings-v2
  (version "prd.mega" "ver.2"
           :version-status-published :account-product-type-sub-ledger-savings))

(def ^:private data
  {:bank-id "org.1"
   :name "Super-saver to mega-saver"
   :source-product-id "prd.super"
   :target-product-id "prd.mega"
   :target-version-id "ver.2"})

(deftest new-migration-test
  (testing "a migration between two savings products is authored in draft"
    ;; Source and target are different products — splitting a product
    ;; line is the case this exists for, not only evolving one.
    (let [m (SUT/new-migration data savings-v1 savings-v2)]
      (is (= :cash-account-migration-status-draft (:status m)))
      (is (= "org.1" (:bank-id m)))
      (is (= "prd.super" (:source-product-id m)))
      (is (= "prd.mega" (:target-product-id m)))
      (is (= "ver.2" (:target-version-id m)))
      (is (string? (:migration-id m)))
      (is (number? (:created-at m)))))
  (testing "nothing about it is approved or dated by authoring it"
    (let [m (SUT/new-migration data savings-v1 savings-v2)]
      (is (not (contains? m :approved-at)))
      (is (not (contains? m :notified-on)))
      (is (not (contains? m :due-on)))))
  (testing "an empty version narrowing is left off rather than stored empty"
    (let [m (SUT/new-migration (assoc data :source-version-ids [])
                               savings-v1
                               savings-v2)]
      (is (not (contains? m :source-version-ids)))))
  (testing "naming versions narrows the cohort"
    (let [m (SUT/new-migration (assoc data :source-version-ids ["ver.1"])
                               savings-v1
                               savings-v2)]
      (is (= ["ver.1"] (:source-version-ids m)))))
  (testing "the notice and move dates are carried when given"
    (let [m (SUT/new-migration
             (assoc data :notified-on 20260601 :due-on 20260801)
             savings-v1
             savings-v2)]
      (is (= 20260601 (:notified-on m)))
      (is (= 20260801 (:due-on m))))))

(deftest compatibility-test
  (testing "product type is the one thing that must match"
    (let [current (version "prd.cur" "ver.9"
                           :version-status-published
                           :account-product-type-sub-ledger-current)
          result (SUT/new-migration data savings-v1 current)]
      (is (error/rejection? result))
      (is (= :cash-account-migration/product-type-mismatch
             (error/kind result)))))
  (testing
    "everything else may differ — that is an account's problem, not
            the migration's"
    ;; A target allowing only EUR is a perfectly valid migration; the
    ;; GBP accounts within it are reported ineligible when it runs.
    (let [eur (assoc savings-v2 :currency "EUR")]
      (is (not (error/anomaly? (SUT/new-migration data savings-v1 eur)))))))

(deftest target-status-test
  (testing "a draft target is refused — the terms are still being written"
    (let [draft (assoc savings-v2 :status :version-status-draft)
          result (SUT/new-migration data savings-v1 draft)]
      (is (error/rejection? result))
      (is (= :cash-account-migration/target-not-published
             (error/kind result)))))
  (testing "a discarded target is refused — the terms were abandoned"
    (let [discarded (assoc savings-v2 :status :version-status-discarded)
          result (SUT/new-migration data savings-v1 discarded)]
      (is (error/rejection? result))
      (is (= :cash-account-migration/target-not-published
             (error/kind result))))))

(deftest degenerate-migration-test
  (testing "migrating a version onto itself moves nobody and is refused"
    (let [result (SUT/new-migration (assoc data :source-version-ids ["ver.2"])
                                    savings-v1
                                    savings-v2)]
      (is (error/rejection? result))
      (is (= :cash-account-migration/target-is-source (error/kind result)))))
  (testing
    "a source narrowed to several versions including the target is not
            degenerate — the others still move"
    (is (not (error/anomaly? (SUT/new-migration
                              (assoc data :source-version-ids ["ver.1" "ver.2"])
                              savings-v1
                              savings-v2))))))

(deftest notice-test
  (testing "notice given after the move is not notice"
    (let [result (SUT/new-migration
                  (assoc data :notified-on 20260801 :due-on 20260601)
                  savings-v1
                  savings-v2)]
      (is (error/rejection? result))
      (is (= :cash-account-migration/notice-after-due (error/kind result)))))
  (testing
    "same-day notice is allowed here — how long is long enough is not
            settled in the domain"
    (is (not (error/anomaly? (SUT/new-migration (assoc data
                                                       :notified-on 20260601
                                                       :due-on 20260601)
                                                savings-v1
                                                savings-v2)))))
  (testing "either date alone is fine — a draft need not have both yet"
    (is (not (error/anomaly? (SUT/new-migration (assoc data :due-on 20260601)
                                                savings-v1
                                                savings-v2))))
    (is (not (error/anomaly? (SUT/new-migration
                              (assoc data :notified-on 20260601)
                              savings-v1
                              savings-v2))))))

(def ^:private target (assoc savings-v2 :currency "GBP"))

(defn- account
  [opts]
  (merge {:account-id "acc.1"
          :product-id "prd.super"
          :version-id "ver.1"
          :currency "GBP"
          :status :cash-account-status-opened}
         opts))

(deftest in-cohort?-test
  (let [migration {:source-product-id "prd.super"}]
    (testing "an account of the source product is in the cohort"
      (is (true? (SUT/in-cohort? migration (account {})))))
    (testing
      "an account of another product is not — the scan sees the whole
              bank, and only this product's accounts are being moved"
      (is (not (SUT/in-cohort? migration (account {:product-id "prd.other"})))))
    (testing "naming no versions takes every version of the product"
      (is (true? (SUT/in-cohort? (assoc migration :source-version-ids [])
                                 (account {:version-id "ver.7"})))))
    (testing "naming versions narrows to accounts pinned to those"
      (let [narrowed (assoc migration :source-version-ids ["ver.1" "ver.2"])]
        (is (true? (SUT/in-cohort? narrowed (account {:version-id "ver.2"}))))
        (is (not (SUT/in-cohort? narrowed (account {:version-id "ver.9"}))))))))

(deftest verdict-test
  (testing "an open account on an allowed currency would move"
    (is (= :cash-account-migration-outcome-eligible
           (:outcome (SUT/verdict target (account {}))))))
  (testing "an account already on the target is reported, not moved again"
    ;; This is what a re-run sees for everything an earlier run moved,
    ;; and is how the pass stays idempotent without tracking its work.
    (let [v (SUT/verdict target (account {:version-id "ver.2"}))]
      (is (= :cash-account-migration-outcome-ineligible (:outcome v)))
      (is (= :cash-account-migration-ineligibility-already-on-target
             (:ineligibility v)))))
  (testing "a closed account's terms are not in play"
    (let [v (SUT/verdict target
                         (account {:status :cash-account-status-closed}))]
      (is (= :cash-account-migration-ineligibility-account-not-open
             (:ineligibility v)))))
  (testing "a currency the target does not allow leaves that account behind"
    ;; The migration itself stays valid — this is the case the whole
    ;; per-account eligibility design exists for.
    (let [v (SUT/verdict target (account {:currency "EUR"}))]
      (is (= :cash-account-migration-outcome-ineligible (:outcome v)))
      (is (= :cash-account-migration-ineligibility-currency-not-allowed
             (:ineligibility v)))))
  (testing "an eligible verdict carries no reason — there is nothing to explain"
    (is (not (contains? (SUT/verdict target (account {})) :ineligibility)))))

(deftest run-lifecycle-test
  (let [migration {:bank-id "org.1" :migration-id "mig.1"}
        run (SUT/new-run migration 20260801 true)]
    (testing "a run opens as running, and a preview says so on the record"
      (is (= :cash-account-migration-run-status-running (:status run)))
      (is (true? (:dry-run run)))
      (is (= 20260801 (:business-day run)))
      (is (number? (:started-at run))))
    (testing "closing carries the counts a reader wants before any verdict"
      (let [closed (SUT/close-run
                    run
                    {:seen 9588 :moved 9176 :ineligible 412 :failed 0})]
        (is (= :cash-account-migration-run-status-completed (:status closed)))
        (is (= 9588 (:accounts-seen closed)))
        (is (= 412 (:accounts-ineligible closed)))
        (is (number? (:finished-at closed)))))
    (testing "a run that could not finish is failed, not completed"
      (let [failed (SUT/fail-run run (error/reject :some/anomaly {}))]
        (is (= :cash-account-migration-run-status-failed (:status failed)))
        (is (string? (:failure-reason failed)))))))

(deftest preview-running-test
  (let [now 1000000000000
        minute (* 60 1000)
        run (fn [dry-run status started-at]
              {:run-id "run.1"
               :migration-id "mig.1"
               :dry-run dry-run
               :status status
               :started-at started-at})
        running :cash-account-migration-run-status-running]
    (testing "nil when no preview is running"
      (is (nil? (SUT/check-no-preview-running [] now)))
      (is (nil? (SUT/check-no-preview-running
                 [(run true :cash-account-migration-run-status-completed now)
                  (run true :cash-account-migration-run-status-failed now)]
                 now))))
    (testing "a running commit does not hold a preview back"
      (is (nil? (SUT/check-no-preview-running [(run false running now)] now))))
    (testing "rejects while a preview is running, naming it"
      (let [r (SUT/check-no-preview-running [(run true running (- now minute))]
                                            now)]
        (is (error/rejection? r))
        (is (= :cash-account-migration/preview-running (error/kind r)))
        (is (= "run.1" (:run-id (error/payload r))))))
    (testing "a preview running past the abandon window no longer counts"
      (is (nil? (SUT/check-no-preview-running
                 [(run true running (- now (* 16 minute)))]
                 now))))))

(deftest account-verdict-test
  (let [run {:bank-id "org.1" :run-id "run.1" :migration-id "mig.1"}]
    (testing "a verdict records where the account was"
      (let [v (SUT/account-verdict run
                                   (account {})
                                   {:outcome
                                    :cash-account-migration-outcome-eligible})]
        (is (= "acc.1" (:account-id v)))
        (is (= "ver.1" (:source-version-id v)))))
    (testing "an ineligible verdict carries its reason"
      (let [v (SUT/account-verdict
               run
               (account {})
               {:outcome :cash-account-migration-outcome-ineligible
                :ineligibility
                :cash-account-migration-ineligibility-currency-not-allowed})]
        (is (= :cash-account-migration-ineligibility-currency-not-allowed
               (:ineligibility v)))))))

(def ^:private approvable
  {:bank-id "org.1"
   :migration-id "mig.1"
   :status :cash-account-migration-status-draft
   :notified-on 20260601
   :due-on 20260801})

(def ^:private operator
  {:kind :actor-kind-operator :principal-id "queenswood-admin"})

(deftest approve-test
  (testing "a draft with a notice window is approved, by whom recorded"
    (let [approved (SUT/approve-migration approvable operator)]
      (is (= :cash-account-migration-status-approved (:status approved)))
      (is (number? (:approved-at approved)))
      (is (= operator (:approved-by approved)))))
  (testing "approving without a notice window is refused"
    ;; Approving commits to moving customers' accounts. A draft may sit
    ;; without dates while an operator decides; approving one may not.
    (doseq [missing [{:notified-on nil} {:due-on nil}
                     {:notified-on nil :due-on nil}]]
      (let [result (SUT/approve-migration (merge approvable missing) operator)]
        (is (error/rejection? result))
        (is (= :cash-account-migration/notice-required (error/kind result))))))
  (testing "only a draft may be approved"
    (doseq [status [:cash-account-migration-status-approved
                    :cash-account-migration-status-completed
                    :cash-account-migration-status-cancelled]]
      (let [result (SUT/approve-migration (assoc approvable :status status)
                                          operator)]
        (is (error/rejection? result))
        (is (= :cash-account-migration/invalid-status (error/kind result)))))))

(deftest cancel-test
  (testing "a draft and an approved migration may both be cancelled"
    (doseq [status [:cash-account-migration-status-draft
                    :cash-account-migration-status-approved]]
      (let [cancelled (SUT/cancel-migration (assoc approvable :status status)
                                            operator)]
        (is (= :cash-account-migration-status-cancelled (:status cancelled)))
        (is (number? (:cancelled-at cancelled)))
        (is (= operator (:cancelled-by cancelled))))))
  (testing "a completed migration cannot be cancelled"
    ;; Its accounts have moved; saying otherwise would misdescribe them.
    (let [result
          (SUT/cancel-migration
           (assoc approvable :status :cash-account-migration-status-completed)
           operator)]
      (is (error/rejection? result))
      (is (= :cash-account-migration/invalid-status (error/kind result))))))

(deftest commit-guard-test
  (testing "only an approved migration may be committed"
    (doseq [status [:cash-account-migration-status-draft
                    :cash-account-migration-status-completed
                    :cash-account-migration-status-cancelled]]
      (let [result (SUT/ensure-committable (assoc approvable :status status))]
        (is (error/rejection? result))
        (is (= :cash-account-migration/invalid-status (error/kind result))))))
  (testing "an approved migration passes the guard and completes"
    (let [approved
          (assoc approvable :status :cash-account-migration-status-approved)]
      (is (nil? (SUT/ensure-committable approved)))
      (let [completed (SUT/complete-migration approved)]
        (is (= :cash-account-migration-status-completed (:status completed)))
        (is (number? (:completed-at completed)))))))

(deftest due?-test
  (let [approved
        (assoc approvable :status :cash-account-migration-status-approved)
        in-force {:effective-from 20260701 :effective-to nil}]
    (testing "approved, due, and the target in force"
      (is (true? (boolean (SUT/due? approved in-force 20260801)))))
    (testing "due later is not due today"
      (is (false? (boolean (SUT/due? approved in-force 20260731)))))
    (testing "a draft is never due, whatever its dates say"
      (is (false? (boolean (SUT/due? approvable in-force 20260801)))))
    (testing "a migration with no due date is not due"
      (is (false? (boolean
                   (SUT/due? (dissoc approved :due-on) in-force 20260801)))))
    (testing "a target whose window has not opened is not due yet"
      ;; Not-due is not dead: push the target's start out and the
      ;; migration simply becomes due later, untouched.
      (is (false? (boolean
                   (SUT/due? approved {:effective-from 20260901} 20260801)))))
    (testing "a target whose window has closed is not due"
      (is (false? (boolean (SUT/due? approved
                                     {:effective-from 20260101
                                      :effective-to 20260701}
                                     20260801)))))))

(deftest commit-verdict-test
  (let [run {:bank-id "org.1" :run-id "run.1" :migration-id "mig.1"}]
    (testing "an account that moved records the version it left"
      ;; migrated is a fact where eligible was a forecast, and the two
      ;; sit in the same table.
      (let [v (SUT/account-verdict run (account {}) SUT/moved-verdict)]
        (is (= :cash-account-migration-outcome-migrated (:outcome v)))
        (is (= "ver.1" (:source-version-id v)))))
    (testing "an account that could not be moved records why"
      (let [v (SUT/account-verdict run
                                   (account {})
                                   (SUT/failed-verdict
                                    (error/reject :cash-account/invalid-status
                                                  {})))]
        (is (= :cash-account-migration-outcome-failed (:outcome v)))
        (is (string? (:failure-reason v)))))))

(deftest name-test
  (testing "a migration carries the name an operator gave it"
    (is (= "Super-saver to mega-saver"
           (:name (SUT/new-migration data savings-v1 savings-v2)))))
  (testing "a blank name is refused here, not left to the record"
    ;; protojure drops an empty string from the wire and the proto2
    ;; required field then fails the Java parse — so without this the
    ;; failure would surface as a serialisation error, nowhere near the
    ;; thing that caused it.
    (doseq [blank [nil "" "   "]]
      (let [result
            (SUT/new-migration (assoc data :name blank) savings-v1 savings-v2)]
        (is (error/rejection? result))
        (is (= :cash-account-migration/name-required (error/kind result)))))))
