(ns com.repldriven.queenswood.policy.domain
  (:require
    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.utility.interface :as utility]))

(defn live?
  "Whether a policy participates in evaluation: only an active one does,
  a disabled one being paused and an archived one retired."
  [policy]
  (= :policy-status-active (:status policy)))

(defn new-policy
  [data]
  (let [{:keys [policy-id
                name
                category
                capabilities
                limits
                description
                status
                labels]
         :or {capabilities []
              limits []
              status :policy-status-active
              labels {}}}
        data]
    ;; A supplied :policy-id makes the resulting save idempotent --
    ;; seed data (e.g. the bootstrap-service's platform / micro
    ;; restricted policies loaded from YAML) carries a stable id so
    ;; subsequent bootstrap runs upsert the same row instead of
    ;; piling up duplicates. Runtime-created policies pass no id and
    ;; get a freshly generated one.
    (utility/assoc-some
     {:policy-id (or policy-id (utility/generate-id "pol"))
      :name name
      :category category
      :capabilities capabilities
      :limits limits
      :labels labels
      :status status
      :created-at (utility/now)}
     :description
     description)))

(defn archive
  "Transition a policy to the archived lifecycle state. Rejects when
  the policy still has `bindings` — archival is for a policy no longer
  bound to anything, so the operator unbinds first."
  [policy bindings]
  (if (seq bindings)
    (error/reject :policy/still-bound
                  {:message "Cannot archive a policy that is still bound"
                   :policy-id (:policy-id policy)
                   :binding-count (count bindings)})
    (let [now (utility/now)]
      (assoc policy
             :status :policy-status-archived
             :archived-at now
             :updated-at now))))

(defn new-binding
  [data]
  (let [{:keys [policy-id target reason actor]} data]
    (utility/assoc-some
     {:binding-id (utility/generate-id "bnd")
      :policy-id policy-id
      :target target
      :created-at (utility/now)
      :created-by (select-keys actor [:kind :principal-id])}
     :reason
     reason)))
