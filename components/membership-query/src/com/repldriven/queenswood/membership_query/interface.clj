(ns com.repldriven.queenswood.membership-query.interface
  "Reads of who may act for a bank: Memberships, Invitations and the
  AccessEvent history the `membership` processor writes, and the
  invitation token's minting and hash. Every fn takes `txn`, a live
  transaction or a config map. The invitation reads take an optional
  `:now` in epoch milliseconds, defaulting to the current time; a
  pending invitation past `expires-at` reads as expired, and no
  invitation they return carries `:token-hash`. The `-record` reads are
  the write side's: the invitation as stored, token hash and status
  included."
  (:require
    [com.repldriven.queenswood.membership-query.core :as core]
    [com.repldriven.queenswood.membership-query.domain :as domain]))

;; ---
;; tokens
;; ---

(defn new-invitation-token
  "Mint an invitation token: 32 bytes of `SecureRandom`, base64url
  without padding (43 characters). Returns `{:token :token-hash}`, the
  hash the SHA-256 of the token as lower-case hex. Only the hash is
  stored."
  []
  (core/new-invitation-token))

(defn token-hash
  "The SHA-256 of a presented token, as lower-case hex, or nil when
  `token` is not a string.

  Args:
  - token: the token string."
  [token]
  (core/token-hash token))

;; ---
;; memberships
;; ---

(defn list-by-user
  "List every membership of a user, ended ones included. Returns a
  vector (possibly empty) of Membership maps, or an anomaly.

  Args:
  - txn: FDB transaction or config.
  - user-id: user id (string)."
  [txn user-id]
  (core/list-by-user txn user-id))

(defn list-by-bank
  "List every membership of a bank, ended ones included. Returns a
  vector (possibly empty) of Membership maps, or an anomaly.

  Args:
  - txn: FDB transaction or config.
  - bank-id: bank id (string)."
  [txn bank-id]
  (core/list-by-bank txn bank-id))

(defn list-active-by-user
  "List a user's active memberships. Returns a vector (possibly empty)
  of Membership maps, or an anomaly.

  Args:
  - txn: FDB transaction or config.
  - user-id: user id (string)."
  [txn user-id]
  (core/list-active-by-user txn user-id))

(defn list-active-by-bank
  "List a bank's active memberships. Returns a vector (possibly empty)
  of Membership maps, or an anomaly.

  Args:
  - txn: FDB transaction or config.
  - bank-id: bank id (string)."
  [txn bank-id]
  (core/list-active-by-bank txn bank-id))

(defn list-active-by-banks
  "List several banks' active memberships in one transaction, as a map
  from bank id to its vector (possibly empty) of Membership maps, or
  an anomaly.

  Args:
  - txn: FDB transaction or config.
  - bank-ids: bank ids (strings)."
  [txn bank-ids]
  (core/list-active-by-banks txn bank-ids))

(defn find-by-id
  "Load a Membership by id, active or ended. Returns the map or a
  `:membership/not-found` rejection anomaly.

  Args:
  - txn: FDB transaction or config.
  - membership-id: membership id (string)."
  [txn membership-id]
  (core/find-by-id txn membership-id))

;; ---
;; invitations
;; ---

(defn effective-status
  "An invitation's status as of `now`: expired when it is pending and
  `expires-at` has passed, otherwise its stored status.

  Args:
  - invitation: the Invitation map.
  - now: epoch milliseconds."
  [invitation now]
  (domain/effective-status invitation now))

(defn find-invitation
  "Load an invitation of a bank. Returns the Invitation map or an
  `:invitation/not-found` rejection anomaly.

  Args:
  - txn: FDB transaction or config.
  - bank-id: bank id (string).
  - invitation-id: invitation id (string).
  - opts: optional `:now`."
  ([txn bank-id invitation-id]
   (core/find-invitation txn bank-id invitation-id {}))
  ([txn bank-id invitation-id opts]
   (core/find-invitation txn bank-id invitation-id opts)))

(defn find-invitation-for-recipient
  "Load an invitation as its recipient sees it, with no bank named. The
  proof is `{:token-hash :email :email-verified?}`: a token hash equal to
  the invitation's, or an email whose lower case is the invited address
  with `:email-verified?` true. Returns the Invitation map, or an
  `:invitation/not-found` rejection anomaly when the invitation is
  unknown or the proof does not reach it.

  Args:
  - txn: FDB transaction or config.
  - invitation-id: invitation id (string).
  - proof: the recipient's proof.
  - opts: optional `:now`."
  ([txn invitation-id proof]
   (core/find-invitation-for-recipient txn invitation-id proof {}))
  ([txn invitation-id proof opts]
   (core/find-invitation-for-recipient txn invitation-id proof opts)))

(defn get-invitation-record
  "Load an invitation of a bank as stored, for a write. Returns the
  Invitation map or an `:invitation/not-found` rejection anomaly.

  Args:
  - txn: FDB transaction or config.
  - bank-id: bank id (string).
  - invitation-id: invitation id (string)."
  [txn bank-id invitation-id]
  (core/get-invitation-record txn bank-id invitation-id))

(defn get-invitation-record-for-recipient
  "Load an invitation as stored, for a write, when the proof reaches it,
  as `find-invitation-for-recipient` does.

  Args:
  - txn: FDB transaction or config.
  - invitation-id: invitation id (string).
  - proof: the recipient's proof."
  [txn invitation-id proof]
  (core/get-invitation-record-for-recipient txn invitation-id proof))

(defn list-invitations-by-bank
  "List a bank's pending, expired and accepted invitations; declined and
  withdrawn ones are left out. Returns a vector (possibly empty) of
  Invitation maps, or an anomaly.

  Args:
  - txn: FDB transaction or config.
  - bank-id: bank id (string).
  - opts: optional `:now`."
  ([txn bank-id]
   (core/list-invitations-by-bank txn bank-id {}))
  ([txn bank-id opts]
   (core/list-invitations-by-bank txn bank-id opts)))

(defn list-pending-invitations-by-email
  "List the pending, unexpired invitations to an address, in any bank,
  matched lower-cased. The caller establishes the address is verified.
  Returns a vector (possibly empty) of Invitation maps, or an anomaly.

  Args:
  - txn: FDB transaction or config.
  - email: the address (string).
  - opts: optional `:now`."
  ([txn email]
   (core/list-pending-invitations-by-email txn email {}))
  ([txn email opts]
   (core/list-pending-invitations-by-email txn email opts)))

;; ---
;; history
;; ---

(defn list-access-events
  "Page a bank's access events, newest first by default. An invitation's
  events carry its id, address and role as `role-after`; a membership's
  carry the user, membership id and `role-before`, and a role change
  `role-after`.

  Args:
  - txn: FDB transaction or config.
  - bank-id: bank id (string).
  - opts: `{:after :before :limit :order}`; `:limit` defaults to 100 and
    `:order` to `:desc`. A cursor is an event's `access-event-id`.

  Returns `{:access-events :before :after}`, `:after` set only when more
  events remain, or an anomaly."
  ([txn bank-id]
   (core/list-access-events txn bank-id {}))
  ([txn bank-id opts]
   (core/list-access-events txn bank-id opts)))
