<script>
  /*
    Living showcase for @queenswood/ui.
    Mounts the real exported components — this page IS the spec. If it
    looks wrong, the components are wrong; if you change a component,
    refresh this page and the truth updates with it. No HTML mockups,
    no porting step.

    Sections: Marks, Wordmark, Chrome, Surfaces, Pines, Golds, Type,
              Buttons, Badges, Fields, Tables, PageHeader, Sidenav, Drawer.
  */

  import {
    Logo, Wordmark, AppNav, ThemeToggle,
    Button, Badge, AccountStatusBadge,
    SearchField,
    Sidenav, SidenavGroup, SidenavItem,
    PageHeader, Drawer,
    Table, Thead, Tbody, Tr, Th, Td,
    Expander, MoneyCell, Phase, sumMinor,
    TrialBalance,
    PolicyMatrix, CATEGORY_TONE,
    Field, Input, Select,
    Panel, PanelHead, Chip, ToastHost, toast,
    RolePill, Tabs, Tag, Callout, Textarea, TokenBox,
    ProductPicker, VersionList, MigrationStatusBadge,
    Card, CardHeader, CardBody, CardFooter, CodeCard,
    ProgressSpine, BankStateBand, SceneCard, RawCalls, TaskPipeline,
    themeState, resolvedTheme,
  } from "../src/index.js";

  const SECTIONS = [
    { id: "marks",      label: "Marks" },
    { id: "wordmark",   label: "Wordmark" },
    { id: "chrome",     label: "Chrome" },
    { id: "surfaces",   label: "Surfaces" },
    { id: "pines",      label: "Forest greens" },
    { id: "golds",      label: "Crown golds" },
    { id: "type",       label: "Type" },
    { id: "buttons",    label: "Buttons" },
    { id: "badges",     label: "Badges" },
    { id: "fields",     label: "Fields" },
    { id: "searchfield", label: "Search field" },
    { id: "tables",     label: "Tables" },
    { id: "pageheader", label: "PageHeader" },
    { id: "sidenav",    label: "Sidenav" },
    { id: "drawer",     label: "Drawer" },
    { id: "cards",      label: "Cards" },
    { id: "ledger",     label: "Ledger tree-table" },
    { id: "policies",   label: "Policies" },
    { id: "trial-balance",  label: "Trial balance band" },
    { id: "progressspine",  label: "Progress spine" },
    { id: "bankstateband",  label: "Bank-state band" },
    { id: "scenecard",      label: "Scene card" },
    { id: "rawcalls",       label: "Raw calls" },
    { id: "panels",         label: "Panels" },
    { id: "chips",          label: "Chips" },
    { id: "toast",          label: "Toast" },
    { id: "access",         label: "People and access" },
    { id: "migrations",     label: "Migration pickers" },
  ];

  // Migration primitive demos
  let chipFilter = $state("all");
  let accessTab = $state("members");
  let accessReason = $state("Joining the payments team");
  let pickerOpen = $state(false);
  let pickedProduct = $state("prd.instant-access-savings");
  let sourceVersions = $state(["prv.v2", "prv.v3"]);
  let targetVersion = $state("prv.v4");

  const DEMO_PRODUCTS = [
    { id: "prd.instant-access-savings", name: "Instant Access Savings", type: "savings", publishedCount: 4, accountCount: 9588 },
    { id: "prd.youth-saver", name: "Youth Saver", type: "savings", publishedCount: 2, accountCount: 1204 },
    { id: "prd.fixed-term-12m", name: "12-Month Fixed Term", type: "deposit", publishedCount: 3, accountCount: 431 },
  ];

  const DEMO_VERSIONS = [
    { id: "prv.v1", number: 1, published: true, meta: "2.10%", from: "from 1 Jan 2024", right: "312" },
    { id: "prv.v2", number: 2, published: true, meta: "2.75%", from: "from 1 Jun 2025", right: "4,881" },
    { id: "prv.v3", number: 3, published: true, meta: "3.10%", from: "from 1 Jan 2026", right: "4,307" },
    { id: "prv.v4", number: 4, published: true, meta: "3.40%", from: "from 1 Sep 2026", right: "GBP" },
    { id: "prv.v5", number: 5, published: false, meta: "3.55% · draft", from: null, right: "GBP" },
  ];

  // Scenario sandbox demos
  let scOpen = $state(true);

  const VARIANTS = [
    { id: "A", note: "Eight-tree forest, gold band + teeth, baubles." },
    { id: "B", note: "Variant A plus jewels seated in the teeth." },
    { id: "C", note: "Monoline forest + crown stroke; bone-filled baubles." },
    { id: "D", note: "Four-tree forest, five-tooth crown, narrower band." },
    { id: "E", note: "Variant A inset in concentric seal rings." },
  ];

  const LOGO_SIZES = [200, 120, 80, 48, 32, 24];
  const WM_GROTESK_SIZES = [48, 32, 24, 18, 14, 12];
  const WM_SERIF_SIZES   = [72, 48, 32, 24, 18];

  const SURFACES = [
    { name: "bone",   value: "#f4f1ea",                 onDark: false },
    { name: "bone-2", value: "#ebe6dc",                 onDark: false },
    { name: "paper",  value: "#fbf9f4",                 onDark: false },
    { name: "ink",    value: "#161310",                 onDark: true  },
    { name: "ink-2",  value: "#2a2622",                 onDark: true  },
    { name: "muted",  value: "#6b645b",                 onDark: true  },
    { name: "rule",   value: "rgba(20, 15, 10, 0.10)",  onDark: false, overlay: true },
    { name: "rule-2", value: "rgba(20, 15, 10, 0.06)",  onDark: false, overlay: true },
  ];

  const PINES = [
    { name: "pine-1", value: "oklch(0.28 0.045 150)" },
    { name: "pine-2", value: "oklch(0.36 0.055 148)" },
    { name: "pine-3", value: "oklch(0.44 0.060 145)" },
    { name: "pine-4", value: "oklch(0.52 0.060 142)" },
    { name: "pine-5", value: "oklch(0.60 0.055 140)" },
  ];

  const GOLDS = [
    { name: "gold",        value: "oklch(0.66 0.135 72)" },
    { name: "gold-bright", value: "oklch(0.78 0.140 80)" },
    { name: "gold-deep",   value: "oklch(0.52 0.120 68)" },
    { name: "gold-1",      value: "oklch(0.88 0.155 92)" },
    { name: "gold-2",      value: "oklch(0.80 0.175 86)" },
    { name: "gold-3",      value: "oklch(0.70 0.180 78)" },
    { name: "gold-4",      value: "oklch(0.56 0.160 68)" },
    { name: "gold-5",      value: "oklch(0.42 0.125 58)" },
  ];

  const DEMO_USER = {
    name: "Alex Morgan",
    "avatar-url": "data:image/svg+xml;utf8," + encodeURIComponent(
      `<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 40 40'>
         <rect width='40' height='40' fill='%23ebe6dc'/>
         <circle cx='20' cy='16' r='7' fill='%236b645b'/>
         <path d='M5 40c2-9 8-13 15-13s13 4 15 13' fill='%236b645b'/>
       </svg>`
    ),
  };

  function noop() {}

  // Demo state for Fields + Drawer sections. Fields and Drawer share
  // bindings so editing in one section updates the other — useful to
  // see the binding semantics live.
  let scSearch = $state("");
  let demoName = $state("GBP Current Account");
  let demoType = $state("Current Account");
  let demoCcy  = $state("GBP");
  let demoRate = $state(265);
  let drawerDemoOpen = $state(false);

  // Demo state for the ledger tree-table (§16). Shapes mirror what the
  // console Ledger page builds from the ledger API: each balance is
  // keyed by (balance-type, balance-status) and its `minor` is the signed
  // net (credit − debit, credit-positive). The account total is the sum.
  const LEDGER_ACCOUNTS = [
    {
      id: "led.01jq8wm4zr5k2c7d9f3h6n0pqx",
      name: "Customer Deposits — GBP Pool", gl: "2100", ccy: "GBP",
      balances: [
        { type: "default", phase: "posted",            currency: "GBP", minor:  482014055 },
        { type: "default", phase: "pending-incoming",  currency: "GBP", minor:    1230000 },
        { type: "default", phase: "pending-outgoing",  currency: "GBP", minor:   -4598020 },
      ],
    },
    {
      id: "led.01jq8wmd2k7n3p6r9t1w4y8b5e",
      name: "Cash at Correspondent — GBP", gl: "1100", ccy: "GBP",
      balances: [
        { type: "default",          phase: "posted", currency: "GBP", minor: -12845000 },
        { type: "interest-accrued", phase: "posted", currency: "GBP", minor:   -120466 },
      ],
    },
    {
      id: "led.01jq8wmr3w6y9b2d5g8k1n4q7t",
      name: "Settlement Suspense — GBP", gl: "1900", ccy: "GBP",
      balances: [
        { type: "default", phase: "posted",           currency: "GBP", minor:        0 },
        { type: "default", phase: "pending-incoming", currency: "GBP", minor:  2450000 },
        { type: "default", phase: "pending-outgoing", currency: "GBP", minor: -2450000 },
      ],
    },
  ];

  let ledgerOpen = $state(
    Object.fromEntries(LEDGER_ACCOUNTS.map((a) => [a.id, true])),
  );
  const ledgerToggle = (id) => (ledgerOpen[id] = !ledgerOpen[id]);
  const ledgerKey = (e, id) => {
    if (e.key === "Enter" || e.key === " ") { e.preventDefault(); ledgerToggle(id); }
  };

  // Demo state for the policy matrix (§17). The two sample policies are
  // already in the flattened view-model shape (the console adapter
  // produces this from the wire) — amounts in MINOR units.
  const cap = (effect, domain, action, reason, filters = []) =>
    ({ effect, domain, action, reason, filters });
  const lim = (domain, bound, reason, extra = {}) =>
    ({ domain, bound, reason, filters: extra.filters ?? [], allow: extra.allow ?? null });
  const maxC = (value, window) => ({ kind: "max", aggregate: { type: "count", value, window } });
  const minA = (minor, ccy, window) => ({ kind: "min", aggregate: { type: "amount", minor, ccy, window } });
  const rangeA = (loMinor, hiMinor, ccy, window) => ({
    kind: "range",
    min: { type: "amount", minor: loMinor, ccy, window },
    max: { type: "amount", minor: hiMinor, ccy, window },
  });

  const POLICIES = [
    {
      policyId: "pol.00000000000000000000000001",
      name: "Platform policy",
      description: "The platform baseline bound to every bank. Grants every domain action and caps each with a platform ceiling.",
      enabled: true,
      category: "restricted",
      capabilities: [
        cap("allow", "balance", "create", "Allow creating balances"),
        cap("allow", "balance", "apply", "Allow applying transaction legs to balances"),
        cap("allow", "cash_account", "open", "Allow opening cash accounts"),
        cap("allow", "outbound_payment", "send", "Allow outbound payments"),
        cap("allow", "party", "create", "Allow creating parties"),
      ],
      limits: [
        lim("balance", minA(0, "GBP", "instant"), "Available balance must stay non-negative on user-driven transfers.", {
          allow: "improving",
          filters: [{ key: "computed", value: "available" }, { key: "txn", value: "internal-transfer" }, { key: "txn", value: "outbound-transfer" }],
        }),
        lim("outbound_payment", maxC(100000, "daily"), "Max 100,000 outbound payments per day."),
        lim("party", maxC(100000, "instant"), "Max 100,000 parties."),
      ],
    },
    {
      policyId: "pol.0000000000000000000000000a",
      name: "Standard tier policy",
      description: "The everyday tenant tier. Move money over Faster Payments within daily ceilings; cannot publish products.",
      enabled: true,
      category: "standard",
      capabilities: [
        cap("allow", "party", "create", "Register natural-person customers.", [{ key: "type", value: "natural-person" }]),
        cap("allow", "outbound_payment", "send", "Send via UK Faster Payments.", [{ key: "scheme", value: "fps" }]),
        cap("deny", "cash_account_product", "publish", "Standard tenants cannot publish their own products."),
      ],
      limits: [
        lim("outbound_payment", rangeA(100, 1_000_000, "GBP", "instant"), "Single outbound payment between £1 and £10,000.", {
          filters: [{ key: "scheme", value: "fps" }],
        }),
        lim("outbound_payment", { kind: "max", aggregate: { type: "amount", minor: 2_500_000, ccy: "GBP", window: "daily" } }, "Up to £25,000 sent per day."),
      ],
    },
  ];

  let policySelected = $state(0);
  let policyShowUngoverned = $state(false);
  let policyQuery = $state("");
  const selectedPolicy = $derived(POLICIES[policySelected]);
