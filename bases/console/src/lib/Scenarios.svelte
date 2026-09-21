<script>
  /* Scenarios — a customer-facing SANDBOX that proves the platform
     works by running real, HTTP-driven scenarios live. Eight scenes tell
     one continuous story — a bank opening its doors — fired manually in
     order. State is CUMULATIVE: each scene builds on the last, and the
     bank-state band accumulates the evidence as scenes complete.

     This runner fires REAL calls against bank-api as the signed-in bank
     (org tier): products, parties (poll until IDV flips), accounts (a
     current and a savings each for Arthur and Ford), funding the bank's
     own funds via the now-org-tier simulate inbound-transfer and the
     customers from there by internal payment, internal transfers (current
     → savings), a deliberately overdrawing transfer the non-negative-
     balance policy refuses, an outbound Faster Payment over the scheme
     (Ford pays Arthur for beer and nuts), and interest via the bank-tier
     daily-interest job force-start. Real ids discovered during a run are threaded
     through `ctx` and persisted, so later scenes reference the entities
     earlier scenes actually created. Re-running writes real server state;
     creation steps reuse an existing entity where they can so a re-run
     doesn't hard-fail. */

  import { fly } from "svelte/transition";
  import {
    PageHeader,
    ProgressSpine,
    BankStateBand,
    SceneCard,
    RawCalls,
    TaskPipeline,
    Button,
    Badge,
  } from "@queenswood/ui";
  import * as api from "./api.mjs";

  let { user, memberships = [] } = $props();

  const bankName = $derived(memberships?.[0]?.["bank-name"]);
  const kicker = $derived(bankName ? `${bankName} · Sandbox` : "Sandbox");

  // The bank whose books we move. From the membership prop, or /v1/me
  // if the prop didn't carry it (older bank-api).
  let bankId = $state();
  $effect(() => {
    if (bankId) return;
    const m = memberships?.[0]?.["bank-id"];
    if (m) {
      bankId = m;
      return;
    }
    api.get_me().then((r) => {
      bankId = r.body?.memberships?.[0]?.["bank-id"];
    });
  });

  // ── fixtures ──────────────────────────────────────────────────────
  const TODAY = new Date().toISOString().slice(0, 10);
  // Platform-seeded product templates (current / savings).
  const TPL_CURRENT = "tpl.00000000000000000000000001";
  const TPL_SAVINGS = "tpl.00000000000000000000000002";
  // Amounts in pence.
  const FUND_EACH = 100000; // £1,000 funded to each current account
  const ARTHUR_SAVE = 75000; // £750 Arthur current → savings
  const FORD_SAVE = 35000; // £350 Ford current → savings
  const OVERDRAW = 50000; // £500 Arthur tries to move with only £250 left
  const FORD_PAYS = 2000; // £20.00 Ford → Arthur, outbound FPS (beer and nuts)
  const INTEREST = 110; // £0.75 + £0.35 capitalised across both savers

  const ADDRESS = {
    "building-number": "155",
    street: "Country Lane",
    town: "Cottington",
    postcode: "CT12 4XY",
    country: "GBR",
  };
  // Zaphod's middle name carries the Onfido-sim reject trigger (the
  // applicant first name must contain "reject"); his display name stays
  // clean, so the Parties list just shows "Zaphod Beeblebrox".
  const PARTY = {
    arthur: {
      type: "person", "display-name": "Arthur Dent",
      "given-name": "Arthur", "family-name": "Dent",
      "date-of-birth": "1950-07-27", nationality: "GB", address: ADDRESS,
      "national-identifier": { type: "national-insurance", value: "TN555101A", "issuing-country": "GB" },
    },
    ford: {
      type: "person", "display-name": "Ford Prefect",
      "given-name": "Ford", "family-name": "Prefect",
      "date-of-birth": "1948-04-01", nationality: "GB", address: ADDRESS,
      "national-identifier": { type: "national-insurance", value: "TN555102B", "issuing-country": "GB" },
    },
    zaphod: {
      type: "person", "display-name": "Zaphod Beeblebrox",
      "given-name": "Zaphod", "middle-names": "Reject", "family-name": "Beeblebrox",
      "date-of-birth": "1947-02-02", nationality: "GB", address: ADDRESS,
      "national-identifier": { type: "national-insurance", value: "TN555103C", "issuing-country": "GB" },
    },
  };

  // The console views each scene "pays off" in.
  const VIEWS = {
    products: { label: "Products", href: "#/products" },
    parties: { label: "Parties", href: "#/parties" },
    accounts: { label: "Accounts", href: "#/accounts" },
    ledger: { label: "Ledger", href: "#/ledger" },
    policies: { label: "Policies", href: "#/policies" },
    jobs: { label: "Jobs", href: "#/jobs" },
  };

  const SCENES = [
    {
      id: "s1", num: "01", title: "Stock the shelves", view: "products",
      story:
        "Draft a current account at 0 bps and a savings product at 3.65%, publish them, then revise savings to a new version — the old one auto-archives.",
      backing: ["create-product-happy", "publish-draft", "open-new-draft-after-publish"],
      steps: [
        { name: "Draft current account (0 bps)", raw: [{ method: "POST", path: "/v1/cash-account-products", tag: "request" }] },
        { name: "Publish it", raw: [{ method: "POST", path: "/v1/cash-account-products/{id}/versions/{v}/publish", tag: "request" }] },
        { name: "Draft savings @ 3.65%", raw: [{ method: "POST", path: "/v1/cash-account-products", tag: "request" }] },
        { name: "Publish it", raw: [{ method: "POST", path: "/v1/cash-account-products/{id}/versions/{v}/publish", tag: "request" }] },
        { name: "Revise savings → v2", raw: [{ method: "POST", path: "/v1/cash-account-products/{id}/versions", tag: "request" }, { method: "POST", path: "/v1/cash-account-products/{id}/versions/2/publish", tag: "request" }] },
        { name: "Prior version auto-archives", tone: "exception", raw: [{ method: "GET", path: "/v1/cash-account-products/{id}", tag: "poll" }] },
      ],
    },
    {
      id: "s2", num: "02", title: "Identity decides the account", view: "parties",
      story:
        "Onboard Arthur Dent and Ford Prefect — their identity checks clear and both go active. Onboard Zaphod Beeblebrox, whose check is rejected, and the platform denies him an account.",
      backing: ["parties/create-party-happy", "idv-reject-marks-party-rejected"],
      steps: [
        { name: "Onboard Arthur Dent", raw: [{ method: "POST", path: "/v1/parties", tag: "request" }] },
        { name: "Identity check clears → active", raw: [{ method: "GET", path: "/v1/parties/{id}", tag: "poll" }] },
        { name: "Onboard Ford Prefect", raw: [{ method: "POST", path: "/v1/parties", tag: "request" }] },
        { name: "Identity check clears → active", raw: [{ method: "GET", path: "/v1/parties/{id}", tag: "poll" }] },
        { name: "Onboard Zaphod Beeblebrox", raw: [{ method: "POST", path: "/v1/parties", tag: "request" }] },
        { name: "Identity check rejected → rejected", tone: "exception", raw: [{ method: "GET", path: "/v1/parties/{id}", tag: "poll" }] },
      ],
    },
    {
      id: "s3", num: "03", title: "Open the accounts", view: "accounts",
      story:
        "Open a current and a savings account for both Arthur and Ford — four accounts in all. Each opens, then settles to opened with its own sort-code and account number.",
      backing: ["cash-accounts/create-account-happy"],
      steps: [
        { name: "Open Arthur's current account", raw: [{ method: "POST", path: "/v1/cash-accounts", tag: "request" }] },
        { name: "Open Arthur's savings account", raw: [{ method: "POST", path: "/v1/cash-accounts", tag: "request" }] },
        { name: "Open Ford's current account", raw: [{ method: "POST", path: "/v1/cash-accounts", tag: "request" }] },
        { name: "Open Ford's savings account", raw: [{ method: "POST", path: "/v1/cash-accounts", tag: "request" }] },
        { name: "All four settle to opened", raw: [{ method: "GET", path: "/v1/cash-accounts/{id}", tag: "poll" }] },
      ],
    },
    {
      id: "s4", num: "04", title: "Money in, double-entry out", view: "ledger",
      story:
        "£2,000 arrives from outside into the bank's own funds, then the bank pays Arthur and Ford £1,000 each by internal payment. The books move — 1100 cash-at-correspondent debited, own funds credited, then customer balances — debits equal credits, to the penny.",
      backing: ["simulate/inbound-transfer"],
      steps: [
        { name: "Fund the bank · £2,000 into own funds", raw: [{ method: "POST", path: "/v1/simulate/banks/{bank-id}/inbound-transfer", tag: "request" }] },
        { name: "Pay Arthur and Ford £1,000 each from own funds", raw: [{ method: "POST", path: "/v1/payments/internal", tag: "request" }, { method: "POST", path: "/v1/payments/internal", tag: "request" }] },
        { name: "Await settlement", raw: [{ method: "GET", path: "/v1/cash-accounts/{id}/balances", tag: "poll" }] },
        { name: "Trial balance ties", raw: [{ method: "GET", path: "/v1/ledger-accounts", tag: "request" }] },
      ],
    },
    {
      id: "s5", num: "05", title: "Customers save", view: "accounts",
      story:
        "Arthur moves £750 from current to savings; Ford moves £350. Internal transfers settle instantly and the savings balances climb — money moving between a customer's own accounts.",
      backing: ["intra-bank-internal-transfer"],
      steps: [
        { name: "Arthur saves £750 · current → savings", raw: [{ method: "POST", path: "/v1/payments/internal", tag: "request" }] },
        { name: "Ford saves £350 · current → savings", raw: [{ method: "POST", path: "/v1/payments/internal", tag: "request" }] },
        { name: "Savings balances climb", raw: [{ method: "GET", path: "/v1/cash-accounts/{id}/balances", tag: "poll" }] },
      ],
    },
    {
      id: "s6", num: "06", title: "Policy holds the line", view: "policies",
      story:
        "Arthur tries to move £500 to savings — but his current only holds £250. The platform's non-negative-balance policy refuses the transfer before any money moves. Nothing posts; the books are untouched.",
      backing: ["curative-inbound-when-in-breach"],
      steps: [
        { name: "Arthur sends £500 · current → savings", raw: [{ method: "POST", path: "/v1/payments/internal", tag: "request" }] },
        { name: "Refused · available must stay ≥ £0", tone: "exception", raw: [{ method: "GET", path: "/v1/me/effective-policies", tag: "request" }] },
        { name: "Nothing posted · current still £250", raw: [{ method: "GET", path: "/v1/cash-accounts/{id}/balances", tag: "poll" }] },
      ],
    },
    // Outbound must follow the policy scene: s6 asserts Arthur sits at
    // exactly £250, and this credits him £20. Scene ids are stable keys —
    // display order is the array order, so s8 sits before s7 here.
    {
      id: "s8", num: "07", title: "Friends settle up", view: "accounts",
      story:
        "Ford pays Arthur £20.00 for beer and nuts — an outbound Faster Payment to Arthur's account number. It leaves Ford's current through the ClearBank scheme path and lands back in Arthur's: money out one door, in another.",
      backing: ["e2e/full-happy-path", "payments/outbound-held-then-declined"],
      steps: [
        { name: "Ford pays Arthur £20.00 · outbound FPS", raw: [{ method: "POST", path: "/v1/payments/outbound", tag: "request" }] },
        { name: "Scheme settles → completed", raw: [{ method: "GET", path: "/v1/payments/outbound/{id}", tag: "poll" }] },
        { name: "Ford −£20 · Arthur +£20", raw: [{ method: "GET", path: "/v1/cash-accounts/{id}/balances", tag: "poll" }] },
      ],
    },
    {
      id: "s7", num: "08", title: "The bank runs itself overnight", view: "jobs",
      story:
        "Force-start the seeded daily-interest job. The accrue → capitalise pipeline gives each funded savings account its statement line and posts the bank's own entry once for the run — and it ties to the penny.",
      backing: ["scheduler-force-start", "interest-accrual"],
      steps: [
        { name: "Force-start daily-interest job", tone: "exception", raw: [{ method: "POST", path: "/v1/jobs/{id}/runs", tag: "request" }] },
        { name: "Accrue interest", raw: [{ method: "GET", path: "/v1/jobs/{id}/runs/{run}", tag: "poll" }] },
        { name: "Capitalise interest", raw: [{ method: "GET", path: "/v1/jobs/{id}/runs/{run}", tag: "poll" }] },
        { name: "Post the run's ledger entry", raw: [{ method: "GET", path: "/v1/ledger-accounts", tag: "request" }] },
        { name: "Interest ties to the penny", raw: [{ method: "GET", path: "/v1/ledger-accounts", tag: "request" }] },
      ],
    },
  ];

  const sceneById = (id) => SCENES.find((s) => s.id === id);
  const sceneIndex = (id) => SCENES.findIndex((s) => s.id === id);

  // ── persisted state ───────────────────────────────────────────────
  // Versioned keys: bumped whenever scene semantics change so stale
  // localStorage doesn't strand the runner. v3 re-cut the back half
  // (four accounts per the happy-path shape, a save scene, a policy
  // refusal) and rekeyed ctx.accounts; v4 inserts the outbound-payment
  // scene (Ford pays Arthur), renumbering the scenes after it.
  const DONE_KEY = "queenswood.scenarios.v4.done";
  const CTX_KEY = "queenswood.scenarios.v4.ctx";
  const load = (k, fb) => {
    try {
      const r = localStorage.getItem(k);
      return r ? JSON.parse(r) : fb;
    } catch {
      return fb;
    }
  };
  const save = (k, v) => {
    try {
      localStorage.setItem(k, JSON.stringify(v));
    } catch {}
  };

  let done = $state(load(DONE_KEY, []));
  let ctx = $state(load(CTX_KEY, {})); // { products, parties, accounts }
  let runStates = $state({}); // id -> { stepRuns:[{status}], failed? }
  let openIds = $state({});
  let rawOpen = $state({});
  let toasts = $state([]);
  let busy = $state(false);
  const timers = {};
  let toastSeq = 0;
  const persistDone = () => save(DONE_KEY, done);
  const persistCtx = () => save(CTX_KEY, ctx);

  const isDone = (id) => done.includes(id);

  function statusOf(id) {
    const rs = runStates[id];
    if (rs && !rs.failed) return "running";
    if (isDone(id)) return "done";
    const idx = sceneIndex(id);
    if (idx === 0) return "ready";
    return isDone(SCENES[idx - 1].id) ? "ready" : "locked";
  }
  function firstActionableIndex() {
    for (let i = 0; i < SCENES.length; i++) {
      if (!isDone(SCENES[i].id)) return i;
    }
    return -1;
  }

  const fmtMoney = (pence) =>
    "£" +
    (pence / 100).toLocaleString("en-GB", {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    });

  const bank = $derived.by(() => {
    const d = (id) => isDone(id);
    const productsLive = d("s1") ? 2 : 0;
    const applicants = d("s2") ? 3 : 0;
    const activeCustomers = d("s2") ? 2 : 0;
    const accountsOpen = d("s3") ? 4 : 0;
    let cash = 0;
    if (d("s4")) cash += 2 * FUND_EACH; // both currents funded £1,000
    // Saving (s5) moves money between a customer's own accounts and the
    // refused overdraw (s6) posts nothing — both leave the total flat.
    if (d("s7")) cash += INTEREST; // overnight interest capitalised
    return { productsLive, applicants, activeCustomers, accountsOpen, cash };
  });

  const spineSteps = $derived(
    SCENES.map((s) => ({ num: s.num, label: s.title, status: statusOf(s.id) })),
  );
  const cells = $derived([
    { figure: done.length, unit: `/ ${SCENES.length}`, label: "Scenes run" },
    { figure: bank.productsLive, label: "Products live", muted: !bank.productsLive },
    { figure: bank.activeCustomers, unit: `/ ${bank.applicants}`, label: "Active customers", muted: !bank.activeCustomers },
    { figure: fmtMoney(bank.cash), label: "Customer money held", muted: !bank.cash },
  ]);
  const nextIdx = $derived(firstActionableIndex());

  function pipeFor(s) {
    const rs = runStates[s.id];
    if (rs && rs.stepRuns) {
      return s.steps.map((st, i) => ({ name: st.name, status: rs.stepRuns[i].status }));
    }
    if (isDone(s.id)) {
      return s.steps.map((st) => ({ name: st.name, status: st.tone === "exception" ? "exception" : "ok" }));
    }
    return s.steps.map((st) => ({ name: st.name, status: "pending" }));
  }
  const rawRows = (s) => s.steps.flatMap((st) => st.raw);

  // ── execution helpers ─────────────────────────────────────────────
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const tick = () => sleep(450); // pacing for narration-only steps
  const ok2xx = (r) => r.status >= 200 && r.status < 300;

  async function poll(fn, until, { tries = 30, delay = 600 } = {}) {
    let last;
    for (let i = 0; i < tries; i++) {
      last = await fn();
      if (until(last)) return last;
      await sleep(delay);
    }
    throw new Error("timed out waiting for the expected state");
  }

  // The cached ids below are real FDB ids persisted in localStorage. A
  // bank-api restart wipes FDB, orphaning them — so each ensure* verifies
  // the cached entity still exists and recreates it if not, rather than
  // stranding the sandbox on a ghost id (which polls forever / 404s).
  async function ensureProduct(kind, body) {
    const cached = ctx.products?.[kind];
    if (cached?.productId) {
      const list = await api.list_cash_account_products();
      const live = (list.body?.["cash-account-products"] || []).some(
        (p) => p["product-id"] === cached.productId,
      );
      if (live) return cached;
    }
    const res = await api.create_cash_account_product(body);
    let productId, versionId;
    if (ok2xx(res)) {
      productId = res.body["product-id"];
      versionId = res.body["version-id"];
    } else {
      // per-type cap / existing draft on a re-run — reuse it.
      const list = await api.list_cash_account_products();
      const found = (list.body?.["cash-account-products"] || []).find((p) => p.name === body.name);
      if (!found) throw new Error(`create product "${body.name}": ${res.status}`);
      productId = found["product-id"];
      versionId = found["version-id"];
    }
    ctx.products = { ...(ctx.products || {}), [kind]: { productId, versionId } };
    persistCtx();
    return ctx.products[kind];
  }
  async function publishProduct(p) {
    if (p?.versionId) await api.publish_cash_account_product(p.productId, p.versionId);
  }
  async function reviseSavings(sav, body) {
    const res = await api.open_cash_account_product_draft(sav.productId, body);
    if (ok2xx(res)) {
      const v2 = res.body["version-id"];
      await api.publish_cash_account_product(sav.productId, v2);
      ctx.products.savings.versionId = v2;
      persistCtx();
    }
  }
  async function ensureParty(key, body) {
    const cached = ctx.parties?.[key];
    if (cached) {
      const r = await api.get_party(cached);
      if (r.status === 200) return cached;
    }
    const res = await api.create_party(body);
    if (!ok2xx(res)) throw new Error(`create party ${key}: ${res.status}`);
    const id = res.body["party-id"];
    ctx.parties = { ...(ctx.parties || {}), [key]: id };
    persistCtx();
    return id;
  }
  const pollParty = (id, status) =>
    poll(() => api.get_party(id), (r) => r.status === 200 && r.body?.status === status, { tries: 40, delay: 600 });

  async function ensureAccount(key, body) {
    const cached = ctx.accounts?.[key];
    if (cached?.accountId) {
      const r = await api.get_cash_account(cached.accountId);
      if (r.status === 200) return cached;
    }
    const res = await api.open_cash_account(body);
    let accountId;
    if (ok2xx(res)) {
      accountId = res.body["account-id"];
    } else {
      const list = await api.list_cash_accounts();
      const found = (list.body?.["cash-accounts"] || []).find(
        (a) => a["party-id"] === body["party-id"] && a["product-id"] === body["product-id"],
      );
      if (!found) throw new Error(`open account ${key}: ${res.status}`);
      accountId = found["account-id"];
    }
    const opened = await poll(
      () => api.get_cash_account(accountId),
      (r) => r.status === 200 && r.body?.["account-status"] === "opened",
      { tries: 40, delay: 600 },
    );
    const rec = { accountId, bban: opened.body?.bban };
    ctx.accounts = { ...(ctx.accounts || {}), [key]: rec };
    persistCtx();
    return rec;
  }

  // ── the scene programs ────────────────────────────────────────────
  const PROD_CURRENT = { name: "Current Account", "template-id": TPL_CURRENT, currency: "GBP", "interest-rate-bps": 0, "effective-from": TODAY };
  const PROD_SAVINGS = { name: "Savings", "template-id": TPL_SAVINGS, currency: "GBP", "interest-rate-bps": 365, "effective-from": TODAY };

  const EXEC = {
    async s1({ step }) {
      const cur = await step(0, () => ensureProduct("current", PROD_CURRENT));
      await step(1, () => publishProduct(cur));
      const sav = await step(2, () => ensureProduct("savings", PROD_SAVINGS));
      await step(3, () => publishProduct(sav));
      await step(4, () => reviseSavings(sav, PROD_SAVINGS));
      await step(5, () => tick());
    },
    async s2({ step }) {
      const arthur = await step(0, () => ensureParty("arthur", PARTY.arthur));
      await step(1, () => pollParty(arthur, "active"));
      const ford = await step(2, () => ensureParty("ford", PARTY.ford));
      await step(3, () => pollParty(ford, "active"));
      const zaphod = await step(4, () => ensureParty("zaphod", PARTY.zaphod));
      await step(5, () => pollParty(zaphod, "rejected"));
    },
    async s3({ step }) {
      const open = (key, party, name, kind) => () =>
        ensureAccount(key, { "party-id": ctx.parties[party], name, currency: "GBP", "product-id": ctx.products[kind].productId });
      await step(0, open("arthurCurrent", "arthur", "Arthur Current", "current"));
      await step(1, open("arthurSavings", "arthur", "Arthur Savings", "savings"));
      await step(2, open("fordCurrent", "ford", "Ford Current", "current"));
      await step(3, open("fordSavings", "ford", "Ford Savings", "savings"));
      await step(4, () => tick());
    },
    async s4({ step }) {
      const ac = ctx.accounts.arthurCurrent;
      const fc = ctx.accounts.fordCurrent;
      let house;
      await step(0, async () => {
        const r = await api.simulate_inbound_transfer(bankId, { amount: 2 * FUND_EACH, currency: "GBP" });
        if (!ok2xx(r)) throw new Error(`fund the bank: ${r.status}`);
        house = r.body["account-id"];
      });
      const pay = (acct, who) => async () => {
        const r = await api.submit_internal_payment({ "debtor-account-id": house, "creditor-account-id": acct.accountId, currency: "GBP", amount: FUND_EACH, reference: `Funding ${who}` });
        if (!ok2xx(r)) throw new Error(`pay ${who}: ${r.status}`);
      };
      await step(1, async () => {
        await pay(ac, "Arthur")();
        await pay(fc, "Ford")();
      });
      await step(2, () =>
        poll(() => api.get_cash_account_balances(ac.accountId), (r) => r.status === 200 && (r.body?.["available-balance"]?.value ?? 0) >= FUND_EACH));
      await step(3, () => api.list_ledger_accounts());
    },
    async s5({ step }) {
      const { arthurCurrent, arthurSavings, fordCurrent, fordSavings } = ctx.accounts;
      const save = (from, to, amount, who) => async () => {
        const r = await api.submit_internal_payment({ "debtor-account-id": from.accountId, "creditor-account-id": to.accountId, currency: "GBP", amount, reference: `${who} saves` });
        if (!ok2xx(r)) throw new Error(`${who} save: ${r.status}`);
      };
      await step(0, save(arthurCurrent, arthurSavings, ARTHUR_SAVE, "Arthur"));
      await step(1, save(fordCurrent, fordSavings, FORD_SAVE, "Ford"));
      await step(2, () =>
        poll(() => api.get_cash_account_balances(arthurSavings.accountId), (r) => r.status === 200 && (r.body?.["available-balance"]?.value ?? 0) >= ARTHUR_SAVE));
    },
    async s6({ step }) {
      const ac = ctx.accounts.arthurCurrent;
      await step(0, async () => {
        // Arthur's current holds £250; £500 out would breach the
        // platform non-negative-balance limit. Expect a synchronous 429.
        const r = await api.submit_internal_payment({ "debtor-account-id": ac.accountId, "creditor-account-id": ctx.accounts.arthurSavings.accountId, currency: "GBP", amount: OVERDRAW, reference: "Overdraw attempt" });
        if (r.status !== 429) throw new Error(`expected 429 policy limit, got ${r.status}`);
      });
      await step(1, () => api.list_my_effective_policies());
      await step(2, () =>
        poll(() => api.get_cash_account_balances(ac.accountId), (r) => r.status === 200 && (r.body?.["available-balance"]?.value ?? 0) === FUND_EACH - ARTHUR_SAVE));
    },
    async s7({ step }) {
      await step(0, async () => {
        const list = await api.list_jobs();
        const jobs = list.body?.jobs ?? list.body?.["jobs"] ?? [];
        const job = jobs.find((j) => /interest/i.test(j.name || j["job-id"] || ""));
        if (!job) throw new Error("daily-interest job not found");
        const r = await api.force_start_job(job["job-id"] ?? job.id);
        if (!ok2xx(r)) throw new Error(`force-start: ${r.status}`);
      });
      await step(1, () => tick());
      await step(2, () => tick());
      await step(3, () => api.list_ledger_accounts());
      await step(4, () => tick());
    },
    async s8({ step }) {
      const ford = ctx.accounts.fordCurrent;
      const arthur = ctx.accounts.arthurCurrent;
      let paymentId;
      await step(0, async () => {
        // Arthur's BBAN is sort-code ++ account-number from the SCAN
        // address on his current account — the scheme delivers there.
        const bban =
          arthur.bban ?? (await api.get_cash_account(arthur.accountId)).body?.bban;
        if (!bban) throw new Error("Arthur's current has no SCAN bban yet");
        const r = await api.submit_outbound_payment({
          "debtor-account-id": ford.accountId,
          "creditor-bban": bban,
          "creditor-name": "Arthur Dent",
          currency: "GBP",
          amount: FORD_PAYS,
          scheme: "fps",
          reference: "Beer and nuts",
        });
        if (!ok2xx(r)) throw new Error(`outbound submit: ${r.status}`);
        paymentId = r.body?.["payment-id"];
      });
      await step(1, () =>
        poll(
          () => api.get_outbound_payment(paymentId),
          (r) => r.status === 200 && r.body?.["payment-status"] === "completed",
          { tries: 40, delay: 600 },
        ));
      // Outbound to a same-bank account round-trips back as an inbound:
      // Ford −£20, Arthur +£20, so Arthur's current climbs to £270.
      await step(2, () =>
        poll(
          () => api.get_cash_account_balances(arthur.accountId),
          (r) =>
            r.status === 200 &&
            (r.body?.["available-balance"]?.value ?? 0) >= FUND_EACH - ARTHUR_SAVE + FORD_PAYS,
        ));
    },
  };

  // ── run engine ────────────────────────────────────────────────────
  async function runScene(id) {
    if (busy) return;
    const s = sceneById(id);
    const st = statusOf(id);
    if (st === "locked" || st === "running") return;
    busy = true;
    runStates[id] = { stepRuns: s.steps.map((_, i) => ({ status: i === 0 ? "running" : "pending" })) };
    openIds[id] = true;
    pushToast(`Running Scene ${s.num} — ${s.title}…`);

    const sr = runStates[id].stepRuns;
    let cur = 0;
    const step = async (i, fn) => {
      cur = i;
      sr[i].status = "running";
      const r = await fn();
      sr[i].status = s.steps[i].tone === "exception" ? "exception" : "ok";
      if (i + 1 < sr.length) sr[i + 1].status = "running";
      return r;
    };

    try {
      await EXEC[id]({ step });
      finishScene(id);
    } catch (e) {
      if (sr[cur]) sr[cur].status = "failed";
      runStates[id] = { stepRuns: sr, failed: true };
      pushToast(`Scene ${s.num} failed — ${e?.message ?? e}`);
      console.error("[scenarios]", id, e);
    } finally {
      busy = false;
    }
  }
  function finishScene(id) {
    const s = sceneById(id);
    runStates[id] = undefined;
    if (!done.includes(id)) done.push(id);
    persistDone();
    openIds[id] = true;
    pushToast(`Scene ${s.num} complete — ${s.title}`, true);
  }
  function reset() {
    for (const k in timers) clearTimeout(timers[k]);
    for (const s of SCENES) runStates[s.id] = undefined;
    done = [];
    ctx = {};
    persistDone();
    persistCtx();
    pushToast("Sandbox reset — the bank is closed again");
  }

  // ── navigation ────────────────────────────────────────────────────
  const toggleOpen = (id) => (openIds[id] = !openIds[id]);
  const toggleRaw = (id) => (rawOpen[id] = !rawOpen[id]);
  function jumpTo(id, after) {
    openIds[id] = true;
    requestAnimationFrame(() => {
      document.getElementById(`scene-${id}`)?.scrollIntoView({ behavior: "smooth", block: "start" });
    });
    if (after) setTimeout(after, 360);
  }
  const jumpRun = (id) => jumpTo(id, () => runScene(id));

  function pushToast(msg, ok = false) {
    const id = ++toastSeq;
    toasts.push({ id, msg, ok });
    setTimeout(() => {
      toasts = toasts.filter((t) => t.id !== id);
    }, 2800);
  }
