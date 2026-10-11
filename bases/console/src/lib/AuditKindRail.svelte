<script>
  /* The audit log's filter rail: the kinds of performer as pressable
     chips, then every kind of act as a searchable list of checkboxes,
     grouped by the console section it belongs to. A group's checkbox
     selects or clears all its kinds; nothing selected lists every kind.

     The page owns the selection: `kinds` and `performers` come in, and
     `onKinds` / `onPerformers` hand back the next selection, in rail
     order. */

  import { untrack } from "svelte";
  import {
    Chip,
    Checkbox,
    AUDIT_GROUPS,
    AUDIT_KINDS,
    AUDIT_GROUP_OF,
    PERFORMER_KINDS,
    auditKindLabel,
  } from "@queenswood/ui";

  let { kinds, performers, onKinds, onPerformers, onClear } = $props();

  let query = $state("");
  // Groups holding a selected kind start open.
  let open = $state(untrack(() => new Set(kinds.map((k) => AUDIT_GROUP_OF[k]))));

  const selected = $derived(new Set(kinds));

  export function reveal(kind) {
    open = new Set([...open, AUDIT_GROUP_OF[kind]]);
  }

  function groupState(group) {
    const n = group.kinds.filter((k) => selected.has(k)).length;
    return n === 0 ? false : n === group.kinds.length ? true : "mixed";
  }

  function setKinds(changed, on) {
    const next = new Set(selected);
    for (const k of changed) on ? next.add(k) : next.delete(k);
    onKinds(AUDIT_KINDS.filter((k) => next.has(k)));
  }

  function togglePerformer(kind) {
    const next = new Set(performers);
    next.has(kind) ? next.delete(kind) : next.add(kind);
    onPerformers(PERFORMER_KINDS.map((p) => p.kind).filter((p) => next.has(p)));
  }

  function toggleOpen(id) {
    const next = new Set(open);
    next.has(id) ? next.delete(id) : next.add(id);
    open = next;
  }

  // Each group with the kinds the search matches: a group whose label
  // matches keeps all its kinds.
  const shown = $derived.by(() => {
    const q = query.trim().toLowerCase();
    if (!q) return AUDIT_GROUPS.map((g) => ({ ...g, matches: g.kinds }));
    return AUDIT_GROUPS.map((g) => ({
      ...g,
      matches: g.label.toLowerCase().includes(q)
        ? g.kinds
        : g.kinds.filter(
            (k) =>
              k.includes(q.replace(/\s+/g, "-")) ||
              auditKindLabel(k).toLowerCase().includes(q),
          ),
    })).filter((g) => g.matches.length > 0);
  });
</script>

