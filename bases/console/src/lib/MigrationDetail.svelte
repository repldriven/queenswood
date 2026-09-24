<script>
  /* MigrationDetail — the stacked panels for the selected migration:
     hero (identity, actions, guards, source→target), notice timeline,
     preview, run history, and the per-account outcomes.

     Approving moves nothing — it puts the migration on the scheduler's
     work list for its due date, and the scheduler's migration task is
     the only thing that moves accounts. Approve is blocked while any
     guard is unresolved, so the button never produces a surprise 4xx.

     Edit renders disabled with a title saying why: bank-api has no
     route to edit a migration. A control that silently does nothing is
     worse than one that says it can't.

     The outcomes table filters and pages in the browser because the run
     accounts endpoint is neither filtered nor cursor-paginated. At a
     five-figure population that wants to become query params. */

  import {
    Panel,
    PanelHead,
    Button,
    Chip,
    SearchField,
    MigrationStatusBadge,
    Table,
    Thead,
    Tbody,
    Tr,
    Th,
    Td,
    INELIGIBILITY_LABEL,
    INELIGIBILITY_ORDER,
    MIGRATION_GUARDS,
    daysBetween,
    fmtDay,
    fmtDuration,
    fmtRate,
    fmtRateDelta,
    fmtStamp,
    shortEnum,
    todayIso,
  } from "@queenswood/ui";
  import {
    list_cash_account_migration_runs,
    list_cash_account_migration_run_accounts,
  } from "./api.mjs";

  let {
    migration,
    productById,
    versionById,
    population,
    onpreview,
    onapprove,
    oncancel,
  } = $props();

  let runs = $state([]);
  let outcomes = $state([]);
  let runsLoading = $state(true);
  let previewing = $state(false);
  let outcome = $state("all");
  let acctQuery = $state("");
  let limit = $state(40);

  const PAGE = 40;
  const today = todayIso();

  const sourceProduct = $derived(productById[migration.sourceProductId] ?? null);
  const targetProduct = $derived(productById[migration.targetProductId] ?? null);
  const sourceVersions = $derived(
    migration.sourceVersionIds.map((id) => versionById[id]).filter(Boolean),
  );
  const targetVersion = $derived(versionById[migration.targetVersionId] ?? null);
  const frozen = $derived(
    migration.status === "completed" || migration.status === "cancelled",
  );

  // Every guard bank-api would raise on this migration, evaluated here
  // so approval is never a surprise rejection. `dates` carries no wire
  // type — a draft may legitimately be saved without them.
  const guards = $derived.by(() => {
    const out = [];
    if (migration.sourceVersionIds.length === 0) {
      out.push({
        type: MIGRATION_GUARDS["source-product-not-found"],
        message: "No source versions are selected, so no account is in scope.",
      });
    }
    if (migration.sourceVersionIds.includes(migration.targetVersionId)) {
      out.push({
        type: MIGRATION_GUARDS["target-is-source"],
        message:
          "The target version is also a source version — those accounts are already where they would move to.",
      });
    }
    if (sourceProduct && targetProduct && sourceProduct.type !== targetProduct.type) {
      out.push({
        type: MIGRATION_GUARDS["product-type-mismatch"],
        message: `Source is a ${sourceProduct.type} product and target is a ${targetProduct.type} one. A migration cannot change a product's type.`,
      });
    }
    if (targetVersion && !targetVersion.published) {
      out.push({
        type: MIGRATION_GUARDS["target-not-published"],
        message: `The target version is ${targetVersion.status}. Only a published version can be a target.`,
      });
    }
    if (migration.notifiedOn && migration.dueOn && migration.notifiedOn > migration.dueOn) {
      out.push({
        type: MIGRATION_GUARDS["notice-after-due"],
        message: "Customers would be notified after their accounts had already moved.",
      });
    }
    if (!migration.notifiedOn || !migration.dueOn) {
      out.push({
        message:
          "Notice and due dates are required before this migration can be approved.",
      });
    }
    return out;
  });

  // Newest settled run — the one the preview panel reports on. The API
  // returns runs newest-first.
  function settledRun(list) {
    return list.find((r) => shortEnum(r.status) === "completed") ?? null;
  }

  async function loadRuns() {
    runsLoading = true;
    const res = await list_cash_account_migration_runs(migration.id);
    const next =
      res.status >= 200 && res.status < 300 ? (res.body?.items ?? []) : [];
    runs = next;
    runsLoading = false;
    await loadOutcomes(settledRun(next));
  }

  async function loadOutcomes(run) {
    if (!run) {
      outcomes = [];
      return;
    }
    const res = await list_cash_account_migration_run_accounts(
      migration.id,
      run["run-id"],
    );
    outcomes =
      res.status >= 200 && res.status < 300 ? (res.body?.items ?? []) : [];
  }

  const shownRun = $derived(settledRun(runs));

  $effect(() => {
    loadRuns();
  });

  async function preview() {
    previewing = true;
    const run = await onpreview();
    previewing = false;
    if (!run) return;
    outcome = "all";
    acctQuery = "";
    limit = PAGE;
    await loadRuns();
  }

  const seen = $derived(shownRun?.["accounts-seen"] ?? 0);
  const moved = $derived(shownRun?.["accounts-moved"] ?? 0);
  const held = $derived(shownRun?.["accounts-ineligible"] ?? 0);
  const failed = $derived(shownRun?.["accounts-failed"] ?? 0);
  // A dry run decides but doesn't move, so its movable cohort is what
  // it marked eligible rather than what it moved.
  const movable = $derived(
    shownRun?.["dry-run"] ? Math.max(0, seen - held - failed) : 0,
  );

  // Three decimals: at ~9,600 accounts a 31-account slice is 0.32% and
  // rounds away at anything coarser.
  const pct = (n) => (seen ? `${((n / seen) * 100).toFixed(3)}%` : "0%");
  const num = (n) => (n ?? 0).toLocaleString("en-GB");

  const heldByReason = $derived.by(() => {
    const tally = {};
    for (const a of outcomes) {
      if (shortEnum(a.outcome) !== "ineligible") continue;
      const reason = shortEnum(a.ineligibility);
      tally[reason] = (tally[reason] ?? 0) + 1;
    }
    return INELIGIBILITY_ORDER.filter((r) => tally[r]).map((r) => ({
      reason: r,
      label: INELIGIBILITY_LABEL[r],
      count: tally[r],
    }));
  });

  const outcomeCounts = $derived.by(() => {
    const tally = { migrated: 0, eligible: 0, ineligible: 0, failed: 0 };
    for (const a of outcomes) {
      const o = shortEnum(a.outcome);
      if (o in tally) tally[o] += 1;
    }
    return tally;
  });

  const rows = $derived.by(() => {
    const q = acctQuery.trim().toLowerCase();
    return outcomes.filter((a) => {
      if (outcome !== "all" && shortEnum(a.outcome) !== outcome) return false;
      if (!q) return true;
      const acct = population?.accountById?.[a["account-id"]];
      return `${acct?.number ?? a["account-id"]} ${acct?.owner ?? ""}`
        .toLowerCase()
        .includes(q);
    });
  });

  // Where an account ended up, or would. A dry run records no
  // `to-version-id` even on an eligible verdict — nothing moved it — so
  // the destination comes from the migration's target instead, shown
  // muted to keep "would move to v3" distinct from "moved to v3".
  function destination(a) {
    const actual = versionById[a["to-version-id"]];
    if (actual) return { label: `v${actual.number}`, prospective: false };
    if (shortEnum(a.outcome) === "eligible" && targetVersion) {
      return { label: `v${targetVersion.number}`, prospective: true };
    }
    return { label: "—", prospective: false };
  }

  const rateDelta = $derived(
    fmtRateDelta(
      targetVersion?.rateBps,
      sourceVersions.map((v) => v.rateBps),
    ),
  );

  // Accounts under the selected source versions, and under the source
  // product as a whole. Both come from the population sweep; a run's
  // accounts-seen is the same figure once a preview exists.
  const onSourceVersions = $derived.by(() => {
    if (!population) return null;
    return migration.sourceVersionIds.reduce(
      (sum, id) => sum + (population.byVersion[id] ?? 0),
      0,
    );
  });
  const onSourceProduct = $derived(
    population ? (population.byProduct[migration.sourceProductId] ?? 0) : null,
  );

  const kicker = $derived(
    [
      sourceProduct?.type,
      migration.sourceProductId === migration.targetProductId
        ? "version change"
        : "product change",
    ]
      .filter(Boolean)
      .join(" · "),
  );

  // Where the migration sits against its own notice window, in the
  // operator's words rather than the timeline's dots.
  const caption = $derived.by(() => {
    if (migration.status === "draft") {
      return {
        parts: [
          "This migration has no notice window yet. ",
          { strong: "Set a notice date and a due date" },
          " to make it approvable — customers must be told before their accounts move.",
        ],
      };
    }
    if (migration.status === "cancelled") {
      const gap = daysBetween(migration.cancelledAt?.slice(0, 10), migration.dueOn);
      return {
        parts: [
          `Cancelled ${fmtDay(migration.cancelledAt?.slice(0, 10)) ?? "—"}`,
          gap != null ? `, ${Math.abs(gap)} days ${gap >= 0 ? "before" : "after"} the due date` : "",
          ". No accounts moved.",
        ],
      };
    }
    if (migration.status === "completed") {
      const notice = daysBetween(migration.notifiedOn, migration.dueOn);
      return {
        parts: [
          notice != null ? `Notice ran ${notice} days. ` : "",
          `Completed ${fmtDay(migration.completedAt?.slice(0, 10)) ?? "—"}`,
          ` across ${runs.filter((r) => !r["dry-run"]).length || 1} business day(s).`,
        ],
      };
    }
    const until = daysBetween(today, migration.dueOn);
    const since = daysBetween(migration.notifiedOn, today);
    if (until == null) {
      return { parts: ["This migration is approved but has no due date set."] };
    }
    if (until > 0) {
      return {
        parts: [
          "Today is ",
          { strong: fmtDay(today) },
          since != null ? `. Customers were notified ${since} days ago; accounts move in ` : ". Accounts move in ",
          { strong: `${until} days` },
          ".",
        ],
      };
    }
    if (until === 0) {
      return {
        parts: ["Today is ", { strong: fmtDay(today) }, " — the due date. Accounts are moving now."],
      };
    }
    return {
      parts: [
        `Due date passed ${Math.abs(until)} days ago; the scheduler is still working through the population.`,
      ],
    };
  });

  const milestones = $derived.by(() => {
    const nodes = [
      { key: "created", value: fmtDay(migration.createdAt?.slice(0, 10)), day: migration.createdAt?.slice(0, 10) },
      { key: "customers notified", value: fmtDay(migration.notifiedOn), day: migration.notifiedOn },
      { key: "accounts move", value: fmtDay(migration.dueOn), day: migration.dueOn },
    ];
    if (migration.status === "cancelled") {
      nodes.push({
        key: "cancelled",
        value: fmtDay(migration.cancelledAt?.slice(0, 10)),
        day: migration.cancelledAt?.slice(0, 10),
        cancelled: true,
      });
    } else {
      nodes.push({
        key: "completed",
        value: fmtDay(migration.completedAt?.slice(0, 10)),
        day: migration.completedAt?.slice(0, 10),
      });
    }
    return nodes.map((n) => ({
      ...n,
      state: n.cancelled
        ? "cancelled"
        : !n.day
          ? "future"
          : n.day < today
            ? "done"
            : n.day === today
              ? "now"
              : "future",
    }));
  });

  const NO_EDIT = "bank-api has no route to edit a migration yet";