</script>

<AppNav user={DEMO_USER} onSignOut={noop} />

<div class="page">
  <aside class="rail">
    <div class="rail-head">
      <span class="rail-eyebrow">ui · v0.0.0</span>
      <span class="rail-title">Queenswood</span>
      <span class="rail-sub">Design system showcase</span>
    </div>
    <nav class="rail-nav">
      {#each SECTIONS as s (s.id)}
        <a href="#{s.id}">{s.label}</a>
      {/each}
    </nav>
    <p class="rail-foot">
      Real components, real tokens. Edit <code>ui/src/</code> and refresh.
    </p>
  </aside>

  <main class="main">
    <header class="lede">
      <div class="eyebrow">Living spec</div>
      <h1>Queenswood, in parts.</h1>
      <p>
        Every mark, swatch, and primitive on this page is mounted from the
        real exports of <code>@queenswood/ui</code>. There is no port,
        no mockup, no second source of truth.
      </p>
      <p class="lede-meta">
        Theme: pref <code>{themeState.pref}</code>, resolved <code>{resolvedTheme()}</code>.
        Cycle from the AppNav above.
      </p>
    </header>

    <!-- =================== 01 Marks =================== -->
    <section id="marks" class="section">
      <div class="section-head">
        <span class="kicker">01 — Marks</span>
        <h2>The crowned forest, in five voices.</h2>
        <p class="lead">Five logo variants ship today. Pick by context: A for general use, B when the chrome needs a beat more decoration, C for monoline applications, D where horizontal space is tight, E for seals and stamps.</p>
      </div>

      <div class="marks-row">
        {#each VARIANTS as v (v.id)}
          <figure class="mark-card">
            <div class="mark-frame">
              <Logo variant={v.id} size={132} idPrefix="sc-{v.id}" />
            </div>
            <figcaption>
              <span class="mark-tag">Mark {v.id}</span>
              <span class="mark-note">{v.note}</span>
            </figcaption>
          </figure>
        {/each}
      </div>

      <div class="subhead">
        <h3>Size scale — Mark A</h3>
        <p>Geometry is intrinsically forgiving down to ~24 px; below that the band reads as a slab. The forest dissolves first.</p>
      </div>
      <div class="size-row">
        {#each LOGO_SIZES as size (size)}
          <div class="size-cell">
            <div class="size-frame" style:height="{size + 24}px">
              <Logo variant="A" size={size} idPrefix="sc-sz-{size}" />
            </div>
            <span class="size-label">{size}px</span>
          </div>
        {/each}
      </div>
    </section>

    <!-- =================== 02 Wordmark =================== -->
    <section id="wordmark" class="section">
      <div class="section-head">
        <span class="kicker">02 — Wordmark</span>
        <h2>Two voices, one name.</h2>
        <p class="lead">Grotesk for product chrome, where it sits next to data and form controls. Serif for marketing, mastheads, and anywhere the name needs to slow the eye down.</p>
      </div>

      <div class="wm-grid">
        <div class="wm-col">
          <div class="wm-col-head">
            <span class="wm-name">Grotesk</span>
            <span class="wm-meta">Geist · 300/500 · 0.18em</span>
          </div>
          {#each WM_GROTESK_SIZES as s (s)}
            <div class="wm-row">
              <Wordmark variant="grotesk" size={s} />
              <span class="wm-size">{s}px</span>
            </div>
          {/each}
        </div>

        <div class="wm-col">
          <div class="wm-col-head">
            <span class="wm-name">Serif</span>
            <span class="wm-meta">Cormorant Garamond · 500 · 0.04em</span>
          </div>
          {#each WM_SERIF_SIZES as s (s)}
            <div class="wm-row">
              <Wordmark variant="serif" size={s} />
              <span class="wm-size">{s}px</span>
            </div>
          {/each}
        </div>
      </div>
    </section>

    <!-- =================== 03 Chrome =================== -->
    <section id="chrome" class="section">
      <div class="section-head">
        <span class="kicker">03 — Chrome</span>
        <h2>AppNav &amp; theme toggle.</h2>
        <p class="lead">Sticky bar for authenticated screens. Backdrop-blurred surface, hairline rule below. The instance live at the top of this page is the same component — click the half-disc icon to cycle <code>auto → light → dark → auto</code>.</p>
      </div>

      <div class="nav-demo-stack">
        <div class="nav-demo">
          <div class="nav-demo-tag">Signed in · name + avatar</div>
          <div class="nav-demo-frame">
            <AppNav user={DEMO_USER} onSignOut={noop} />
            <div class="nav-demo-body"></div>
          </div>
        </div>

        <div class="nav-demo">
          <div class="nav-demo-tag">Signed in · name only</div>
          <div class="nav-demo-frame">
            <AppNav user={{ name: "Alex Morgan" }} onSignOut={noop} />
            <div class="nav-demo-body"></div>
          </div>
        </div>

        <div class="nav-demo">
          <div class="nav-demo-tag">Signed in · no user yet</div>
          <div class="nav-demo-frame">
            <AppNav user={null} onSignOut={noop} />
            <div class="nav-demo-body"></div>
          </div>
        </div>
      </div>

      <div class="subhead">
        <h3>ThemeToggle</h3>
        <p>The cycler in isolation. Reflects the current preference; click cycles. The resolved theme is what you see right now — if pref is <code>auto</code> and the system is dark, resolved is <code>dark</code>.</p>
      </div>
      <div class="toggle-demo">
        <ThemeToggle />
        <dl class="toggle-state">
          <div><dt>pref</dt><dd><code>{themeState.pref}</code></dd></div>
          <div><dt>resolved</dt><dd><code>{resolvedTheme()}</code></dd></div>
          <div><dt>system</dt><dd><code>{themeState.systemDark ? "dark" : "light"}</code></dd></div>
        </dl>
      </div>
    </section>

    <!-- =================== 04 Surfaces =================== -->
    <section id="surfaces" class="section">
      <div class="section-head">
        <span class="kicker">04 — Surfaces</span>
        <h2>Paper, bone, ink.</h2>
        <p class="lead">The raw palette. Same hex values in both themes — a bone swatch is always cream. For backgrounds and text in components, prefer the semantic tokens (<code>--surface</code>, <code>--surface-raised</code>, <code>--fg</code>, <code>--fg-2</code>, <code>--fg-muted</code>), which adapt automatically.</p>
      </div>

      <div class="swatch-grid">
        {#each SURFACES as t (t.name)}
          <div class="swatch">
            <div
              class="swatch-block"
              class:overlay={t.overlay}
              style:background={t.value}
              style:color={t.onDark ? "var(--paper)" : "var(--ink)"}
            >
              <span class="swatch-aa">Aa</span>
            </div>
            <div class="swatch-meta">
              <span class="swatch-name">--{t.name}</span>
              <span class="swatch-value">{t.value}</span>
            </div>
          </div>
        {/each}
      </div>
    </section>

    <!-- =================== 05 Pines =================== -->
    <section id="pines" class="section">
      <div class="section-head">
        <span class="kicker">05 — Forest greens</span>
        <h2>Pine, 1 through 5.</h2>
        <p class="lead">All five share chroma ~0.05 around hue 142–150. Use them as a depth scale, not as semantic states.</p>
      </div>

      <div class="ramp">
        {#each PINES as t (t.name)}
          <div class="ramp-step" style:background={t.value}>
            <span class="ramp-name">--{t.name}</span>
            <span class="ramp-value">{t.value}</span>
          </div>
        {/each}
      </div>
    </section>

    <!-- =================== 06 Golds =================== -->
    <section id="golds" class="section">
      <div class="section-head">
        <span class="kicker">06 — Crown golds</span>
        <h2>Three crown tones, one bright ramp.</h2>
        <p class="lead"><code>gold</code>, <code>gold-bright</code>, and <code>gold-deep</code> drive the crown itself. The numbered ramp <code>gold-1</code>—<code>gold-5</code> is for accents, highlights, and the inside of borders.</p>
      </div>

      <div class="swatch-grid">
        {#each GOLDS as t (t.name)}
          <div class="swatch">
            <div class="swatch-block" style:background={t.value} style:color="var(--ink)">
              <span class="swatch-aa">Aa</span>
            </div>
            <div class="swatch-meta">
              <span class="swatch-name">--{t.name}</span>
              <span class="swatch-value">{t.value}</span>
            </div>
          </div>
        {/each}
      </div>
    </section>

    <!-- =================== 07 Type =================== -->
    <section id="type" class="section">
      <div class="section-head">
        <span class="kicker">07 — Type</span>
        <h2>Three stacks, one feeling.</h2>
        <p class="lead">Grotesk carries product copy; serif carries the brand; mono carries the receipts.</p>
      </div>

      <article class="spec">
        <header class="spec-head">
          <span class="spec-name">Grotesk</span>
          <code class="spec-stack">--grotesk &nbsp;·&nbsp; 'Geist', ui-sans-serif, system-ui, sans-serif</code>
        </header>
        <div class="spec-display grotesk">A handsome forest, well-kept.</div>
        <p class="spec-body grotesk">
          Geist is the working face. Used for buttons, fields, dashboard chrome, body
          copy on product surfaces — anywhere the reader is here to get something done.
          Numerals are lining and tabular by default, which matters more than it should.
        </p>
        <div class="spec-scale grotesk">
          <span style="font-size:48px;font-weight:600">Aa</span>
          <span style="font-size:32px;font-weight:500">Aa</span>
          <span style="font-size:20px;font-weight:500">Aa</span>
          <span style="font-size:14px;font-weight:500">Aa</span>
          <span style="font-size:12px;font-weight:400">Aa</span>
        </div>
      </article>

      <article class="spec">
        <header class="spec-head">
          <span class="spec-name">Serif</span>
          <code class="spec-stack">--serif &nbsp;·&nbsp; 'Cormorant Garamond', ui-serif, Georgia, serif</code>
        </header>
        <div class="spec-display serif">A handsome forest, well-kept.</div>
        <p class="spec-body serif">
          Cormorant is the brand face. It sits on the landing page, in section
          headers, and anywhere the word "Queenswood" needs to slow the eye for half
          a beat. Set it generously — at small sizes its features collapse and it
          starts looking like a different font.
        </p>
        <div class="spec-scale serif">
          <span style="font-size:72px;font-weight:500">Aa</span>
          <span style="font-size:48px;font-weight:500">Aa</span>
          <span style="font-size:32px;font-weight:500">Aa</span>
          <span style="font-size:20px;font-weight:400">Aa</span>
        </div>
      </article>

      <article class="spec">
        <header class="spec-head">
          <span class="spec-name">Mono</span>
          <code class="spec-stack">--mono &nbsp;·&nbsp; 'Geist Mono', ui-monospace, monospace</code>
        </header>
        <div class="spec-display mono">A handsome forest, well-kept.</div>
        <p class="spec-body mono">
          Geist Mono carries the receipts: token names, ids, amounts in fixed
          tables, anything that wants to line up vertically. It is deliberately
          rarely used; when it appears, it means "this is data".
        </p>
        <div class="spec-scale mono">
          <span style="font-size:32px">Aa</span>
          <span style="font-size:20px">Aa</span>
          <span style="font-size:14px">Aa</span>
          <span style="font-size:12px">Aa</span>
        </div>
      </article>
    </section>

    <!-- =================== 08 Buttons =================== -->
    <section id="buttons" class="section">
      <div class="section-head">
        <span class="kicker">08 — Buttons</span>
        <h2>Five intents, three sizes.</h2>
        <p class="lead">Variants encode intent, not color — components ask for "primary" or "danger" and <code>tokens.css</code> decides the actual fill. Pair with sizes (<code>sm</code>, <code>md</code>, <code>lg</code>) and modifiers (<code>block</code>, <code>solid</code> on danger).</p>
      </div>

      <div class="btn-matrix">
        <div class="btn-row">
          <span class="btn-label">primary</span>
          <Button variant="primary" size="sm">Save</Button>
          <Button variant="primary">Save</Button>
          <Button variant="primary" size="lg">Save</Button>
        </div>
        <div class="btn-row">
          <span class="btn-label">brand</span>
          <Button variant="brand" size="sm">Publish</Button>
          <Button variant="brand">Publish</Button>
          <Button variant="brand" size="lg">Publish</Button>
        </div>
        <div class="btn-row">
          <span class="btn-label">line</span>
          <Button variant="line" size="sm">Refresh</Button>
          <Button variant="line">Refresh</Button>
          <Button variant="line" size="lg">Refresh</Button>
        </div>
        <div class="btn-row">
          <span class="btn-label">ghost</span>
          <Button variant="ghost" size="sm">Edit</Button>
          <Button variant="ghost">Edit</Button>
          <Button variant="ghost" size="lg">Edit</Button>
        </div>
        <div class="btn-row">
          <span class="btn-label">danger</span>
          <Button variant="danger" size="sm">Discard</Button>
          <Button variant="danger">Discard</Button>
          <Button variant="danger" solid size="lg">Delete forever</Button>
        </div>
      </div>

      <div class="subhead">
        <h3>Block (full width)</h3>
        <p>Use inside a Drawer footer, or anywhere the action owns the row.</p>
      </div>
      <div class="btn-block-frame">
        <Button variant="primary" size="lg" block>Create product</Button>
      </div>

      <div class="subhead">
        <h3>Disabled</h3>
        <p>Opacity drops, pointer becomes "not-allowed", click animation is suppressed.</p>
      </div>
      <div class="btn-row">
        <Button variant="primary" disabled>Save</Button>
        <Button variant="brand" disabled>Publish</Button>
        <Button variant="line" disabled>Refresh</Button>
        <Button variant="ghost" disabled>Edit</Button>
        <Button variant="danger" disabled>Discard</Button>
      </div>
    </section>

    <!-- =================== 09 Badges =================== -->
    <section id="badges" class="section">
      <div class="section-head">
        <span class="kicker">09 — Badges</span>
        <h2>Status pills.</h2>
        <p class="lead">Leading dot, lowercased label. Five tones cover the common lifecycle states; <code>neutral</code> is the fallback when nothing specific fits.</p>
      </div>

      <div class="badge-row">
        <Badge tone="draft">draft</Badge>
        <Badge tone="published">published</Badge>
        <Badge tone="archived">archived</Badge>
        <Badge tone="pending">pending</Badge>
        <Badge tone="neutral">neutral</Badge>
      </div>

      <p class="lead">Invitation states: <code>pending</code>, <code>accepted</code>, <code>expired</code>, <code>withdrawn</code> and <code>declined</code>.</p>
      <div class="badge-row">
        <Badge tone="pending">pending</Badge>
        <Badge tone="accepted">accepted</Badge>
        <Badge tone="expired">expired</Badge>
        <Badge tone="withdrawn">withdrawn</Badge>
        <Badge tone="declined">declined</Badge>
      </div>

      <p class="lead">Cash-account lifecycle, mapped onto those tones via <code>AccountStatusBadge</code>.</p>
      <div class="badge-row">
        <AccountStatusBadge status="opened" />
        <AccountStatusBadge status="opening" />
        <AccountStatusBadge status="closing" />
        <AccountStatusBadge status="closed" />
      </div>
    </section>

    <!-- =================== 10 Fields =================== -->
    <section id="fields" class="section">
      <div class="section-head">
        <span class="kicker">10 — Fields</span>
        <h2>Form controls.</h2>
        <p class="lead">Label / control / hint stacked, 40px controls, gold focus ring. Three controls so far: text Input, number Input with affix, and native-styled Select. Bindings work two-way — edit any field here and the same values appear in the Drawer demo below.</p>
      </div>

      <form class="field-demo" onsubmit={(e) => e.preventDefault()}>
        <Field label="Account type" htmlFor="sc-type">
          <Select id="sc-type" bind:value={demoType}>
            <option>Current Account</option>
            <option>Settlement Account</option>
            <option>Savings</option>
            <option>Loan</option>
            <option>Deposit</option>
          </Select>
        </Field>
        <Field label="Currency" htmlFor="sc-ccy">
          <Select id="sc-ccy" bind:value={demoCcy}>
            <option>GBP</option>
            <option>EUR</option>
            <option>USD</option>
          </Select>
        </Field>
        <Field label="Product name" htmlFor="sc-name">
          <Input id="sc-name" bind:value={demoName} />
          {#snippet hint()}
            Defaults to <code>&#123;currency&#125; &#123;type&#125;</code>. Override if needed.
          {/snippet}
        </Field>
        <Field label="Interest rate" htmlFor="sc-rate" hint="Basis points. 100 bps = 1.00%.">
          <Input id="sc-rate" type="number" affix="bps" bind:value={demoRate} />
        </Field>
      </form>
    </section>

    <!-- =================== 10b Search field =================== -->
    <section id="searchfield" class="section">
      <div class="section-head">
        <span class="kicker">10b — Search field</span>
        <h2>Search, with a clear.</h2>
        <p class="lead">Leading magnifier, gold focus ring, and a trailing ✕ that shows only once there's a value. <code>size="sm"</code> is the 34px compact variant used inside panel headers. Two-way bound via <code>bind:value</code>.</p>
      </div>

      <form class="field-demo" onsubmit={(e) => e.preventDefault()}>
        <SearchField bind:value={scSearch} placeholder="Search by account number…" />
        <SearchField bind:value={scSearch} size="sm" placeholder="Search transactions…" />
      </form>
    </section>

    <!-- =================== 11 Tables =================== -->
    <section id="tables" class="section">
      <div class="section-head">
        <span class="kicker">11 — Tables</span>
        <h2>Data rows.</h2>
        <p class="lead">Hairlined surface, mono ids, tabular rates, hover row. <code>Td</code> takes <code>mono</code>, <code>muted</code>, <code>emphasized</code>, <code>tabular</code>, and <code>align</code> as visual modifiers. Cells can hold any primitive — the Status column drops a Badge.</p>
      </div>

      <Table>
        <Thead>
          <Tr>
            <Th>ID</Th>
            <Th>Name</Th>
            <Th>Status</Th>
            <Th align="right">Rate</Th>
            <Th>Currency</Th>
          </Tr>
        </Thead>
        <Tbody>
          <Tr>
            <Td mono muted>prd.01ks5kya</Td>
            <Td emphasized>GBP Current Account</Td>
            <Td><Badge tone="draft">draft</Badge></Td>
            <Td align="right" mono tabular>265</Td>
            <Td mono>GBP</Td>
          </Tr>
          <Tr>
            <Td mono muted>prd.01ks5kxd</Td>
            <Td emphasized>GBP Settlement</Td>
            <Td><Badge tone="published">published</Badge></Td>
            <Td align="right" mono tabular>0</Td>
            <Td mono>GBP</Td>
          </Tr>
          <Tr>
            <Td mono muted>prd.01ks3rwb</Td>
            <Td emphasized>GBP Deposit</Td>
            <Td><Badge tone="archived">archived</Badge></Td>
            <Td align="right" mono tabular>510</Td>
            <Td mono>GBP</Td>
          </Tr>
        </Tbody>
      </Table>
    </section>

    <!-- =================== 12 PageHeader =================== -->
    <section id="pageheader" class="section">
      <div class="section-head">
        <span class="kicker">12 — PageHeader</span>
        <h2>Top of every page.</h2>
        <p class="lead">Three-part stack — kicker / title / sub — on the left, action cluster on the right via the <code>actions</code> snippet.</p>
      </div>

      <div class="ph-frame">
        <PageHeader
          kicker="Galactic Bank"
          title="Products"
          sub="Drafts are iterable; publishing commits a version and auto-archives the one it supersedes."
        >
          {#snippet actions()}
            <Button variant="ghost">Refresh</Button>
            <Button variant="primary">New product</Button>
          {/snippet}
        </PageHeader>
      </div>
    </section>

    <!-- =================== 13 Sidenav =================== -->
    <section id="sidenav" class="section">
      <div class="section-head">
        <span class="kicker">13 — Sidenav</span>
        <h2>Section navigation.</h2>
        <p class="lead">Left rail with grouped items. Drag the right edge (or double-click it) to snap between expanded (220px) and icon-only (60px) — width persists in <code>localStorage</code>. The component writes its width to <code>--sidenav-w</code>; consumers use <code>grid-template-columns: var(--sidenav-w, 220px) 1fr</code> on their shell.</p>
      </div>

      <div class="sidenav-demo">
        <Sidenav top={0}>
          <SidenavGroup title="Manage">
            <SidenavItem href="#sidenav" title="Organizations">
              {#snippet icon()}
                <svg viewBox="0 0 16 16" aria-hidden="true">
                  <rect x="2" y="2" width="5" height="5" rx="0.8" />
                  <rect x="9" y="2" width="5" height="5" rx="0.8" />
                  <rect x="2" y="9" width="5" height="5" rx="0.8" />
                  <rect x="9" y="9" width="5" height="5" rx="0.8" />
                </svg>
              {/snippet}
              Organizations
            </SidenavItem>
            <SidenavItem href="#sidenav" current title="Products">
              {#snippet icon()}
                <svg viewBox="0 0 16 16" aria-hidden="true">
                  <path d="M2 4 L14 4" />
                  <path d="M2 8 L14 8" />
                  <path d="M2 12 L14 12" />
                </svg>
              {/snippet}
              Products
            </SidenavItem>
            <SidenavItem href="#sidenav" title="Parties">
              {#snippet icon()}
                <svg viewBox="0 0 16 16" aria-hidden="true">
                  <circle cx="8" cy="6" r="2.6" />
                  <path d="M3 14c0.8-3 2.6-4.6 5-4.6s4.2 1.6 5 4.6" />
                </svg>
              {/snippet}
              Parties
            </SidenavItem>
            <SidenavItem href="#sidenav" title="Accounts">
              {#snippet icon()}
                <svg viewBox="0 0 16 16" aria-hidden="true">
                  <rect x="2.5" y="3.5" width="11" height="9" rx="1" />
                  <path d="M2.5 7.5 L13.5 7.5" />
                  <circle cx="11" cy="10" r="0.8" />
                </svg>
              {/snippet}
              Accounts
            </SidenavItem>
          </SidenavGroup>
          <SidenavGroup title="Compliance">
            <SidenavItem href="#sidenav" title="Policies">
              {#snippet icon()}
                <svg viewBox="0 0 16 16" aria-hidden="true">
                  <path d="M8 2 L13 4 V8 C13 11 10.5 13 8 14 C5.5 13 3 11 3 8 V4 Z" />
                  <path d="M5.8 8 L7.4 9.6 L10.4 6.6" />
                </svg>
              {/snippet}
              Policies
            </SidenavItem>
          </SidenavGroup>
        </Sidenav>
      </div>
    </section>

    <!-- =================== 14 Drawer =================== -->
    <section id="drawer" class="section">
      <div class="section-head">
        <span class="kicker">14 — Drawer</span>
        <h2>Right-side panel.</h2>
        <p class="lead">For create/edit forms, detail panes, secondary content that doesn't deserve a route. The body uses the Fields above — edit and the values stay in sync. Esc, the scrim, or the X close.</p>
      </div>

      <div class="drawer-demo-trigger">
        <Button variant="primary" onclick={() => drawerDemoOpen = true}>Open drawer</Button>
      </div>
    </section>

    <!-- =================== 15 Cards =================== -->
    <section id="cards" class="section">
      <div class="section-head">
        <span class="kicker">15 — Cards</span>
        <h2>Containers for content.</h2>
        <p class="lead">Composable: <code>Card</code> wraps <code>CardHeader</code> / <code>CardBody</code> / <code>CardFooter</code>. Variants: <code>default</code>, <code>feature</code>, <code>sunk</code>, <code>outline</code>. Pair with <code>href</code> for clickable cards.</p>
      </div>

      <div class="subhead">
        <h3>Design-decision cards</h3>
        <p>The pattern from the home page: kicker + serif title + body + ref link. Three to a row, equal height. One <code>feature</code> card per grid is plenty.</p>
      </div>
      <div class="card-grid-3">
        <Card href="#cards">
          <CardHeader kicker="ADR-0013" title="One unified API. OpenAPI is the contract." />
          <CardBody>
            <p>Bank-shaped, not implementation-shaped. The spec drives client generation, validation, and documentation — there is no second source of truth.</p>
          </CardBody>
          <CardFooter><a href="#cards">single-unified-api →</a></CardFooter>
        </Card>

        <Card href="#cards">
          <CardHeader kicker="policy-evaluation" title="Policies as data, not hard-coded rules." />
          <CardBody>
            <p>Capabilities and limits are records. A curative-permit pattern lets a tenant self-correct out of breach without a manual override.</p>
          </CardBody>
          <CardFooter><a href="#cards">policy-evaluation →</a></CardFooter>
        </Card>

        <Card variant="feature" href="#cards">
          <CardHeader kicker="REPL · TESTCONTAINERS" title="REPL on the inside." />
          <CardBody>
            <p>Start a REPL, evaluate a comment block, and the whole system — FDB, Pulsar, HTTP — boots inside Testcontainers. The dev loop is the system.</p>
          </CardBody>
          <CardFooter><a href="#cards">just repl →</a></CardFooter>
        </Card>
      </div>

      <div class="subhead">
        <h3>Variants</h3>
        <p><code>default</code> (surface-raised), <code>feature</code> (inverted), <code>sunk</code> (recessed), <code>outline</code> (hairline only).</p>
      </div>
      <div class="card-grid-4">
        <Card>
          <CardHeader kicker="DEFAULT" title="A standard surface." />
          <CardBody><p>Surface-raised background with a hairline border.</p></CardBody>
        </Card>
        <Card variant="feature">
          <CardHeader kicker="FEATURE" title="An inverted accent." />
          <CardBody><p>Inverts surface tone in both themes for emphasis.</p></CardBody>
        </Card>
        <Card variant="sunk">
          <CardHeader kicker="SUNK" title="A recessed area." />
          <CardBody><p>Surface-sunk background. For inset content.</p></CardBody>
        </Card>
        <Card variant="outline">
          <CardHeader kicker="OUTLINE" title="A hairline frame." />
          <CardBody><p>Transparent fill, just the rule.</p></CardBody>
        </Card>
      </div>

      <div class="subhead">
        <h3>CodeCard</h3>
        <p>Dark fill regardless of theme — terminal aesthetic. Title bar with traffic-light dots + filename. Syntax token classes (<code>.syn-comment</code>, <code>.syn-keyword</code>, <code>.syn-string</code>, <code>.syn-fn</code>, <code>.syn-emphasis</code>, …) for hand-classed snippets or build-time highlighters.</p>
      </div>
      <CodeCard filename="~/queenswood · zsh">
<pre><span class="syn-comment"># Authed via OAuth (Keycloak). Issue an API key for machine-to-machine.</span>
<span class="syn-keyword">curl</span> -X POST https://api.queenswood.local/v1/organisations \
  -H <span class="syn-string">"Authorization: Bearer $QW_OAUTH_TOKEN"</span> \
  -H <span class="syn-string">"Content-Type: application/json"</span> \
  -d <span class="syn-string">'&#123; "name": "northwind-fs",
       "jurisdiction": "GB" &#125;'</span>

<span class="syn-comment">#</span> <span class="syn-emphasis">&#123; "id": "org_01HW7…",</span>
<span class="syn-comment">#  </span>  <span class="syn-emphasis">"status": "active" &#125;</span></pre>
      </CodeCard>
    </section>

    <!-- =================== 16 Ledger tree-table =================== -->
    <section id="ledger" class="section">
      <div class="section-head">
        <span class="kicker">16 — Ledger tree-table</span>
        <h2>Accounts that decompose into their balances.</h2>
        <p class="lead"><code>&lt;Table tree&gt;</code> turns a table into an expandable tree-table. An account row expands to the balances that comprise it; the available figure is their sum via <code>sumMinor</code>, so the tree is a real decomposition. <code>MoneyCell</code> tones by sign (negative → danger, zero → muted) and <code>Phase</code> marks the balance status. Rows are <code>role=button</code> + <code>tabindex=0</code> — Enter / Space toggle.</p>
      </div>

      <Table tree>
        <Thead>
          <Tr>
            <Th />
            <Th>ID</Th>
            <Th>Name</Th>
            <Th>GL Code</Th>
            <Th align="right">Available Balance</Th>
          </Tr>
        </Thead>
        <Tbody>
          {#each LEDGER_ACCOUNTS as acc (acc.id)}
            <Tr
              expandable
              expanded={ledgerOpen[acc.id]}
              onclick={() => ledgerToggle(acc.id)}
              onkeydown={(e) => ledgerKey(e, acc.id)}
            >
              <Td expander><Expander /></Td>
              <Td mono muted>{acc.id}</Td>
              <Td emphasized>{acc.name}<span class="qw-denom">{acc.ccy}</span></Td>
              <Td mono>{acc.gl}</Td>
              <MoneyCell
                minor={sumMinor(acc.balances)} ccy={acc.ccy} emphasized
                meta={`${acc.balances.length} balance${acc.balances.length === 1 ? "" : "s"}`} />
            </Tr>
            {#if ledgerOpen[acc.id]}
              {#each acc.balances as b, i (b.type + ":" + b.phase)}
                <Tr balance last={i === acc.balances.length - 1}>
                  <Td expander />
                  <Td />
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
    </section>

    <!-- =================== 17 Policies =================== -->
    <section id="policies" class="section">
      <div class="section-head">
        <span class="kicker">17 — Policies</span>
        <h2>Capabilities and limits, per domain.</h2>
        <p class="lead">A policy grants <strong>capabilities</strong> (an <code>allow</code>/<code>deny</code> on a domain action) and bounds them with <strong>limits</strong>. Pick a policy from the master table; <code>PolicyMatrix</code> reads it one row per domain, capabilities and limits side by side. <code>Effect</code>/<code>Bound</code>/<code>Improving</code>/<code>FilterChips</code> are the atoms; a limit marked <code>improving</code> is a curative permit.</p>
      </div>

      <Table>
        <Thead>
          <Tr>
            <Th>Policy</Th>
            <Th>Category</Th>
            <Th align="right">Capabilities</Th>
            <Th align="right">Limits</Th>
          </Tr>
        </Thead>
        <Tbody>
          {#each POLICIES as p, i (p.policyId)}
            <Tr
              role="button"
              tabindex="0"
              aria-selected={i === policySelected}
              onclick={() => (policySelected = i)}
              onkeydown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); policySelected = i; } }}
            >
              <Td emphasized>{p.name}</Td>
              <Td><Badge tone={CATEGORY_TONE[p.category]}>{p.category}</Badge></Td>
              <Td align="right" mono tabular>{p.capabilities.length}</Td>
              <Td align="right" mono tabular>{p.limits.length}</Td>
            </Tr>
          {/each}
        </Tbody>
      </Table>

      <div class="policy-controls">
        <input type="search" bind:value={policyQuery} placeholder="Filter domains, actions, reasons…" />
        <label><input type="checkbox" bind:checked={policyShowUngoverned} /> Show ungoverned domains</label>
      </div>

      <h3 class="policy-sub">{selectedPolicy.name}</h3>
      <PolicyMatrix policy={selectedPolicy} showUngoverned={policyShowUngoverned} query={policyQuery} />
    </section>

    <section id="trial-balance" class="section">
      <div class="section-head">
        <h2>Trial balance band</h2>
        <p class="lead"><code>&lt;TrialBalance&gt;</code> summarises the ledger as one balanced block per currency — Σ debits against Σ credits, with the assertion (<code>Σ&nbsp;debit&nbsp;===&nbsp;Σ&nbsp;credit</code>) as the hero. Currencies never sum together; there is no grand total. Each <code>&lt;TrialBalanceCard&gt;</code> derives <code>balanced</code> / <code>diff</code> from the two minor figures (integer equality on pence) — debit/credit dots share the <code>&lt;GlType&gt;</code> warm/cool encoding. A currency can sit out of balance intraday by the in-flight amount, shown here by USD.</p>
      </div>
      <TrialBalance
        asOf="09:42 UTC"
        blocks={[
          { ccy: "GBP", sym: "£", name: "Sterling", accounts: 14, debitMinor: 2431077542000, creditMinor: 2431077542000 },
          { ccy: "EUR", sym: "€", name: "Euro", accounts: 6, debitMinor: 590244010000, creditMinor: 590244010000 },
          { ccy: "USD", sym: "$", name: "US Dollar", accounts: 5, debitMinor: 215000000000, creditMinor: 183979955000 },
        ]}
      />
    </section>

    <section id="progressspine" class="section">
      <div class="section-head">
        <h2>Progress spine</h2>
        <p class="lead"><code>&lt;ProgressSpine&gt;</code> draws a multi-step narrative arc — numbered nodes joined by connectors, one per step, in <code>locked / ready / running / done</code> states. A done node fills and its connector fills; a ready node wears a gold ring; a running node spins; a locked node shows a lock. Clicking a node calls <code>onJump(i)</code>.</p>
      </div>
      <ProgressSpine
        title="A bank opening its doors"
        progressLabel="scenes run"
        steps={[
          { num: "01", label: "Stock the shelves", status: "done" },
          { num: "02", label: "Identity decides the account", status: "done" },
          { num: "03", label: "Money in, double-entry out", status: "running" },
          { num: "04", label: "Nothing is ever lost", status: "ready" },
          { num: "05", label: "Send it out", status: "locked" },
          { num: "06", label: "Runs itself overnight", status: "locked" },
        ]}
      />
    </section>

    <section id="bankstateband" class="section">
      <div class="section-head">
        <h2>Bank-state band</h2>
        <p class="lead"><code>&lt;BankStateBand&gt;</code> is a horizontal evidence strip — a row of figure cells (a muted cell reads as still-zero) plus a flexible attention cell whose tone (<code>idle / good / gold</code>) tints the icon tile. The page supplies the attention copy via the <code>icon</code>, <code>title</code>, <code>sub</code>, and <code>action</code> snippets.</p>
      </div>
      <BankStateBand
        cells={[
          { figure: 3, unit: "/ 6", label: "Scenes run" },
          { figure: 2, label: "Products live" },
          { figure: 2, unit: "/ 3", label: "Active customers" },
          { figure: "£2,500.00", label: "Customer money held" },
        ]}
        attentionTone="good"
      >
        {#snippet icon()}
          <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="8" cy="8" r="6.4" stroke-opacity="0.4" /><path d="M5.2 8.2 L7.1 10 L10.8 6" /></svg>
        {/snippet}
        {#snippet title()}Books tie — debits equal credits{/snippet}
        {#snippet sub()}2 customers · <span class="mono">£2,500.00</span> held{/snippet}
        {#snippet action()}<Button variant="brand" size="sm">Run Scene 04</Button>{/snippet}
      </BankStateBand>
    </section>

    <section id="scenecard" class="section">
      <div class="section-head">
        <h2>Scene card</h2>
        <p class="lead"><code>&lt;SceneCard&gt;</code> is one sequential-unlock card: head (number, title, payoff chip, story, status badge + run button + chevron) over an expandable body the page supplies as the <code>body</code> snippet. <code>status</code> drives the border, badge, and run button. The body here mounts <code>&lt;TaskPipeline&gt;</code>, including its new <code>exception</code> (flagged) step status.</p>
      </div>
      <SceneCard
        num="03"
        title="Money in, double-entry out"
        story="Open accounts for two customers and fund one with an inbound Faster Payment. The books move — debits equal credits, to the penny."
        status="ready"
        payoffLabel="ledger"
        open={scOpen}
        onToggle={() => (scOpen = !scOpen)}
        onRun={() => {}}
      >
        {#snippet body()}
          <TaskPipeline
            steps={[
              { name: "Mint admin token", status: "exception" },
              { name: "Open accounts", status: "ok" },
              { name: "Fund · inbound FPS", status: "running" },
              { name: "Trial balance ties", status: "pending" },
            ]}
          />
        {/snippet}
      </SceneCard>

      <p class="lead">
        <code>&lt;TaskPipeline dense&gt;</code> with per-step <code>detail</code>
        and <code>alert</code> — the shape used inside a run history row,
        where the run has recorded what each task actually did rather than
        the schedule projecting what it will do. <code>alert</code> is the
        figure that still needs reading when the task itself succeeded.
      </p>
      <TaskPipeline
        dense
        steps={[
          { name: "accrue", status: "ok", detail: "12,480 processed · 3.4s" },
          {
            name: "capitalize",
            status: "ok",
            detail: "12,480 processed · 8.1s",
            alert: "400 failed",
          },
          { name: "migrate", status: "skipped", detail: "never ran" },
        ]}
      />
    </section>

    <section id="rawcalls" class="section">
      <div class="section-head">
        <h2>Raw calls</h2>
        <p class="lead"><code>&lt;RawCalls&gt;</code> is a dark terminal block revealing the underlying API calls behind a friendly step list — method, path, and the runner verb tag — with a footer naming the backing scenario id(s). Hidden until <code>show</code>.</p>
      </div>
      <RawCalls
        show
        backing={["create-product-happy", "publish-draft"]}
        rows={[
          { method: "POST", path: "/v1/cash-account-products", tag: "request" },
          { method: "POST", path: "/v1/cash-account-products/prd…/versions/1/publish", tag: "request" },
          { method: "GET", path: "/v1/cash-account-products/prd…/versions/1", tag: "poll" },
        ]}
      />
    </section>

    <section id="panels" class="section">
      <div class="section-head">
        <h2>Panels</h2>
        <p class="lead"><code>&lt;Panel&gt;</code> is the raised, hairline-bordered surface every detail screen stacks; <code>pad</code> is <code>none</code> (a full-bleed table owns its own edges), <code>sm</code>, <code>md</code> or <code>lg</code>. <code>&lt;PanelHead&gt;</code> gives it a title bar with a muted count or qualifier and a right-hand note or actions snippet.</p>
      </div>
      <div class="stack">
        <Panel>
          <PanelHead title="Runs" count={2} note="one run per business day" />
          <Table>
            <Thead>
              <Tr><Th>Business day</Th><Th>Kind</Th><Th align="right">Seen</Th></Tr>
            </Thead>
            <Tbody>
              <Tr><Td mono>30 Jul 2026</Td><Td>dry run</Td><Td mono tabular align="right">9,588</Td></Tr>
              <Tr><Td mono>2 Jul 2026</Td><Td>dry run</Td><Td mono tabular align="right">9,588</Td></Tr>
            </Tbody>
          </Table>
        </Panel>
        <Panel pad="lg">
          A <code>pad="lg"</code> panel — 22px by 24px, what a hero uses.
        </Panel>
      </div>
    </section>

    <section id="chips" class="section">
      <div class="section-head">
        <h2>Chips</h2>
        <p class="lead"><code>&lt;Chip&gt;</code> is a pressable filter pill with an optional trailing count, publishing <code>aria-pressed</code> so the active facet is heard as well as seen. Distinct from <code>&lt;FilterChips&gt;</code>, which renders a policy's scope read-only and toggles nothing.</p>
      </div>
      <div class="row">
        <Chip pressed={chipFilter === "all"} count={9588} onclick={() => chipFilter = "all"}>All</Chip>
        <Chip pressed={chipFilter === "move"} count={9039} onclick={() => chipFilter = "move"}>Would move</Chip>
        <Chip pressed={chipFilter === "held"} count={549} onclick={() => chipFilter = "held"}>Held back</Chip>
        <Chip pressed={chipFilter === "failed"} count={0} onclick={() => chipFilter = "failed"}>Failed</Chip>
        <Chip disabled count={0}>Disabled</Chip>
      </div>
    </section>

    <section id="toast" class="section">
      <div class="section-head">
        <h2>Toast</h2>
        <p class="lead">One slot, bottom-centre. <code>toast(message, detail)</code> from anywhere; a second toast replaces the first and restarts the timer rather than stacking, because these confirm an action just taken and only the newest is still worth reading. Mount <code>&lt;ToastHost /&gt;</code> once, in the app shell.</p>
      </div>
      <div class="row">
        <Button onclick={() => toast("Migration approved", "mig.01kz3wyzcjhkab9h91x9ngedr")}>
          Toast with detail
        </Button>
        <Button variant="ghost" onclick={() => toast("Saved — preview discarded because the scope changed")}>
          Toast, no detail
        </Button>
      </div>
    </section>

    <section id="access" class="section">
      <div class="section-head">
        <h2>People and access</h2>
        <p class="lead"><code>&lt;RolePill&gt;</code> names a role, toned by reach up the ladder. <code>&lt;Tag&gt;</code> marks a name — you, the member who created the organisation, an operator — and presses nothing, unlike <code>&lt;Chip&gt;</code>. <code>&lt;Tabs&gt;</code> switches panels and carries a count. <code>&lt;Callout&gt;</code> is the shape every API refusal takes, with the problem's type on a mono line. <code>&lt;Textarea&gt;</code> counts toward its limit, and <code>&lt;TokenBox&gt;</code> holds a secret the API answers once.</p>
      </div>
      <div class="stack">
        <div class="row">
          <RolePill role="owner" />
          <RolePill role="admin" />
          <RolePill role="developer" />
          <RolePill role="viewer" />
          <Tag tone="you">you</Tag>
          <Tag tone="founder">created organisation</Tag>
          <Tag tone="operator">operator</Tag>
        </div>
        <Tabs
          label="People"
          bind:value={accessTab}
          tabs={[
            { id: "members", label: "Members", count: 5 },
            { id: "invitations", label: "Invitations", count: "3 pending" },
            { id: "history", label: "History", count: "8+" },
          ]}
        />
        <Callout tone="danger" title="Make someone else an owner first" type="409 · :membership/last-owner">
          Ada Lovelace is this organisation’s only owner. Grant the owner role to another member, then change this one.
        </Callout>
        <Callout tone="warn" title="You won’t see this again">
          Queenswood keeps only a hash of the link’s token, so it can’t be shown twice.
        </Callout>
        <Callout tone="info">
          The link expires seven days after it is sent.
        </Callout>
        <Textarea bind:value={accessReason} maxlength={500} placeholder="Kept on the record." />
        <TokenBox
          label="The link — shown once"
          base="https://console.example/#/invitations/accept"
          secret="?i=inv.01kprbmgcj35ptc8npmybhh4sm&t=mZzGQMQVkb1hnhQq6hXAZEOHjWHnjB8aHdRJAJw3hMw"
        />
      </div>
    </section>

    <section id="migrations" class="section">
      <div class="section-head">
        <h2>Migration pickers</h2>
        <p class="lead"><code>&lt;ProductPicker&gt;</code> is a searchable product chooser — a native <code>&lt;select&gt;</code> can't carry the type, published-version count and live account count the choice actually turns on. <code>&lt;VersionList&gt;</code> picks one version (<code>mode="single"</code>) or several (<code>mode="multi"</code>); a non-published row dims but stays selectable, so choosing a draft target raises the real rejection rather than being silently impossible. <code>&lt;MigrationStatusBadge&gt;</code> maps a migration, run or per-account outcome onto the Badge tones.</p>
      </div>
      <div class="stack">
        <div class="row">
          <MigrationStatusBadge status="draft" />
          <MigrationStatusBadge status="approved" />
          <MigrationStatusBadge status="completed" />
          <MigrationStatusBadge status="cancelled" />
          <MigrationStatusBadge status="running" kind="run" />
          <MigrationStatusBadge status="ineligible" kind="outcome" />
        </div>
        <div style="max-width: 460px">
          <ProductPicker
            products={DEMO_PRODUCTS}
            value={pickedProduct}
            open={pickerOpen}
            ontoggle={() => pickerOpen = !pickerOpen}
            onselect={(p) => { pickedProduct = p.id; pickerOpen = false; }}
            hiddenNote="1 product hidden — a migration's target must be the same product type as its source (savings)."
          />
        </div>
        <div style="max-width: 460px">
          <VersionList
            mode="multi"
            title="Instant Access Savings · 5 versions"
            action={{ label: "Select all published", onclick: () => sourceVersions = DEMO_VERSIONS.filter((v) => v.published).map((v) => v.id) }}
            versions={DEMO_VERSIONS}
            selected={sourceVersions}
            onchange={(ids) => sourceVersions = ids}
          />
        </div>
        <div style="max-width: 460px">
          <VersionList
            mode="single"
            name="sc-target-version"
            title="Instant Access Savings · target"
            versions={DEMO_VERSIONS}
            selected={targetVersion}
            onchange={(id) => targetVersion = id}
          />
        </div>
      </div>
    </section>

    <footer class="foot">
      <span class="foot-mark"><Logo variant="C" size={36} idPrefix="sc-foot" /></span>
      <span class="foot-text">
        End of showcase. Add new exports to <code>ui/src/index.js</code> and a section here for each one.
      </span>
    </footer>
  </main>
</div>

<ToastHost />

<!-- Drawer lives outside the page grid because it's fixed-position. -->
<Drawer
  open={drawerDemoOpen}
  onClose={() => drawerDemoOpen = false}
  kicker="Define"
  title="New product"
  sub="Drafts are iterable—save, come back, edit again. Publishing commits a version and releases it to onboarding flows."
>
  <Field label="Account type" htmlFor="dd-type">
    <Select id="dd-type" bind:value={demoType}>
      <option>Current Account</option>
      <option>Settlement Account</option>
      <option>Savings</option>
      <option>Loan</option>
      <option>Deposit</option>
    </Select>
  </Field>
  <Field label="Currency" htmlFor="dd-ccy">
    <Select id="dd-ccy" bind:value={demoCcy}>
      <option>GBP</option>
      <option>EUR</option>
      <option>USD</option>
    </Select>
  </Field>
  <Field label="Product name" htmlFor="dd-name">
    <Input id="dd-name" bind:value={demoName} />
    {#snippet hint()}
      Defaults to <code>&#123;currency&#125; &#123;type&#125;</code>. Override if needed.
    {/snippet}
  </Field>
  <Field label="Interest rate" htmlFor="dd-rate" hint="Basis points. 100 bps = 1.00%.">
    <Input id="dd-rate" type="number" affix="bps" bind:value={demoRate} />
  </Field>

  {#snippet footer()}
    <Button variant="primary" size="lg" block onclick={() => drawerDemoOpen = false}>
      Create product
    </Button>
  {/snippet}
</Drawer>

<style>
  :global(body) {
    margin: 0;
    background: var(--surface);
    color: var(--fg);
    font-family: var(--grotesk);
    -webkit-font-smoothing: antialiased;
    text-rendering: optimizeLegibility;
  }

  .page {
    display: grid;
    grid-template-columns: 260px minmax(0, 1fr);
    gap: 0;
    max-width: 1480px;
    margin: 0 auto;
    padding: 0 32px;
  }

  /* ===== Rail ===== */
  .rail {
    position: sticky;
    top: 72px;
    align-self: start;
    height: calc(100vh - 80px);
    display: flex;
    flex-direction: column;
    gap: 28px;
    padding: 56px 24px 24px 0;
    border-right: 1px solid var(--rule-2);
    overflow-y: auto;
  }
  .rail-head { display: flex; flex-direction: column; gap: 4px; }
  .rail-eyebrow {
    font-family: var(--mono);
    font-size: 11px;
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--fg-muted);
  }
  .rail-title {
    font-family: var(--serif);
    font-size: 28px;
    font-weight: 500;
    letter-spacing: 0.01em;
    color: var(--fg);
    line-height: 1;
  }
  .rail-sub { font-size: 13px; color: var(--fg-muted); }
  .rail-nav { display: flex; flex-direction: column; gap: 2px; }
  .rail-nav a {
    display: block;
    padding: 8px 0;
    font-size: 14px;
    color: var(--fg-2);
    text-decoration: none;
    border-bottom: 1px solid var(--rule-2);
    transition: color 0.12s, padding-left 0.16s;
  }
  .rail-nav a:hover { color: var(--pine-4); padding-left: 6px; }
  .rail-foot { margin-top: auto; font-size: 12px; line-height: 1.55; color: var(--fg-muted); }
  .rail-foot code {
    font-family: var(--mono);
    font-size: 11px;
    background: var(--surface-sunk);
    padding: 1px 5px;
    border-radius: 3px;
  }

  /* ===== Main ===== */
  .main { padding: 56px 0 96px 48px; min-width: 0; }
  .lede { margin-bottom: 96px; }
  .eyebrow {
    font-family: var(--mono);
    font-size: 11px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--gold-deep);
    margin-bottom: 18px;
  }
  .lede h1 {
    font-family: var(--serif);
    font-weight: 500;
    font-size: clamp(48px, 6vw, 92px);
    line-height: 0.96;
    letter-spacing: -0.005em;
    margin: 0 0 24px 0;
    color: var(--fg);
  }
  .lede p {
    max-width: 56ch;
    font-size: 18px;
    line-height: 1.5;
    color: var(--fg-2);
    margin: 0;
  }
  .lede code, .section-head code, .rail-foot code, .foot-text code {
    font-family: var(--mono);
    font-size: 0.9em;
    color: var(--fg);
  }

  /* ===== Section scaffolding ===== */
  .section { padding: 64px 0; border-top: 1px solid var(--rule); }
  .section-head { margin-bottom: 48px; }
  .kicker {
    font-family: var(--mono);
    font-size: 12px;
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--fg-muted);
    display: block;
    margin-bottom: 14px;
  }
  .section-head h2 {
    font-family: var(--serif);
    font-weight: 500;
    font-size: clamp(32px, 3.4vw, 48px);
    line-height: 1.02;
    margin: 0 0 18px 0;
    color: var(--fg);
  }
  .section-head .lead {
    max-width: 64ch;
    font-size: 16px;
    line-height: 1.6;
    color: var(--fg-2);
    margin: 0;
  }
  .subhead { margin: 48px 0 24px 0; }
  .subhead h3 {
    font-family: var(--grotesk);
    font-weight: 500;
    font-size: 14px;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    margin: 0 0 8px 0;
    color: var(--fg);
  }
  .subhead p { font-size: 14px; color: var(--fg-muted); margin: 0; max-width: 60ch; }

  /* ===== Marks ===== */
  .marks-row {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
    gap: 0;
    border: 1px solid var(--rule-2);
    background: var(--surface-raised);
  }
  .mark-card {
    margin: 0;
    padding: 32px 16px 20px;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 18px;
    border-right: 1px solid var(--rule-2);
  }
  .mark-card:last-child { border-right: none; }
  .mark-frame { width: 132px; height: 132px; display: flex; align-items: center; justify-content: center; }
  .mark-card figcaption { display: flex; flex-direction: column; gap: 6px; align-items: center; text-align: center; }
  .mark-tag {
    font-family: var(--mono);
    font-size: 11px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--gold-deep);
  }
  .mark-note { font-size: 12px; line-height: 1.45; color: var(--fg-muted); max-width: 22ch; }

  .size-row {
    display: flex;
    align-items: flex-end;
    gap: 32px;
    padding: 24px;
    background: var(--surface-raised);
    border: 1px solid var(--rule-2);
    overflow-x: auto;
  }
  .size-cell { display: flex; flex-direction: column; align-items: center; gap: 12px; flex: 0 0 auto; }
  .size-frame { display: flex; align-items: center; justify-content: center; }
  .size-label { font-family: var(--mono); font-size: 11px; color: var(--fg-muted); letter-spacing: 0.04em; }

  /* ===== Wordmark ===== */
  .wm-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 1px;
    background: var(--rule-2);
    border: 1px solid var(--rule-2);
  }
  .wm-col {
    background: var(--surface-raised);
    padding: 32px 28px;
    display: flex;
    flex-direction: column;
    gap: 24px;
  }
  .wm-col-head {
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding-bottom: 16px;
    border-bottom: 1px solid var(--rule-2);
  }
  .wm-name {
    font-family: var(--mono);
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: var(--gold-deep);
  }
  .wm-meta { font-size: 13px; color: var(--fg-muted); }
  .wm-row {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 16px;
    padding: 8px 0;
  }
  .wm-size { font-family: var(--mono); font-size: 11px; color: var(--fg-muted); }

  /* ===== Chrome (AppNav demos) ===== */
  .nav-demo-stack { display: flex; flex-direction: column; gap: 24px; }
  .nav-demo-tag {
    font-family: var(--mono);
    font-size: 11px;
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--fg-muted);
    margin-bottom: 10px;
  }
  .nav-demo-frame {
    border: 1px solid var(--rule);
    border-radius: 4px;
    overflow: hidden;
    background: var(--surface);
  }
  .nav-demo-body {
    height: 80px;
    background:
      repeating-linear-gradient(
        45deg,
        transparent 0,
        transparent 12px,
        var(--rule-2) 12px,
        var(--rule-2) 13px
      );
  }
  .nav-demo-frame :global(.nav) { position: relative; top: auto; }

  /* ===== ThemeToggle demo ===== */
  .toggle-demo {
    display: flex;
    align-items: center;
    gap: 28px;
    padding: 24px;
    background: var(--surface-raised);
    border: 1px solid var(--rule-2);
    border-radius: 4px;
    margin-top: 8px;
  }
  .toggle-state { margin: 0; display: grid; grid-template-columns: repeat(3, auto); gap: 4px 28px; }
  .toggle-state > div { display: flex; flex-direction: column; gap: 2px; }
  .toggle-state dt {
    font-family: var(--mono);
    font-size: 10px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--fg-muted);
    margin: 0;
  }
  .toggle-state dd { margin: 0; font-family: var(--mono); font-size: 13px; color: var(--fg); }
  .toggle-state code { font-family: var(--mono); }

  .lede-meta {
    margin-top: 20px !important;
    font-family: var(--mono);
    font-size: 12px !important;
    color: var(--fg-muted) !important;
    max-width: none !important;
  }
  .lede-meta code {
    background: var(--surface-sunk);
    padding: 1px 6px;
    border-radius: 3px;
    font-size: 11px;
    color: var(--fg);
  }

  /* ===== Swatches ===== */
  .swatch-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
    gap: 16px;
  }
  .swatch { display: flex; flex-direction: column; gap: 12px; }
  .swatch-block {
    height: 140px;
    border-radius: 4px;
    display: flex;
    align-items: flex-end;
    justify-content: flex-start;
    padding: 14px;
    font-family: var(--serif);
    font-size: 48px;
    font-weight: 500;
    line-height: 1;
    border: 1px solid var(--rule-2);
  }
  .swatch-block.overlay {
    background-color: var(--surface) !important;
    background-image: linear-gradient(var(--rule), var(--rule));
  }
  .swatch-block.overlay > .swatch-aa { color: var(--fg); }
  .swatch-aa { display: inline-block; }
  .swatch-meta { display: flex; flex-direction: column; gap: 2px; }
  .swatch-name {
    font-family: var(--mono);
    font-size: 12px;
    color: var(--fg);
    letter-spacing: 0.02em;
  }
  .swatch-value { font-family: var(--mono); font-size: 11px; color: var(--fg-muted); }

  /* ===== Ramps ===== */
  .ramp {
    display: grid;
    grid-template-columns: repeat(5, 1fr);
    gap: 0;
    border: 1px solid var(--rule-2);
    border-radius: 4px;
    overflow: hidden;
  }
  .ramp-step {
    aspect-ratio: 4 / 5;
    padding: 16px;
    display: flex;
    flex-direction: column;
    justify-content: flex-end;
    gap: 4px;
    color: var(--paper);
  }
  .ramp-name { font-family: var(--mono); font-size: 12px; letter-spacing: 0.02em; }
  .ramp-value { font-family: var(--mono); font-size: 10px; opacity: 0.75; }

  /* ===== Type specimens ===== */
  .spec {
    padding: 32px 0;
    border-top: 1px solid var(--rule-2);
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    gap: 20px;
  }
  .spec:first-of-type { border-top: none; padding-top: 0; }
  .spec-head { display: flex; align-items: baseline; gap: 16px; flex-wrap: wrap; }
  .spec-name {
    font-family: var(--mono);
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: var(--gold-deep);
  }
  .spec-stack { font-family: var(--mono); font-size: 11px; color: var(--fg-muted); }
  .spec-display { font-size: clamp(40px, 5vw, 72px); line-height: 1; letter-spacing: -0.005em; color: var(--fg); }
  .spec-display.grotesk { font-family: var(--grotesk); font-weight: 500; }
  .spec-display.serif   { font-family: var(--serif);   font-weight: 500; letter-spacing: 0.01em; }
  .spec-display.mono    { font-family: var(--mono);    font-weight: 400; font-size: clamp(28px, 3.4vw, 44px); letter-spacing: 0; }
  .spec-body { max-width: 60ch; line-height: 1.55; color: var(--fg-2); margin: 0; }
  .spec-body.grotesk { font-family: var(--grotesk); font-size: 16px; }
  .spec-body.serif   { font-family: var(--serif);   font-size: 22px; line-height: 1.45; }
  .spec-body.mono    { font-family: var(--mono);    font-size: 13px; }
  .spec-scale { display: flex; align-items: baseline; gap: 24px; padding: 8px 0; color: var(--fg); }
  .spec-scale.grotesk { font-family: var(--grotesk); }
  .spec-scale.serif   { font-family: var(--serif); }
  .spec-scale.mono    { font-family: var(--mono); }

  /* ===== Buttons demo ===== */
  .btn-matrix {
    display: flex;
    flex-direction: column;
    gap: 16px;
    padding: 24px;
    background: var(--surface-raised);
    border: 1px solid var(--rule-2);
    border-radius: 6px;
  }
  .btn-row {
    display: flex;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;
  }
  .btn-label {
    font-family: var(--mono);
    font-size: 11px;
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--fg-muted);
    width: 80px;
    flex: 0 0 80px;
  }
  .btn-block-frame {
    padding: 24px;
    background: var(--surface-raised);
    border: 1px solid var(--rule-2);
    border-radius: 6px;
    max-width: 420px;
  }

  /* ===== Badges demo ===== */
  .badge-row {
    display: flex;
    gap: 12px;
    align-items: center;
    flex-wrap: wrap;
    padding: 28px 24px;
    background: var(--surface-raised);
    border: 1px solid var(--rule-2);
    border-radius: 6px;
  }

  /* ===== Generic demo layouts ===== */
  .row {
    display: flex;
    gap: 12px;
    align-items: center;
    flex-wrap: wrap;
  }
  .stack {
    display: flex;
    flex-direction: column;
    gap: 18px;
  }

  /* ===== Fields demo ===== */
  .field-demo {
    padding: 28px;
    background: var(--surface-raised);
    border: 1px solid var(--rule-2);
    border-radius: 6px;
    display: flex;
    flex-direction: column;
    gap: 18px;
    max-width: 480px;
  }

  /* ===== PageHeader demo ===== */
  .ph-frame {
    padding: 28px 32px;
    background: var(--surface-raised);
    border: 1px solid var(--rule-2);
    border-radius: 6px;
  }

  /* ===== Sidenav demo ===== */
  .sidenav-demo {
    position: relative;
    height: 380px;
    width: 320px;
    border: 1px solid var(--rule);
    border-radius: 6px;
    overflow: hidden;
    background: var(--surface);
  }
  /* Strip the sticky positioning so the Sidenav sits inside the frame
     rather than escaping it. Real consumers leave the sticky intact. */
  .sidenav-demo :global(.sidenav) {
    position: relative !important;
    top: auto !important;
    height: 100% !important;
    border-right: none !important;
  }

  /* ===== Drawer demo ===== */
  .drawer-demo-trigger {
    padding: 28px;
    background: var(--surface-raised);
    border: 1px solid var(--rule-2);
    border-radius: 6px;
    display: flex;
    justify-content: flex-start;
  }

  /* ===== Cards demo ===== */
  .card-grid-3 {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 24px;
  }
  .card-grid-3 > * { min-height: 200px; }
  .card-grid-4 {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 20px;
  }
  @media (min-width: 1200px) {
    .card-grid-4 { grid-template-columns: repeat(4, minmax(0, 1fr)); }
  }

  /* ===== Foot ===== */
  .foot {
    margin-top: 96px;
    padding: 32px 0;
    border-top: 1px solid var(--rule);
    display: flex;
    align-items: center;
    gap: 20px;
  }
  .foot-text { font-size: 13px; color: var(--fg-muted); max-width: 60ch; }

  /* §17 policy controls */
  .policy-controls {
    display: flex;
    align-items: center;
    gap: 16px;
    margin: 16px 0 6px;
  }
  .policy-controls input[type="search"] {
    height: 34px;
    padding: 0 12px;
    border-radius: 6px;
    border: 1px solid var(--rule);
    background: var(--surface-raised);
    color: var(--fg);
    font: inherit;
    font-size: 13px;
    min-width: 280px;
  }
  .policy-controls label {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    font-size: 12px;
    color: var(--fg-muted);
  }
  .policy-sub {
    font-family: var(--grotesk);
    font-weight: 600;
    font-size: 16px;
    margin: 6px 0 0;
    color: var(--fg);
  }
  .section :global(.qw-table tbody tr[aria-selected="true"] td) {
    background: light-dark(oklch(0.95 0.022 145), oklch(0.235 0.035 145));
  }
</style>
