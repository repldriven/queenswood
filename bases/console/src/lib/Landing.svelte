<script>
  /* Marketing-style landing for the unauthenticated console.
     Structure ported from design_handoff_queenswood_logo/Queenswood Home.html.
     The three "Sign in" CTAs route to /#/sign-in, where the user picks
     an identity provider. Keycloak handoff happens from SignInPage. */

  import { push } from "svelte-spa-router";
  import {
    Logo,
    Wordmark,
    ThemeToggle,
    Card,
    CardHeader,
    CardBody,
    CardFooter,
    CodeCard,
    // Embedded screens — real components mounted with static data, so the
    // "screenshots" are the actual UI and theme + stay in sync.
    Table,
    Thead,
    Tbody,
    Tr,
    Th,
    Td,
    Badge,
    MoneyCell,
    Phase,
    Expander,
    sumMinor,
    TrialBalance,
    PolicyMatrix,
    ProgressSpine,
    BankStateBand,
    TaskPipeline,
    AccountStatusBadge,
    Button,
    formatMoney,
    formatSigned,
  } from "@queenswood/ui";
  import DocViewer from "./DocViewer.svelte";

  // Slug helper for the CardFooter ref link: trims the GitHub URL down
  // to the doc's human-readable slug (strips path, leading numbers,
  // and the .md extension). "0013-single-unified-api.md" → "single-
  // unified-api"; "policy-evaluation.md" → "policy-evaluation".
  function slugFromHref(href) {
    const file = href.split("/").pop() ?? "";
    return file.replace(/\.md$/i, "").replace(/^\d+-/, "");
  }

  // The Engineering Choices grid. Last card has variant="feature" so
  // it stands out as the section's one inverted accent. Body uses
  // {@html} because one entry contains inline <code>.
  const PRINCIPLES = [
    { key: "adr-0013", kicker: "ADR · 0013 · 0014",
      title: "One unified API. OpenAPI is the contract.",
      href: "https://github.com/repldriven/queenswood/blob/main/docs/adr/0013-single-unified-api.md",
      body: "Bank-shaped, not implementation-shaped. The spec drives client generation, validation, and documentation — there is no second source of truth." },
    { key: "tdd-policy", kicker: "TDD · policy-evaluation",
      title: "Policies as data, not hard-coded rules.",
      href: "https://github.com/repldriven/queenswood/blob/main/docs/tdd/policy-evaluation.md",
      body: "Capabilities and limits are records. A curative-permit pattern lets your customers self-correct balances out of breach without a manual override." },
    { key: "tdd-interest", kicker: "TDD · interest",
      title: "Pennies are conserved by construction.",
      href: "https://github.com/repldriven/queenswood/blob/main/docs/tdd/interest.md",
      body: "Integer micro-unit arithmetic with sub-minor-unit carry. Daily accrual, capitalisation at whatever cadence you choose, and one ledger entry per run rather than per account — ties out exactly." },
    { key: "tdd-scenario", kicker: "TDD · scenario-testing",
      title: "A pure model runs beside the real system.",
      href: "https://github.com/repldriven/queenswood/blob/main/docs/tdd/scenario-testing.md",
      body: "Tests pass only when the two agree. Property-based testing via fugato plus hand-authored EDN scenarios, sharing one runner." },
    { key: "adr-0018", kicker: "ADR · 0018",
      title: "A write earns command status, or stays synchronous.",
      href: "https://github.com/repldriven/queenswood/blob/main/docs/adr/0018-command-writes-are-earned.md",
      body: "A write goes over the bus to a processor only when it needs multi-record atomicity under contention, has idempotency stakes, other bricks must react to it, or it arrives from unreliable ingress. Everything else is a direct write." },
    { key: "adr-0019", kicker: "ADR · 0019",
      title: "Processors are composed at deployment time.",
      href: "https://github.com/repldriven/queenswood/blob/main/docs/adr/0019-processor-packaging.md",
      body: "One thin base per service group; a project's <code>application.yml</code> alone decides which processors a JVM hosts. Grouped by boundary, not throughput — financial and operational processors never share a JVM." },
    { key: "adr-0002", kicker: "ADR · 0002",
      title: "The changelog is the outbox.",
      href: "https://github.com/repldriven/queenswood/blob/main/docs/adr/0002-foundationdb-record-layer.md",
      body: "FoundationDB Record Layer gives multi-record ACID by default; the transactional outbox pattern falls out of the storage engine — no separate table." },
    { key: "adr-0001", kicker: "ADR · 0001",
      title: "mono is upstream, not a fork.",
      href: "https://github.com/repldriven/queenswood/blob/main/docs/adr/0001-reuse-mono-as-upstream.md",
      body: "Shared infrastructure comes from mono as a git dependency pinned to a tag and sha; the workspace holds only the bank's own bricks. Upgrading mono is a one-line bump in two shims under <code>deps/</code>." },
    { key: "recipe-tc", kicker: "Recipe · testcontainers",
      title: "REPL on the inside.",
      href: "https://github.com/repldriven/mono/blob/main/docs/recipes/test/testcontainers.md",
      body: "Start a REPL, evaluate a comment block, and the whole system — FDB, Pulsar, HTTP, Keycloak — boots inside Testcontainers. The dev loop is the system.",
      variant: "feature" },
  ];

  // Markdown docs imported as raw strings at build time. Each card
  // in the Engineering Choices section opens its doc in a modal via
  // `DocViewer`; plain clicks intercept and open the modal, modified
  // clicks (cmd / ctrl / shift / middle) fall through to the github
  // anchor in a new tab.
  import adr0013 from "../../../../docs/adr/0013-single-unified-api.md?raw";
  import tddPolicy from "../../../../docs/tdd/policy-evaluation.md?raw";
  import tddInterest from "../../../../docs/tdd/interest.md?raw";
  import tddScenario from "../../../../docs/tdd/scenario-testing.md?raw";
  import adr0018 from "../../../../docs/adr/0018-command-writes-are-earned.md?raw";
  import adr0019 from "../../../../docs/adr/0019-processor-packaging.md?raw";
  import adr0002 from "../../../../docs/adr/0002-foundationdb-record-layer.md?raw";
  import adr0001 from "../../../../docs/adr/0001-reuse-mono-as-upstream.md?raw";
  import recipeTC from "../../../../docs/recipes/test/testcontainers.md?raw";

  const docs = {
    "adr-0013": {
      raw: adr0013,
      path: "docs/adr/0013-single-unified-api.md",
      label: "ADR · 0013 · 0014",
    },
    "tdd-policy": {
      raw: tddPolicy,
      path: "docs/tdd/policy-evaluation.md",
      label: "TDD · policy-evaluation",
    },
    "tdd-interest": {
      raw: tddInterest,
      path: "docs/tdd/interest.md",
      label: "TDD · interest",
    },
    "tdd-scenario": {
      raw: tddScenario,
      path: "docs/tdd/scenario-testing.md",
      label: "TDD · scenario-testing",
    },
    "adr-0018": {
      raw: adr0018,
      path: "docs/adr/0018-command-writes-are-earned.md",
      label: "ADR · 0018",
    },
    "adr-0019": {
      raw: adr0019,
      path: "docs/adr/0019-processor-packaging.md",
      label: "ADR · 0019",
    },
    "adr-0002": {
      raw: adr0002,
      path: "docs/adr/0002-foundationdb-record-layer.md",
      label: "ADR · 0002",
    },
    "adr-0001": {
      raw: adr0001,
      path: "docs/adr/0001-reuse-mono-as-upstream.md",
      label: "ADR · 0001",
    },
    "recipe-tc": {
      raw: recipeTC,
      path: "docs/recipes/test/testcontainers.md",
      label: "Recipe · testcontainers",
    },
  };

  let openDocKey = $state(null);

  function openDoc(key) {
    return (e) => {
      // Let cmd/ctrl/shift/middle-click fall through to the anchor's
      // default — opens GitHub in a new tab the way a power user expects.
      if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return;
      e.preventDefault();
      openDocKey = key;
    };
  }

  function goSignIn() {
    push("/sign-in");
  }

  /* ── Embedded-screen data ─────────────────────────────────────────
     Each feature screen below is a SNIPPET — a few rows of the real
     component, never the full page (no search, drawers, or headers).
     The figures mirror the Scenarios sandbox story (Arthur Dent / Ford
     Prefect; current @ 0 bps, savings @ 3.65%; £1,000 in; £750 / £350
     saved) so the whole page reads as one coherent bank. Shapes match
     what the console builds from the API — see ui/showcase. */

  // 01 · Products — a draft/published/archived slice.
  const DEMO_PRODUCTS = [
    { name: "Everyday Current", type: "current", rate: 0, ccy: "GBP", status: "published" },
    { name: "Instant Saver", type: "savings", rate: 365, ccy: "GBP", status: "published" },
    { name: "Term Deposit 12m", type: "savings", rate: 410, ccy: "GBP", status: "draft" },
    { name: "Instant Saver", type: "savings", rate: 250, ccy: "GBP", status: "archived" },
  ];

  // 02 · Parties — IDV clears two, rejects one; an org awaits review.
  const DEMO_PARTIES = [
    { name: "Arthur Dent", type: "person", status: "active" },
    { name: "Ford Prefect", type: "person", status: "active" },
    { name: "Zaphod Beeblebrox", type: "person", status: "rejected" },
    { name: "Milliways Ltd", type: "organization", status: "pending" },
  ];
  const PARTY_TONE = { active: "published", pending: "pending", rejected: "rejected" };

  // 03 · Accounts — Arthur's current after the £1,000 in and the £750 save.
  const DEMO_ACCOUNT = {
    sortCode: "04-00-04",
    number: "12345678",
    ccy: "GBP",
    available: 25000,
    txns: [
      { date: "18 Jun", desc: "Saved to Instant Saver", minor: -75000, bal: 25000 },
      { date: "18 Jun", desc: "Inbound Faster Payment", minor: 100000, bal: 100000 },
    ],
  };

  // 04 · Ledger — the two accounts that moved when £2,000 came in. Minor
  // is the signed net (credit − debit, credit-positive): the asset reads
  // negative, the deposit liability positive — and the books tie.
  const DEMO_LEDGER = [
    {
      id: "led.1100", name: "Cash at Correspondent", gl: "1100", ccy: "GBP",
      balances: [{ type: "default", phase: "posted", currency: "GBP", minor: -200000 }],
    },
    {
      id: "led.2100", name: "Customer Deposits — GBP", gl: "2100", ccy: "GBP",
      balances: [{ type: "default", phase: "posted", currency: "GBP", minor: 200000 }],
    },
  ];
  let ledgerOpen = $state(Object.fromEntries(DEMO_LEDGER.map((a) => [a.id, true])));
  const ledgerToggle = (id) => (ledgerOpen[id] = !ledgerOpen[id]);
  const ledgerKey = (e, id) => {
    if (e.key === "Enter" || e.key === " ") { e.preventDefault(); ledgerToggle(id); }
  };

  // 05 · Policies — the platform baseline. Leads with the non-negative
  // balance limit (a curative/improving permit) that refuses overdrafts.
  const DEMO_POLICY = {
    policyId: "pol.00000000000000000000000001",
    name: "Platform policy",
    description: "The platform baseline bound to every bank.",
    enabled: true,
    category: "restricted",
    capabilities: [
      { effect: "allow", domain: "cash_account", action: "open", reason: "Allow opening cash accounts", filters: [] },
      { effect: "allow", domain: "outbound_payment", action: "send", reason: "Allow outbound payments", filters: [] },
      { effect: "allow", domain: "party", action: "create", reason: "Allow creating parties", filters: [] },
    ],
    limits: [
      {
        domain: "balance",
        bound: { kind: "min", aggregate: { type: "amount", minor: 0, ccy: "GBP", window: "instant" } },
        reason: "Available balance must stay non-negative on user-driven transfers.",
        allow: "improving",
        filters: [
          { key: "computed", value: "available" },
          { key: "txn", value: "internal-transfer" },
        ],
      },
      {
        domain: "outbound_payment",
        bound: { kind: "max", aggregate: { type: "count", value: 100000, window: "daily" } },
        reason: "Max 100,000 outbound payments per day.",
        allow: null,
        filters: [],
      },
    ],
  };

  // 06 · Jobs — the seeded daily-interest pipeline, run to completion.
  const DEMO_PIPELINE = [
    { name: "Accrue daily interest", status: "ok" },
    { name: "Capitalise — statement line per account", status: "ok" },
    { name: "Trial balance ties", status: "ok" },
  ];

  // The Scenarios spine that frames the page — a bank opening its doors.
  const DEMO_SCENES = [
    { num: "01", label: "Stock the shelves", status: "done" },
    { num: "02", label: "Identity decides the account", status: "done" },
    { num: "03", label: "Open the accounts", status: "done" },
    { num: "04", label: "Money in, double-entry out", status: "done" },
    { num: "05", label: "Customers save", status: "running" },
    { num: "06", label: "Policy holds the line", status: "ready" },
    { num: "07", label: "Friends settle up", status: "locked" },
    { num: "08", label: "Runs itself overnight", status: "locked" },
  ];
  const DEMO_BANK_CELLS = [
    { figure: 4, unit: "/ 8", label: "Scenes run" },
    { figure: 2, label: "Products live" },
    { figure: 2, unit: "/ 3", label: "Active customers" },
    { figure: "£2,000.00", label: "Customer money held" },
  ];
