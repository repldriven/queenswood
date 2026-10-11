<script>
  /* ChangesetTable — the fields an act changed, as the act left them,
     then as they were.

         <ChangesetTable changeset={entry.changeset} />

     `changeset` is `{ before, after }`, either side absent. Each side is
     a table of every changed field: a field that side lacks shows a
     dash, and an object or array its JSON, pretty-printed. A create,
     with no `before`, says so in place of the Before table, and no
     changeset says the act changed no stored fields. */

  import { changedFields } from "./audit.js";

  let { changeset } = $props();

  const fields = $derived(changedFields(changeset));

  const sides = $derived([
    { title: "After", values: changeset?.after, none: "The act left none of these fields." },
    { title: "Before", values: changeset?.before, none: "A create has no before." },
  ]);

  function shown(v) {
    if (v === undefined || v === null) return null;
    if (typeof v === "object") return JSON.stringify(v, null, 2);
    return String(v);
  }
</script>

{#if !changeset}
  <div class="none">This act changed no stored fields.</div>
{:else}
  <div class="qw-changeset">
    {#each sides as side (side.title)}
      <div class="side">
        <h4>{side.title}</h4>
        {#if side.values}
          <div class="table" role="table" aria-label={side.title}>
            {#each fields as field (field)}
              {@const value = shown(side.values[field])}
              <div class="row" role="row">
                <span class="field" role="rowheader">{field}</span>
                <span class="value" class:absent={value === null} class:before={side.title === "Before"} role="cell">{value ?? "—"}</span>
              </div>
            {/each}
          </div>
        {:else}
          <div class="note">{side.none}</div>
        {/if}
      </div>
    {/each}
  </div>
{/if}

<style>
  .qw-changeset {
    display: flex;
    flex-direction: column;
    gap: 16px;
  }
  .side {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }
  h4 {
    margin: 0;
    font-size: 12px;
    font-weight: 500;
    color: var(--fg-2);
  }
  .table {
    border: 1px solid var(--rule-2);
    border-radius: 8px;
    overflow: hidden;
    background: var(--surface-raised);
  }
  .row {
    display: grid;
    grid-template-columns: minmax(90px, 150px) minmax(0, 1fr);
    gap: 14px;
    align-items: baseline;
    padding: 8px 14px;
    border-bottom: 1px solid var(--rule-2);
    font-family: var(--mono);
    font-size: 11.5px;
  }
  .row:last-child {
    border-bottom: none;
  }
  .field {
    color: var(--fg-muted);
    overflow-wrap: anywhere;
  }
  .value {
    color: var(--fg);
    white-space: pre-wrap;
    overflow-wrap: anywhere;
  }
  .value.before {
    color: var(--fg-2);
  }
  .absent {
    color: var(--fg-muted);
    opacity: 0.5;
  }
  .note {
    font-size: 12px;
    color: var(--fg-muted);
  }
  .none {
    padding: 12px 14px;
    border: 1px dashed var(--rule);
    border-radius: 8px;
    font-size: 12.5px;
    color: var(--fg-muted);
  }
</style>
