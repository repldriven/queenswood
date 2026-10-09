(ns com.repldriven.queenswood.user.domain
  (:require
    [com.repldriven.mono.utility.interface :as utility]))

(defn new-user
  "Build a User map from OIDC claims."
  [{:keys [issuer sub email name avatar-url identity-provider]}]
  (utility/assoc-some {:user-id (utility/generate-id "usr")
                       :issuer issuer
                       :sub sub
                       :name name
                       :identity-provider (or identity-provider
                                              :identity-provider-unknown)
                       :email email
                       :created-at (utility/now)}
                      :avatar-url
                      avatar-url))

(defn update-user
  "Merge a fresh set of OIDC claims into an existing User -- used
  when a known (issuer, sub) re-signs in and their email / name /
  avatar may have changed. user-id, issuer, sub and created-at stay
  put; updated-at refreshes."
  [user {:keys [email name avatar-url identity-provider]}]
  (-> user
      (assoc :updated-at (utility/now))
      (utility/assoc-some
       :email email
       :name name
       :avatar-url avatar-url
       :identity-provider identity-provider)))

(defn claims-changed?
  "True when any of the mutable user-claim fields differ between the
  existing User and a fresh claims map. Used by core/upsert-by-sub to
  skip the FDB write on no-op re-signins."
  [user {:keys [email name avatar-url identity-provider]}]
  (or (and email (not= email (:email user)))
      (and name (not= name (:name user)))
      (and avatar-url (not= avatar-url (:avatar-url user)))
      (and identity-provider
           (not= identity-provider (:identity-provider user)))))
