(ns com.repldriven.queenswood.api.me.coercion
  (:require
    [com.repldriven.queenswood.api-schema.interface :as coercion]))

(def ^:private identity-provider-enum
  (coercion/enum-coercion {"google" :identity-provider-google
                           "github" :identity-provider-github
                           "password" :identity-provider-password}
                          :identity-provider-unknown))

(def identity-provider-enum-schema (:enum-schema identity-provider-enum))

(def ^:private user-status-enum
  (coercion/enum-coercion {"active" :user-status-active
                           "suspended" :user-status-suspended}
                          :user-status-unknown))

(def user-status-enum-schema (:enum-schema user-status-enum))

(def ^:private role-enum
  (coercion/enum-coercion {"owner" :role-owner
                           "admin" :role-admin
                           "developer" :role-developer
                           "viewer" :role-viewer}
                          :role-unknown))

(def role-enum-schema (:enum-schema role-enum))
