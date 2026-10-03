(ns com.repldriven.queenswood.form3-webhook.system
  (:require
    [com.repldriven.queenswood.form3-webhook.signature :as signature]

    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str]))

(defn- pem
  [value file]
  (if (str/blank? file) value (slurp file)))

(defn- credentials-from
  [{:keys [key-id private-key private-key-file public-key public-key-file]}]
  (let [private-key (pem private-key private-key-file)
        public-key (pem public-key public-key-file)
        generated (when (and (str/blank? private-key) (str/blank? public-key))
                    (signature/key-pair))]
    {:key-id (if (str/blank? key-id) (str (utility/uuidv7)) key-id)
     :private-key (or (:private-key generated)
                      (when-not (str/blank? private-key)
                        (signature/private-key private-key)))
     :public-key (or (:public-key generated)
                     (when-not (str/blank? public-key)
                       (signature/public-key public-key)))}))

(def ^:private credentials
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (credentials-from config)))
   :system/config {:key-id nil
                   :private-key nil
                   :private-key-file nil
                   :public-key nil
                   :public-key-file nil}
   :system/instance-schema map?})

(system/defcomponents :form3-webhook {:credentials credentials})
