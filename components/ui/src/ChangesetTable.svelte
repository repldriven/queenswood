<script>
  /* ChangesetTable — the fields an act changed, each as it was and as
     the act left it.

         <ChangesetTable changeset={entry.changeset} />

     `changeset` is `{ before, after }`, either side absent: a field one
     side lacks shows a dash, an object compact JSON, and a create, with
     no `before`, says so beneath. No changeset says the act changed no
     stored fields. */

  import { changedFields } from "./audit.js";

  let { changeset } = $props();

  const fields = $derived(changedFields(changeset));

  function shown(v) {
    if (v === undefined || v === null) return null;
    return typeof v === "string" ? v : JSON.stringify(v);
  }
</script>

{#if !changeset}
  <div class="none">This act changed no stored fields.</div>
{:else}
  <div class="qw-changeset" role="table" aria-label="Changeset">
    <div class="row head" role="row">
      <span role="columnheader">Field</span>
      <span role="columnheader">Before</span>
      <span role="columnheader">After</span>
    </div>
    {#each fields as field (field)}
      {@const before = shown(changeset.before?.[field])}
      {@const after = shown(changeset.after?.[field])}
      <div class="row" role="row">
        <span class="field" role="cell">{field}</span>
        <span class="before" class:absent={before === null} role="cell">{before ?? "—"}</span>
        <span class="after" class:absent={after === null} role="cell">{after ?? "—"}</span>
      </div>
    {/each}
  </div>
  {#if !changeset.before}
    <div class="note">A create has no before.</div>
  {/if}
{/if}

<style>
  .qw-changeset {
    border: 1px solid var(--rule-2);
    border-radius: 8px;
    overflow: hidden;
    background: var(--surface-raised);
  }
  .row {
    display: grid;
    grid-template-columns: minmax(90px, 130px) minmax(0, 1fr) minmax(0, 1fr);
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
  .row.head {
    padding: 7px 14px;
    background: var(--surface-sunk);
    font-size: 10px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--fg-muted);
  }
  .field {
    color: var(--fg-muted);
    overflow-wrap: anywhere;
  }
  .before {
    color: var(--fg-muted);
    text-decoration: line-through;
    text-decoration-color: light-dark(rgba(160, 50, 30, 0.45), rgba(220, 110, 80, 0.5));
    overflow-wrap: anywhere;
  }
  .after {
    color: var(--fg);
    overflow-wrap: anywhere;
  }
  .absent {
    color: var(--fg-muted);
    text-decoration: none;
    opacity: 0.5;
  }
  .note {
    margin-top: 8px;
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
