<script>
  /* PolicyMatrix — the per-domain view of one policy.

     The point of this screen: read DOWN the Domain column and ACROSS to
     see everything that applies to a domain — its capabilities and its
     limits, side by side — without opening anything. One row per domain,
     grouped into sections (Core Banking / Payments / Identity / People / Platform)
     with a separator row that counts how many of the section's domains
     the policy governs.

     Props
       policy           the flattened Policy (see policy.js)
       showUngoverned   also render domains the policy doesn't mention,
                        shown muted as "inherits platform default"
       query            optional case-insensitive filter over domain /
                        action / reason / filter text

     Composition
       <Effect> / <Bound> / <Improving> / <FilterChips> are the reusable
       atoms; this component owns the table chrome + the stacked-cell
       layout. It inlines the `.qw-table` base so it's drop-in on its own;
       if you already render via <Table>, lift these rules into a
       `qw-table--matrix` mode the same way the ledger added `tree`. */

  import Effect from "./Effect.svelte";
  import Bound from "./Bound.svelte";
  import Improving from "./Improving.svelte";
  import FilterChips from "./FilterChips.svelte";
  import { GROUP_ORDER, groupByDomain, sectionRows } from "./policy.js";

  let { policy, showUngoverned = false, query = "" } = $props();

  const byDomain = $derived(groupByDomain(policy));
  const q = $derived(query.trim().toLowerCase());

  // A capability's reason only earns a line when it adds something beyond
  // the verb — i.e. it's a deny, or it's filtered. "Allow creating
  // balances" next to an `allow create` badge is noise; suppress it.
  const capShowsReason = (c) => Boolean(c.reason) && (c.effect === "deny" || c.filters.length > 0);

  // Provenance: present only on the effective (merged) view. The badge
  // shows the origin tier (platform / micro / …), falling back to the
  // policy name; the full name + id sit in the tooltip.
  const originLabel = (o) => o?.tier ?? o?.name ?? null;
  const originTitle = (o) => [o?.name, o?.policyId].filter(Boolean).join(" · ");
  const originText = (o) => (o ? `${o.tier ?? ""} ${o.name ?? ""}` : "");

  function matchesQuery(row) {
    if (!q) return true;
    let hay = row.label.toLowerCase();
    if (row.data) {
      for (const c of row.data.caps)
        hay += ` ${c.effect} ${c.action} ${c.reason ?? ""} ${c.filters.map((f) => f.key + f.value).join(" ")} ${originText(c.origin)}`;
      for (const l of row.data.lims)
        hay += ` ${l.reason ?? ""} ${l.allow ?? ""} ${l.filters.map((f) => f.key + f.value).join(" ")} ${originText(l.origin)}`;
    }
    return hay.toLowerCase().includes(q);
  }

  // Build visible sections: a section appears only if it has visible rows.
  const sections = $derived.by(() =>
    GROUP_ORDER.map((group) => {
      const all = sectionRows(group, byDomain);
      const rows = all
        .filter((r) => (r.governed || showUngoverned) && matchesQuery(r));
      return {
        group,
        rows,
        governed: all.filter((r) => r.governed).length,
        total: all.length,
      };
    }).filter((s) => s.rows.length > 0)
  );
</script>

