// Thin fetch wrapper that attaches the Keycloak access token. All
// console traffic terminates at bank-api (`/v1/*`); cross-origin is
// avoided because the same nginx pod that serves this SPA also
// proxies `/v1/*` to bank-api, and in Vite dev the proxy in
// vite.config.js does the same locally.
import { fresh_token } from "./auth.mjs";

// The bank every bank-scoped call acts on, sent as `Bank-Id`. Without it
// the API resolves the person's only membership, and refuses a person
// who holds several.
let bank_id = null;

export function set_bank_id(id) {
  bank_id = id ?? null;
}

async function request(path, opts = {}) {
  const token = await fresh_token();
  const headers = {
    "Content-Type": "application/json",
    ...(bank_id ? { "Bank-Id": bank_id } : {}),
    ...(opts.headers ?? {}),
    Authorization: `Bearer ${token}`,
  };
  const res = await fetch(path, { ...opts, headers });
  const body =
    res.status === 204 ? null : await res.json().catch(() => null);
  return { status: res.status, body };
}

// Mutations get an `Idempotency-Key` so a retried POST/PUT/DELETE
// doesn't double-apply on the server side. Same convention bank-app
// uses; bank-api keys against this header to dedupe.
function mutate(path, opts = {}) {
  return request(path, {
    ...opts,
    headers: {
      "Idempotency-Key": crypto.randomUUID(),
      ...(opts.headers ?? {}),
    },
  });
}

export function get_me() {
  return request("/v1/me");
}

// Look up a company on the register of record (used during onboarding,
// before the user has a bank). Returns the profile or a 404.
export function lookup_company(number) {
  return request(`/v1/companies/${number}`);
}

// First-sign-in onboarding: binds a new bank to the confirmed legal
// entity. `{ companyNumber, bankName }`.
export function onboard({ companyNumber, bankName }) {
  return mutate("/v1/onboarding/me", {
    method: "POST",
    body: JSON.stringify({
      "company-number": companyNumber,
      "bank-name": bankName,
    }),
  });
}

// ─── Cash-account products (org-scoped) ───
//
// Ported from bank-app/src/lib/api.mjs. bank-app uses an org-selector
// (operator acting on behalf of any org) and so wraps each call in
// `org_request` to mint a per-org service-account JWT. The console
// user has a single membership; their own user JWT already carries
// the right org context, so we just use `request()` and let bank-api
// resolve the org from the token's claims.

function product_request_body(data) {
  // Mirrors bank-app's `product_request_body` — the bank-api accepts
  // the same shape regardless of caller. `interest-rate-bps` is
  // optional (omitted for products with no interest).
  const body = {
    name: data.name,
    "template-id": data["template-id"],
    currency: data.currency,
  };
  if (data["interest-rate-bps"]) {
    body["interest-rate-bps"] = data["interest-rate-bps"];
  }
  if (data["opening-reward"]) {
    body["opening-reward"] = data["opening-reward"];
  }
  if (data["effective-from"]) {
    body["effective-from"] = data["effective-from"];
  }
  if (data["effective-to"]) {
    body["effective-to"] = data["effective-to"];
  }
  return body;
}

export function list_cash_account_product_templates() {
  return request("/v1/cash-account-product-templates");
}

export function list_cash_account_products() {
  return request("/v1/cash-account-products");
}

export function create_cash_account_product(data) {
  return mutate("/v1/cash-account-products", {
    method: "POST",
    body: JSON.stringify(product_request_body(data)),
  });
}

export function open_cash_account_product_draft(product_id, data) {
  return mutate(`/v1/cash-account-products/${product_id}/versions`, {
    method: "POST",
    body: JSON.stringify(product_request_body(data)),
  });
}

export function update_cash_account_product_draft(product_id, version_id, data) {
  return mutate(
    `/v1/cash-account-products/${product_id}/versions/${version_id}`,
    {
      method: "PUT",
      body: JSON.stringify(product_request_body(data)),
    },
  );
}

export function discard_cash_account_product_draft(product_id, version_id) {
  return mutate(
    `/v1/cash-account-products/${product_id}/versions/${version_id}`,
    { method: "DELETE" },
  );
}

export function publish_cash_account_product(product_id, version_id) {
  return mutate(
    `/v1/cash-account-products/${product_id}/versions/${version_id}/publish`,
    { method: "POST" },
  );
}

// ─── Cash-account migrations (org-scoped) ───
//
// A migration is a statement of intent: authoring one moves nothing,
// and neither does approving it — only the scheduler's migration task
// moves accounts. So the surface here is author, preview, and read.
//
// A preview is a POST because it appends a dry run to the migration's
// run history; the GET on the same path lists every run, previews and
// commits alike, which is what the Runs table shows.
//
// Approving moves nothing — it puts the migration on the scheduler's
// work list for its due date. There is still no edit route, so the
// console renders Edit disabled.

export function list_cash_account_migrations() {
  return request("/v1/cash-account-migrations");
}

