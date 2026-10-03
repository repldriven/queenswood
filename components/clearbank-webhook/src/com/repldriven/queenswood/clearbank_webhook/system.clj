(ns com.repldriven.queenswood.clearbank-webhook.system
  (:require
    [com.repldriven.queenswood.clearbank-webhook.signature :as signature]

    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str]))

(defn- read-key
  [parse file]
  (when-not (str/blank? file) (parse (slurp file))))

(defn- key-pair-from
  [{:keys [private-key-file public-key-file]}]
  (if (and (str/blank? private-key-file) (str/blank? public-key-file))
    (signature/key-pair)
    (let-nom> [private-key (read-key signature/private-key private-key-file)
               public-key (read-key signature/public-key public-key-file)]
      (utility/assoc-some {}
                          :private-key private-key
                          :public-key public-key))))

(def ^:private key-pair
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (key-pair-from config)))
   :system/config {:private-key-file nil :public-key-file nil}
   :system/instance-schema map?})

(system/defcomponents :clearbank-webhook {:key-pair key-pair})