<div class="qw-table-wrap">
  <table class="qw-table qw-matrix">
    <colgroup>
      <col class="c-domain" /><col class="c-caps" /><col class="c-limits" />
    </colgroup>
    <thead>
      <tr><th>Domain</th><th>Capabilities</th><th>Limits</th></tr>
    </thead>
    <tbody>
      {#each sections as section}
        <tr class="qw-pm-group">
          <td colspan="3">
            <span class="gr-name">{section.group}</span>
            <span class="gr-count">{section.governed} of {section.total} governed</span>
          </td>
        </tr>

        {#each section.rows as row}
          <tr class:ungoverned={!row.governed}>
            <td class="m-domain"><span class="m-dname">{row.label}</span></td>

            <!-- Capabilities -->
            <td>
              {#if !row.governed}
                <span class="m-inherit">inherits platform default</span>
              {:else if row.data.caps.length === 0}
                <span class="m-none">—</span>
              {:else}
                <div class="m-cell">
                  {#each row.data.caps as c}
                    <div class="m-entry">
                      <div class="m-head">
                        <Effect effect={c.effect} />
                        <span class="m-action">{c.action}</span>
                        {#if c.filters.length}<FilterChips filters={c.filters} />{/if}
                        {#if originLabel(c.origin)}
                          <span class="m-origin" title={originTitle(c.origin)}>{originLabel(c.origin)}</span>
                        {/if}
                      </div>
                      {#if capShowsReason(c)}<div class="m-reason">{c.reason}</div>{/if}
                    </div>
                  {/each}
                </div>
              {/if}
            </td>

            <!-- Limits -->
            <td>
              {#if !row.governed || row.data.lims.length === 0}
                <span class="m-none">no limit</span>
              {:else}
                <div class="m-cell">
                  {#each row.data.lims as l}
                    <div class="m-entry">
                      <div class="m-head">
                        <Bound bound={l.bound} />
                        {#if l.allow === "improving"}<Improving />{/if}
                        {#if originLabel(l.origin)}
                          <span class="m-origin" title={originTitle(l.origin)}>{originLabel(l.origin)}</span>
                        {/if}
                      </div>
                      {#if l.filters.length}<FilterChips filters={l.filters} />{/if}
                      {#if l.reason}<div class="m-reason">{l.reason}</div>{/if}
                    </div>
                  {/each}
                </div>
              {/if}
            </td>
          </tr>
        {/each}
      {/each}

      {#if sections.length === 0}
        <tr><td colspan="3" class="m-empty">No domains match “{query}”.</td></tr>
      {/if}
    </tbody>
  </table>
</div>

<style>
  /* --- table chrome (inlined so the component is drop-in) --- */
  .qw-table-wrap {
    background: var(--surface-raised);
    border: 1px solid var(--rule-2);
    border-radius: 8px;
    overflow: hidden;
  }
  .qw-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 13px;
    color: var(--fg);
  }
  .qw-table thead th {
    text-align: left;
    font-family: var(--mono);
    font-size: 10px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--fg-muted);
    font-weight: 500;
    padding: 11px 18px;
    background: var(--surface-sunk);
    border-bottom: 1px solid var(--rule-2);
    white-space: nowrap;
  }
  .qw-table tbody td {
    padding: 13px 18px;
    border-bottom: 1px solid var(--rule-2);
    vertical-align: top;
    color: var(--fg-2);
  }
  .qw-table tbody tr:last-child td { border-bottom: none; }

  /* --- matrix specifics --- */
  .qw-matrix { table-layout: fixed; }
  .qw-matrix col.c-domain { width: 19%; }
  .qw-matrix col.c-caps   { width: 40.5%; }
  .qw-matrix col.c-limits { width: 40.5%; }
  .qw-matrix tbody tr:not(.qw-pm-group):hover td { background: var(--hover-overlay); }
  .qw-matrix tbody tr.ungoverned td { background: var(--surface-sunk); }

  .m-domain { color: var(--fg); }
  .m-domain .m-dname { font-family: var(--grotesk); font-weight: 600; font-size: 13.5px; letter-spacing: -0.005em; }

  /* group separator rows */
  .qw-pm-group td {
    background: var(--surface-sunk);
    padding: 9px 18px;
    border-top: 1px solid var(--rule-2);
    border-bottom: 1px solid var(--rule-2);
  }
  .qw-matrix tbody tr.qw-pm-group:first-child td { border-top: none; }
  .qw-pm-group .gr-name { font-family: var(--mono); font-size: 10px; letter-spacing: 0.1em; text-transform: uppercase; color: var(--fg-2); font-weight: 500; }
  .qw-pm-group .gr-count { font-family: var(--mono); font-size: 10px; letter-spacing: 0.04em; color: var(--fg-muted); margin-left: 10px; }

  /* stacked entries within a cell */
  .m-cell { display: flex; flex-direction: column; }
  .m-entry { padding: 2px 0; }
  .m-entry + .m-entry { margin-top: 9px; padding-top: 9px; border-top: 1px dashed var(--rule-2); }
  .m-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
  .m-action { font-family: var(--grotesk); font-weight: 500; font-size: 14px; color: var(--fg); letter-spacing: -0.005em; }
  .m-reason { font-size: 12px; color: var(--fg-muted); margin-top: 5px; line-height: 1.45; text-wrap: pretty; }

  /* provenance badge — which policy/tier decided this entry (effective
     view only). Sits to the right of the head, set apart from filter
     chips by an accent ring. */
  .m-origin {
    margin-left: auto;
    display: inline-flex;
    align-items: center;
    height: 18px;
    padding: 0 7px;
    border-radius: 9px;
    font-family: var(--mono);
    font-size: 10px;
    letter-spacing: 0.02em;
    color: var(--fg-2);
    background: var(--surface-sunk);
    border: 1px solid var(--rule);
    white-space: nowrap;
  }

  .m-none { color: var(--fg-muted); font-size: 13px; opacity: 0.8; }
  .m-inherit { color: var(--fg-muted); font-size: 12.5px; font-style: italic; opacity: 0.75; }
  .m-empty { text-align: center; color: var(--fg-muted); padding: 40px 16px; }

  /* ungoverned rows: lift chip/pill fills off the sunk row background */
  .qw-matrix tbody tr.ungoverned :global(.qw-fchip),
  .qw-matrix tbody tr.ungoverned :global(.qw-window) {
    background: var(--surface-raised);
  }
</style>
