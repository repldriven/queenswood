(ns com.repldriven.queenswood.form3-webhook.components
  (:require
    [com.repldriven.queenswood.form3-webhook.examples :as examples]

    [com.repldriven.mono.utility.interface :refer [vname]]))

(def Resource
  [:map
   [:id string?]
   [:type {:optional true} [:maybe string?]]
   [:organisation_id {:optional true} [:maybe string?]]
   [:version {:optional true} [:maybe int?]]
   [:attributes {:optional true} [:maybe map?]]
   [:relationships {:optional true} [:maybe map?]]])

(def Notification
  [:map
   {:json-schema/example examples/Notification}
   [:id string?]
   [:organisation_id {:optional true} [:maybe string?]]
   [:event_type string?]
   [:record_type string?]
   [:version {:optional true} [:maybe int?]]
   [:action_time {:optional true} [:maybe string?]]
   [:actioned_by {:optional true} [:maybe string?]]
   [:data [:ref "Resource"]]])

(def NotificationRejected
  [:map
   {:json-schema/example examples/NotificationRejected}
   [:type string?]
   [:title string?]
   [:status int?]
   [:detail {:optional true} [:maybe string?]]])

(defn- registry
  [vars]
  (reduce (fn [m v] (assoc m (vname v) @v)) {} vars))

(def component-registry
  (registry [#'Resource #'Notification #'NotificationRejected]))

(def example-registry
  (reduce (fn [m v] (assoc m (vname v) {:value @v}))
          {}
          [#'examples/Notification #'examples/NotificationRejected]))
