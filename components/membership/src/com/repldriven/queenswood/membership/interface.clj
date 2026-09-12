(ns com.repldriven.queenswood.membership.interface
  "Who may act for a bank. A Membership joins a User to a Bank with a
  role and is ended, never deleted; an Invitation, created with the
  SHA-256 of a token the caller minted, is accepted by a recipient
  proving the token or a verified email; every write records an
  AccessEvent in its own FDB transaction, reading what its rules need
  inside it. Every fn takes `txn`, a live transaction or a config map.
  Writes and the invitation reads take an optional `:now` in epoch
  milliseconds, defaulting to the current time, and a pending
  invitation past `expires-at` reads as expired without being written
  so. An actor is `{:kind :principal-id :role}`, `:kind`
  `:actor-kind-member` or `:actor-kind-operator`, and an operator acts
  as an owner. No invitation returned carries `:token-hash`."
  (:require
    [com.repldriven.queenswood.membership.core :as core]))

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

(defn new-membership
  "Create an active membership linking a User to a Bank with a Role.

  Args:
  - txn: FDB transaction or config.
  - input: map with `:user-id`, `:bank-id`, and optional
    `:role` (`:role-*` keyword; defaults to `:role-owner`).

  Returns the Membership map or an anomaly."
  [txn input]
  (core/new-membership txn input))

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

(defn find-by-id
  "Load a Membership by id, active or ended. Returns the map or a
  `:membership/not-found` rejection anomaly.

  Args:
  - txn: FDB transaction or config.
  - membership-id: membership id (string)."
  [txn membership-id]
  (core/find-by-id txn membership-id))

(defn change-role
  "Set a membership's role, recording a role-changed event. Refuses a
  membership of another bank as `:membership/not-found`, an ended one
  as `:membership/invalid-status`, a change the actor's role does not
  allow as `:membership/role-not-granted` (unauthorized), and a demotion
  of the bank's last active owner as `:membership/last-owner`.

  Args:
  - txn: FDB transaction or config.
  - bank-id: the actor's bank.
  - membership-id: the membership to change.
  - role: the new `:role-*` keyword.
  - opts: `:actor`, and optional `:reason` and `:now`.

  Returns the updated Membership map or an anomaly."
  [txn bank-id membership-id role opts]
  (core/change-role txn bank-id membership-id role opts))

(defn remove-member
  "End a membership of the actor's bank, recording a member-removed
  event with `ended-at` and `ended-by`. Refuses as `change-role` does.

  Args:
  - txn: FDB transaction or config.
  - bank-id: the actor's bank.
  - membership-id: the membership to end.
  - opts: `:actor`, and optional `:reason` and `:now`.

  Returns the ended Membership map or an anomaly."
  [txn bank-id membership-id opts]
  (core/remove-member txn bank-id membership-id opts))

(defn leave
  "End the caller's own membership, recording a member-left event with
  the member as actor. Refuses another person's membership as
  `:membership/not-found`, an ended one as `:membership/invalid-status`,
  and the bank's last active owner as `:membership/last-owner`.

  Args:
  - txn: FDB transaction or config.
  - membership-id: the membership to end.
  - opts: `:user-id` of the caller, and optional `:reason` and `:now`.

  Returns the ended Membership map or an anomaly."
  [txn membership-id opts]
  (core/leave txn membership-id opts))

;; ---
;; invitations
;; ---

(defn invite
  "Create a pending invitation, recording an invitation-created event.
  Refuses a role the actor may not grant as
  `:membership/role-not-granted` (unauthorized), an operator's invitation
  without a reason as `:invitation/reason-required`, an address an active
  member holds as `:invitation/already-member`, and an address with a
  pending invitation in the bank as `:invitation/already-exists`. A
  token hash another invitation holds fails the transaction.

  Args:
  - txn: FDB transaction or config.
  - bank-id: the bank the invitation is to.
  - invitation: `:email` as typed and `:role`.
  - opts: `:actor`, `:token-hash` from `new-invitation-token`, and
    optional `:reason` and `:now`.

  Returns the Invitation map or an anomaly."
  [txn bank-id invitation opts]
  (core/invite txn bank-id invitation opts))

(defn accept
  "Accept an invitation as its recipient: writes the membership with the
  invitation's role and `invitation-id`, the invitation accepted with
  `accepted-by-user-id`, and an invitation-accepted event with the
  member as actor. Refuses an invitation the proof does not reach as
  `:invitation/not-found`, one that is not pending as
  `:invitation/invalid-status`, and a person already an active member of
  the bank as `:membership/already-exists`.

  Args:
  - txn: FDB transaction or config.
  - invitation-id: the invitation.
  - proof: as `find-invitation-for-recipient`.
  - opts: `:user-id` of the accepting person, and optional `:reason` and
    `:now`.

  Returns the new Membership map or an anomaly."
  [txn invitation-id proof opts]
  (core/accept txn invitation-id proof opts))

(defn decline
  "Decline an invitation as its recipient, recording an
  invitation-declined event with the member as actor. Refuses as
  `accept` does, except for the membership check.

  Args:
  - txn: FDB transaction or config.
  - invitation-id: the invitation.
  - proof: as `find-invitation-for-recipient`.
  - opts: `:user-id` of the declining person, and optional `:reason` and
    `:now`.

  Returns the declined Invitation map or an anomaly."
  [txn invitation-id proof opts]
  (core/decline txn invitation-id proof opts))

(defn withdraw
  "Withdraw a pending invitation of the actor's bank, recording an
  invitation-withdrawn event. Refuses an unknown invitation as
  `:invitation/not-found`, one that is not pending as
  `:invitation/invalid-status`, and an invitation to a role the actor
  may not grant as `:membership/role-not-granted` (unauthorized).

  Args:
  - txn: FDB transaction or config.
  - bank-id: the actor's bank.
  - invitation-id: the invitation.
  - opts: `:actor`, and optional `:reason` and `:now`.

  Returns the withdrawn Invitation map or an anomaly."
  [txn bank-id invitation-id opts]
  (core/withdraw txn bank-id invitation-id opts))

(defn resend
  "Resend a pending or expired invitation of the actor's bank under a
  fresh token hash and `expires-at`, recording an invitation-resent
  event; the previous token no longer reaches it. Refuses as `withdraw`
  does, allowing an expired invitation.

  Args:
  - txn: FDB transaction or config.
  - bank-id: the actor's bank.
  - invitation-id: the invitation.
  - opts: `:actor`, `:token-hash` from `new-invitation-token`, and
    optional `:reason` and `:now`.

  Returns the pending Invitation map or an anomaly."
  [txn bank-id invitation-id opts]
  (core/resend txn bank-id invitation-id opts))

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

(defn record-bank-created
  "Record a bank-created event for a new bank. When `:membership` is
  given, the event names its user, id and role.

  Args:
  - txn: FDB transaction or config.
  - bank-id: the new bank.
  - opts: `:actor`, and optional `:membership`, `:reason` and `:now`.

  Returns the AccessEvent map or an anomaly."
  [txn bank-id opts]
  (core/record-bank-created txn bank-id opts))

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
