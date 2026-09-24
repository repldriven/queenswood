(ns com.repldriven.queenswood.api.me.routes
  (:require
    [com.repldriven.queenswood.api.me.handlers :as handlers]))

(def routes
  [["/me"
    {:openapi {:tags ["Me"] :security [{"bearerAuth" ["user"]}]}}
    [""
     {:get {:summary "Retrieve the signed-in user"
            :openapi {:operationId "RetrieveMe"
                      :description
                      (str "The user with their active memberships, each "
                           "naming its bank and role, and whether they are "
                           "an operator. The user record is created on the "
                           "person's first signed-in request.")}
            :responses {200 {:description (str "The user, their active "
                                               "memberships with role and "
                                               "bank name, and the operator "
                                               "flag.")
                             :body [:ref "Me"]}}
            :handler handlers/get-me}}]]])