</script>

<!-- reusable icon snippets -->
{#snippet icoCheck()}
  <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="8" cy="8" r="6.4" stroke-opacity="0.4" /><path d="M5.2 8.2 L7.1 10 L10.8 6" /></svg>
{/snippet}
{#snippet icoSpark()}
  <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M8 2.5 V13.5 M2.5 8 H13.5 M4.4 4.4 L11.6 11.6 M11.6 4.4 L4.4 11.6" stroke-opacity="0.55" /><circle cx="8" cy="8" r="2" /></svg>
{/snippet}
{#snippet icoPlay()}
  <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M5 3.5 L12.5 8 L5 12.5 Z" /></svg>
{/snippet}

<PageHeader
  {kicker}
  title="Scenarios"
  sub="Watch the platform run for real. Eight scenes tell one story — a bank opening its doors — fired in order against the live API. State carries across the whole session, so the books you see are the books the scenarios actually moved."
>
  {#snippet titleAside()}
    <span class="cum-chip" title="State carries across scenes — each builds on the last.">
      <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M2.5 5 L8 2 L13.5 5 L8 8 Z" /><path d="M2.5 8 L8 11 L13.5 8" /><path d="M2.5 11 L8 14 L13.5 11" /></svg>
      cumulative
    </span>
  {/snippet}
  {#snippet actions()}
    <Button variant="ghost" onclick={reset}>
      <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M13.5 8 A 5.5 5.5 0 1 1 11.5 4" /><path d="M13.5 2.5 V5 H11" /></svg>
      Reset sandbox
    </Button>
  {/snippet}
</PageHeader>

<ProgressSpine
  title="A bank opening its doors"
  steps={spineSteps}
  progressLabel="scenes run"
  onJump={(i) => jumpTo(SCENES[i].id)}
/>

<BankStateBand {cells} attentionTone={done.length === 0 ? "idle" : "good"}>
  {#snippet icon()}
    {#if done.length === 0}{@render icoSpark()}{:else}{@render icoCheck()}{/if}
  {/snippet}
  {#snippet title()}
    {#if done.length === 0}The bank hasn't opened yet
    {:else if nextIdx === -1}The bank is open, funded, and running
    {:else}Books tie — debits equal credits{/if}
  {/snippet}
  {#snippet sub()}
    {#if done.length === 0}
      Run Scene 01 to stock the shelves and watch the platform build a bank, live.
    {:else if nextIdx === -1}
      All eight scenes complete · books tie to the penny
    {:else}
      {bank.activeCustomers} customer{bank.activeCustomers === 1 ? "" : "s"} · <span class="mono">{fmtMoney(bank.cash)}</span> held
    {/if}
  {/snippet}
  {#snippet action()}
    {#if done.length === 0}
      <Button variant="brand" size="sm" onclick={() => jumpRun(SCENES[0].id)}>{@render icoPlay()}<span>Run Scene 01</span></Button>
    {:else if nextIdx !== -1}
      <Button variant="brand" size="sm" onclick={() => jumpRun(SCENES[nextIdx].id)}>{@render icoPlay()}<span>Run Scene {SCENES[nextIdx].num}</span></Button>
    {/if}
  {/snippet}
</BankStateBand>

<section class="scene-list">
  {#each SCENES as s (s.id)}
    <SceneCard
      num={s.num}
      title={s.title}
      story={s.story}
      status={statusOf(s.id)}
      payoffLabel={VIEWS[s.view].label.toLowerCase()}
      open={!!openIds[s.id]}
      elId={`scene-${s.id}`}
      onToggle={() => toggleOpen(s.id)}
      onRun={() => runScene(s.id)}
    >
      {#snippet body()}
        <div>
          <div class="block-title">
            <span>Steps — run in sequence</span>
            <Button variant="ghost" size="sm" onclick={() => toggleRaw(s.id)}>
              <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M6 5 L3 8 L6 11" /><path d="M10 5 L13 8 L10 11" /></svg>
              <span>{rawOpen[s.id] ? "Hide raw calls" : "Show raw calls"}</span>
            </Button>
          </div>
          <div class="block-pipe"><TaskPipeline steps={pipeFor(s)} /></div>
          <div class="block-raw"><RawCalls rows={rawRows(s)} backing={s.backing} show={!!rawOpen[s.id]} /></div>
        </div>

        {#if statusOf(s.id) === "done"}
          <div class="payoff">
            <div class="payoff-head">
              <span class="ph-ico">{@render icoCheck()}</span>
              <span class="ph-title">Result</span>
              <a class="see-link" href={VIEWS[s.view].href}>
                <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M1.5 8 C3.5 4.5 6 3 8 3 s4.5 1.5 6.5 5 C12.5 11.5 10 13 8 13 s-4.5-1.5-6.5-5 Z" /><circle cx="8" cy="8" r="1.8" /></svg>
                <span>See it in {VIEWS[s.view].label}</span>
                <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M3 8 H12.5" /><path d="M9 4.5 L12.5 8 L9 11.5" /></svg>
              </a>
            </div>
            <div class="payoff-body">{@render payoff(s)}</div>
          </div>
        {:else if statusOf(s.id) === "ready"}
          <div class="scene-cta">
            <Button variant="brand" onclick={() => runScene(s.id)}>{@render icoPlay()}<span>Run this scene</span></Button>
            <span class="cta-hint">Fires {s.steps.length} steps against the live API · pays off in <span class="mono">{VIEWS[s.view].label}</span></span>
          </div>
        {:else if statusOf(s.id) === "locked"}
          <div class="scene-cta">
            <span class="cta-hint">Run Scene {SCENES[Math.max(0, sceneIndex(s.id) - 1)].num} first to unlock this scene.</span>
          </div>
        {/if}
      {/snippet}
    </SceneCard>
  {/each}
</section>

<!-- per-scene payoff widgets -->
{#snippet payoff(s)}
  {#if s.id === "s1"}
    <div class="prod-chips">
      <div class="prod-chip"><span class="pc-name">Current Account</span><span class="pc-rate">0 bps</span><Badge tone="published">published</Badge><span class="pc-ver">v1</span></div>
      <div class="prod-chip"><span class="pc-name">Savings</span><span class="pc-rate">3.65%</span><Badge tone="published">published</Badge><span class="pc-ver">v2</span></div>
      <div class="prod-chip archived"><span class="pc-name">Savings</span><Badge tone="archived">archived</Badge><span class="pc-ver">v1</span></div>
    </div>
  {:else if s.id === "s2"}
    <div class="party-lines">
      <div class="party-line"><span class="pl-name">Arthur Dent</span><span class="pl-flow"><Badge tone="archived">pending</Badge><span class="arr">→</span><Badge tone="published">active</Badge></span></div>
      <div class="party-line"><span class="pl-name">Ford Prefect</span><span class="pl-flow"><Badge tone="archived">pending</Badge><span class="arr">→</span><Badge tone="published">active</Badge></span></div>
      <div class="party-line"><span class="pl-name">Zaphod Beeblebrox</span><span class="pl-flow"><Badge tone="archived">pending</Badge><span class="arr">→</span><Badge tone="rejected">rejected</Badge></span></div>
    </div>
  {:else if s.id === "s3"}
    <div class="prod-chips">
      <div class="prod-chip"><span class="pc-name">Arthur · Current</span><Badge tone="published">opened</Badge></div>
      <div class="prod-chip"><span class="pc-name">Arthur · Savings</span><Badge tone="published">opened</Badge></div>
      <div class="prod-chip"><span class="pc-name">Ford · Current</span><Badge tone="published">opened</Badge></div>
      <div class="prod-chip"><span class="pc-name">Ford · Savings</span><Badge tone="published">opened</Badge></div>
    </div>
    <div class="tb-tie">{@render icoCheck()}<span>Four accounts open — each with its own sort code and account number.</span></div>
  {:else if s.id === "s4"}
    <div class="tb">
      <div class="tb-row head"><span>Code</span><span>Account</span><span>Debit</span><span>Credit</span></div>
      <div class="tb-row"><span class="tb-code">1100</span><span class="tb-acct">Customer cash at bank</span><span class="tb-dr">£2,000.00</span><span class="tb-cr"></span></div>
      <div class="tb-row"><span class="tb-code">2100</span><span class="tb-acct">Customer account balances</span><span class="tb-dr"></span><span class="tb-cr">£2,000.00</span></div>
      <div class="tb-row total"><span class="tb-code"></span><span class="tb-acct">Trial balance</span><span class="tb-dr">£2,000.00</span><span class="tb-cr">£2,000.00</span></div>
    </div>
    <div class="tb-tie">{@render icoCheck()}<span>Two £1,000 credits · debits equal credits — the books tie to the penny.</span></div>
  {:else if s.id === "s5"}
    <div class="pay-lines">
      <div class="pay-line"><span class="py-amt">£750.00</span><Badge tone="published">settled</Badge><span class="py-desc">Arthur · current → savings · savings <span class="mono">£0 → £750</span></span></div>
      <div class="pay-line"><span class="py-amt">£350.00</span><Badge tone="published">settled</Badge><span class="py-desc">Ford · current → savings · savings <span class="mono">£0 → £350</span></span></div>
    </div>
    <div class="tb-tie">{@render icoCheck()}<span>Each customer moved money between their own accounts — debits still equal credits.</span></div>
  {:else if s.id === "s6"}
    <div class="pay-lines">
      <div class="pay-line"><span class="py-amt">−£500.00</span><Badge tone="rejected">refused</Badge><span class="py-desc">Arthur · current → savings · current holds only <span class="mono">£250</span></span></div>
    </div>
    <div class="tb-tie neutral">{@render icoSpark()}<span><span class="hl">Available balance must stay at or above £0</span> — the platform policy refused the transfer before any money moved. Nothing posted.</span></div>
  {:else if s.id === "s7"}
    <div class="joblet">
      <TaskPipeline steps={[{ name: "accrue", status: "ok" }, { name: "capitalise", status: "ok" }]} />
      <div class="jl-note">The daily-interest job accrues silently, then capitalises — one statement line per funded savings account, and the bank's own entry posted once for the run rather than once per account. See the run in <span class="mono">Jobs</span> and the postings in the <span class="mono">Ledger</span>.</div>
    </div>
    <div class="tb-tie">{@render icoCheck()}<span>Interest posting ties to the penny.</span></div>
  {:else if s.id === "s8"}
    <div class="pay-lines">
      <div class="pay-line"><span class="py-amt">£20.00</span><Badge tone="published">completed</Badge><span class="py-desc">Ford → Arthur · outbound FPS · ref <span class="mono">Beer and nuts</span></span></div>
    </div>
    <div class="tb-tie">{@render icoCheck()}<span>Ford <span class="mono">−£20</span>, Arthur <span class="mono">+£20</span> — it left over the scheme and arrived back, and the books still tie.</span></div>
  {/if}
{/snippet}

<div class="toast-wrap">
  {#each toasts as t (t.id)}
    <div class="toast" transition:fly={{ y: 8, duration: 200 }}>
      {#if t.ok}<span class="t-ok">{@render icoCheck()}</span>{/if}
      <span>{t.msg}</span>
    </div>
  {/each}
</div>

<style>
  /* cumulative chip beside the page title */
  .cum-chip {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    height: 22px;
    padding: 0 10px;
    border-radius: 999px;
    font-family: var(--mono);
    font-size: 10.5px;
    letter-spacing: 0.04em;
    text-transform: lowercase;
    background: light-dark(oklch(0.92 0.05 80), oklch(0.30 0.060 78));
    color: light-dark(oklch(0.45 0.120 68), oklch(0.85 0.115 82));
  }
  .cum-chip svg { width: 11px; height: 11px; stroke: currentColor; fill: none; }

  .scene-list { display: flex; flex-direction: column; gap: 14px; }

  /* scene-body inner blocks */
  .block-title {
    font-family: var(--mono);
    font-size: 10px;
    letter-spacing: 0.1em;
    text-transform: uppercase;
    color: var(--gold-deep);
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }
  .block-pipe { margin-top: 12px; }
  .block-raw { margin-top: 12px; }

  .scene-cta { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
  .scene-cta .cta-hint { font-size: 12.5px; color: var(--fg-muted); }
  .cta-hint .mono { font-family: var(--mono); }

  /* payoff panel */
  .payoff {
    border: 1px solid var(--rule);
    border-radius: 9px;
    background: var(--surface-raised);
    overflow: hidden;
  }
  .payoff-head {
    display: flex;
    align-items: center;
    gap: 9px;
    padding: 11px 15px;
    border-bottom: 1px solid var(--rule-2);
    background: light-dark(oklch(0.97 0.02 84), oklch(0.30 0.035 80));
  }
  .payoff-head .ph-ico { width: 18px; height: 18px; color: var(--ok); flex: 0 0 auto; }
  .payoff-head .ph-ico :global(svg) { width: 18px; height: 18px; }
  .payoff-head .ph-title { font-size: 13px; font-weight: 500; color: var(--fg); }
  .payoff-body { padding: 16px; }

  .see-link {
    margin-left: auto;
    display: inline-flex;
    align-items: center;
    gap: 6px;
    height: 26px;
    padding: 0 10px;
    border-radius: 6px;
    border: 1px solid var(--rule);
    font-family: var(--grotesk);
    font-size: 12px;
    font-weight: 500;
    color: var(--fg);
    text-decoration: none;
    transition: background 0.12s, border-color 0.12s;
  }
  .see-link:hover {
    background: var(--hover-overlay);
    border-color: light-dark(rgba(20, 15, 10, 0.18), rgba(244, 241, 234, 0.2));
  }
  .see-link svg { width: 12px; height: 12px; }

  /* product chips */
  .prod-chips { display: flex; flex-wrap: wrap; gap: 10px; }
  .prod-chip {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 10px 14px;
    border: 1px solid var(--rule);
    border-radius: 8px;
    background: var(--surface-sunk);
  }
  .prod-chip.archived { opacity: 0.55; }
  .prod-chip .pc-name { font-size: 13px; font-weight: 500; color: var(--fg); }
  .prod-chip .pc-rate { font-family: var(--mono); font-size: 11px; color: var(--fg-muted); }
  .prod-chip .pc-ver { font-family: var(--mono); font-size: 10px; color: var(--fg-muted); }

  /* party lines */
  .party-lines { display: flex; flex-direction: column; gap: 10px; }
  .party-line { display: flex; align-items: center; gap: 12px; }
  .party-line .pl-name { font-size: 13.5px; color: var(--fg); font-weight: 500; min-width: 168px; }
  .party-line .pl-flow { display: inline-flex; align-items: center; gap: 8px; font-family: var(--mono); font-size: 11px; color: var(--fg-muted); }
  .party-line .pl-flow .arr { opacity: 0.55; }

  /* mini trial balance */
  .tb {
    display: flex;
    flex-direction: column;
    border: 1px solid var(--rule-2);
    border-radius: 8px;
    overflow: hidden;
  }
  .tb-row {
    display: grid;
    grid-template-columns: 60px 1fr 120px 120px;
    gap: 12px;
    align-items: center;
    padding: 9px 14px;
    border-bottom: 1px solid var(--rule-2);
    font-size: 12.5px;
  }
  .tb-row:last-child { border-bottom: none; }
  .tb-row.head { background: var(--surface-sunk); }
  .tb-row.head span {
    font-family: var(--mono);
    font-size: 10px;
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--fg-muted);
  }
  .tb-row .tb-code { font-family: var(--mono); color: var(--fg-muted); }
  .tb-row .tb-acct { color: var(--fg); }
  .tb-row .tb-dr,
  .tb-row .tb-cr {
    text-align: right;
    font-family: var(--mono);
    font-variant-numeric: tabular-nums;
  }
  .tb-row .tb-dr { color: var(--debit); }
  .tb-row .tb-cr { color: var(--credit); }
  .tb-row.total { background: var(--surface-sunk); font-weight: 500; }
  .tb-row.total .tb-acct { color: var(--fg); }

  .tb-tie {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    margin-top: 12px;
    font-size: 12.5px;
    color: var(--ok);
  }
  .tb-tie :global(svg) { width: 15px; height: 15px; flex: 0 0 auto; }
  .tb-tie.neutral { color: var(--gold-deep); }
  .tb-tie .hl { color: var(--gold-deep); }

  /* payment lines */
  .pay-lines { display: flex; flex-direction: column; gap: 12px; }
  .pay-line { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
  .pay-line .py-amt {
    font-family: var(--mono);
    font-size: 13px;
    color: var(--fg);
    min-width: 96px;
    font-variant-numeric: tabular-nums;
  }
  .pay-line .py-desc { font-size: 12.5px; color: var(--fg-muted); }
  .py-desc .mono { font-family: var(--mono); }

  /* joblet */
  .joblet { display: flex; flex-direction: column; gap: 12px; }
  .joblet .jl-note { font-size: 12.5px; color: var(--fg-2); line-height: 1.5; }
  .jl-note .mono { font-family: var(--mono); }

  /* toast */
  .toast-wrap {
    position: fixed;
    bottom: 24px;
    left: 50%;
    transform: translateX(-50%);
    z-index: 80;
    display: flex;
    flex-direction: column;
    gap: 8px;
    align-items: center;
    pointer-events: none;
  }
  .toast {
    display: flex;
    align-items: center;
    gap: 9px;
    padding: 10px 16px;
    border-radius: 999px;
    background: var(--ink);
    color: var(--bone);
    font-size: 13px;
    box-shadow: 0 12px 30px -10px rgba(0, 0, 0, 0.5);
  }
  .toast :global(svg) { width: 15px; height: 15px; }
  .toast .t-ok { color: var(--gold-bright); display: inline-flex; }
</style>
