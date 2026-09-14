<script>
  /* Tabs — a tablist of buttons, the selected one underlined in pine.
     Each tab may carry a mono count or qualifier.

         <Tabs label="People" bind:value={tab}
               tabs={[{ id: "members", label: "Members", count: 5 },
                      { id: "history", label: "History" }]} />

     Pair each panel with `id="pane-{id}"`, `role="tabpanel"` and
     `aria-labelledby="tab-{id}"`; the tab publishes the matching
     `aria-controls`. Left and right arrows move between tabs. */

  let { tabs = [], value = $bindable(), label } = $props();

  function select(id) {
    value = id;
  }

  function onkeydown(e, i) {
    if (e.key !== "ArrowRight" && e.key !== "ArrowLeft") return;
    const step = e.key === "ArrowRight" ? 1 : -1;
    const next = tabs[(i + step + tabs.length) % tabs.length];
    select(next.id);
    document.getElementById(`tab-${next.id}`)?.focus();
  }
</script>

<div class="qw-tabs" role="tablist" aria-label={label}>
  {#each tabs as t, i (t.id)}
    <button
      type="button"
      class="qw-tab"
      role="tab"
      id="tab-{t.id}"
      aria-selected={value === t.id}
      aria-controls="pane-{t.id}"
      tabindex={value === t.id ? 0 : -1}
      onclick={() => select(t.id)}
      onkeydown={(e) => onkeydown(e, i)}
    >
      {t.label}
      {#if t.count !== undefined && t.count !== null && t.count !== ""}
        <span class="qw-tab-count">{t.count}</span>
      {/if}
    </button>
  {/each}
</div>

<style>
  .qw-tabs {
    display: flex;
    align-items: stretch;
    gap: 2px;
    border-bottom: 1px solid var(--rule);
  }
  .qw-tab {
    position: relative;
    display: inline-flex;
    align-items: center;
    gap: 8px;
    height: 38px;
    padding: 0 14px;
    border: none;
    background: transparent;
    color: var(--fg-muted);
    font: inherit;
    font-family: var(--grotesk);
    font-size: 13.5px;
    font-weight: 500;
    cursor: pointer;
    border-radius: 6px 6px 0 0;
  }
  .qw-tab:hover {
    color: var(--fg);
    background: var(--hover-overlay);
  }
  .qw-tab[aria-selected="true"] {
    color: var(--fg);
  }
  .qw-tab[aria-selected="true"]::after {
    content: "";
    position: absolute;
    left: 8px;
    right: 8px;
    bottom: -1px;
    height: 2px;
    background: var(--pine-4);
    border-radius: 2px 2px 0 0;
  }
  .qw-tab:focus-visible {
    outline: 2px solid var(--gold);
    outline-offset: -2px;
  }
  .qw-tab-count {
    font-family: var(--mono);
    font-size: 11px;
    font-weight: 400;
    color: var(--fg-muted);
  }
</style>
