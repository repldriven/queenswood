// The audit log's view-model: every kind of act the API records, the
// console sections that group them in the filter rail, a kind's readable
// label and the tone its dot takes, and the kinds of performer. The page
// itself lives in console.

// Each kind's prefix, the label of the subject it names, and the kinds
// of act that take both.
const SUBJECTS = [
  ["bank", "Bank", ["bank-created", "bank-tier-changed", "bank-status-changed"]],
  ["invitation", "Invitation", ["invitation-created", "invitation-resent", "invitation-accepted", "invitation-declined", "invitation-withdrawn"]],
  ["member", "Member", ["member-role-changed", "member-removed", "member-left"]],
  ["party", "Party", ["party-created", "party-suspended", "party-resumed", "party-closed", "party-merged"]],
  ["verification-session", "Verification session", ["verification-session-opened"]],
  ["cash-account", "Cash account", ["cash-account-opened", "cash-account-suspended", "cash-account-resumed", "cash-account-close-requested", "cash-account-rotation-requested", "cash-account-inbound-payment-simulated"]],
  ["product-version", "Product version", ["product-version-drafted", "product-version-updated", "product-version-published", "product-version-discarded"]],
  ["product", "Product", ["product-created"]],
  ["migration", "Migration", ["migration-created", "migration-approved", "migration-cancelled"]],
  ["migration", "Migration run", ["migration-previewed"]],
  ["internal-payment", "Internal payment", ["internal-payment-submitted"]],
  ["outbound-payment", "Outbound payment", ["outbound-payment-submitted"]],
  ["payee-check", "Payee check", ["payee-check-made"]],
  ["transaction", "Transaction", ["transaction-simulated"]],
  ["webhook-endpoint", "Webhook endpoint", ["webhook-endpoint-registered", "webhook-endpoint-updated", "webhook-endpoint-enabled", "webhook-endpoint-disabled", "webhook-endpoint-removed", "webhook-endpoint-secret-rotated"]],
  ["webhook", "Webhook", ["webhook-test-sent"]],
  ["webhook-delivery", "Webhook delivery", ["webhook-delivery-resent"]],
  ["job", "Job", ["job-schedule-updated", "job-paused", "job-resumed"]],
  ["job-run", "Job run", ["job-run-forced"]],
];

// The rail's groups, in the console's own section order. Policies has
// no audited kinds, so no group.
export const AUDIT_GROUPS = [
  { id: "products", label: "Products", kinds: ["product-created", "product-version-drafted", "product-version-updated", "product-version-published", "product-version-discarded"] },
  { id: "parties", label: "Parties", kinds: ["party-created", "verification-session-opened", "party-suspended", "party-resumed", "party-merged", "party-closed"] },
  { id: "payments", label: "Payments", kinds: ["payee-check-made", "internal-payment-submitted", "outbound-payment-submitted", "cash-account-inbound-payment-simulated", "transaction-simulated"] },
  { id: "accounts", label: "Accounts", kinds: ["cash-account-opened", "cash-account-suspended", "cash-account-resumed", "cash-account-rotation-requested", "cash-account-close-requested"] },
  { id: "migrations", label: "Migrations", kinds: ["migration-previewed", "migration-created", "migration-approved", "migration-cancelled"] },
  { id: "jobs", label: "Jobs", kinds: ["job-schedule-updated", "job-paused", "job-resumed", "job-run-forced"] },
  { id: "people", label: "People", kinds: ["invitation-created", "invitation-resent", "invitation-accepted", "invitation-declined", "invitation-withdrawn", "member-role-changed", "member-removed", "member-left"] },
  { id: "bank", label: "Bank", kinds: ["bank-created", "bank-tier-changed", "bank-status-changed"] },
  { id: "webhooks", label: "Webhooks", kinds: ["webhook-endpoint-registered", "webhook-endpoint-updated", "webhook-endpoint-enabled", "webhook-endpoint-disabled", "webhook-endpoint-secret-rotated", "webhook-endpoint-removed", "webhook-test-sent", "webhook-delivery-resent"] },
];

// Every kind, in rail order, which is the order a request names them in.
export const AUDIT_KINDS = AUDIT_GROUPS.flatMap((g) => g.kinds);

export const AUDIT_GROUP_OF = Object.fromEntries(
  AUDIT_GROUPS.flatMap((g) => g.kinds.map((k) => [k, g.id])),
);

const LABEL = Object.fromEntries(
  SUBJECTS.flatMap(([prefix, label, kinds]) =>
    kinds.map((k) => {
      const verb = k.slice(prefix.length + 1).replace(/-/g, " ");
      return [k, `${label} ${verb}`];
    }),
  ),
);

// "Cash account close requested" for `cash-account-close-requested`; an
// unknown kind as it came.
export function auditKindLabel(kind) {
  return LABEL[kind] ?? kind;
}

// The tone of a kind's dot: `end` for an act that stops or removes,
// `run` for one that sends or runs something, `new` for one that
// creates or approves, and `change` for the rest.
const END = /removed|left|declined|withdrawn|suspended|closed|cancelled|discarded|disabled|paused/;
const RUN = /forced|simulated|previewed|test-sent|resent|submitted|made/;
const NEW = /created|opened|registered|drafted|published|approved|accepted/;

export function auditKindTone(kind) {
  if (END.test(kind)) return "end";
  if (RUN.test(kind)) return "run";
  if (NEW.test(kind)) return "new";
  return "change";
}

// The kinds of performer the API names, and what the console calls each.
// Display names are expected to change: keep them here only.
export const PERFORMER_KINDS = [
  { kind: "member", label: "Member" },
  { kind: "bank", label: "Bank" },
  { kind: "operator", label: "Operator" },
];

export const PERFORMER_LABEL = Object.fromEntries(
  PERFORMER_KINDS.map((p) => [p.kind, p.label]),
);

// The fields a changeset changed, both sides' keys in order.
export function changedFields(changeset) {
  const { before = {}, after = {} } = changeset ?? {};
  return [...new Set([...Object.keys(before ?? {}), ...Object.keys(after ?? {})])];
}

// The first 14 characters of a long id and an ellipsis.
export function shortId(id) {
  return id && id.length > 18 ? `${id.slice(0, 14)}…` : id;
}

const DAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const pad = (n) => String(n).padStart(2, "0");

// The UTC day an instant falls on, as a key two instants share when
// they fall on the same day.
export function utcDayKey(iso) {
  return new Date(iso).toISOString().slice(0, 10);
}

// "Today · Sun 11 Oct", "Yesterday · Sat 10 Oct", then "Fri 9 Oct", by
// UTC day.
export function utcDayLabel(iso, now = Date.now()) {
  const d = new Date(iso);
  const base = `${DAYS[d.getUTCDay()]} ${d.getUTCDate()} ${MONTHS[d.getUTCMonth()]}`;
  const days = Math.round(
    (Date.parse(new Date(now).toISOString().slice(0, 10)) -
      Date.parse(utcDayKey(iso))) /
      864e5,
  );
  if (days === 0) return `Today · ${base}`;
  if (days === 1) return `Yesterday · ${base}`;
  return base;
}

// "07:36", in UTC.
export function utcHhmm(iso) {
  const d = new Date(iso);
  return `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`;
}
