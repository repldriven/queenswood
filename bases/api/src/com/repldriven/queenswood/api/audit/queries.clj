(ns com.repldriven.queenswood.api.audit.queries
  "The bank's audit log, newest first, each act's actor and the person
  it concerns named as the access routes name them."
  (:require
    [com.repldriven.queenswood.api.access.names :as names]
    [com.repldriven.queenswood.api.cursor :as cursor]
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.audit.interface :as audit]
    [com.repldriven.queenswood.bank-query.interface :as banks]
    [com.repldriven.queenswood.member-query.interface :as members]
    [com.repldriven.queenswood.user.interface :as users]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]))

(defn- config
  [{:keys [record-db record-store]}]
  {:record-db record-db :record-store record-store})

(defn- lookup
  [txn]
  (fn [user-id] (users/find-by-id txn user-id)))

(def ^:private access-details
  "The details of an act on the bank, a member or an invitation that the
  audit log shows as fields of its own."
  [:subject-user-id :member-id :role-before :role-after :reason])

(defn- ->audit-event
  "An audit event as the bank's audit log shows it: its actor and the
  person it concerns named, an invitation's address read from the
  invitation, and its details."
  [event names emails]
  (let [{:keys [subject-kind subject-id details created-by created-at]} event
        invitation? (= :audit-subject-kind-invitation subject-kind)
        subject-user-id (:subject-user-id details)]
    (-> (select-keys event
                     [:audit-event-id :bank-id :kind :subject-kind :subject-id
                      :request-id])
        (merge (cond-> (select-keys details access-details)
                       (:role-before details)
                       (update :role-before keyword)

                       (:role-after details)
                       (update :role-after keyword)))
        (assoc :actor (names/->actor created-by names)
               :occurred-at created-at)
        (utility/assoc-some :subject-name
                            (get names subject-user-id)
                            :member-id
                            (when (= :audit-subject-kind-member subject-kind)
                              subject-id)
                            :invitation-id
                            (when invitation? subject-id)
                            :email
                            (when invitation? (get emails subject-id))))))

(defn- invitation-emails
  "The address of each invitation the events name, by invitation id."
  [txn bank-id events]
  (reduce (fn [emails invitation-id]
            (let [invitation (members/find-invitation txn
                                                      bank-id
                                                      invitation-id)]
              (if (error/anomaly? invitation)
                emails
                (assoc emails invitation-id (:email invitation)))))
          {}
          (distinct (keep (fn [{:keys [subject-kind subject-id]}]
                            (when (= :audit-subject-kind-invitation
                                     subject-kind)
                              subject-id))
                          events))))

(defn- named-audit-events
  [txn bank-id events]
  (let-nom> [names (names/user-names
                    (lookup txn)
                    (concat (names/actor-ids (map :created-by events))
                            (keep (comp :subject-user-id :details) events)))]
    (let [emails (invitation-emails txn bank-id events)]
      (mapv (fn [event] (->audit-event event names emails)) events))))

(defn list-audit-events
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [page kind]} (:query parameters)
        txn (config request)
        result (let-nom>
                 [_ (banks/get-bank txn bank-id)
                  found (audit/list-by-bank txn
                                            bank-id
                                            (assoc (cursor/page-opts page)
                                                   :kinds
                                                   kind))
                  named (named-audit-events txn bank-id (:audit-events found))]
                 (cursor/page-body (cursor/request-path request)
                                   page
                                   named
                                   found))]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body result})))
