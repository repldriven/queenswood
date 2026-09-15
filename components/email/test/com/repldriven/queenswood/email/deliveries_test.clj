(ns com.repldriven.queenswood.email.deliveries-test
  "The event processor and the claim against a real record store: one
  delivery for a repeated changelog event id, and a claim that takes a
  due delivery once, leaves a live lease alone and takes a passed one
  again. Envelopes are built here from the invitation event's own
  schema and handed to the processor directly."
  (:require
    [com.repldriven.queenswood.email.test-system]

    [com.repldriven.queenswood.email.store :as SUT]

    [com.repldriven.queenswood.email.domain :as domain]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.processor.interface :as processor]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.test :refer [deftest is testing]]))

(def ^:private config-file "classpath:email/application-test.yml")

(defn- fdb-config
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])})

(defn- envelope
  [sys event-name event-id data]
  (let [schemas (system/instance sys [:avro :serde])]
    {:id event-id
     :event event-name
     :payload (avro/serialize (get schemas "invitation-changed") data)
     :causation-id (:invitation-id data)
     :correlation-id nil}))

(defn- consume
  [sys message]
  (processor/process (system/instance sys [:email :event-processor-impl])
                     message))

(deftest one-delivery-per-changelog-event-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         event-id (str (utility/uuidv7))
         data {:bank-id "bnk.email"
               :invitation-id (utility/generate-id "inv")
               :expires-at 1790000000000}
         message (envelope sys "invitation-created" event-id data)]
     (nom-test> [_ (consume sys message)
                 _ (consume sys message)
                 written (SUT/find-delivery-by-changelog-event-id config
                                                                  event-id)
                 _ (testing "a created invitation writes a pending delivery"
                     (is (= {:bank-id "bnk.email"
                             :kind :email-kind-invitation
                             :invitation-id (:invitation-id data)
                             :expires-at 1790000000000
                             :changelog-event-id event-id
                             :status :email-delivery-status-pending}
                            (select-keys written
                                         [:bank-id :kind :invitation-id
                                          :expires-at
                                          :changelog-event-id :status]))))
                 claimed (SUT/claim-due-deliveries config
                                                   {:now (utility/now)
                                                    :claimed-by "runner-a"
                                                    :lease-ms
                                                    domain/claim-lease-ms
                                                    :limit 16})
                 _ (testing "and a redelivered event writes no second"
                     (is (= [(:delivery-id written)]
                            (mapv :delivery-id claimed))))
                 other-id (str (utility/uuidv7))
                 _ (consume sys
                            (envelope sys "invitation-withdrawn" other-id data))
                 ignored (SUT/find-delivery-by-changelog-event-id config
                                                                  other-id)
                 _ (testing "an event with no email writes nothing"
                     (is (nil? ignored)))]))))

(deftest claim-respects-the-lease-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         now (utility/now)
         delivery (domain/new-invitation-delivery {:bank-id "bnk.email"
                                                   :invitation-id
                                                   (utility/generate-id "inv")
                                                   :expires-at 1790000000000}
                                                  (str (utility/uuidv7))
                                                  now)
         opts
         {:claimed-by "runner-a" :lease-ms domain/claim-lease-ms :limit 16}]
     (nom-test> [_ (SUT/save-delivery
                    config
                    (assoc delivery :next-attempt-at (+ now 60000)))
                 early (SUT/claim-due-deliveries config (assoc opts :now now))
                 _ (testing
                     "a delivery whose next attempt has not come is not claimed"
                     (is (empty? early)))
                 _ (SUT/save-delivery config delivery)
                 first-claim (SUT/claim-due-deliveries config
                                                       (assoc opts :now now))
                 _ (testing "a due delivery is claimed in flight under a lease"
                     (is (= 1 (count first-claim)))
                     (is (= {:status :email-delivery-status-in-flight
                             :claimed-by "runner-a"
                             :claim-lease-expires-at (+ now
                                                        domain/claim-lease-ms)}
                            (select-keys (first first-claim)
                                         [:status :claimed-by
                                          :claim-lease-expires-at]))))
                 live (SUT/claim-due-deliveries
                       config
                       (assoc opts :now (inc now) :claimed-by "runner-b"))
                 _ (testing
                     "a second runner takes nothing while the lease holds"
                     (is (empty? live)))
                 passed (SUT/claim-due-deliveries
                         config
                         (assoc opts
                                :now (+ now domain/claim-lease-ms)
                                :claimed-by "runner-b"))
                 _ (testing "and takes the delivery once the lease has passed"
                     (is (= ["runner-b"] (mapv :claimed-by passed))))]))))
