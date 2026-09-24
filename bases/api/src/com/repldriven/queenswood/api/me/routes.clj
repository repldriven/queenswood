(ns com.repldriven.queenswood.api.me.routes
  (:require
    [com.repldriven.queenswood.api.me.handlers :as handlers]))

(def routes
  [["/me"
    {:openapi {:tags ["Me"] :security [{"bearerAuth" ["user"]}]}}
    [""
     {:get {:summary "Retrieve the signed-in person"
            :openapi {:operationId "RetrieveMe"
                      :description
                      (str "The signed-in person's user record, and whether "
                           "they are an operator. The record is created on "
                           "the person's first signed-in request. Their "
                           "memberships are listed at `/v1/me/memberships`.")}
            :responses {200 {:description (str "The person, and the operator "
                                               "flag.")
                             :body [:ref "Me"]}}
            :handler handlers/get-me}}]]])