export function get_cash_account_migration(migration_id) {
  return request(`/v1/cash-account-migrations/${migration_id}`);
}

export function create_cash_account_migration(data) {
  return mutate("/v1/cash-account-migrations", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export function approve_cash_account_migration(migration_id) {
  return mutate(`/v1/cash-account-migrations/${migration_id}/approve`, {
    method: "POST",
  });
}

export function cancel_cash_account_migration(migration_id) {
  return mutate(`/v1/cash-account-migrations/${migration_id}/cancel`, {
    method: "POST",
  });
}

export function list_cash_account_migration_runs(migration_id) {
  return request(`/v1/cash-account-migrations/${migration_id}/previews`);
}

export function preview_cash_account_migration(migration_id) {
  return mutate(`/v1/cash-account-migrations/${migration_id}/previews`, {
    method: "POST",
  });
}

// The per-account verdicts one run recorded. Unpaginated and
// unfiltered server-side today, so the outcomes panel filters and pages
// in the browser; see the note on the Migrations screen.
export function list_cash_account_migration_run_accounts(migration_id, run_id) {
  return request(
    `/v1/cash-account-migrations/${migration_id}/previews/${run_id}/accounts`,
  );
}

// ─── Ledger accounts (org-scoped, read-only) ───
//
// The bank's chart of accounts (GL accounts). The list endpoint returns
// the accounts without balances; each account's constituent balances —
// keyed by (balance-type, balance-status) with credit/debit minor units —
// come from a separate per-account endpoint. The Ledger page fetches the
// list, then the balances for each account, to render the tree-table.

export function list_ledger_accounts() {
  return request("/v1/ledger-accounts");
}

export function list_ledger_account_balances(account_id) {
  return request(`/v1/ledger-accounts/${account_id}/balances`);
}

// ─── Policies (org-scoped, read-only) ───
//
// The policies effective for the caller's own bank — the always-on
// platform tier plus any bound to the bank. `/v1/policies` proper is
// admin-only (the ops console); this `/me` variant is scoped to the
// tenant via the bank-id on their token. The wire shape is the nested
// protojure policy; policy-adapter.mjs flattens it for the matrix.

export function list_my_policies() {
  return request("/v1/me/policies");
}

// The resolved effective decision for my bank: capabilities/limits
// collapsed (deny-wins, most-restrictive) to one survivor per scope,
// each carrying its origin policy. policy-adapter.mjs flattens it.
export function list_my_effective_policies() {
  return request("/v1/me/effective-policies");
}

// ─── Jobs (scheduler, org-scoped, read-only) ───
//
// The bank's scheduled jobs — preset task pipelines run on a cadence
// (daily interest accrual + capitalisation today). The list endpoint
// returns the jobs with their schedule (periodicity, run-time-minutes,
// enabled) and last/next-run timestamps, but not the run outcome; the
// status an operator watches — succeeded/failed/running — comes from a
// job's runs (newest-first), so the Jobs page fetches the latest run
// per job to drive its badge. Read-only: force-start and schedule
// edits aren't surfaced yet.

export function list_jobs() {
  return request("/v1/jobs");
}

export function list_job_runs(job_id) {
  return request(`/v1/jobs/${job_id}/runs`);
}

// Force-start a job now. Runs the pipeline synchronously server-side and
// returns the completed run; idempotent (the daily-limit policy guards
// double-accrual).
export function force_start_job(job_id) {
  return mutate(`/v1/jobs/${job_id}/runs`, { method: "POST" });
}

// Edit a job's schedule — any of periodicity / run-time-minutes /
// enabled (omitted fields keep their current value). Toggling enabled is
// the pause/resume control.
export function update_job_schedule(job_id, body) {
  return mutate(`/v1/jobs/${job_id}/schedule`, {
    method: "PUT",
    body: JSON.stringify(body),
  });
}

// ─── Parties (org-scoped) ───
//
// bank-api `Party` shape carries summary fields only (party-id, type,
// display-name, status, created-at, updated-at). The richer record
// the Parties drawer wants — given/family names, dob, address,
// national identifier — is what `create_party` accepts but not what
// `list_parties` returns. The drawer falls back to "—" for the
// per-field detail until the read endpoint surfaces it.

export function list_parties() {
  return request("/v1/parties");
}

// Fetch a party. Pass `embed` (e.g. ["person-identification", "address",
// "national-identifier"]) to opt sub-records into the detail response;
// without it the GET returns just the summary.
export function get_party(party_id, { embed } = {}) {
  const q =
    embed && embed.length
      ? "?" + embed.map((e) => `embed[${e}]=true`).join("&")
      : "";
  return request(`/v1/parties/${party_id}${q}`);
}

export function create_party(data) {
  return mutate("/v1/parties", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

// ─── Cash accounts (org-scoped) ───
//
// Open an account against a party + published product (returns
// `account-status: "opening"`; poll the GET until `"opened"`, at which
// point the record carries its assigned SCAN `bban`). The Accounts page
// lists accounts (with `embed[balances]` so the rail can show each
// available balance) and fetches the selected account's transactions.

export function list_cash_accounts({ embed, after } = {}) {
  const params = [];
  if (embed?.length) params.push(...embed.map((e) => `embed[${e}]=true`));
  if (after) params.push(`page[after]=${encodeURIComponent(after)}`);
  const q = params.length ? "?" + params.join("&") : "";
  return request(`/v1/cash-accounts${q}`);
}

export function get_cash_account(account_id) {
  return request(`/v1/cash-accounts/${account_id}`);
}

export function get_cash_account_balances(account_id) {
  return request(`/v1/cash-accounts/${account_id}/balances`);
}

export function get_cash_account_transactions(account_id) {
  return request(`/v1/cash-accounts/${account_id}/transactions`);
}

export function open_cash_account(data) {
  return mutate("/v1/cash-accounts", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

// ─── Payments (org-scoped) ───
//
// Internal transfers move money between two accounts at this bank;
// outbound payments leave via the scheme adapter. ClearBank-sim magic
// values force outcomes: creditor-name "6a41a29eafcf455493" → held then
// declined; a creditor-bban whose sort code (first 6) matches the bank
// + a non-existent account number returns as an unmatched inbound that
// parks in 2500 suspense.

export function submit_internal_payment(data) {
  return mutate("/v1/payments/internal", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export function submit_outbound_payment(data) {
  return mutate("/v1/payments/outbound", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export function get_outbound_payment(payment_id) {
  return request(`/v1/payments/outbound/${payment_id}`);
}

// ─── Simulate (sandbox) ───
//
// Drives money onto the books the way the scheme would. The
// inbound-transfer route is org-tier (a bank can fund its own bank);
// accrue/capitalize remain admin-only, so the sandbox runs interest via
// the bank-tier daily-interest job force-start instead.

export function list_rewards(account_id) {
  return request(`/v1/rewards?account-id=${encodeURIComponent(account_id)}`);
}

export function check_payee(data) {
  return mutate("/v1/payee-checks", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export function simulate_inbound_transfer(bank_id, data) {
  return mutate(`/v1/simulate/banks/${bank_id}/inbound-transfer`, {
    method: "POST",
    body: JSON.stringify(data),
  });
}

// ─── People and access (org-scoped) ───
//
// Every level reads the members, invitations and history; changing them
// needs `org:admin`, and the rules that depend on the target (an admin
// never acts on an owner, a bank is never ownerless) refuse in the
// domain. Member and invitation lists are unpaged; the history pages by
// cursor, and its `links.next` is a ready-made `/v1/...` path. Creating
// and resending an invitation answer the invitation, and email its link.

function with_reason(reason) {
  return JSON.stringify(reason ? { reason } : {});
}

export function list_members() {
  return request("/v1/members");
}

export function change_member_role(membership_id, { role, reason }) {
  return mutate(`/v1/members/${membership_id}/change-role`, {
    method: "POST",
    body: JSON.stringify(reason ? { role, reason } : { role }),
  });
}

export function remove_member(membership_id, { reason } = {}) {
  return mutate(`/v1/members/${membership_id}/remove`, {
    method: "POST",
    body: with_reason(reason),
  });
}

export function leave_membership(membership_id) {
  return mutate(`/v1/me/memberships/${membership_id}/leave`, {
    method: "POST",
  });
}

export function list_invitations() {
  return request("/v1/invitations");
}

export function create_invitation({ email, role, reason }) {
  return mutate("/v1/invitations", {
    method: "POST",
    body: JSON.stringify(reason ? { email, role, reason } : { email, role }),
  });
}

export function withdraw_invitation(invitation_id, { reason } = {}) {
  return mutate(`/v1/invitations/${invitation_id}/withdraw`, {
    method: "POST",
    body: with_reason(reason),
  });
}

export function resend_invitation(invitation_id, { reason } = {}) {
  return mutate(`/v1/invitations/${invitation_id}/resend`, {
    method: "POST",
    body: with_reason(reason),
  });
}

// An invitation as the person it was sent to sees it. The link's token
// travels in `Invitation-Token`; without one, the signed-in person's
// verified email must be the invited address.
function invitation_token(token) {
  return token ? { "Invitation-Token": token } : {};
}

export function get_my_invitation(invitation_id, token) {
  return request(`/v1/me/invitations/${invitation_id}`, {
    headers: invitation_token(token),
  });
}

export function accept_invitation(invitation_id, token) {
  return mutate(`/v1/me/invitations/${invitation_id}/accept`, {
    method: "POST",
    headers: invitation_token(token),
  });
}

export function decline_invitation(invitation_id, token) {
  return mutate(`/v1/me/invitations/${invitation_id}/decline`, {
    method: "POST",
    headers: invitation_token(token),
  });
}

// `next` is the previous page's `links.next`; without it, the newest page.
export function list_access_events({ next, size = 8 } = {}) {
  return request(next ?? `/v1/access-events?page[size]=${size}`);
}
