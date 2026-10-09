(ns com.repldriven.queenswood.access-api.interface
  "Members, invitations and the audit log as the banking API
  publishes them: the malli components their bodies are built from,
  the examples those bodies and the rejection bodies carry, and the
  invitation example a bank example embeds."
  (:require
    [com.repldriven.queenswood.access-api.components :as components]
    [com.repldriven.queenswood.access-api.examples :as examples]))

;; ---
;; schemas
;; ---

(def
  ^{:doc
    "Malli registry of the member, invitation and audit schemas,
  keyed by the name each appears under in the document's
  `components/schemas`: `Actor`, `AuditEvent`, `AuditEventId`,
  `AuditEventKind`, `AuditEventList`, `ActorKind`,
  `ChangeRoleRequest`, `CreateInvitationRequest`, `EmailAddress`,
  `Invitation`, `InvitationId`, `InvitationStatus`,
  `InvitationList`, `Member`, `MemberList`, `ReasonRequest`,
  `RecipientInvitation`, `RecipientInvitationList`. Merged into the
  coercion registry in `api.clj`, so `[:ref \"X\"]` resolves them on
  any route."}
  registry
  components/registry)

;; ---
;; examples
;; ---

(def
  ^{:doc
    "Map of example name to example value for the member,
  invitation and audit bodies, feeding the document's
  `components/examples` section."}
  examples
  examples/registry)

(def
  ^{:doc
    "A pending invitation, as the invitation routes return it and as a
  bank example embeds it."}
  Invitation
  examples/Invitation)

(def
  ^{:doc
    "RFC 9457 body for a 409 `:invitation/already-exists` rejection:
  That address has a pending invitation."}
  InvitationAlreadyExists
  examples/InvitationAlreadyExists)

(def
  ^{:doc
    "RFC 9457 body for a 409 `:invitation/already-member` rejection:
  That address belongs to a member already."}
  InvitationAlreadyMember
  examples/InvitationAlreadyMember)

(def
  ^{:doc
    "RFC 9457 body for a 409 `:invitation/invalid-status` rejection:
  Invitation is not in a state that allows this."}
  InvitationInvalidStatus
  examples/InvitationInvalidStatus)

(def
  ^{:doc
    "RFC 9457 body for a 404 `:invitation/not-found` rejection:
  Invitation not found."}
  InvitationNotFound
  examples/InvitationNotFound)

(def
  ^{:doc
    "RFC 9457 body for a 409 `:member/already-exists` rejection:
  Already a member of this bank."}
  MemberAlreadyExists
  examples/MemberAlreadyExists)

(def
  ^{:doc
    "RFC 9457 body for a 409 `:member/invalid-status` rejection:
  Member is not in a state that allows this."}
  MemberInvalidStatus
  examples/MemberInvalidStatus)

(def
  ^{:doc
    "RFC 9457 body for a 409 `:member/last-owner` rejection: Make
  someone else an owner first."}
  MemberLastOwner
  examples/MemberLastOwner)

(def
  ^{:doc
    "RFC 9457 body for a 404 `:member/not-found` rejection:
  Member not found."}
  MemberNotFound
  examples/MemberNotFound)

(def
  ^{:doc
    "RFC 9457 body for a 422 `:invitation/reason-required` rejection:
  An operator's invitation needs a reason."}
  ReasonRequired
  examples/ReasonRequired)

(def
  ^{:doc
    "RFC 9457 body for a 403 `:member/role-not-granted` rejection:
  Your role does not allow this."}
  RoleNotGranted
  examples/RoleNotGranted)