</script>

<DocViewer
  doc={openDocKey ? docs[openDocKey] : null}
  onClose={() => (openDocKey = null)}
/>

<div class="announce">
  <span class="pill">v0.1.0</span>
  <span>
    Bring your own <strong>ClearBank</strong> and <strong>Onfido</strong> — bundled
    simulators for development, plug in your own accounts per bank.
  </span>
  <a target="_blank" rel="noreferrer"
    href="https://github.com/repldriven/queenswood/tree/main/bases/bank-clearbank-adapter"
    >ClearBank adapter ↗</a
  >
  <a target="_blank" rel="noreferrer"
    href="https://github.com/repldriven/queenswood/tree/main/bases/bank-onfido-adapter"
    >Onfido adapter ↗</a
  >
</div>

<nav class="nav">
  <div class="nav-inner">
    <a class="brand" href="/">
      <Logo variant="A" size={28} idPrefix="nav" />
      <span class="wm"><Wordmark variant="grotesk" size={14} /></span>
    </a>
    <ul>
      <li><a href="#platform">Platform</a></li>
      <li><a href="#engineering">Engineering</a></li>
      <li><a target="_blank" rel="noreferrer" href="https://github.com/repldriven/queenswood">GitHub</a></li>
    </ul>
    <div class="spacer"></div>
    <div class="cta-row">
      <ThemeToggle />
      <button class="btn solid" onclick={goSignIn}>Sign in</button>
    </div>
  </div>
