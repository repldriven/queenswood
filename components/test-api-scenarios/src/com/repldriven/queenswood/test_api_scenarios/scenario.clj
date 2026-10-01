(ns com.repldriven.queenswood.test-api-scenarios.scenario
  (:require
    [com.repldriven.mono.error.interface :as error]

    [malli.core :as m]
    [malli.error :as me]

    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.walk :as walk]))

(def scenarios-dir "test-api-scenarios/scenarios")

(def fixtures-dir "test-api-scenarios/fixtures")

(def ^:private request
  [:map {:closed true}
   [:method keyword?]
   [:path {:optional true} any?]
   [:url {:optional true} any?]
   [:base {:optional true} [:enum :payment-simulator]]
   [:path-params {:optional true} map?]
   [:query-params {:optional true} map?]
   [:headers {:optional true} map?]
   [:body {:optional true} any?]
   [:form {:optional true} map?]
   [:auth {:optional true} any?]])

(def ^:private response
  [:map {:closed true}
   [:status {:optional true} any?]
   [:body {:optional true} any?]
   [:headers {:optional true} map?]
   [:problem {:optional true} map?]])

(def ^:private alias-key [:or keyword? [:vector keyword?]])

(def ^:private verb-steps
  {:api/request [[:request request]
                 [:assert {:optional true} response]
                 [:as {:optional true} alias-key]
                 [:token-as {:optional true} alias-key]
                 [:fault {:optional true} [:enum :lost-reply]]
                 [:idempotency-key {:optional true} false?]]
   :api/poll [[:request request]
              [:until response]
              [:timeout-ms {:optional true} pos-int?]
              [:interval-ms {:optional true} pos-int?]
              [:as {:optional true} alias-key]]
   :api/race [[:request request]
              [:assert
               [:map {:closed true}
                [:status any?]
                [:fresh pos-int?]]]
              [:count pos-int?]
              [:as {:optional true} alias-key]]
   :auth/mint-token [[:for map?]
                     [:as alias-key]]
   :auth/mint-user-token [[:realm keyword?]
                          [:client-id any?]
                          [:username any?]
                          [:password any?]
                          [:scope {:optional true} any?]
                          [:as alias-key]]
   :auth/sign-token [[:realm {:optional true} keyword?]
                     [:claims map?]
                     [:kid {:optional true} any?]
                     [:as alias-key]]
   :idv/verify [[:party any?]
                [:auth any?]
                [:outcome {:optional true} any?]
                [:document {:optional true} map?]
                [:channel {:optional true} any?]
                [:email {:optional true} any?]
                [:as {:optional true} alias-key]]
   :mail/await-invitation [[:to any?]
                           [:invitation-id any?]
                           [:nth {:optional true} pos-int?]
                           [:timeout-ms {:optional true} pos-int?]
                           [:as {:optional true} alias-key]]
   :wait [[:duration-ms pos-int?]]
   :webhook/open-receiver [[:as alias-key]]
   :webhook/await-delivery [[:address any?]
                            [:where {:optional true} map?]
                            [:count {:optional true} pos-int?]
                            [:secret {:optional true} any?]
                            [:timeout-ms {:optional true} pos-int?]
                            [:as {:optional true} alias-key]]
   :assert/equals [[:actual any?]
                   [:expected any?]]
   :keycloak/add-signing-key [[:realm keyword?]
                              [:as {:optional true} alias-key]]})

(def ^:private fixture-step
  [:map {:closed true}
   [:fixture keyword?]
   [:as keyword?]
   [:with {:optional true} map?]])

(def step
  (into [:multi
         {:dispatch (fn [s] (if (contains? s :fixture) ::fixture (:command s)))}
         [::fixture fixture-step]]
        (map (fn [[verb entries]] [verb
                                   (into [:map {:closed true}
                                          [:command [:= verb]]]
                                         entries)]))
        verb-steps))

