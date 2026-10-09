(ns com.repldriven.queenswood.api.companies.queries
  (:require
    [com.repldriven.queenswood.api.commands :as commands]

    [com.repldriven.queenswood.company-api.interface :as company-api]))

(defn- dispatcher
  [request]
  (let [{:keys [dispatchers]} request
        {:keys [companies]} dispatchers]
    companies))

(defn lookup
  "Resolve `company-number` against the registry of record. Returns the
  `commands/send` ring response — 200 plus the company body on success.
  Onboarding calls this directly and snapshots the body onto the bank."
  [request company-number]
  (let [response (commands/send (dispatcher request)
                                request
                                "lookup-company"
                                "company"
                                {:company-number company-number})]
    (cond-> response
            (= 200 (:status response))
            (update :body company-api/->body))))

(defn lookup-company
  [request]
  (let [{:keys [company-number]} (get-in request [:parameters :path])]
    (lookup request company-number)))
