(ns ^:eftest/synchronized com.repldriven.queenswood.api.webhook.writes-test
  "The webhook writes as a caller reaches them: a request built by
  hand, routed, coerced, handled, and coerced back through the
  response schema the route declares.

  The `webhook` component is stood in for by a map in an atom, so no
  FoundationDB is booted — but neither rule under test is written
  here. The double asks `webhook/check-address`, which is pure and
  takes the host's addresses as the caller already resolved them, so a
  refused address is refused by `domain.clj` and the test resolves to
  a fixed address rather than to the network. Its rejections carry the
  kinds the component raises, so the statuses come off `errors.clj`'s
  table.

  The stub credential names a bank and a service credential's two
  levels, `org:viewer` and `org:developer`, and no `:principal-id`. `cache-response` claims nothing without one, so
  what answers a repeated create is the component's own read-back off
  the idempotency key — the mechanism REQ-026 names."
  (:require
    [com.repldriven.queenswood.api.api :as api]
    [com.repldriven.queenswood.api.auth :as auth]

    [com.repldriven.queenswood.webhook.interface :as webhook]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.json.interface :as json]

    [clojure.test :refer [deftest is testing]])
  (:import
    (java.io ByteArrayInputStream)))

(def ^:private bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7")
(def ^:private endpoint-id "whe.01kprbmgcj35ptc8npmybhh4sg")
(def ^:private delivery-id "whd.01kprbmgcj35ptc8npmybhh4sh")
(def ^:private base "/v1/webhook-endpoints")
(def ^:private address "https://hooks.example.com/queenswood")

(def ^:private minted
  "The ids the double hands out, one per registration."
  ["whe.01kprbmgcj35ptc8npmybhh4sg" "whe.01kprbmgcj35ptc8npmybhh4sk"])

(def ^:private routable
  "A host's resolved addresses, as the caller would hand them to the
  rule: one documentation address, in no range the rule refuses."
  ["203.0.113.10"])

(def ^:private refused-ranges
  "One address per range REQ-027 names, against the range it belongs
  to."
  {"loopback" "127.0.0.1"
   "link-local" "169.254.10.1"
   "private" "10.1.2.3"
   "the metadata service" "169.254.169.254"})

(def ^:private authenticated
  {:name ::authenticated
   :enter (fn [ctx]
            (assoc-in ctx
             [:request :auth]
             {:bank-id bank-id :roles #{auth/org-viewer auth/org-developer}}))})

(def ^:private platform-hosts
  "What the deployment's `WEBHOOK_PLATFORM_HOSTS` reaches the handler
  as: one comma-separated string on the request, put there by the
  server's component-injecting interceptors."
  "console.example.test,hooks.example.com")

(def ^:private configured
  {:name ::configured
   :enter (fn [ctx] (assoc-in ctx [:request :platform-hosts] platform-hosts))})

(def ^:private handler (delay (api/app {:interceptors [authenticated]})))

(def ^:private platform-handler
  (delay (api/app {:interceptors [authenticated configured]})))

(defn- call
  [method uri {:keys [body idempotency-key platform]}]
  (let [headers (cond-> {"accept" "application/json"}
                        body
                        (assoc "content-type" "application/json")
                        idempotency-key
                        (assoc "idempotency-key"
                               idempotency-key))
        request (cond-> {:request-method method :uri uri :headers headers}
                        body
                        (assoc :body
                               (ByteArrayInputStream.
                                (.getBytes ^String (json/write-str body)
                                           "UTF-8"))))
        response ((if platform @platform-handler @handler) request)]
    (cond-> response
            (:body response)
            (update :body #(json/read-str (slurp %) :key-fn keyword)))))

;; --- the component, stood in for ------------------------------------

(defn- stored
  [id data]
  (merge {:bank-id bank-id
          :endpoint-id id
          :address (:address data)
          :status :webhook-endpoint-status-enabled
          :created-at 1779955200000
          :updated-at 1779955200000
          :secret "whsec_9Qk3sVQm0d1tYf8pZr2XwLbN7cJhGeAu5KiRoT4"}
         (select-keys data [:description :kinds])))

(defn- new-state [] (atom {:endpoints {} :by-key {}}))

(defn- register-double
  "Mints one endpoint per idempotency key, reading the first back when
  the key repeats, and asks the domain rule about the address first.
  `opts` is where the handler passes the hosts the deployment refuses,
  so the double applies them as the component would."
  [state resolved]
  (fn [_ _bank-id {:keys [idempotency-key] :as data} opts]
    (or (webhook/check-address (:address data)
                               resolved
                               (:platform-hosts opts))
        (if-let [seen (get-in @state [:by-key idempotency-key])]
          (get-in @state [:endpoints seen])
          (let [id (nth minted (count (:endpoints @state)))
                endpoint (stored id data)]
            (swap! state
              (fn [s]
                (-> s
                    (assoc-in [:endpoints id] endpoint)
                    (assoc-in [:by-key idempotency-key] id))))
            endpoint)))))

(defn- update-double
  [state resolved]
  (fn [_ _bank-id id data opts]
    (or (webhook/check-address (:address data)
                               resolved
                               (:platform-hosts opts))
        (let [endpoint (stored id data)]
          (swap! state assoc-in [:endpoints id] endpoint)
          endpoint))))

(defn- key-for [n] (str "01jsx6k7h0abfdv8qpm2ytn3w" n))

(defn- register
  [endpoint-address key]
  (call :post
        base
        {:body {:address endpoint-address :kinds ["cash-account.opened"]}
         :idempotency-key key}))

(defn- update-address
  [endpoint-address]
  (call :put
        (str base "/" endpoint-id)
        {:body {:address endpoint-address}}))

;; --- AC-19: a repeated create mints nothing -------------------------

(deftest a-repeated-create-under-one-key-answers-the-first-test
  (let [state (new-state)]
    (with-redefs [webhook/register (register-double state routable)]
      (let [first-call (register address (key-for 1))
            second-call (register address (key-for 1))]
        (is (= 201 (:status first-call)))
        (is (= 201 (:status second-call)))
        (testing "the second answers the endpoint the first registered"
          (is (= (get-in first-call [:body :endpoint :endpoint-id])
                 (get-in second-call [:body :endpoint :endpoint-id]))))
        (testing "and no second endpoint was minted"
          (is (= 1 (count (:endpoints @state)))))))))

(deftest a-create-under-a-fresh-key-mints-its-own-test
  (let [state (new-state)]
    (with-redefs [webhook/register (register-double state routable)]
      (register address (key-for 1))
      (register address (key-for 2))
      (is
       (= 2 (count (:endpoints @state)))
       "two keys are two registrations, so the read-back is keyed
           rather than blanket"))))

;; --- AC-20: the addresses the platform refuses ----------------------

(defn- refuses?
  [{:keys [status body]}]
  (and (= 422 status)
       (= ":webhook-endpoint/invalid-address" (:type body))))

(deftest registration-refuses-an-address-the-rule-refuses-test
  (let [state (new-state)]
    (testing "a non-HTTPS address is refused by the rule, not by the shape"
      (with-redefs [webhook/register (register-double state routable)]
        (is (refuses? (register "http://hooks.example.com/queenswood"
                                (key-for 1))))))
    (doseq [[range-name resolved] refused-ranges]
      (testing (str "a host resolving into " range-name)
        (with-redefs [webhook/register (register-double state [resolved])]
          (is (refuses? (register address (key-for 2)))))))
    (is (= {} (:endpoints @state))
        "nothing was written for any refused address")))

(deftest update-refuses-an-address-the-rule-refuses-test
  (let [state (new-state)]
    (testing "a non-HTTPS address is refused on update too"
      (with-redefs [webhook/update-endpoint (update-double state routable)]
        (is (refuses? (update-address "http://hooks.example.com/hook")))))
    (doseq [[range-name resolved] refused-ranges]
      (testing (str "a host resolving into " range-name)
        (with-redefs [webhook/update-endpoint (update-double state [resolved])]
          (is (refuses? (update-address address))))))
    (is (= {} (:endpoints @state))
        "nothing was written for any refused address")))

;; --- AC-22: each rejection reaches its documented status ------------

(def ^:private invalid-status
  "The payload `domain.clj` raises a wrong-source-state transition
  with: the message, the endpoint, the status it is in and the
  statuses the transition starts from."
  {:message "Endpoint is not in a state that allows this"
   :endpoint-id endpoint-id
   :status :webhook-endpoint-status-removed
   :allowed #{:webhook-endpoint-status-enabled
              :webhook-endpoint-status-paused}})

(deftest a-missing-endpoint-is-404-test
  (with-redefs [webhook/get-endpoint (fn [& _]
                                       (error/reject
                                        :webhook-endpoint/not-found
                                        {:message "Webhook endpoint not found"
                                         :bank-id bank-id
                                         :endpoint-id endpoint-id}))]
    (let [{:keys [status body]} (call :get (str base "/" endpoint-id) {})]
      (is (= 404 status) "the name heuristic, with no override entry")
      (is (= ":webhook-endpoint/not-found" (:type body)))
      (is (= "Webhook endpoint not found" (:detail body))))))

(deftest a-missing-delivery-is-404-test
  (with-redefs [webhook/resend (fn [& _]
                                 (error/reject :webhook-delivery/not-found
                                               {:message
                                                "Webhook delivery not found"
                                                :endpoint-id endpoint-id
                                                :delivery-id delivery-id}))]
    (let [{:keys [status body]}
          (call :post
                (str base "/" endpoint-id "/deliveries/" delivery-id "/resend")
                {:idempotency-key (key-for 3)})]
      (is (= 404 status))
      (is (= ":webhook-delivery/not-found" (:type body))))))

(deftest an-address-the-platform-refuses-is-422-test
  (with-redefs [webhook/register (register-double (new-state) routable)]
    (let [{:keys [status body]} (register "http://hooks.example.com/queenswood"
                                          (key-for 4))]
      (is (= 422 status) "the rejection default, with no override entry")
      (is (= ":webhook-endpoint/invalid-address" (:type body))))))

(deftest the-deployments-own-hosts-reach-the-write-test
  (testing "the configured hosts refuse an address naming one of them"
    (with-redefs [webhook/register (register-double (new-state) routable)]
      (let [{:keys [status body]}
            (call :post
                  base
                  {:body {:address "https://hooks.example.com/queenswood"}
                   :idempotency-key (key-for 5)
                   :platform true})]
        (is (= 422 status))
        (is (= ":webhook-endpoint/invalid-address" (:type body))))))
  (testing "and admit an address naming none of them"
    (with-redefs [webhook/register (register-double (new-state) routable)]
      (let [{:keys [status]} (call :post
                                   base
                                   {:body {:address
                                           "https://tenant.example/queenswood"}
                                    :idempotency-key (key-for 6)
                                    :platform true})]
        (is (= 201 status)))))
  (testing "and an update takes the same set"
    (with-redefs [webhook/update-endpoint (update-double (new-state) routable)]
      (let [{:keys [status body]}
            (call :put
                  (str base "/" endpoint-id)
                  {:body {:address "https://console.example.test/hook"}
                   :platform true})]
        (is (= 422 status))
        (is (= ":webhook-endpoint/invalid-address" (:type body)))))))

(deftest a-transition-from-the-wrong-state-is-409-test
  (with-redefs [webhook/disable (fn [& _]
                                  (error/reject :webhook-endpoint/invalid-status
                                                invalid-status))]
    (let [{:keys [status body]}
          (call :post (str base "/" endpoint-id "/disable") {})]
      (is (= 409 status) "the override table, not the 422 default")
      (is (= ":webhook-endpoint/invalid-status" (:type body)))
      (is (= (:message invalid-status) (:detail body))
          "the rejection's message reaches the caller as `detail`"))))

(deftest the-409-rejection-carries-the-four-keys-test
  (let [payload (error/payload (error/reject :webhook-endpoint/invalid-status
                                             invalid-status))]
    (is
     (= #{:message :endpoint-id :status :allowed} (set (keys payload)))
     "the payload the lifecycle recipe mandates, beside the status
         the table gives it")
    (is (= endpoint-id (:endpoint-id payload)))
    (is (contains? (:allowed payload) :webhook-endpoint-status-enabled))))