</script>

<!-- Hero -->
<Panel pad="lg">
  <div class="hero-top">
    <div class="hero-ident">
      <span class="hero-kicker">{kicker}</span>
      <h2 class="hero-name">{migration.name}</h2>
      <span class="hero-id">{migration.id}</span>
    </div>
    <div class="hero-right">
      <MigrationStatusBadge status={migration.status} />
      <div class="hero-actions">
        {#if migration.status === "draft"}
          <Button size="sm" disabled title={NO_EDIT}>Edit</Button>
          <Button size="sm" onclick={preview} disabled={previewing}>
            {previewing ? "Running…" : "Run preview"}
          </Button>
          <Button
            size="sm"
            variant="primary"
            disabled={guards.length > 0}
            title={guards.length
              ? "Resolve the items below before approving"
              : undefined}
            onclick={onapprove}
          >
            Approve
          </Button>
          <Button size="sm" variant="ghost" onclick={oncancel}>Discard</Button>
        {:else if migration.status === "approved"}
          <Button size="sm" disabled title={NO_EDIT}>Edit</Button>
          <Button size="sm" onclick={preview} disabled={previewing}>
            {previewing ? "Running…" : "Run preview"}
          </Button>
          <Button size="sm" variant="ghost" onclick={oncancel}>
            Cancel migration
          </Button>
        {:else if migration.status === "completed"}
          <Button size="sm" disabled title="Run reports aren't exportable yet">
            Export report
          </Button>
        {/if}
      </div>
    </div>
  </div>

  {#if migration.status === "draft"}
    {#each guards as g}
      <div class="guard">
        <svg
          viewBox="0 0 16 16"
          fill="none"
          stroke="currentColor"
          stroke-width="1.5"
          stroke-linecap="round"
          stroke-linejoin="round"
          aria-hidden="true"
        >
          <path d="M8 2.2 L14.4 13.4 H1.6 Z" />
          <path d="M8 6.4 V9.4 M8 11.4 v0.01" />
        </svg>
        <span class="guard-text">
          {g.message}
          {#if g.type}<span class="guard-type">{g.type}</span>{/if}
        </span>
      </div>
    {/each}
  {/if}

  <div class="mig-flow">
    <div class="flow-side">
      <span class="flow-label">From</span>
      <span class="flow-product">{sourceProduct?.name ?? migration.sourceProductId}</span>
      <div class="vchips">
        {#each sourceVersions as v (v.id)}
          <span class="vchip">v{v.number}{fmtRate(v.rateBps) ? ` ${fmtRate(v.rateBps)}` : ""}</span>
        {/each}
        {#if sourceVersions.length === 0}
          <span class="vchip">none selected</span>
        {/if}
      </div>
      <div class="facts">
        <div class="fact">
          <span class="k">Accounts on source versions</span>
          <span class="v">{onSourceVersions == null ? "—" : num(onSourceVersions)}</span>
        </div>
        <div class="fact">
          <span class="k">All accounts on product</span>
          <span class="v">{onSourceProduct == null ? "—" : num(onSourceProduct)}</span>
        </div>
      </div>
    </div>

    <div class="flow-arrow" aria-hidden="true">
      <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
        <path d="M3 10 H17 M12 5 L17 10 L12 15" />
      </svg>
    </div>

    <div class="flow-side flow-to">
      <span class="flow-label">To</span>
      <span class="flow-product">{targetProduct?.name ?? migration.targetProductId}</span>
      <div class="vchips">
        {#if targetVersion}
          <span class="vchip target">
            v{targetVersion.number}{fmtRate(targetVersion.rateBps) ? ` ${fmtRate(targetVersion.rateBps)}` : ""}
            <span class="vchip-status" class:ok={targetVersion.published}>
              {targetVersion.status}
            </span>
          </span>
        {/if}
      </div>
      <div class="facts">
        <div class="fact">
          <span class="k">Rate change</span>
          <span class="v {rateDelta?.tone ?? ''}">{rateDelta?.text ?? "—"}</span>
        </div>
        <div class="fact">
          <span class="k">Currencies allowed</span>
          <span class="v">{targetVersion?.currencies.join(", ") || "—"}</span>
        </div>
        <div class="fact">
          <span class="k">Effective from</span>
          <span class="v">{fmtDay(targetVersion?.effectiveFrom) ?? "—"}</span>
        </div>
      </div>
    </div>
  </div>
</Panel>

<!-- Notice timeline -->
<Panel pad="md">
  <ol class="timeline">
    {#each milestones as node, i}
      <li class="tl-node {node.state}" class:first={i === 0} class:last={i === milestones.length - 1}>
        <span class="tl-key">{node.key}</span>
        <span class="tl-value" class:unset={!node.value}>{node.value ?? "not set"}</span>
      </li>
    {/each}
  </ol>
  <p class="tl-caption">
    {#each caption.parts as part}
      {#if typeof part === "string"}{part}{:else}<strong>{part.strong}</strong>{/if}
    {/each}
  </p>
</Panel>

<!-- Preview -->
<Panel>
  <PanelHead
    title="Preview"
    count={frozen ? "final result" : "dry run — nothing moves"}
    note={shownRun ? `ran ${fmtStamp(shownRun["started-at"])}` : undefined}
  />
  {#if runsLoading}
    <div class="prev-none">Loading…</div>
  {:else if !shownRun}
    <div class="prev-none">
      <p>No preview yet. Run one to see which accounts would move — and why the rest would not.</p>
      <Button variant="ghost" size="sm" onclick={preview} disabled={previewing}>
        {previewing ? "Running preview…" : "Run preview"}
      </Button>
    </div>
  {:else}
    <div class="prev-body">
      <div>
        <span class="prev-label">Accounts seen</span>
        <div class="prev-figure">{num(seen)}</div>
        <span class="prev-sub">every account on {sourceProduct?.name ?? "the source product"}</span>

        <div class="bar">
          <span class="seg movable" style:width={pct(movable + moved)}></span>
          <span class="seg held" style:width={pct(held)}></span>
          <span class="seg failed" style:width={pct(failed)}></span>
        </div>

        <div class="legend">
          {#if moved > 0}
            <div class="leg"><span class="sw movable"></span><span>Migrated</span><span class="n">{num(moved)}</span></div>
          {/if}
          {#if movable > 0}
            <div class="leg">
              <span class="sw movable"></span>
              <span>{frozen ? "Would have moved" : "Would move"}</span>
              <span class="n">{num(movable)}</span>
            </div>
          {/if}
          {#if held > 0}
            <div class="leg"><span class="sw held"></span><span>Held back</span><span class="n">{num(held)}</span></div>
          {/if}
          <div class="leg"><span class="sw failed"></span><span>Failed</span><span class="n">{num(failed)}</span></div>
        </div>
      </div>

      <div>
        <span class="prev-why">Why accounts are held back</span>
        {#if heldByReason.length === 0}
          <p class="prev-none-held">Nothing held back — every account on the source moves.</p>
        {:else}
          <div class="reasons">
            {#each heldByReason as r (r.reason)}
              <div class="reason">
                <span class="rc">{r.reason}</span>
                <span class="rl">{r.label}</span>
                <span class="rn">{num(r.count)}</span>
              </div>
            {/each}
          </div>
        {/if}
      </div>
    </div>
  {/if}
</Panel>

<!-- Runs -->
<Panel>
  <PanelHead title="Runs" count={runs.length} note="one run per business day" />
  {#if runs.length === 0}
    <div class="prev-none">No runs yet. A preview counts as a dry run and will appear here.</div>
  {:else}
    <Table>
      <Thead>
        <Tr>
          <Th>Business day</Th>
          <Th>Kind</Th>
          <Th>Status</Th>
          <Th align="right">Seen</Th>
          <Th align="right">Moved</Th>
          <Th align="right">Held back</Th>
          <Th align="right">Failed</Th>
          <Th align="right">Duration</Th>
        </Tr>
      </Thead>
      <Tbody>
        {#each runs as r (r["run-id"])}
          <Tr class={r["dry-run"] ? "dry" : undefined}>
            <Td mono>{fmtDay(r["business-day"])}</Td>
            <Td><span class="kind">{r["dry-run"] ? "dry run" : "live"}</span></Td>
            <Td>
              <MigrationStatusBadge status={r.status} kind="run" />
              {#if r.error}<span class="run-error">{r.error}</span>{/if}
            </Td>
            <Td mono tabular align="right" muted={!r["accounts-seen"]}>{num(r["accounts-seen"])}</Td>
            <Td mono tabular align="right" muted={!r["accounts-moved"]}>{num(r["accounts-moved"])}</Td>
            <Td mono tabular align="right" muted={!r["accounts-ineligible"]}>{num(r["accounts-ineligible"])}</Td>
            <Td mono tabular align="right" muted={!r["accounts-failed"]}>
              <span class:bad={r["accounts-failed"] > 0}>{num(r["accounts-failed"])}</span>
            </Td>
            <Td mono tabular align="right">{fmtDuration(r["started-at"], r["finished-at"])}</Td>
          </Tr>
        {/each}
      </Tbody>
    </Table>
  {/if}
</Panel>

<!-- Per-account outcomes -->
{#if shownRun && outcomes.length > 0}
  <Panel>
    <header class="out-head">
      <div class="out-chips">
        <Chip pressed={outcome === "all"} count={outcomes.length} onclick={() => ((outcome = "all"), (limit = PAGE))}>
          All
        </Chip>
        {#if outcomeCounts.migrated > 0}
          <Chip pressed={outcome === "migrated"} count={outcomeCounts.migrated} onclick={() => ((outcome = "migrated"), (limit = PAGE))}>
            Migrated
          </Chip>
        {/if}
        {#if outcomeCounts.eligible > 0}
          <Chip pressed={outcome === "eligible"} count={outcomeCounts.eligible} onclick={() => ((outcome = "eligible"), (limit = PAGE))}>
            {frozen ? "Would have moved" : "Would move"}
          </Chip>
        {/if}
        {#if outcomeCounts.ineligible > 0}
          <Chip pressed={outcome === "ineligible"} count={outcomeCounts.ineligible} onclick={() => ((outcome = "ineligible"), (limit = PAGE))}>
            Held back
          </Chip>
        {/if}
        {#if outcomeCounts.failed > 0}
          <Chip pressed={outcome === "failed"} count={outcomeCounts.failed} onclick={() => ((outcome = "failed"), (limit = PAGE))}>
            Failed
          </Chip>
        {/if}
      </div>
      <div class="out-search">
        <SearchField
          bind:value={acctQuery}
          size="sm"
          placeholder="Account or owner…"
          ariaLabel="Search outcomes"
        />
      </div>
    </header>

    {#if rows.length === 0}
      <div class="prev-none">No account matches that filter.</div>
    {:else}
      <Table>
        <Thead>
          <Tr>
            <Th>Account</Th>
            <Th>Ccy</Th>
            <Th>From</Th>
            <Th>To</Th>
            <Th>Outcome</Th>
            <Th>Reason</Th>
          </Tr>
        </Thead>
        <Tbody>
          {#each rows.slice(0, limit) as a (a["account-id"])}
            {@const acct = population?.accountById?.[a["account-id"]]}
            {@const reason = shortEnum(a.ineligibility)}
            <Tr>
              <Td>
                <span class="acct-num">{acct?.number ?? a["account-id"]}</span>
                {#if acct?.owner}<span class="acct-owner">{acct.owner}</span>{/if}
              </Td>
              <Td mono>{acct?.ccy ?? "—"}</Td>
              <Td mono>{versionById[a["from-version-id"]] ? `v${versionById[a["from-version-id"]].number}` : "—"}</Td>
              {@const to = destination(a)}
              <Td mono muted={to.prospective} title={to.prospective ? "Would move here — a dry run moves nothing" : undefined}>
                {to.label}
              </Td>
              <Td><MigrationStatusBadge status={a.outcome} kind="outcome" /></Td>
              <Td>
                {#if reason}
                  <span class="reason-label">{INELIGIBILITY_LABEL[reason] ?? reason}</span>
                  <span class="reason-code">
                    {reason}{reason === "account-not-open" && acct?.status ? ` · ${acct.status}` : ""}
                  </span>
                {:else if a["failure-reason"]}
                  <span class="reason-label">{a["failure-reason"]}</span>
                {:else}
                  <span class="reason-none">—</span>
                {/if}
              </Td>
            </Tr>
          {/each}
        </Tbody>
      </Table>
      <div class="out-foot">
        <span>showing {num(Math.min(limit, rows.length))} of {num(rows.length)}</span>
        {#if rows.length > limit}
          <Button variant="ghost" size="sm" onclick={() => (limit += PAGE)}>
            Show {PAGE} more
          </Button>
        {/if}
      </div>
    {/if}
  </Panel>
{/if}

<style>
  /* Hero */
  .hero-top {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 20px;
    flex-wrap: wrap;
  }
  .hero-ident {
    display: flex;
    flex-direction: column;
    min-width: 0;
  }
  .hero-kicker {
    font-family: var(--mono);
    font-size: 11px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--gold-deep);
  }
  .hero-name {
    margin: 4px 0 0;
    font-family: var(--grotesk);
    font-size: 21px;
    font-weight: 500;
    color: var(--fg);
  }
  .hero-id {
    margin-top: 4px;
    font-family: var(--mono);
    font-size: 11.5px;
    color: var(--fg-muted);
  }
  .hero-right {
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    gap: 10px;
  }
  .hero-actions {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
    justify-content: flex-end;
  }

  .guard {
    display: flex;
    align-items: flex-start;
    gap: 9px;
    margin-top: 16px;
    padding: 10px 12px;
    border-radius: 6px;
    background: light-dark(oklch(0.95 0.045 85), oklch(0.26 0.045 80));
    color: light-dark(oklch(0.4 0.095 60), oklch(0.86 0.09 85));
    font-size: 12.5px;
    line-height: 1.45;
  }
  .guard svg {
    width: 14px;
    height: 14px;
    flex: 0 0 auto;
    margin-top: 2px;
  }
  .guard-type {
    display: block;
    margin-top: 3px;
    font-family: var(--mono);
    font-size: 10.5px;
    opacity: 0.75;
  }

  .mig-flow {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 44px minmax(0, 1fr);
    align-items: start;
    margin-top: 18px;
    padding-top: 18px;
    border-top: 1px solid var(--rule-2);
  }
  .flow-side {
    display: flex;
    flex-direction: column;
    gap: 8px;
    min-width: 0;
  }
  .flow-to {
    padding-left: 22px;
    border-left: 1px solid var(--rule-2);
  }
  .flow-label {
    font-family: var(--mono);
    font-size: 10px;
    letter-spacing: 0.1em;
    text-transform: uppercase;
    color: var(--fg-muted);
  }
  .flow-product {
    font-size: 14.5px;
    font-weight: 500;
    color: var(--fg);
  }
  .vchips {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }
  .vchip {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    height: 22px;
    padding: 0 8px;
    border-radius: 5px;
    font-family: var(--mono);
    font-size: 11px;
    color: var(--fg-2);
    background: var(--surface-sunk);
    border: 1px solid var(--rule-2);
    white-space: nowrap;
  }
  .vchip.target {
    border-color: light-dark(oklch(0.78 0.09 82), oklch(0.52 0.1 78));
    background: light-dark(oklch(0.96 0.03 85), oklch(0.28 0.04 80));
  }
  .vchip-status {
    color: var(--danger);
  }
  .vchip-status.ok {
    color: var(--pos);
  }
  .facts {
    display: flex;
    flex-direction: column;
    gap: 6px;
    margin-top: 4px;
  }
  .fact {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 12px;
    font-size: 12.5px;
  }
  .fact .k {
    color: var(--fg-muted);
  }
  .fact .v {
    font-family: var(--mono);
    font-variant-numeric: tabular-nums;
    color: var(--fg-2);
    white-space: nowrap;
  }
  .fact .v.up {
    color: var(--pos);
  }
  .fact .v.down {
    color: var(--danger);
  }
  .flow-arrow {
    display: flex;
    justify-content: center;
    padding-top: 26px;
    color: var(--gold-deep);
  }
  .flow-arrow svg {
    width: 20px;
    height: 20px;
  }

  /* Timeline */
  .timeline {
    display: grid;
    grid-auto-flow: column;
    grid-auto-columns: 1fr;
    margin: 0;
    padding: 0;
    list-style: none;
  }
  .tl-node {
    position: relative;
    padding-top: 22px;
    padding-right: 12px;
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
  .tl-node::before {
    content: "";
    position: absolute;
    top: 0;
    left: 0;
    width: 11px;
    height: 11px;
    border-radius: 50%;
    background: var(--surface-raised);
    border: 1.5px solid var(--rule);
    z-index: 1;
  }
  .tl-node::after {
    content: "";
    position: absolute;
    top: 5px;
    left: 0;
    right: 0;
    height: 1.5px;
    background: var(--rule);
  }
  .tl-node.first::after {
    left: 5px;
  }
  .tl-node.last::after {
    right: calc(100% - 6px);
  }
  .tl-node.done::before {
    background: var(--pine-4);
    border-color: var(--pine-4);
  }
  .tl-node.done::after {
    background: var(--pine-4);
  }
  .tl-node.now::before {
    background: var(--gold);
    border-color: var(--gold-deep);
    box-shadow: 0 0 0 3px light-dark(oklch(0.93 0.06 85), oklch(0.3 0.06 80));
  }
  .tl-node.cancelled::before {
    background: var(--danger);
    border-color: var(--danger);
  }
  .tl-key {
    font-family: var(--mono);
    font-size: 10px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--fg-muted);
  }
  .tl-value {
    font-size: 13px;
    font-weight: 500;
    color: var(--fg);
  }
  .tl-value.unset {
    font-weight: 400;
    color: var(--fg-muted);
  }
  .tl-caption {
    margin: 18px 0 0;
    font-size: 12.5px;
    line-height: 1.5;
    color: var(--fg-muted);
  }
  .tl-caption strong {
    color: var(--fg);
    font-weight: 500;
  }

  /* Preview */
  .prev-none {
    padding: 34px 24px;
    text-align: center;
    font-size: 13px;
    color: var(--fg-muted);
  }
  .prev-none p {
    margin: 0 0 14px;
  }
  .prev-body {
    display: grid;
    grid-template-columns: minmax(0, 0.9fr) minmax(0, 1.1fr);
    gap: 28px;
    padding: 20px 24px;
  }
  .prev-label {
    display: block;
    font-family: var(--mono);
    font-size: 11px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--fg-muted);
  }
  .prev-figure {
    font-family: var(--mono);
    font-size: 36px;
    font-weight: 500;
    line-height: 1;
    font-variant-numeric: tabular-nums;
    color: var(--fg);
    margin: 8px 0 6px;
  }
  .prev-sub {
    display: block;
    font-family: var(--mono);
    font-size: 11px;
    color: var(--fg-muted);
  }
  .bar {
    display: flex;
    height: 8px;
    margin: 16px 0 14px;
    border-radius: 999px;
    overflow: hidden;
    background: var(--surface-sunk);
  }
  .seg.movable {
    background: var(--pine-4);
  }
  .seg.held {
    background: var(--gold);
  }
  .seg.failed {
    background: var(--danger);
  }
  .legend {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }
  .leg {
    display: grid;
    grid-template-columns: 10px minmax(0, 1fr) auto;
    align-items: center;
    gap: 9px;
    font-size: 13px;
    color: var(--fg-2);
  }
  .leg .n {
    font-family: var(--mono);
    font-variant-numeric: tabular-nums;
  }
  .sw {
    width: 8px;
    height: 8px;
    border-radius: 2px;
  }
  .sw.movable {
    background: var(--pine-4);
  }
  .sw.held {
    background: var(--gold);
  }
  .sw.failed {
    background: var(--danger);
  }
  .prev-why {
    display: block;
    font-family: var(--mono);
    font-size: 10px;
    letter-spacing: 0.1em;
    text-transform: uppercase;
    color: var(--gold-deep);
    margin-bottom: 6px;
  }
  .prev-none-held {
    margin: 8px 0 0;
    font-size: 13px;
    color: var(--fg-muted);
  }
  .reasons {
    display: flex;
    flex-direction: column;
  }
  .reason {
    display: grid;
    grid-template-columns: auto minmax(0, 1fr) auto;
    align-items: baseline;
    gap: 0 14px;
    padding: 8px 0;
    border-bottom: 1px solid var(--rule-2);
    font-size: 13px;
  }
  .reason:last-child {
    border-bottom: none;
  }
  .rc {
    font-family: var(--mono);
    font-size: 11px;
    color: var(--fg-muted);
  }
  .rn {
    font-family: var(--mono);
    font-variant-numeric: tabular-nums;
    color: var(--fg);
  }

  /* Runs */
  .kind {
    font-family: var(--mono);
    font-size: 10.5px;
    color: var(--fg-muted);
    background: var(--surface-sunk);
    border: 1px solid var(--rule-2);
    border-radius: 4px;
    padding: 2px 6px;
    white-space: nowrap;
  }
  .run-error {
    display: block;
    margin-top: 4px;
    max-width: 46ch;
    font-size: 12px;
    color: var(--fg-muted);
  }
  .bad {
    color: var(--danger);
  }
  :global(.qw-table tr.dry td) {
    background: light-dark(rgba(20, 15, 10, 0.018), rgba(244, 241, 234, 0.022));
  }
  :global(.qw-table tr.dry:hover td) {
    background: var(--hover-overlay);
  }

  /* Outcomes */
  .out-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    flex-wrap: wrap;
    padding: 14px 20px;
    border-bottom: 1px solid var(--rule-2);
  }
  .out-chips {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
  }
  .out-search {
    width: 240px;
  }
  .acct-num {
    display: block;
    font-family: var(--mono);
    color: var(--fg);
  }
  .acct-owner {
    display: block;
    margin-top: 2px;
    font-size: 11.5px;
    color: var(--fg-muted);
  }
  .reason-label {
    display: block;
  }
  .reason-code {
    display: block;
    margin-top: 2px;
    font-family: var(--mono);
    font-size: 10.5px;
    color: var(--fg-muted);
    opacity: 0.75;
  }
  .reason-none {
    color: var(--fg-muted);
  }
  .out-foot {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    padding: 12px 20px;
    border-top: 1px solid var(--rule-2);
    font-family: var(--mono);
    font-size: 11px;
    color: var(--fg-muted);
  }

  @media (max-width: 1180px) {
    .mig-flow {
      grid-template-columns: 1fr;
    }
    .flow-arrow {
      display: none;
    }
    .flow-to {
      padding-left: 0;
      border-left: none;
      border-top: 1px solid var(--rule-2);
      padding-top: 18px;
      margin-top: 18px;
    }
    .prev-body {
      grid-template-columns: 1fr;
    }
  }
  @media (max-width: 1080px) {
    .out-search {
      width: 100%;
    }
  }
</style>
