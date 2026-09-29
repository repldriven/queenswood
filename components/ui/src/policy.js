// policy.js — the policy domain model + grouping for the matrix view.
//
// A Policy (proto: Policy) is first-class data:
//
//   {
//     policyId, name, description, enabled,
//     category: "standard" | "restricted" | "emergency",
//     capabilities: Capability[],
//     limits: Limit[],
//     labels: { [k]: v },
//     createdAt, updatedAt,
//   }
//
// The proto packs the domain into a `oneof kind` on Capability/Limit and
// the verb into a per-domain action enum. Flatten that into a uniform
// view-model before rendering (see the console policy-adapter):
//
//   Capability = {
//     effect: "allow" | "deny",        // proto Effect enum
//     domain: <DomainKey>,             // which oneof arm was set
//     action: "create" | "open" | …,   // the per-domain action enum, lowercased
//     reason?: string,
//     filters: { key, value }[],       // flattened from the *CapabilityFilter messages
//   }
//
//   Limit = {
//     domain: <DomainKey>,
//     bound: Bound,                    // see bounds.js
//     reason?: string,
//     allow?: "improving",             // proto LimitAllow (curative permit) or null
//     filters: { key, value }[],
//   }
//
// Keep the flatten in the API/adapter layer; the components only ever see
// this shape.

// The domains, in display order, each tagged with the section it
// belongs to in the matrix. "Core Banking" folds the ledger + accounts
// domains together; Payments / Identity / Platform are their own sections.
export const DOMAINS = {
  balance:              { label: "Balance",              group: "Core Banking" },
  transaction:          { label: "Transaction",          group: "Core Banking" },
  interest:             { label: "Interest",             group: "Core Banking" },
  ledger_account:       { label: "Ledger Account",       group: "Core Banking" },
  cash_account:         { label: "Cash Account",         group: "Core Banking" },
  cash_account_product: { label: "Cash Account Product", group: "Core Banking" },
  cash_account_migration: { label: "Cash Account Migration", group: "Core Banking" },
  outbound_payment:     { label: "Outbound Payment",     group: "Payments" },
  inbound_payment:      { label: "Inbound Payment",      group: "Payments" },
  internal_payment:     { label: "Internal Payment",     group: "Payments" },
  payee_check:          { label: "Payee Check",          group: "Payments" },
  party:                { label: "Party",                group: "Identity" },
  idv:                  { label: "IDV",                  group: "Identity" },
  bank:                 { label: "Bank",                 group: "Platform" },
  policy:               { label: "Policy",               group: "Platform" },
};

export const DOMAIN_ORDER = Object.keys(DOMAINS);
export const GROUP_ORDER = ["Core Banking", "Payments", "Identity", "Platform"];

// Category → <Badge> tone. Restricted reads cautionary (amber/gold),
// emergency reads alarming (red), standard reads settled (pine).
export const CATEGORY_TONE = {
  standard: "standard",
  restricted: "restricted",
  emergency: "emergency",
};

// A domain's capabilities with each action's together, actions in the
// order the policy first names them, and within an action the allow
// ahead of the denies that carve exceptions out of it.
function byAction(caps) {
  const first = new Map();
  for (const c of caps) if (!first.has(c.action)) first.set(c.action, first.size);
  const rank = (c) => (c.effect === "allow" ? 0 : 1);
  return [...caps].sort(
    (a, b) => first.get(a.action) - first.get(b.action) || rank(a) - rank(b),
  );
}

// Collapse a policy's flat capabilities + limits into a per-domain map:
//   { [domainKey]: { caps: Capability[], lims: Limit[] } }
// Domains the policy never mentions are simply absent (they "inherit the
// platform default"); the matrix decides whether to show them.
export function groupByDomain(policy) {
  const map = {};
  const slot = (d) => (map[d] ||= { caps: [], lims: [] });
  for (const c of policy.capabilities ?? []) slot(c.domain).caps.push(c);
  for (const l of policy.limits ?? []) slot(l.domain).lims.push(l);
  for (const d of Object.values(map)) d.caps = byAction(d.caps);
  return map;
}

// Rows for one matrix section: the domains in `group`, each with its
// capabilities + limits and whether the policy governs it at all.
export function sectionRows(group, byDomain) {
  return DOMAIN_ORDER.filter((d) => DOMAINS[d].group === group).map((d) => ({
    domain: d,
    label: DOMAINS[d].label,
    data: byDomain[d] ?? null,
    governed: Boolean(byDomain[d]),
  }));
}
