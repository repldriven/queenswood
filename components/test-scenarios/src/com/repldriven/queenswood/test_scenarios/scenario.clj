(ns com.repldriven.queenswood.test-scenarios.scenario
  (:require
    [com.repldriven.mono.error.interface :as error]

    [malli.core :as m]
    [malli.error :as me]

    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]))

(def scenarios-dir "test-scenarios/scenarios")

(defn- model-id
  [prefix]
  [:and
   simple-keyword?
   [:fn {:error/message (str "a :" prefix "-<n> id")}
    (fn [k] (some? (re-matches (re-pattern (str prefix "-\\d+")) (name k))))]])

(defn- prefixed
  [prefix]
  [:and
   simple-keyword?
   [:fn {:error/message (str "a :" prefix "* keyword")}
    (fn [k] (str/starts-with? (name k) prefix))]])

(def ^:private acct (model-id "acct"))

(def ^:private bank (model-id "bank"))

(def ^:private prod (model-id "prod"))

(def ^:private party (model-id "party"))

(def ^:private pmt (model-id "pmt"))

(def ^:private e2e simple-keyword?)

(def ^:private currency [:re #"^[A-Z]{3}$"])

(def ^:private gl-account-code (prefixed "gl-account-code-"))

(def ^:private none [:cat])

(def ^:private capability
  [:map {:closed true}
   [:effect [:enum :effect-allow :effect-deny]]
   [:reason string?]
   [:kind [:map-of simple-keyword? [:map {:closed true} [:action keyword?]]]]])

(def ^:private count-limit
  [:map {:closed true}
   [:kind
    [:map-of simple-keyword?
     [:map {:closed true}
      [:filters {:optional true}
       [:vector [:map {:closed true} [:action keyword?]]]]]]]
   [:bound
    [:map {:closed true}
     [:kind
      [:map {:closed true}
       [:max
        [:map {:closed true}
         [:aggregate
          [:map {:closed true}
           [:kind
            [:map {:closed true}
             [:count
              [:map {:closed true}
               [:value nat-int?]
               [:window
                [:enum :time-window-daily
                 :time-window-instant]]]]]]]]]]]]]]
   [:reason string?]])

(def ^:private policy
  [:map {:closed true}
   [:name string?]
   [:category keyword?]
   [:capabilities {:optional true} [:vector capability]]
   [:limits {:optional true} [:vector count-limit]]])

(def verbs
  {:create-bank {:kind :model :args none}
   :create-customer {:kind :model :args [:cat bank [:? prod]]}
   :open-account {:kind :model :args [:cat bank party prod]}
   :close-account {:kind :model :args [:cat acct]}
   :create-product {:kind :model
                    :args [:cat bank [:enum :current :savings] int?]}
   :publish-product {:kind :model :args [:cat prod]}
   :open-draft {:kind :model :args [:cat prod]}
   :discard-draft {:kind :model :args [:cat prod]}
   :update-product-draft {:kind :model
                          :args [:cat prod
                                 [:map {:closed true}
                                  [:effective-from int?]
                                  [:effective-to [:maybe int?]]]]}
   :create-person-party {:kind :model
                         :args [:cat bank [:? [:maybe simple-keyword?]]]}
   :inbound-transfer {:kind :model :args [:cat acct int? [:? e2e]]}
   :outbound-payment {:kind :model
                      :args [:alt [:cat acct int?] [:cat acct acct int?]]}
   :internal-transfer {:kind :model :args [:cat acct acct int? [:? currency]]}
   :accrue-interest {:kind :model :args [:cat bank int?]}
   :capitalize-interest {:kind :model :args [:cat bank int?]}
   :hold-inbound {:kind :model :args [:cat acct int? [:? e2e]]}
   :release-inbound {:kind :model :args [:cat acct]}
   :bind-policy {:kind :model :args [:cat bank policy]}
   :fixture/apply-fee {:kind :fixture :args [:cat acct int?]}
   :fixture/fund-house {:kind :fixture :args [:cat bank int?]}
   :close-ledger-account {:kind :reality :args [:cat bank gl-account-code]}
   :admit-inbound {:kind :reality :args [:cat [:or acct string?] int? e2e]}
   :settle-inbound-event {:kind :reality :args [:cat acct int? string?]}
   :outbound-payment-pending {:kind :reality :args [:cat acct int?]}
   :outbound-payment-redelivered {:kind :reality :args [:cat acct int?]}
   :reject-outbound-payment {:kind :reality :args [:cat pmt]}
   :settle-outbound-event {:kind :reality :args [:cat pmt]}
   :return-outbound-payment {:kind :reality :args [:cat pmt]}
   :return-outbound-event {:kind :reality :args [:cat pmt int?]}
   :publish-scheme-event {:kind :reality :args [:cat string? map?]}
   :force-start-job {:kind :reality :args [:cat bank string?]}
   :assert-outcome {:kind :assert
                    :args [:cat [:enum :succeeded :denied :timed-out]]}
   :assert-rejection-kind {:kind :assert :args [:cat qualified-keyword?]}
   :assert-no-anomaly {:kind :assert :args none}
   :assert-balance {:kind :assert :args [:cat acct int?]}
   :assert-gl-balance {:kind :assert
                       :args [:cat bank gl-account-code currency int?]}
   :assert-admission {:kind :assert :args [:cat boolean? [:maybe string?]]}
   :assert-inbound-status {:kind :assert
                           :args [:cat e2e
                                  [:maybe (prefixed "inbound-payment-status-")]
                                  [:? acct]]}
   :assert-outbound-status {:kind :assert
                            :args [:cat pmt
                                   (prefixed "outbound-payment-status-")]}
   :assert-scheme-commands {:kind :assert :args [:cat pmt nat-int?]}
   :assert-intents {:kind :assert :args [:cat pmt nat-int?]}
   :assert-dead-lettered {:kind :assert :args [:cat e2e]}
   :assert-provider-balances {:kind :assert :args [:cat bank]}
   :assert-interest-reconciliation {:kind :assert :args [:cat bank currency]}
   :assert-interest-run {:kind :assert
                         :args [:cat bank int? [:enum :accrue :capitalize]
                                map?]}})

(defn kind
  [command]
  (get-in verbs [command :kind]))

(def step
  (into [:multi {:dispatch :command}]
        (map (fn [[verb {:keys [args]}]] [verb
                                          [:map {:closed true}
                                           [:command [:= verb]]
                                           [:args [:and vector? args]]]]))
        verbs))

(defn- of-kinds
  [kinds message]
  [:and
   step
   [:fn {:error/message message}
    (fn [s] (contains? kinds (kind (:command s))))]])

(def ^:private given-step
  (of-kinds #{:model :fixture :reality}
            "a :given step changes state, never reads or asserts"))

(def ^:private then-step
  (of-kinds #{:read :assert} "a :then step reads or asserts, never writes"))

(def schema
  [:map {:closed true}
   [:name string?]
   [:model {:optional true} [:enum :compared :reality]]
   [:given {:optional true} [:vector given-step]]
   [:when [:vector step]]
   [:then {:optional true} [:vector then-step]]])

(defn steps
  [scenario]
  (let [{:keys [given then]} scenario
        when-steps (:when scenario)]
    (vec (concat given when-steps then))))

(defn compared?
  [scenario]
  (not= :reality (:model scenario)))

(defn- reality-verbs
  [scenario]
  (into (sorted-set)
        (comp (map :command) (filter (fn [c] (= :reality (kind c)))))
        (steps scenario)))

(defn- check-compared
  [resource-path scenario]
  (let [named (reality-verbs scenario)]
    (if (and (compared? scenario) (seq named))
      (error/fail :test-scenarios/scenario
                  {:message (str "A compared scenario names a verb the model"
                                 " has no rule for; mark it :model :reality")
                   :resource resource-path
                   :verbs (vec named)})
      scenario)))

(defn- validate
  [resource-path parsed]
  (if (m/validate schema parsed)
    parsed
    (error/fail :test-scenarios/scenario
                {:message "Scenario failed schema validation"
                 :resource resource-path
                 :explain (me/humanize (m/explain schema parsed))})))

(defn parse
  [resource-path parsed]
  (error/let-nom>
    [valid (validate resource-path parsed)]
    (check-compared resource-path valid)))

(defn from-resource
  [resource-path]
  (error/let-nom>
    [src (or (io/resource resource-path)
             (error/fail :test-scenarios/scenario
                         {:message "Scenario resource not found"
                          :resource resource-path}))
     parsed (error/try-nom :test-scenarios/scenario
                           "Failed to parse scenario EDN"
                           (edn/read-string (slurp src)))]
    (parse resource-path parsed)))

(defn resource-files
  []
  (let [root (io/file (.getFile (io/resource scenarios-dir)))
        prefix-len (inc (count (.getPath root)))]
    (->> (file-seq root)
         (filter (fn [f]
                   (and (.isFile ^java.io.File f)
                        (.endsWith (.getName ^java.io.File f) ".edn"))))
         (map (fn [^java.io.File f]
                {:file f :relative (subs (.getPath f) prefix-len)}))
         (sort-by :relative))))
