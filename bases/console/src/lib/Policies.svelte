<script>
  /* Policies page — read-only, effective view.

     Unlike the per-policy view (parked under _parked/PoliciesByPolicy),
     this shows the RESOLVED effective decision for the bank: the policies
     in effect (platform tier plus any bound) collapsed the way evaluation
     resolves them — capabilities deny-wins, limits most-restrictive — to a
     single survivor per domain action. Each survivor carries its origin
     (which policy/tier decided it), shown as a badge in the matrix. There
     is no policy selector: you read the capabilities and limits directly.

     Data: GET /v1/banks/{bank-id}/effective-policy returns { capabilities, limits },
     each entry the flat wire shape plus an `origin`. adaptEffectivePolicy
     flattens it into the { capabilities, limits } view-model PolicyMatrix
     already consumes. */

  import { PageHeader, Button, PolicyMatrix } from "@queenswood/ui";
  import { list_my_effective_policies } from "./api.mjs";
  import { adaptEffectivePolicy } from "./policy-adapter.mjs";

  let { user, memberships } = $props();

  let loading = $state(true);
  let error = $state(null);
  let effective = $state({ capabilities: [], limits: [] });

  // `#/policies?q=balance` lands filtered to that row, which is how the
  // scenarios point at the rule that held.
  let query = $state(new URLSearchParams(location.hash.split("?")[1] ?? "").get("q") ?? "");
  let showUngoverned = $state(false);

  const kicker = $derived(memberships?.[0]?.["bank-name"]);

  async function load() {
    loading = true;
    error = null;
    try {
      const res = await list_my_effective_policies();
      if (res.status < 200 || res.status >= 300) {
        error = res.body?.detail ?? `HTTP ${res.status}`;
        effective = { capabilities: [], limits: [] };
        return;
      }
      effective = adaptEffectivePolicy(res.body);
    } catch (err) {
      error = err.message;
      effective = { capabilities: [], limits: [] };
    } finally {
      loading = false;
    }
  }

  $effect(() => {
    load();
  });
</script>

<PageHeader
  {kicker}
  title="Policies"
  sub="The policies in effect for your bank, resolved into a single decision per action. Where policies overlap, the binding one wins — a deny overrides an allow, and the tightest limit applies. Each entry is tagged with the policy it comes from. A limit marked improving is a curative permit: a breaching action is allowed only when it moves the position back toward compliance."
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
{:else if effective.capabilities.length === 0 && effective.limits.length === 0}
  <div class="empty">
    <p>No policies apply to your bank yet.</p>
    <p class="hint">Effective policies are the platform tier plus any bound to your bank.</p>
  </div>
{:else}
  <div class="policies">
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
          placeholder="Filter domains, actions, reasons, origins…"
        />
      </div>
      <label class="toggle">
        <input type="checkbox" bind:checked={showUngoverned} />
        Show ungoverned domains
      </label>
      <div class="stats">
        <span>{effective.capabilities.length} capabilities</span>
        <span>{effective.limits.length} limits</span>
      </div>
      <div class="legend">
        <span><i class="sw allow"></i> allow</span>
        <span><i class="sw deny"></i> deny</span>
        <span><i class="sw improving"></i> improving</span>
      </div>
    </div>

    <!-- Per-domain matrix of the resolved decision -->
    <PolicyMatrix policy={effective} {showUngoverned} {query} />
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
  .stats {
    display: inline-flex;
    gap: 14px;
    font-family: var(--mono);
    font-size: 11px;
    color: var(--fg-muted);
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
</style>
