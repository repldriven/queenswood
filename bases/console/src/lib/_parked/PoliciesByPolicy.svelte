<script>
  /* Policies page — read-only. Pick a policy from the master table and
     read, per domain, exactly what it permits (capabilities) and how it
     is bounded (limits), side by side, in the matrix below.

     Data: GET /v1/banks/{bank-id}/policies returns the policies effective for the
     caller's bank (the platform tier plus any bound to the bank) in the
     nested protojure wire shape; policy-adapter flattens each into the
     view-model the ui matrix components consume. No per-row fetch —
     each policy already carries its capabilities and limits. Selection,
     search, and the ungoverned toggle are consumer state. */

  import {
    PageHeader,
    Button,
    Table,
    Thead,
    Tbody,
    Tr,
    Th,
    Td,
    Badge,
    PolicyMatrix,
    CATEGORY_TONE,
  } from "@queenswood/ui";
  import { list_my_policies } from "../api.mjs";
  import { adaptPolicies } from "../policy-adapter.mjs";

  let { user, memberships } = $props();

  let loading = $state(true);
  let error = $state(null);
  let policies = $state([]);

  let selected = $state(0);
  let query = $state("");
  let showUngoverned = $state(false);

  const kicker = $derived(memberships?.[0]?.["bank-name"]);
  const policy = $derived(policies[selected] ?? null);

  function categoryTone(category) {
    return CATEGORY_TONE[category] ?? "neutral";
  }

  function displayName(p) {
    return p.name || "Untitled policy";
  }

  async function load() {
    loading = true;
    error = null;
    try {
      const res = await list_my_policies();
      if (res.status < 200 || res.status >= 300) {
        error = res.body?.detail ?? `HTTP ${res.status}`;
        policies = [];
        return;
      }
      policies = adaptPolicies(res.body?.items ?? []);
      if (selected >= policies.length) selected = 0;
    } catch (err) {
      error = err.message;
      policies = [];
    } finally {
      loading = false;
    }
  }

  $effect(() => {
    load();
  });

  function select(i) {
    selected = i;
  }

  function onRowKey(e, i) {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      select(i);
    }
  }

  function formatRelative(iso) {
    if (!iso) return "—";
    const then = new Date(iso).getTime();
    const diff = (Date.now() - then) / 1000;
    if (diff < 60) return "just now";
    if (diff < 3600) return `${Math.floor(diff / 60)} min ago`;
    if (diff < 86400) return `${Math.floor(diff / 3600)} h ago`;
    if (diff < 86400 * 7) return `${Math.floor(diff / 86400)} d ago`;
    return new Date(iso).toLocaleDateString();
  }
</script>

<PageHeader
  {kicker}
  title="Policies"
  sub="Pick a policy to see, per domain, exactly what it permits and how it is bounded. Each policy grants capabilities — an allow or deny on a domain action — and bounds them with limits. A limit marked improving is a curative permit: a breaching action is allowed only when it moves the position back toward compliance."
