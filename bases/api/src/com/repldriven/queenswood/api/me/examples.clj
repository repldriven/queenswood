(ns com.repldriven.queenswood.api.me.examples
  (:require
    [com.repldriven.queenswood.api-schema.interface :refer
     [examples-registry]]))

(def UserId "usr.01kprbmgcj35ptc8npmybhh4s7")

(def MembershipId "mem.01kprbpdwa9q5n2t7vwsx84a3m")

(def BankId "bnk.01kprbqv3z6e0r9d4f1m8nk2yh")

(def User
  {:user-id UserId
   :issuer "https://keycloak.queenswood.example/realms/queenswood"
   :sub "f3c0a18c-2cf7-4d5a-a3b0-c4e9d0b5a124"
   :email "ada@example.com"
   :name "Ada Lovelace"
   :avatar-url "https://lh3.googleusercontent.com/a/AOh14Gh7fA"
   :identity-provider :google
   :status :active
   :created-at "2026-05-18T09:15:00Z"
   :updated-at "2026-05-18T09:15:00Z"})

(def Membership
  {:membership-id MembershipId
   :user-id UserId
   :bank-id BankId
   :bank-name "Ada's Bank"
   :role :owner
   :created-at "2026-05-18T09:15:00Z"
   :updated-at "2026-05-18T09:15:00Z"})

(def Me {:user User :memberships [Membership] :operator false})

(def registry (examples-registry [#'User #'Membership]))
