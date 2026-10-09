(ns com.repldriven.queenswood.member.interface
  "Who may act for a bank, the write side. A Member is a User's place
  in a Bank, with a role, and is ended, never deleted; an Invitation is accepted
  by a recipient proving the token its email carried or a verified
  email. Each act is recorded on the record it changes, as its `_at`
  and `_by`, and a role change as a MemberRoleChange of its own;
  every write runs in its own FDB transaction, reading what its rules
  need through `member-query` inside it. A create or a resend
  co-commits an `invitation-created` or `invitation-resent` entry to the
  invitations changelog. The `member/processor` kind answers the
  access commands with the ids of what they wrote; `new-member` and
  `invite` also join a caller's transaction, as `new-bank` does.

  Every fn takes `txn`, a live transaction or a config map. Writes take
  an optional `:now` in epoch milliseconds, defaulting to the current
  time. An actor is `{:kind :principal-id :role}`, `:kind`
  `:actor-kind-member` or `:actor-kind-operator`, and an operator acts
  as an owner. No invitation returned carries `:token-hash`."
  (:require
    [com.repldriven.queenswood.member.system]

    [com.repldriven.queenswood.member.core :as core]))

;; ---
;; members
;; ---

(defn new-member
  "Make a User an active Member of a Bank with a Role.

  Args:
  - txn: FDB transaction or config.
  - input: map with `:user-id`, `:bank-id`, `:actor` (who creates
    it, `{:kind :principal-id}`), and optional `:role` (`:role-*`
    keyword; defaults to `:role-owner`).

  Returns the Member map or an anomaly."
  [txn input]
  (core/new-member txn input))

(defn change-role
  "Set a member's role, recording a MemberRoleChange. Refuses a
  member of another bank as `:membership/not-found`, an ended one
  as `:membership/invalid-status`, a change the actor's role does not
  allow as `:membership/role-not-granted` (unauthorized), and a demotion
  of the bank's last active owner as `:membership/last-owner`. After the
  same refusals, a role equal to the current one returns the member
  unchanged and writes neither the member nor a role change.

  Args:
  - txn: FDB transaction or config.
  - bank-id: the actor's bank.
  - member-id: the member to change.
  - role: the new `:role-*` keyword.
  - opts: `:actor`, and optional `:reason` and `:now`.

  Returns the updated Member map or an anomaly."
  [txn bank-id member-id role opts]
  (core/change-role txn bank-id member-id role opts))

(defn remove-member
  "Remove a member of the actor's bank, with `ended-at`,
  `ended-by` and any reason as `ended-reason`. Refuses as `change-role`
  does.

  Args:
  - txn: FDB transaction or config.
  - bank-id: the actor's bank.
  - member-id: the member to remove.
  - opts: `:actor`, and optional `:reason` and `:now`.

  Returns the ended Member map or an anomaly."
  [txn bank-id member-id opts]
  (core/remove-member txn bank-id member-id opts))

(defn leave
  "Leave a bank as the caller, ending their own Member as left, with
  them as `ended-by`. Refuses another person's Member as
  `:membership/not-found`, an ended one as `:membership/invalid-status`,
  and the bank's last active owner as `:membership/last-owner`.

  Args:
  - txn: FDB transaction or config.
  - member-id: the caller's Member.
  - opts: `:user-id` of the caller, and optional `:now`.

  Returns the ended Member map or an anomaly."
  [txn member-id opts]
  (core/leave txn member-id opts))

;; ---
;; invitations
;; ---

(defn invite
  "Create a pending invitation, with the actor as `created-by`, and its
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
  "Accept an invitation as its recipient: writes a Member with the
  invitation's role and `invitation-id`, and the invitation accepted
  with the person as `accepted-by`. Refuses an invitation the proof
  does not reach as `:invitation/not-found`, one that is not pending as
  `:invitation/invalid-status`, and a person already an active member of
  the bank as `:membership/already-exists`.

  Args:
  - txn: FDB transaction or config.
  - invitation-id: the invitation.
  - proof: as `member-query`'s `find-invitation-for-recipient`.
  - opts: `:user-id` of the accepting person, and optional `:now`.

  Returns the new Member map or an anomaly."
  [txn invitation-id proof opts]
  (core/accept txn invitation-id proof opts))

(defn decline
  "Decline an invitation as its recipient, with the person as
  `declined-by`. Refuses as `accept` does, except for the check that
  they are not a member already.

  Args:
  - txn: FDB transaction or config.
  - invitation-id: the invitation.
  - proof: as `member-query`'s `find-invitation-for-recipient`.
  - opts: `:user-id` of the declining person, and optional `:now`.

  Returns the declined Invitation map or an anomaly."
  [txn invitation-id proof opts]
  (core/decline txn invitation-id proof opts))

(defn withdraw
  "Withdraw a pending invitation of the actor's bank, with
  `withdrawn-at`, `withdrawn-by` and any reason as `withdrawn-reason`.
  Refuses an unknown invitation as
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
  fresh `expires-at`, with `resent-at` and `resent-by` the latest
  resend's, and its changelog entry. Its token hash returns to its id, so the token of an earlier
  email no longer reaches it. Refuses as `withdraw` does, allowing an
  expired invitation.

  Args:
  - txn: FDB transaction or config.
  - bank-id: the actor's bank.
  - invitation-id: the invitation.
  - opts: `:actor`, and optional `:now`.

  Returns the pending Invitation map or an anomaly."
  [txn bank-id invitation-id opts]
  (core/resend txn bank-id invitation-id opts))

(defn record-invitation-token
  "Record the hash of the token an invitation email carries, replacing
  any earlier one. Refuses an unknown invitation as
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
