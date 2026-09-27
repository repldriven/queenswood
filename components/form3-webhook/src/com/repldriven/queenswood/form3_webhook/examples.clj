(ns com.repldriven.queenswood.form3-webhook.examples)

(def Notification
  {:id "e1fa2883-940a-44d5-88af-e1a62123c67a"
   :organisation_id "ee2fb143-6dfe-4787-b183-ca8ddd4164d2"
   :event_type "created"
   :record_type "payment_admission_tasks"
   :version 0
   :action_time "2026-09-28T12:35:57.380Z"
   :data {:id "7826c3cb-d6fd-41d0-b187-dc23ba928772"
          :type "payment_admission_tasks"
          :organisation_id "ee2fb143-6dfe-4787-b183-ca8ddd4164d2"
          :version 0
          :attributes
          {:name "account_check" :assignee "customer" :status "pending"}}})

(def NotificationRejected
  {:type "payment-webhook/unknown-resource"
   :title "REJECTED"
   :status 400
   :detail "The notification names a resource Form3 does not hold"})