>
  {#snippet actions()}
    <Button variant="ghost" onclick={load}>Refresh</Button>
  {/snippet}
</PageHeader>

{#if error}
  <div class="alert" role="alert">{error}</div>
{/if}

{#if loading}
  <div class="loading">Loading…</div>
{:else if policies.length === 0}
  <div class="empty">
    <p>No policies apply to your bank yet.</p>
    <p class="hint">Effective policies are the platform tier plus any bound to your bank.</p>
  </div>
{:else}
  <div class="policies">
    <!-- Master table: one row per policy, click to select -->
    <Table>
      <Thead>
        <Tr>
          <Th>Policy</Th>
          <Th>Policy ID</Th>
          <Th>Category</Th>
          <Th>Status</Th>
          <Th align="right">Capabilities</Th>
          <Th align="right">Limits</Th>
          <Th>Updated</Th>
          <Th />
        </Tr>
      </Thead>
      <Tbody>
        {#each policies as p, i (p.policyId)}
          <Tr
            role="button"
            tabindex="0"
            aria-selected={i === selected}
            onclick={() => select(i)}
            onkeydown={(e) => onRowKey(e, i)}
          >
            <Td emphasized>{displayName(p)}</Td>
            <Td mono muted>{p.policyId}</Td>
            <Td><Badge tone={categoryTone(p.category)}>{p.category}</Badge></Td>
            <Td>
              <span class="status" class:status--off={!p.enabled}>
                {p.enabled ? "enabled" : "disabled"}
              </span>
            </Td>
            <Td align="right" mono tabular>{p.capabilities.length}</Td>
            <Td align="right" mono tabular>
              <span class:zero={p.limits.length === 0}>{p.limits.length}</span>
            </Td>
            <Td muted>{formatRelative(p.updatedAt)}</Td>
            <Td>
              <span class="chev" aria-hidden="true">
                <svg viewBox="0 0 16 16" fill="none" stroke="currentColor"
                     stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M6 4 L10 8 L6 12" />
                </svg>
              </span>
            </Td>
          </Tr>
        {/each}
      </Tbody>
    </Table>

    <!-- Toolbar -->
    <div class="toolbar">
      <div class="search">
        <svg viewBox="0 0 16 16" aria-hidden="true">
          <circle cx="7" cy="7" r="4.5" />
          <path d="M10.5 10.5 L14 14" />
        </svg>
        <input
          type="search"
          bind:value={query}
          placeholder="Filter domains, actions, reasons…"
        />
      </div>
      <label class="toggle">
        <input type="checkbox" bind:checked={showUngoverned} />
        Show ungoverned domains
      </label>
      <div class="legend">
        <span><i class="sw allow"></i> allow</span>
        <span><i class="sw deny"></i> deny</span>
        <span><i class="sw improving"></i> improving</span>
      </div>
    </div>

    {#if policy}
      <!-- Detail header -->
      <div class="detail">
        <div class="detail-main">
          <div class="detail-head">
            <h2>{displayName(policy)}</h2>
            <Badge tone={categoryTone(policy.category)}>{policy.category}</Badge>
            <span class="status" class:status--off={!policy.enabled}>
              {policy.enabled ? "enabled" : "disabled"}
            </span>
            <span class="detail-id">{policy.policyId}</span>
          </div>
          {#if policy.description}
            <p class="detail-desc">{policy.description}</p>
          {/if}
        </div>
        <div class="stats">
          <div class="stat">
            <span class="stat-n">{policy.capabilities.length}</span>
            <span class="stat-l">capabilities</span>
          </div>
          <div class="stat">
            <span class="stat-n">{policy.limits.length}</span>
            <span class="stat-l">limits</span>
          </div>
        </div>
      </div>

      <!-- Per-domain matrix -->
      <PolicyMatrix {policy} {showUngoverned} {query} />
    {/if}
  </div>
{/if}

<style>
  .policies {
    display: flex;
    flex-direction: column;
    gap: 20px;
  }

  .alert {
    padding: 12px 16px;
    border: 1px solid var(--rule);
    border-radius: 6px;
    background: var(--surface-sunk);
    color: var(--fg);
    font-size: 14px;
  }
  .loading,
  .empty {
    padding: 48px 16px;
    text-align: center;
    color: var(--fg-muted);
    border: 1px dashed var(--rule);
    border-radius: 12px;
  }
  .empty p { margin: 0; }
  .empty .hint { margin-top: 6px; font-size: 13px; }

  /* status pill — pine when enabled, muted when not */
  .status {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-family: var(--mono);
    font-size: 11px;
    color: light-dark(oklch(0.34 0.075 145), oklch(0.82 0.06 145));
  }
  .status::before {
    content: "";
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: currentColor;
  }
  .status--off { color: var(--fg-muted); }

  .zero { color: var(--fg-muted); }

  /* master-row selection: tint + accent bar + chevron rotation. The
     rows are ui <Tr>; we reach their <td> via :global under the
     page wrapper, keyed on aria-selected. */
  .policies :global(.qw-table tbody tr[role="button"]) { cursor: pointer; }
  .policies :global(.qw-table tbody tr[aria-selected="true"] td) {
    background: light-dark(oklch(0.95 0.022 145), oklch(0.235 0.035 145));
  }
  .policies :global(.qw-table tbody tr[aria-selected="true"] td:first-child) {
    box-shadow: inset 3px 0 0 var(--pine-4);
  }
  .policies :global(.qw-table tbody tr:focus-visible) {
    outline: 2px solid var(--gold);
    outline-offset: -2px;
  }
  .chev {
    display: inline-flex;
    color: var(--fg-muted);
    transition: transform 0.16s ease;
  }
  .chev svg { width: 13px; height: 13px; }
  .policies :global(.qw-table tbody tr[aria-selected="true"] .chev) {
    transform: rotate(90deg);
    color: var(--fg);
  }

  /* Policy ID column hides on narrow viewports (3rd visible <th>/<td>). */
  @media (max-width: 860px) {
    .policies :global(.qw-table tr > :nth-child(2)) { display: none; }
  }

  /* toolbar */
  .toolbar {
    display: flex;
    align-items: center;
    gap: 18px;
    flex-wrap: wrap;
  }
  .search {
    position: relative;
    display: inline-flex;
    align-items: center;
    max-width: 380px;
    flex: 1 1 280px;
  }
  .search svg {
    position: absolute;
    left: 11px;
    width: 14px;
    height: 14px;
    fill: none;
    stroke: var(--fg-muted);
    stroke-width: 1.5;
    stroke-linecap: round;
    pointer-events: none;
  }
  .search input {
    width: 100%;
    height: 36px;
    padding: 0 12px 0 32px;
    border-radius: 6px;
    border: 1px solid var(--rule);
    background: var(--surface-raised);
    color: var(--fg);
    font: inherit;
    font-size: 13px;
  }
  .search input:focus {
    outline: none;
    border-color: var(--gold);
  }
  .toggle {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    font-size: 12px;
    color: var(--fg-muted);
    white-space: nowrap;
    cursor: pointer;
  }
  .legend {
    display: inline-flex;
    align-items: center;
    gap: 14px;
    margin-left: auto;
    font-family: var(--mono);
    font-size: 11px;
    color: var(--fg-muted);
  }
  .legend span { display: inline-flex; align-items: center; gap: 6px; }
  .legend .sw {
    width: 9px;
    height: 9px;
    border-radius: 3px;
    display: inline-block;
  }
  .legend .sw.allow { background: light-dark(oklch(0.34 0.075 145), oklch(0.82 0.06 145)); }
  .legend .sw.deny { background: light-dark(oklch(0.45 0.12 30), oklch(0.84 0.10 30)); }
  .legend .sw.improving {
    border-radius: 50%;
    background: light-dark(oklch(0.46 0.12 70), oklch(0.86 0.12 84));
  }

  /* detail header */
  .detail {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 24px;
    flex-wrap: wrap;
  }
  .detail-head {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
  }
  .detail-head h2 {
    font-family: var(--grotesk);
    font-weight: 600;
    font-size: 18px;
    margin: 0;
    color: var(--fg);
  }
  .detail-id {
    font-family: var(--mono);
    font-size: 11px;
    color: var(--fg-muted);
  }
  .detail-desc {
    margin: 8px 0 0;
    max-width: 92ch;
    font-size: 13px;
    line-height: 1.5;
    color: var(--fg-2);
  }
  .stats {
    display: inline-flex;
    gap: 26px;
  }
  .stat {
    display: flex;
    flex-direction: column;
    align-items: flex-end;
  }
  .stat-n {
    font-family: var(--mono);
    font-size: 20px;
    font-weight: 500;
    color: var(--fg);
    font-variant-numeric: tabular-nums;
  }
  .stat-l {
    font-family: var(--mono);
    font-size: 10px;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: var(--fg-muted);
  }
</style>