</nav>

<section class="hero">
  <div class="wrap">
    <span class="ornament" aria-hidden="true">
      <Logo variant="A" size={460} idPrefix="hero-orn" />
    </span>
    <div class="grid">
      <div>
        <span class="eyebrow">Banking platform · v0.1.0</span>
        <h1 class="title">
          Core banking,<br /><em>modernized.</em>
        </h1>
        <p class="lede">
          Everything a modern fintech needs to operate as a bank — a double-entry
          ledger, UK Faster Payments, customer KYC, configurable policies, and
          scheduled interest — under one unified OpenAPI. Use the hosted edition,
          or self-host the open core. MIT-licensed.
        </p>
        <div class="ctas">
          <button class="btn solid" onclick={goSignIn}>Sign in</button>
          <a target="_blank" rel="noreferrer"
            class="btn line"
            href="https://github.com/repldriven/queenswood#readme"
            >Read the docs</a
          >
          <a target="_blank" rel="noreferrer" class="btn ghost" href="https://github.com/repldriven/queenswood"
            >View on GitHub ↗</a
          >
        </div>
        <div class="meta">
          <span class="dot"></span>
          <span>UK&nbsp;FPS&nbsp;Simulator</span>
          <span class="dot"></span>
          <span>IDV&nbsp;Simulator</span>
          <span class="dot"></span>
          <span>OAuth</span>
          <span class="dot"></span>
          <span>OpenAPI&nbsp;3.x</span>
        </div>
      </div>
      <div>
        <CodeCard filename="~/queenswood · zsh">
          <pre><span class="syn-comment"># Signed in via OAuth (Keycloak). Charter a bank.</span>
<span class="syn-keyword">curl</span> -X POST https://api.queenswood.local/v1/banks \
  -H <span class="syn-string">"Authorization: Bearer $QW_OAUTH_TOKEN"</span> \
  -H <span class="syn-string">"Content-Type: application/json"</span> \
  -d <span class="syn-string">{`'{ "name": "Northwind FS",
        "status": "test",
        "tier": "standard",
        "currencies": ["GBP"] }'`}</span>

<span class="syn-comment">#</span> <span class="syn-emphasis">{`{ "bank-id": "bnk.01HW7…",`}</span>
<span class="syn-comment">#</span>   <span class="syn-emphasis">{`"sort-code": "04-00-12" }`}</span></pre>
        </CodeCard>
      </div>
    </div>
  </div>
</section>

<section id="platform" class="capabilities">
  <div class="wrap">
    <div class="cap-grid">
      <div class="cap">
        <span class="cap-label">Core ledger</span>
        <p class="cap-desc">Double-entry postings, ties to the penny.</p>
      </div>
      <div class="cap">
        <span class="cap-label">Multi-tenant</span>
        <p class="cap-desc">Onboard, isolate, bill per bank.</p>
      </div>
      <div class="cap">
        <span class="cap-label">KYC</span>
        <p class="cap-desc">Onfido IDV for your customers.</p>
      </div>
      <div class="cap">
        <span class="cap-label">UK payments</span>
        <p class="cap-desc">Faster Payments via ClearBank.</p>
      </div>
      <div class="cap">
        <span class="cap-label">Interest</span>
        <p class="cap-desc">Accrue and capitalise on your cadence.</p>
      </div>
      <div class="cap">
        <span class="cap-label">Policies</span>
        <p class="cap-desc">Capabilities and limits as data.</p>
      </div>
    </div>
  </div>
</section>

