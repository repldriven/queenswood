(ns com.repldriven.queenswood.membership.interface
  "Who may act for a bank, the write side. A Membership joins a User to a
  Bank with a role and is ended, never deleted; an Invitation is accepted
  by a recipient proving the token its email carried or a verified
  email; every write records an AccessEvent in its own FDB transaction,
  reading what its rules need through `membership-query` inside it. A
  create or a resend co-commits an `invitation-created` or
  `invitation-resent` entry to the invitations changelog. The
  `membership/processor` kind answers the access commands with the ids
  of what they wrote; `new-membership`, `record-bank-created` and
  `invite` also join a caller's transaction, as `new-bank` does.

  Every fn takes `txn`, a live transaction or a config map. Writes take
  an optional `:now` in epoch milliseconds, defaulting to the current
  time. An actor is `{:kind :principal-id :role}`, `:kind`
  `:actor-kind-member` or `:actor-kind-operator`, and an operator acts
  as an owner. No invitation returned carries `:token-hash`."
  (:require
    [com.repldriven.queenswood.membership.system]

    [com.repldriven.queenswood.membership.core :as core]))

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

(defn change-role
  "Set a membership's role, recording a role-changed event. Refuses a
  membership of another bank as `:membership/not-found`, an ended one
  as `:membership/invalid-status`, a change the actor's role does not
  allow as `:membership/role-not-granted` (unauthorized), and a demotion
  of the bank's last active owner as `:membership/last-owner`. After the
  same refusals, a role equal to the current one returns the membership
  unchanged and writes neither the membership nor an event.

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
  "Create a pending invitation, recording an invitation-created event and
  changelog entry. Its token hash is its id until an email's token is
  recorded. Refuses a role the actor may not grant as
  `:membership/role-not-granted` (unauthorized), an operator's invitation
  without a reason as `:invitation/reason-required`, an address an active
  member holds as `:invitation/already-member`, and an address with a
  pending invitation in the bank as `:invitation/already-exists`.

  Args:
  - txn: FDB transaction or config.
  - bank-id: the bank the invitation is to.
  - invitation: `:email` as typed and `:role`.
  - opts: `:actor`, and optional `:reason` and `:now`.

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
  - proof: as `membership-query`'s `find-invitation-for-recipient`.
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
  - proof: as `membership-query`'s `find-invitation-for-recipient`.
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
  fresh `expires-at`, recording an invitation-resent event and changelog
  entry. Its token hash returns to its id, so the token of an earlier
  email no longer reaches it. Refuses as `withdraw` does, allowing an
  expired invitation.

  Args:
  - txn: FDB transaction or config.
  - bank-id: the actor's bank.
  - invitation-id: the invitation.
  - opts: `:actor`, and optional `:reason` and `:now`.

  Returns the pending Invitation map or an anomaly."
  [txn bank-id invitation-id opts]
  (core/resend txn bank-id invitation-id opts))

(defn record-invitation-token
  "Record the hash of the token an invitation email carries, replacing
  any earlier one, and record no event. Refuses an unknown invitation as
  `:invitation/not-found`, one that is not pending or has expired as
  `:invitation/invalid-status`, and one whose `expires-at` is not the
  given one, because it was sent again since, as
  `:invitation/superseded`.

  Args:
  - txn: FDB transaction or config.
  - bank-id: the invitation's bank.
  - invitation-id: the invitation.
  - opts: `:expires-at` the email was for, `:token-hash`, and optional
    `:now`.

  Returns the Invitation map or an anomaly."
  [txn bank-id invitation-id opts]
  (core/record-invitation-token txn bank-id invitation-id opts))

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
