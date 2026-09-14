(ns com.repldriven.queenswood.email.message
  (:require
    [clojure.string :as str])
  (:import
    (java.time Instant ZoneOffset)
    (java.time.format DateTimeFormatter)
    (java.util Locale)))

(def ^:private product-name "Queenswood")

(def ^:private expiry-format
  (-> (DateTimeFormatter/ofPattern "d MMMM yyyy 'at' HH:mm 'UTC'" Locale/UK)
      (.withZone ZoneOffset/UTC)))

(defn- expiry
  [expires-at]
  (.format ^DateTimeFormatter expiry-format (Instant/ofEpochMilli expires-at)))

(defn- role-name
  [role]
  (str/replace (name role) #"^role-" ""))

(defn- escape-html
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn invitation-link
  "The console link that accepts an invitation, the id and token in the
  fragment so neither reaches a server log."
  [console-url invitation-id token]
  (str (str/replace (str console-url) #"/+$" "")
       "/#/invitations/"
       invitation-id
       "?token="
       token))

(defn invitation-message
  "The `smtp/send` message inviting the invited address, with no from:
  the client's is used."
  [{:keys [invitation bank-name inviter-name link]}]
  (let [{:keys [email role expires-at]} invitation
        inviter (or inviter-name product-name)
        role (role-name role)
        expires (expiry expires-at)]
    {:to email
     :subject (str inviter " invited you to " bank-name " on " product-name)
     :text (str inviter
                " invited you to join "
                bank-name
                " on "
                product-name
                " as "
                role
                ".\n\n"
                "Accept the invitation:\n"
                link
                "\n\n"
                "The link expires on "
                expires
                ". If you were not expecting this invitation, you can "
                "ignore this email.\n")
     :html (str "<p>"
                (escape-html inviter)
                " invited you to join <strong>"
                (escape-html bank-name)
                "</strong> on "
                product-name
                " as "
                (escape-html role)
                ".</p>"
                "<p><a href=\""
                (escape-html link)
                "\">Accept the invitation</a></p>"
                "<p>The link expires on "
                (escape-html expires)
                ". If you were not expecting this invitation, you can "
                "ignore this email.</p>")}))