(defn- write-step?
  [{:keys [command request]}]
  (or (and (= :api/request command)
           (contains? #{:post :put :patch :delete} (:method request)))
      (contains? #{:api/race :idv/verify :keycloak/add-signing-key} command)))

(def ^:private given-step
  [:and
   step
   [:fn {:error/message "a :given step asserts its status and nothing else"}
    (fn [s] (empty? (dissoc (:assert s) :status)))]])

(def ^:private then-step
  [:and
   step
   [:fn {:error/message "a :then step writes nothing"}
    (fn [s] (not (write-step? s)))]])

(def schema
  [:map {:closed true}
   [:name string?]
   [:tags {:optional true} [:set [:enum :serial]]]
   [:runs-on {:optional true}
    [:map {:closed true}
     [:payment {:optional true} [:enum :every]]
     [:idv {:optional true} [:enum :every]]]]
   [:requires {:optional true}
    [:set
     [:enum :inbound-notified :inbound-admitted :screened :outbound-returned
      :needs-email]]]
   [:given {:optional true} [:vector given-step]]
   [:when [:vector step]]
   [:then {:optional true} [:vector then-step]]])

(def fixture-schema
  [:map {:closed true}
   [:doc string?]
   [:params {:optional true} map?]
   [:required {:optional true} [:set keyword?]]
   [:steps [:vector step]]])

(defn- read-resource
  [kind resource-path]
  (error/let-nom>
    [src (or (io/resource resource-path)
             (error/fail :test-api-scenarios/scenario
                         {:message (str kind " resource not found")
                          :resource resource-path}))]
    (error/try-nom :test-api-scenarios/scenario
                   (str "Failed to parse " kind " EDN")
                   (edn/read-string (slurp src)))))

(defn- validate
  [kind schema resource-path parsed]
  (if (m/validate schema parsed)
    parsed
    (error/fail :test-api-scenarios/scenario
                {:message (str kind " failed schema validation")
                 :resource resource-path
                 :explain (me/humanize (m/explain schema parsed))})))

(defn resource-files
  [dir]
  (let [root (io/file (.getFile (io/resource dir)))
        prefix-len (inc (count (.getPath root)))]
    (->> (file-seq root)
         (filter (fn [f]
                   (and (.isFile ^java.io.File f)
                        (.endsWith (.getName ^java.io.File f) ".edn"))))
         (map (fn [^java.io.File f]
                {:file f :relative (subs (.getPath f) prefix-len)}))
         (sort-by :relative))))

(defn load-fixture
  [fixture]
  (let [resource-path (str fixtures-dir "/" (name fixture) ".edn")]
    (error/let-nom>
      [parsed (read-resource "Fixture" resource-path)]
      (validate "Fixture" fixture-schema resource-path parsed))))

(defn- local-aliases
  [steps]
  (into #{} (mapcat (fn [s] (keep s [:as :token-as]))) steps))

(defn- marker?
  [tag x]
  (and (vector? x) (= tag (first x))))

(defn- scope-step
  [prefix locals step]
  (let [scoped (fn [k] (if (contains? locals k) (conj prefix k) k))
        step (walk/postwalk
              (fn [x]
                (cond
                 (and (marker? :ref x) (contains? locals (second x)))
                 (into [:ref] (concat prefix (rest x)))

                 (and (marker? :ref-find x) (contains? locals (second x)))
                 (into [:ref-find (scoped (second x))] (drop 2 x))

                 :else
                 x))
              step)]
    (cond-> step
            (and (keyword? (:as step)) (not (contains? step :fixture)))
            (update :as scoped)

            (keyword? (:token-as step))
            (update :token-as scoped)

            (contains? locals (get-in step [:request :auth]))
            (update-in [:request :auth]
                       (fn [k] (into [:ref] (scoped k)))))))

(defn- bind-params
  [params form]
  (walk/postwalk (fn [x] (if (marker? :param x) (get params (second x)) x))
                 form))

(defn- fixture-params
  [fixture {:keys [params required]} with]
  (let [unknown (remove (into (set (keys params)) required) (keys with))
        missing (remove (set (keys with)) required)]
    (cond
     (seq unknown)
     (error/fail :test-api-scenarios/scenario
                 {:message "Fixture given a parameter it does not declare"
                  :fixture fixture
                  :unknown (vec unknown)})

     (seq missing)
     (error/fail :test-api-scenarios/scenario
                 {:message "Fixture missing a required parameter"
                  :fixture fixture
                  :missing (vec missing)})

     :else
     (merge params with))))

(declare expand)

(defn- expand-fixture
  [prefix {:keys [fixture as with]}]
  (error/let-nom>
    [definition (load-fixture fixture)
     params (fixture-params fixture definition with)]
    (expand (conj prefix as) params (:steps definition))))

(defn expand
  "Replace each fixture step in `steps` with the fixture's own steps."
  ([steps] (expand [] {} steps))
  ([prefix params steps]
   (let [locals (if (seq prefix) (local-aliases steps) #{})]
     (reduce (fn [acc step]
               (let [step (bind-params params (scope-step prefix locals step))]
                 (if (contains? step :fixture)
                   (let [expanded (expand-fixture prefix step)]
                     (if (error/anomaly? expanded)
                       (reduced expanded)
                       (into acc expanded)))
                   (conj acc step))))
             []
             steps))))

(defn- expand-sections
  [scenario]
  (reduce (fn [scenario section]
            (if-let [steps (get scenario section)]
              (let [expanded (expand steps)]
                (if (error/anomaly? expanded)
                  (reduced expanded)
                  (assoc scenario section expanded)))
              scenario))
          scenario
          [:given :when :then]))

(defn steps
  [scenario]
  (let [{:keys [given then]} scenario
        when-steps (:when scenario)]
    (vec (concat given when-steps then))))

(defn from-resource
  [resource-path]
  (error/let-nom>
    [parsed (read-resource "Scenario" resource-path)
     valid (validate "Scenario" schema resource-path parsed)]
    (expand-sections valid)))
