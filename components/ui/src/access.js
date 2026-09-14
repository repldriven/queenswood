// People and access view-model helpers — the role ladder, who may act on
// whom, the history's event vocabulary and the date formatting the People
// screen shares between its tables and drawer. The screen itself lives in
// console. The server applies every rule here again inside the
// transaction; these only decide what the screen offers.

export const ROLES = ["viewer", "developer", "admin", "owner"];

export const ROLE_RANK = { viewer: 0, developer: 1, admin: 2, owner: 3 };

export const ROLE_BLURB = {
  viewer: "Reads all of the organisation’s data.",
  developer:
    "Also writes customers, accounts, payments, products, webhooks and jobs.",
  admin:
    "Also manages people: invite, change roles, remove, withdraw and resend.",
  owner: "Everything. Only an owner grants, changes or removes the owner role.",
};

// Wire enums arrive short ("owner") but tolerate the namespaced spelling.
export function accessEnum(x) {
  return String(x ?? "")
    .replace(/^:/, "")
    .replace(/^(role|invitation-status|access-event-kind|actor-kind)-/, "");
}

export function grantableBy(role) {
  if (role === "owner") return [...ROLES];
  if (role === "admin") return ["viewer", "developer", "admin"];
  return [];
}

export function canManagePeople(role) {
  return (ROLE_RANK[role] ?? -1) >= ROLE_RANK.admin;
}

export function canActOn(actorRole, targetRole) {
  return canManagePeople(actorRole) && grantableBy(actorRole).includes(targetRole);
}

// Never ownerless: the bank's only active owner can be neither demoted,
// removed nor let go.
export function isLastOwner(member, members) {
  return (
    accessEnum(member?.role) === "owner" &&
    members.filter((m) => accessEnum(m.role) === "owner").length === 1
  );
}

export const EVENT_LABEL = {
  "bank-created": "organisation created",
  "invitation-created": "invitation created",
  "invitation-resent": "invitation resent",
  "invitation-accepted": "invitation accepted",
  "invitation-declined": "invitation declined",
  "invitation-withdrawn": "invitation withdrawn",
  "role-changed": "role changed",
  "member-removed": "member removed",
  "member-left": "member left",
};

// The dot's family on the history's event column.
export const EVENT_FAMILY = {
  "bank-created": "created",
  "invitation-created": "invite",
  "invitation-resent": "invite",
  "invitation-accepted": "accept",
  "invitation-declined": "end",
  "invitation-withdrawn": "end",
  "role-changed": "role",
  "member-removed": "end",
  "member-left": "end",
};

const MONTHS = [
  "Jan", "Feb", "Mar", "Apr", "May", "Jun",
  "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
];

const pad = (n) => String(n).padStart(2, "0");

// "14 Sep 2026", in UTC.
export function fmtUtcDate(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  return `${pad(d.getUTCDate())} ${MONTHS[d.getUTCMonth()]} ${d.getUTCFullYear()}`;
}

// "14 Sep 2026 09:12", in UTC.
export function fmtUtcDateTime(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  return `${fmtUtcDate(iso)} ${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`;
}

// "in 5 days", "2 hours ago", "in less than an hour".
export function fmtFromNow(iso, now = Date.now()) {
  const ms = new Date(iso).getTime() - now;
  const abs = Math.abs(ms);
  const h = Math.round(abs / 36e5);
  const d = Math.round(abs / 864e5);
  const body =
    abs < 36e5
      ? "less than an hour"
      : h < 36
        ? `${h} hour${h === 1 ? "" : "s"}`
        : `${d} day${d === 1 ? "" : "s"}`;
  return ms < 0 ? `${body} ago` : `in ${body}`;
}

// "soon" inside 36 hours of expiring, "gone" once past, else "".
export function expiryUrgency(iso, now = Date.now()) {
  const ms = new Date(iso).getTime() - now;
  if (ms <= 0) return "gone";
  return ms < 36 * 36e5 ? "soon" : "";
}
