(ns com.repldriven.queenswood.email.deliveries-test
  "The event processor and the claim against a real record store: one
  delivery for a repeated changelog event id, a claim that takes a due
  delivery once, leaves a live claim alone and takes a lapsed one again,
  and a later delivery about the same invitation found as newer. Envelopes are built here from the invitation event's own
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

(def ^:private lease-ms 60000)

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
  ;; this brick's own event handler, handed an envelope, no bus
  ;; nosemgrep: brick-test-drives-pipeline
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
                 written (SUT/find-delivery-by-idempotency-key config event-id)
                 _ (testing "a created invitation writes a pending delivery"
                     (is (= {:bank-id "bnk.email"
                             :kind :email-kind-invitation
                             :kind-id (:invitation-id data)
                             :idempotency-key event-id
                             :status :email-delivery-status-pending}
                            (select-keys written
                                         [:bank-id :kind :kind-id
                                          :idempotency-key :status]))))
                 claimed (SUT/claim-due-deliveries
                          config
                          {:now (utility/now) :lease-ms lease-ms :limit 16})
                 _ (testing "and a redelivered event writes no second"
                     (is (= [(:delivery-id written)]
                            (mapv :delivery-id claimed))))
                 other-id (str (utility/uuidv7))
                 _ (consume sys
                            (envelope sys "invitation-withdrawn" other-id data))
                 ignored (SUT/find-delivery-by-idempotency-key config other-id)
                 _ (testing "an event with no email writes nothing"
                     (is (nil? ignored)))]))))

(deftest claim-lapses-at-the-next-attempt-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         now (utility/now)
         delivery (domain/new-invitation-delivery {:bank-id "bnk.email"
                                                   :invitation-id
                                                   (utility/generate-id "inv")}
                                                  (str (utility/uuidv7))
                                                  now)
         opts {:lease-ms lease-ms :limit 16}]
     (nom-test>
       [_ (SUT/save-delivery config
                             (assoc delivery :next-attempt-at (+ now 60000)))
        early (SUT/claim-due-deliveries config (assoc opts :now now))
        _ (testing "a delivery whose next attempt has not come is not claimed"
            (is (empty? early)))
        _ (SUT/save-delivery config delivery)
        first-claim (SUT/claim-due-deliveries config (assoc opts :now now))
        _
        (testing
          "a due delivery is claimed in flight until the claim
                     lapses"
          (is (= 1 (count first-claim)))
          (is (= {:status :email-delivery-status-in-flight
                  :next-attempt-at (+ now lease-ms)}
                 (select-keys (first first-claim) [:status :next-attempt-at]))))
        live (SUT/claim-due-deliveries config (assoc opts :now (inc now)))
        _ (testing "a second runner takes nothing while the claim holds"
            (is (empty? live)))
        lapsed (SUT/claim-due-deliveries config
                                         (assoc opts :now (+ now lease-ms)))
        _ (testing "and takes the delivery once the claim has lapsed"
            (is (= [(:delivery-id delivery)] (mapv :delivery-id lapsed))))]))))

(deftest newer-delivery-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         now (utility/now)
         invitation {:bank-id "bnk.email"
                     :invitation-id (utility/generate-id "inv")}
         earlier
         (domain/new-invitation-delivery invitation (str (utility/uuidv7)) now)
         later (domain/new-invitation-delivery invitation
                                               (str (utility/uuidv7))
                                               (inc now))]
     (nom-test> [_ (SUT/save-delivery config earlier)
                 alone (SUT/newer-delivery? config earlier)
                 _ (testing "a delivery with none after it has no newer"
                     (is (false? alone)))
                 _ (SUT/save-delivery config later)
                 superseded (SUT/newer-delivery? config earlier)
                 latest (SUT/newer-delivery? config later)
                 _ (testing
                     "a later delivery about the same invitation is newer"
                     (is (true? superseded))
                     (is (false? latest)))]))))