<section class="story">
  <div class="wrap">
    <span class="eyebrow">See it run</span>
    <h2>A bank opening its <em>doors.</em></h2>
    <p class="lead">
      The console ships a sandbox that fires these capabilities against the live
      API, in order — eight scenes that build one cumulative story. The screens
      below are those scenes, rendered with the real components, not mockups.
    </p>
    <div class="spine-wrap">
      <ProgressSpine
        title="A bank opening its doors"
        progressLabel="scenes run"
        steps={DEMO_SCENES}
      />
      <BankStateBand cells={DEMO_BANK_CELLS} attentionTone="good">
        {#snippet icon()}
          <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="8" cy="8" r="6.4" stroke-opacity="0.4" /><path d="M5.2 8.2 L7.1 10 L10.8 6" /></svg>
        {/snippet}
        {#snippet title()}Books tie — debits equal credits{/snippet}
        {#snippet sub()}2 customers · £2,000.00 held{/snippet}
      </BankStateBand>
    </div>
  </div>
</section>

<section class="feat">
  <div class="wrap">
    <div class="grid">
      <div>
        <span class="num">01 — Products</span>
        <h3>Stock the <em>shelves.</em></h3>
        <p>
          Define cash-account products — a current account, a savings product —
          then publish to commit a version. Revise whenever you like; publishing
          the new version auto-archives the one it supersedes.
        </p>
        <ul>
          <li>Draft → publish → archive, versioned at publish</li>
          <li>Interest rate in basis points, per product</li>
          <li>Per-product currency and account type</li>
        </ul>
      </div>
      <div class="visual">
        <div class="topbar">
          <div class="dots"><span class="dot"></span><span class="dot"></span><span class="dot"></span></div>
          console / products
        </div>
        <div class="inner">
          <Table>
            <Thead>
              <Tr><Th>Name</Th><Th>Type</Th><Th align="right">Rate</Th><Th>Status</Th></Tr>
            </Thead>
            <Tbody>
              {#each DEMO_PRODUCTS as p (p.name + p.status + p.rate)}
                <Tr>
                  <Td emphasized>{p.name}</Td>
                  <Td mono muted>{p.type}</Td>
                  <Td align="right" mono tabular>{p.rate} bps</Td>
                  <Td><Badge tone={p.status}>{p.status}</Badge></Td>
                </Tr>
              {/each}
            </Tbody>
          </Table>
        </div>
      </div>
    </div>
  </div>
</section>

<section class="feat reverse">
  <div class="wrap">
    <div class="grid">
      <div>
        <span class="num">02 — Parties &amp; IDV</span>
        <h3>Identity decides the <em>account.</em></h3>
        <p>
          Onboard people and organisations. An Onfido identity check runs
          automatically and flips each party to active or rejected — no operator
          click. A rejected check means no account.
        </p>
        <ul>
          <li>Bring your own Onfido — simulator bundled for dev &amp; tests</li>
          <li>Async accept / reject, webhook-driven</li>
          <li>People and organisations in one register</li>
        </ul>
      </div>
      <div class="visual">
        <div class="topbar">
          <div class="dots"><span class="dot"></span><span class="dot"></span><span class="dot"></span></div>
          console / parties
        </div>
        <div class="inner">
          <Table>
            <Thead>
              <Tr><Th>Name</Th><Th>Type</Th><Th>Status</Th></Tr>
            </Thead>
            <Tbody>
              {#each DEMO_PARTIES as p (p.name)}
                <Tr>
                  <Td emphasized>{p.name}</Td>
                  <Td mono muted>{p.type}</Td>
                  <Td><Badge tone={PARTY_TONE[p.status]}>{p.status}</Badge></Td>
                </Tr>
              {/each}
            </Tbody>
          </Table>
        </div>
      </div>
    </div>
  </div>
</section>

<section class="feat">
  <div class="wrap">
    <div class="grid">
      <div>
        <span class="num">03 — Accounts</span>
        <h3>Open the <em>accounts.</em></h3>
        <p>
          Each customer account leads with its available balance, then the
          posting history with a running balance — an inbound Faster Payment in,
          a transfer to savings out, settled to the penny.
        </p>
        <ul>
          <li>Available balance, broken down by phase</li>
          <li>SCAN address — sort code &amp; account number</li>
          <li>Posting history with a running balance</li>
        </ul>
      </div>
      <div class="visual">
        <div class="topbar">
          <div class="dots"><span class="dot"></span><span class="dot"></span><span class="dot"></span></div>
          console / accounts
        </div>
        <div class="inner">
          <div class="acct-snap">
            <div class="snap-head">
              <div class="snap-figs">
                <span class="snap-label">Available balance</span>
                <span class="snap-figure">{formatMoney(DEMO_ACCOUNT.available, DEMO_ACCOUNT.ccy)}<span class="snap-ccy">{DEMO_ACCOUNT.ccy}</span></span>
                <span class="snap-coords">Sort {DEMO_ACCOUNT.sortCode} · Acct {DEMO_ACCOUNT.number}</span>
              </div>
              <AccountStatusBadge status="opened" />
            </div>
            <Table>
              <Thead>
                <Tr><Th>Date</Th><Th>Description</Th><Th align="right">Amount</Th><Th align="right">Balance</Th></Tr>
              </Thead>
              <Tbody>
                {#each DEMO_ACCOUNT.txns as t (t.desc)}
                  <Tr>
                    <Td mono muted>{t.date}</Td>
                    <Td>{t.desc}</Td>
                    <Td align="right"><span class="amt {t.minor < 0 ? 'debit' : 'credit'}">{formatSigned(t.minor, DEMO_ACCOUNT.ccy)}</span></Td>
                    <Td align="right" mono muted>{formatMoney(t.bal, DEMO_ACCOUNT.ccy)}</Td>
                  </Tr>
                {/each}
              </Tbody>
            </Table>
          </div>
        </div>
      </div>
    </div>
  </div>
</section>

<section class="feat reverse">
  <div class="wrap">
    <div class="grid">
      <div>
        <span class="num">04 — Ledger</span>
        <h3>Money in, <em>double-entry</em> out.</h3>
        <p>
          Funding an account moves the books — cash-at-correspondent debited,
          customer deposits credited. Every posting is half of a balanced pair,
          accounts decompose into their balances, and the trial balance ties to
          the penny.
        </p>
        <ul>
          <li>Double-entry general ledger</li>
          <li>Accounts decompose into their balances</li>
          <li>Trial balance — Σ debit ≡ Σ credit</li>
        </ul>
      </div>
      <div class="visual">
        <div class="topbar">
          <div class="dots"><span class="dot"></span><span class="dot"></span><span class="dot"></span></div>
          console / ledger
        </div>
        <div class="inner">
          <TrialBalance
            asOf="09:42 UTC"
            blocks={[{ ccy: "GBP", sym: "£", name: "Sterling", accounts: 2, debitMinor: 200000, creditMinor: 200000 }]}
          />
          <Table tree>
            <Thead>
              <Tr><Th /><Th>Name</Th><Th>GL</Th><Th align="right">Balance</Th></Tr>
            </Thead>
            <Tbody>
              {#each DEMO_LEDGER as acc (acc.id)}
                <Tr
                  expandable
                  expanded={ledgerOpen[acc.id]}
                  onclick={() => ledgerToggle(acc.id)}
                  onkeydown={(e) => ledgerKey(e, acc.id)}
                >
                  <Td expander><Expander /></Td>
                  <Td emphasized>{acc.name}</Td>
                  <Td mono>{acc.gl}</Td>
                  <MoneyCell minor={sumMinor(acc.balances)} ccy={acc.ccy} emphasized />
                </Tr>
                {#if ledgerOpen[acc.id]}
                  {#each acc.balances as b, i (b.type + ":" + b.phase)}
                    <Tr balance last={i === acc.balances.length - 1}>
                      <Td expander />
                      <Td addr>
                        <span class="qw-tree-mark">
                          <span class="qw-addr-path">{b.type}</span>
                          <Phase phase={b.phase} />
                        </span>
                      </Td>
                      <Td mono muted>{b.currency}</Td>
                      <MoneyCell minor={b.minor} ccy={acc.ccy} />
                    </Tr>
                  {/each}
                {/if}
              {/each}
            </Tbody>
          </Table>
        </div>
      </div>
    </div>
  </div>
</section>

<section class="feat">
  <div class="wrap">
    <div class="grid">
      <div>
        <span class="num">06 — Policies</span>
        <h3>Policy holds the <em>line.</em></h3>
        <p>
          Capabilities and limits are data, not hard-coded rules. The platform's
          non-negative-balance limit refuses an overdraft before any money
          moves; where policies overlap a deny wins, and the tightest limit
          applies.
        </p>
        <ul>
          <li>Capabilities &amp; limits as editable records</li>
          <li>Deny-wins resolution, tightest limit applies</li>
          <li>Curative permits — breach only to move back toward compliance</li>
        </ul>
      </div>
      <div class="visual">
        <div class="topbar">
          <div class="dots"><span class="dot"></span><span class="dot"></span><span class="dot"></span></div>
          console / policies
        </div>
        <div class="inner">
          <PolicyMatrix policy={DEMO_POLICY} showUngoverned={false} query="" />
        </div>
      </div>
    </div>
  </div>
</section>

<section class="feat reverse last">
  <div class="wrap">
    <div class="grid">
      <div>
        <span class="num">08 — Interest &amp; Jobs</span>
        <h3>Runs itself <em>overnight.</em></h3>
        <p>
          A seeded daily job accrues interest with micro-unit precision, then
          capitalises it into the customer's spendable balance — one statement
          line each, and it ties to the penny. Operators set the cadence, or
          force a run.
        </p>
        <ul>
          <li>Micro-unit arithmetic · no floating point</li>
          <li>The ledger posted once a run, not once an account</li>
          <li>Scheduled, or force-run on demand</li>
        </ul>
      </div>
      <div class="visual">
        <div class="topbar">
          <div class="dots"><span class="dot"></span><span class="dot"></span><span class="dot"></span></div>
          jobs / daily-interest
        </div>
        <div class="inner">
          <div class="pipe-snap">
            <span class="pipe-label">accrue → capitalise</span>
            <TaskPipeline steps={DEMO_PIPELINE} />
          </div>
        </div>
      </div>
    </div>
  </div>
</section>

<section id="engineering" class="principles">
  <div class="wrap">
    <span class="eyebrow">Engineering choices</span>
    <h2>
      The interesting bits — <em>for engineers</em> who'd actually read the docs.
    </h2>
    <p class="lead">
      Queenswood is opinionated. Key choices that show up everywhere in the
      codebase, each documented, so you can read on a coffee break.
    </p>
    <div class="grid">
      {#each PRINCIPLES as p (p.key)}
        <Card
          variant={p.variant}
          href={p.href}
          onclick={openDoc(p.key)}
          target="_blank"
          rel="noreferrer"
        >
          <CardHeader kicker={p.kicker} title={p.title} />
          <CardBody>
            {@html p.body}
          </CardBody>
          <CardFooter><a href={p.href}>{slugFromHref(p.href)} →</a></CardFooter>
        </Card>
      {/each}
    </div>
  </div>
</section>

<section class="final">
  <span class="ornament" aria-hidden="true">
    <Logo variant="A" size={520} idPrefix="final-orn" />
  </span>
  <div class="wrap">
    <div class="grid">
      <div>
        <span class="eyebrow muted">Run it locally</span>
        <h2>One install away from a <em>working bank.</em></h2>
        <p>
          Install the chart, port-forward, open the SPA. Or start a REPL with <code
            >just repl</code
          > and bring the whole system up inside Testcontainers. Either way you're
          posting balanced transfers in minutes.
        </p>
        <div class="ctas">
          <button class="btn gold" onclick={goSignIn}>Sign in</button>
          <a target="_blank" rel="noreferrer"
            class="btn line"
            href="https://github.com/repldriven/queenswood#readme"
            >Read the quickstart</a
          >
        </div>
      </div>
      <div>
        <CodeCard filename="install · Kubernetes">
          <pre><span class="syn-comment"># Install the chart — Keycloak, the console, and all services.</span>
<span class="syn-keyword">helm</span> install queenswood \
  oci://ghcr.io/repldriven/queenswood \
  -n queenswood --create-namespace \
  --wait --timeout 10m

<span class="syn-comment"># Then port-forward the console and sign in via Keycloak.</span></pre>
        </CodeCard>
      </div>
    </div>
  </div>
</section>

<footer>
  <div class="wrap">
    <div class="row">
      <div class="brand-block">
        <a class="brand" href="/">
          <Logo variant="A" size={28} idPrefix="footer" />
          <span class="wm"><Wordmark variant="grotesk" size={14} /></span>
        </a>
        <p class="desc">
          Core banking, modernised. Bring your own ClearBank and Onfido —
          open source under MIT, with bundled simulators for development.
        </p>
      </div>
      <div>
        <h5>Product</h5>
        <ul>
          <li>
            <a target="_blank" rel="noreferrer"
              href="https://github.com/repldriven/queenswood/blob/main/docs/prd/onboarding.md"
              >Onboarding</a
            >
          </li>
          <li>
            <a target="_blank" rel="noreferrer"
              href="https://github.com/repldriven/queenswood/blob/main/docs/prd/parties.md"
              >Parties &amp; IDV</a
            >
          </li>
          <li>
            <a target="_blank" rel="noreferrer"
              href="https://github.com/repldriven/queenswood/blob/main/docs/prd/cash-accounts.md"
              >Cash accounts</a
            >
          </li>
          <li>
            <a target="_blank" rel="noreferrer"
              href="https://github.com/repldriven/queenswood/blob/main/docs/prd/payments.md"
              >Payments</a
            >
          </li>
          <li>
            <a target="_blank" rel="noreferrer"
              href="https://github.com/repldriven/queenswood/blob/main/docs/prd/interest.md"
              >Interest</a
            >
          </li>
          <li>
            <a target="_blank" rel="noreferrer"
              href="https://github.com/repldriven/queenswood/blob/main/docs/prd/policies.md"
              >Policies</a
            >
          </li>
        </ul>
      </div>
      <div>
        <h5>Developers</h5>
        <ul>
          <li>
            <a target="_blank" rel="noreferrer" href="https://github.com/repldriven/queenswood#readme">Docs</a>
          </li>
          <li>
            <a target="_blank" rel="noreferrer"
              href="https://github.com/repldriven/queenswood/tree/main/docs/recipes"
              >Recipes</a
            >
          </li>
          <li>
            <a target="_blank" rel="noreferrer"
              href="https://github.com/repldriven/queenswood/tree/main/docs/adr"
              >ADRs</a
            >
          </li>
          <li>
            <a target="_blank" rel="noreferrer" href="https://github.com/repldriven/queenswood/releases"
              >Releases</a
            >
          </li>
        </ul>
      </div>
      <div>
        <h5>Project</h5>
        <ul>
          <li><a target="_blank" rel="noreferrer" href="https://github.com/repldriven/queenswood">GitHub</a></li>
          <li>
            <a target="_blank" rel="noreferrer" href="https://github.com/repldriven/queenswood/issues">Issues</a>
          </li>
          <li>
            <a target="_blank" rel="noreferrer" href="https://github.com/repldriven/queenswood/blob/main/LICENSE"
              >MIT licence</a
            >
          </li>
        </ul>
      </div>
    </div>
    <div class="meta-row">
      <span>© Queenswood · MIT licensed</span>
      <span>v0.1.0 · open source</span>
    </div>
  </div>
</footer>

<style>
  :global(html),
  :global(body),
  :global(#app) {
    /* Landing sits on --surface (the base tier), matching the
       showcase. Cards and code panels live *above* this baseline
       on --surface-raised / --ink, so they read as elevated /
       depressed in both themes. Originally migrated to
       --surface-raised here too, which squashed CodeCard against
       the page in dark mode. */
    background: var(--surface);
    color: var(--fg);
    font-family: var(--grotesk);
    -webkit-font-smoothing: antialiased;
  }
  :global(a) {
    color: inherit;
    text-decoration: none;
  }
  :global(*),
  :global(*::before),
  :global(*::after) {
    box-sizing: border-box;
  }

  /* Always-dark punctuation bar at the very top: ink in both light
     and dark, with bone text. Doesn't theme — it's a constant accent
     element by design. */
  .announce {
    background: var(--ink);
    color: var(--bone);
    font-family: var(--mono);
    font-size: 11px;
    letter-spacing: 0.14em;
    text-transform: uppercase;
    padding: 10px 24px;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 14px;
  }
  .announce .pill {
    background: var(--gold);
    color: var(--ink);
    padding: 2px 8px;
    border-radius: 999px;
    font-weight: 500;
    font-size: 10px;
  }
  .announce a {
    color: var(--bone);
    opacity: 0.85;
  }
  .announce a:hover {
    opacity: 1;
  }
  .announce strong {
    color: var(--gold);
    font-weight: 500;
  }
  @media (max-width: 960px) {
    .announce {
      flex-direction: column;
      gap: 8px;
      padding: 12px 16px;
      text-align: center;
    }
  }

  .nav {
    position: sticky;
    top: 0;
    z-index: 30;
    background: var(--surface-translucent);
    backdrop-filter: saturate(140%) blur(10px);
    border-bottom: 1px solid var(--rule-2);
  }
  .nav-inner {
    max-width: 1280px;
    margin: 0 auto;
    padding: 14px 32px;
    display: flex;
    align-items: center;
    gap: 28px;
  }
  .brand {
    display: flex;
    align-items: center;
    gap: 10px;
  }
  .brand .wm {
    display: inline-block;
  }
  .nav ul {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    align-items: center;
    gap: 22px;
    font-size: 14px;
    color: var(--fg-2);
  }
  .nav ul li a {
    padding: 6px 2px;
    transition: color 0.15s;
  }
  .nav ul li a:hover {
    color: var(--fg);
  }
  .nav .spacer {
    flex: 1;
  }
  .nav .cta-row {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .btn {
    height: 36px;
    padding: 0 14px;
    display: inline-flex;
    align-items: center;
    gap: 8px;
    border-radius: 6px;
    font-size: 14px;
    font-weight: 500;
    letter-spacing: 0.005em;
    border: 1px solid transparent;
    cursor: pointer;
    transition:
      background 0.12s,
      border-color 0.12s,
      color 0.12s,
      transform 0.08s;
    font-family: var(--grotesk);
  }
  .btn:active {
    transform: translateY(0.5px);
  }
  .btn.ghost {
    color: var(--fg);
    background: transparent;
  }
  .btn.ghost:hover {
    background: var(--hover-overlay);
  }
  .btn.line {
    border-color: var(--rule);
    color: var(--fg);
    background: transparent;
  }
  .btn.line:hover {
    background: var(--hover-overlay);
  }
  .btn.solid {
    background: var(--fg);
    color: var(--surface);
  }
  .btn.solid:hover {
    /* Slightly-different shade of fg; inverts cleanly in dark mode
       where the button background is bone and this becomes bone-2. */
    background: var(--fg-2);
  }
  .btn.gold {
    background: var(--gold);
    /* Gold is constant across themes, so the text on it must be
       constant too — raw ink for high contrast in both modes. */
    color: var(--ink);
  }
  .btn.gold:hover {
    background: var(--gold-bright);
  }

  .wrap {
    max-width: 1280px;
    margin: 0 auto;
    padding: 0 32px;
  }

  .hero {
    padding: 80px 0 56px;
    position: relative;
    overflow: hidden;
  }
  .hero .grid {
    display: grid;
    grid-template-columns: 1.05fr 1fr;
    gap: 64px;
    align-items: center;
  }
  .eyebrow {
    font-family: var(--mono);
    font-size: 11px;
    letter-spacing: 0.2em;
    text-transform: uppercase;
    color: var(--fg-muted);
    display: inline-flex;
    align-items: center;
    gap: 8px;
  }
  .eyebrow.muted {
    color: rgba(244, 241, 234, 0.55);
  }
  .eyebrow::before {
    content: "";
    width: 18px;
    height: 1px;
    background: var(--gold-deep);
  }
  h1.title {
    font-family: var(--serif);
    font-weight: 500;
    font-size: 72px;
    line-height: 1.02;
    letter-spacing: -0.012em;
    margin: 18px 0 18px;
    max-width: 14ch;
    text-wrap: pretty;
  }
  h1.title em {
    font-style: italic;
    color: var(--gold-deep);
    font-weight: 500;
  }
  .lede {
    font-size: 19px;
    line-height: 1.55;
    color: var(--fg-2);
    max-width: 50ch;
    text-wrap: pretty;
  }
  .hero .ctas {
    display: flex;
    gap: 12px;
    margin-top: 28px;
    flex-wrap: wrap;
  }
  .hero .meta {
    display: flex;
    gap: 16px;
    align-items: center;
    margin-top: 24px;
    font-family: var(--mono);
    font-size: 11px;
    letter-spacing: 0.16em;
    text-transform: uppercase;
    color: var(--fg-muted);
  }
  .hero .meta .dot {
    width: 5px;
    height: 5px;
    border-radius: 50%;
    background: var(--pine-3);
  }

  /* Terminal/code panels and ADR cards are now CodeCard + Card from
     ui; the chrome/typography lives in the design system. */

  .hero .ornament,
  .final .ornament {
    position: absolute;
    pointer-events: none;
    opacity: 0.07;
  }
  .hero .ornament {
    right: -120px;
    top: 40px;
    width: 460px;
    height: 460px;
  }
  .final .ornament {
    left: -120px;
    bottom: -120px;
    width: 520px;
    height: 520px;
    color: var(--surface);
  }

  .capabilities {
    border-top: 1px solid var(--rule);
    border-bottom: 1px solid var(--rule);
    padding: 56px 0;
  }
  .cap-grid {
    display: grid;
    grid-template-columns: repeat(6, 1fr);
    gap: 0;
  }
  .cap {
    padding: 0 20px;
    border-left: 1px solid var(--rule-2);
    display: flex;
    flex-direction: column;
    gap: 8px;
  }
  .cap:first-child {
    border-left: 0;
    padding-left: 0;
  }
  .cap:last-child {
    padding-right: 0;
  }
  .cap-label {
    font-family: var(--mono);
    font-size: 11px;
    letter-spacing: 0.2em;
    text-transform: uppercase;
    color: var(--gold-deep);
  }
  .cap-desc {
    margin: 0;
    font-size: 14px;
    line-height: 1.5;
    color: var(--fg-2);
    text-wrap: pretty;
  }
  @media (max-width: 1024px) {
    .cap-grid {
      grid-template-columns: repeat(3, 1fr);
      row-gap: 32px;
    }
    .cap:nth-child(3n + 1) {
      border-left: 0;
      padding-left: 0;
    }
  }
  @media (max-width: 640px) {
    .cap-grid {
      grid-template-columns: repeat(2, 1fr);
    }
    .cap {
      border-left: 0;
      padding-left: 0;
    }
    .cap:nth-child(3n + 1) {
      border-left: 0;
    }
  }

  .feat {
    padding: 96px 0;
    border-bottom: 1px solid var(--rule-2);
  }
  .feat.last {
    border-bottom: none;
  }
  .feat .grid {
    display: grid;
    grid-template-columns: 5fr 6fr;
    gap: 80px;
    align-items: center;
  }
  .feat.reverse .grid {
    grid-template-columns: 6fr 5fr;
  }
  .feat.reverse .grid > :first-child {
    order: 2;
  }
  .feat .num {
    font-family: var(--mono);
    font-size: 11px;
    letter-spacing: 0.2em;
    color: var(--gold-deep);
    text-transform: uppercase;
  }
  .feat h3 {
    font-family: var(--serif);
    font-weight: 500;
    font-size: 46px;
    line-height: 1.08;
    letter-spacing: -0.008em;
    margin: 14px 0 18px;
    max-width: 18ch;
    text-wrap: pretty;
  }
  .feat h3 em {
    font-style: italic;
    color: var(--gold-deep);
  }
  .feat p {
    font-size: 17px;
    line-height: 1.6;
    color: var(--fg-2);
    max-width: 50ch;
    margin: 0;
  }
  .feat ul {
    list-style: none;
    margin: 26px 0 28px;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 10px;
  }
  .feat ul li {
    display: flex;
    gap: 12px;
    align-items: baseline;
    font-size: 15px;
    color: var(--fg-2);
  }
  .feat ul li::before {
    content: "";
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--pine-3);
    transform: translateY(-2px);
    flex: 0 0 6px;
  }
  .feat code {
    font-family: var(--mono);
    font-size: 13px;
  }

  .visual {
    background: var(--surface-raised);
    border-radius: 12px;
    border: 1px solid var(--rule);
    box-shadow:
      0 1px 0 rgba(255, 255, 255, 0.7) inset,
      0 24px 60px -32px rgba(20, 15, 10, 0.3);
    overflow: hidden;
    min-height: 280px;
    position: relative;
  }
  .visual .topbar {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 10px 14px;
    border-bottom: 1px solid var(--rule-2);
    background: rgba(20, 15, 10, 0.02);
    font-family: var(--mono);
    font-size: 11px;
    letter-spacing: 0.16em;
    text-transform: uppercase;
    color: var(--fg-muted);
  }
  .visual .topbar .dots {
    display: flex;
    gap: 5px;
    margin-right: 6px;
  }
  .visual .topbar .dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: rgba(20, 15, 10, 0.18);
  }
  .visual .inner {
    padding: 22px;
    height: auto;
    max-height: 440px;
    display: flex;
    flex-direction: column;
    gap: 14px;
    overflow: auto;
  }

  /* Story band — frames the feature screens as the Scenarios arc. */
  .story {
    padding: 88px 0 24px;
    text-align: center;
  }
  .story h2 {
    font-family: var(--serif);
    font-weight: 500;
    font-size: 46px;
    line-height: 1.06;
    letter-spacing: -0.008em;
    margin: 10px 0 14px;
  }
  .story h2 em {
    font-style: italic;
    color: var(--gold-deep);
  }
  .story .lead {
    font-size: 17px;
    color: var(--fg-2);
    line-height: 1.55;
    max-width: 60ch;
    margin: 0 auto 36px;
    text-wrap: pretty;
  }
  .story .eyebrow {
    justify-content: center;
  }
  .spine-wrap {
    display: flex;
    flex-direction: column;
    gap: 20px;
    text-align: left;
  }

  /* Accounts snippet — a balance headline above a few postings. */
  .acct-snap {
    display: flex;
    flex-direction: column;
    gap: 16px;
  }
  .snap-head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
  }
  .snap-figs {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
  .snap-label {
    font-family: var(--mono);
    font-size: 10px;
    letter-spacing: 0.16em;
    text-transform: uppercase;
    color: var(--fg-muted);
  }
  .snap-figure {
    font-family: var(--mono);
    font-variant-numeric: tabular-nums;
    font-weight: 500;
    font-size: 32px;
    line-height: 1;
    letter-spacing: -0.01em;
    color: var(--fg);
  }
  .snap-ccy {
    font-size: 15px;
    color: var(--fg-muted);
    margin-left: 7px;
    letter-spacing: 0.04em;
  }
  .snap-coords {
    font-family: var(--mono);
    font-size: 12px;
    color: var(--fg-muted);
    margin-top: 2px;
  }
  .amt {
    font-family: var(--mono);
    font-variant-numeric: tabular-nums;
  }
  .amt.credit {
    color: var(--pos);
  }
  .amt.debit {
    color: var(--fg);
  }

  /* Jobs snippet — the task pipeline with a mono kicker. */
  .pipe-snap {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }
  .pipe-label {
    font-family: var(--mono);
    font-size: 11px;
    letter-spacing: 0.12em;
    color: var(--gold-deep);
  }

  .principles {
    padding: 96px 0;
    border-bottom: 1px solid var(--rule-2);
  }
  .principles h2 {
    font-family: var(--serif);
    font-weight: 500;
    font-size: 50px;
    line-height: 1.06;
    letter-spacing: -0.008em;
    margin: 8px 0 14px;
    max-width: 22ch;
    text-wrap: pretty;
  }
  .principles h2 em {
    font-style: italic;
    color: var(--gold-deep);
  }
  .principles .lead {
    font-size: 17px;
    color: var(--fg-2);
    max-width: 56ch;
    margin: 0 0 48px;
    line-height: 1.55;
  }
  /* Cards in the principles grid are ui Card primitives now;
     this rule just owns the layout. Equal-height min-height matches
     the ui showcase's card-grid-3 convention. */
  .principles .grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 28px;
  }
  .principles .grid > * {
    min-height: 200px;
  }

  /* Always-dark "Run it locally" CTA section. Constant ink across
     themes — its job is to be a strong visual finish to the page. */
  .final {
    background: var(--ink);
    color: var(--bone);
    padding: 96px 0;
    position: relative;
    overflow: hidden;
  }
  .final h2 {
    font-family: var(--serif);
    font-weight: 500;
    font-size: 60px;
    line-height: 1.04;
    letter-spacing: -0.01em;
    margin: 0 0 14px;
    max-width: 18ch;
    text-wrap: pretty;
  }
  .final h2 em {
    color: var(--gold);
    font-style: italic;
  }
  .final p {
    color: rgba(244, 241, 234, 0.7);
    font-size: 17px;
    line-height: 1.55;
    max-width: 52ch;
    margin: 0 0 32px;
  }
  .final code {
    font-family: var(--mono);
    font-size: 14px;
  }
  .final .grid {
    display: grid;
    grid-template-columns: 1.05fr 1fr;
    gap: 80px;
    align-items: center;
  }
  .final .ctas {
    display: flex;
    gap: 12px;
    flex-wrap: wrap;
  }
  .final .btn.line {
    border-color: rgba(244, 241, 234, 0.25);
    color: var(--bone);
    background: transparent;
  }
  .final .btn.line:hover {
    background: rgba(244, 241, 234, 0.06);
  }

  footer {
    padding: 64px 0 40px;
    color: var(--fg-2);
    background: var(--surface-raised);
  }
  footer .row {
    display: grid;
    grid-template-columns: 2fr repeat(3, 1fr);
    gap: 40px;
    padding-bottom: 40px;
    border-bottom: 1px solid var(--rule);
  }
  footer h5 {
    font-family: var(--mono);
    font-size: 11px;
    letter-spacing: 0.2em;
    text-transform: uppercase;
    color: var(--fg-muted);
    font-weight: 500;
    margin: 0 0 16px;
  }
  footer ul {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 10px;
    font-size: 14px;
  }
  footer ul a:hover {
    color: var(--fg);
  }
  footer .brand-block .desc {
    margin-top: 14px;
    color: var(--fg-muted);
    font-size: 13px;
    line-height: 1.55;
    max-width: 32ch;
  }
  footer .meta-row {
    padding-top: 20px;
    display: flex;
    justify-content: space-between;
    align-items: center;
    font-family: var(--mono);
    font-size: 11px;
    letter-spacing: 0.14em;
    text-transform: uppercase;
    color: var(--fg-muted);
  }
</style>
