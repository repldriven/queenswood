(ns com.repldriven.queenswood.api.me.routes
  (:require
    [com.repldriven.queenswood.api.me.handlers :as handlers]))

(def routes
  [["/me"
    {:openapi {:tags ["Me"] :security [{"bearerAuth" ["user"]}]}}
    [""
     {:get {:summary (str "Retrieve the authenticated user, their active "
                          "memberships, and whether they are an operator")
            :openapi {:operationId "RetrieveMe"}
            :responses {200 {:description (str "The user, their active "
                                               "memberships with role and "
                                               "bank name, and the operator "
                                               "flag.")
                             :body [:ref "Me"]}}
            :handler handlers/get-me}}]]])