<aside class="rail" aria-label="Filter the audit log">
  <div class="sec">
    <span class="label">Performed by</span>
    <div class="performers">
      {#each PERFORMER_KINDS as p (p.kind)}
        <Chip pressed={performers.includes(p.kind)} onclick={() => togglePerformer(p.kind)}>
          {p.label}
        </Chip>
      {/each}
    </div>
  </div>

  <div class="head">
    <div class="top">
      <span class="label">
        {#if kinds.length}
          <b>{kinds.length}</b> of {AUDIT_KINDS.length} kinds
        {:else}
          Kind of act · all {AUDIT_KINDS.length}
        {/if}
      </span>
      <button
        type="button"
        class="clear"
        disabled={!kinds.length && !performers.length}
        onclick={onClear}
      >Clear</button>
    </div>
    <label class="search">
      <svg viewBox="0 0 16 16" aria-hidden="true"><circle cx="7" cy="7" r="4.5" /><path d="M10.5 10.5 L13.5 13.5" /></svg>
      <input type="search" placeholder="Find a kind" autocomplete="off" bind:value={query} />
    </label>
  </div>

  <div class="body">
    {#each shown as group (group.id)}
      {@const isOpen = query.trim() !== "" || open.has(group.id)}
      <div class="group">
        <div class="group-row">
          <Checkbox checked={groupState(group)} onclick={() => setKinds(group.kinds, groupState(group) !== true)}>
            <span class="group-label">{group.label}</span>
            {#snippet aside()}
              {group.kinds.filter((k) => selected.has(k)).length}/{group.kinds.length}
            {/snippet}
          </Checkbox>
          <button
            type="button"
            class="chev"
            class:open={isOpen}
            aria-label="{isOpen ? 'Collapse' : 'Expand'} {group.label}"
            aria-expanded={isOpen}
            onclick={() => toggleOpen(group.id)}
          >
            <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M6 4 L10 8 L6 12" /></svg>
          </button>
        </div>
        {#if isOpen}
          <div class="kinds">
            {#each group.matches as kind (kind)}
              <Checkbox checked={selected.has(kind)} title={kind} onclick={() => setKinds([kind], !selected.has(kind))}>
                {auditKindLabel(kind)}
              </Checkbox>
            {/each}
          </div>
        {/if}
      </div>
    {:else}
      <div class="none">No kind matches “{query}”.</div>
    {/each}
  </div>

  <div class="foot">With nothing selected the log lists every kind.</div>
</aside>

<style>
  .rail {
    position: sticky;
    top: 77px;
    max-height: calc(100vh - 97px);
    display: flex;
    flex-direction: column;
    background: var(--surface-raised);
    border: 1px solid var(--rule-2);
    border-radius: 8px;
    overflow: hidden;
  }
  .sec {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding: 12px 12px 10px;
    border-bottom: 1px solid var(--rule-2);
  }
  .performers {
    display: flex;
    flex-wrap: wrap;
    gap: 5px;
  }
  .head {
    display: flex;
    flex-direction: column;
    gap: 11px;
    padding: 12px 12px 10px;
    border-bottom: 1px solid var(--rule-2);
  }
  .top {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
  }
  .label {
    font-family: var(--mono);
    font-size: 10px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--fg-muted);
    white-space: nowrap;
  }
  .label b {
    color: var(--fg);
    font-weight: 500;
  }
  .clear {
    padding: 2px 4px;
    border: none;
    border-radius: 4px;
    background: none;
    font-family: var(--mono);
    font-size: 11px;
    color: light-dark(var(--pine-3), var(--pine-5));
    cursor: pointer;
  }
  .clear:hover:not(:disabled) {
    background: var(--hover-overlay);
    color: var(--fg);
  }
  .clear:disabled {
    color: var(--fg-muted);
    opacity: 0.5;
    cursor: default;
  }
  .search {
    position: relative;
  }
  .search svg {
    position: absolute;
    left: 10px;
    top: 50%;
    transform: translateY(-50%);
    width: 14px;
    height: 14px;
    fill: none;
    stroke: currentColor;
    stroke-width: 1.6;
    stroke-linecap: round;
    color: var(--fg-muted);
    pointer-events: none;
  }
  .search input {
    width: 100%;
    height: 32px;
    padding: 0 10px 0 31px;
    border: 1px solid var(--rule);
    border-radius: 6px;
    background: var(--surface);
    color: var(--fg);
    font: inherit;
    font-size: 13px;
  }
  .search input::placeholder {
    color: var(--fg-muted);
  }
  .search input:focus {
    outline: 2px solid var(--gold);
    outline-offset: -1px;
    border-color: var(--gold);
  }
  .body {
    display: flex;
    flex-direction: column;
    gap: 1px;
    padding: 6px;
    overflow-y: auto;
  }
  .group-row {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    align-items: center;
    border-radius: 6px;
  }
  .group-row:hover {
    background: var(--hover-overlay);
  }
  .group-label {
    font-weight: 500;
    color: var(--fg);
  }
  .chev {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 28px;
    height: 28px;
    margin-right: 2px;
    border: none;
    border-radius: 5px;
    background: none;
    color: var(--fg-muted);
    cursor: pointer;
  }
  .chev:hover {
    background: var(--hover-overlay);
    color: var(--fg);
  }
  .chev svg {
    width: 13px;
    height: 13px;
    fill: none;
    stroke: currentColor;
    stroke-width: 1.7;
    stroke-linecap: round;
    stroke-linejoin: round;
    transition: transform 0.14s;
  }
  .chev.open svg {
    transform: rotate(90deg);
  }
  .kinds {
    display: flex;
    flex-direction: column;
    padding: 0 0 4px 22px;
  }
  .kinds :global(.qw-checkbox) {
    padding: 4px 8px;
    font-size: 12.5px;
  }
  .kinds :global(.qw-checkbox:hover) {
    background: var(--hover-overlay);
  }
  .none {
    padding: 18px 12px;
    font-size: 12.5px;
    color: var(--fg-muted);
    text-align: center;
  }
  .foot {
    padding: 10px 14px;
    border-top: 1px solid var(--rule-2);
    background: var(--surface-sunk);
    font-size: 11.5px;
    line-height: 1.45;
    color: var(--fg-muted);
  }
  @media (max-width: 980px) {
    .rail {
      position: static;
      max-height: 420px;
    }
  }
</style>
