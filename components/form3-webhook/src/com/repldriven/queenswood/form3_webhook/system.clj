(ns com.repldriven.queenswood.form3-webhook.system
  (:require
    [com.repldriven.queenswood.form3-webhook.signature :as signature]

    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str]))

(defn- credentials-from
  [{:keys [key-id private-key public-key]}]
  (let [generated (when (str/blank? private-key) (signature/key-pair))]
    {:key-id (if (str/blank? key-id) (str (utility/uuidv7)) key-id)
     :private-key (or (:private-key generated)
                      (signature/private-key private-key))
     :public-key (or (:public-key generated)
                     (when-not (str/blank? public-key)
                       (signature/public-key public-key)))}))

(def ^:private credentials
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (credentials-from config)))
   :system/config {:key-id nil :private-key nil :public-key nil}
   :system/instance-schema map?})

(system/defcomponents :form3-webhook {:credentials credentials})
