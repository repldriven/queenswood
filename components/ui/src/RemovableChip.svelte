<script>
  /* RemovableChip — an applied filter, with a × that removes it.

         <RemovableChip onremove={() => drop(kind)}>Invitation accepted</RemovableChip>

     Distinct from Chip, which is pressed and unpressed in place. */

  let { onremove, removeLabel = "Remove", children } = $props();
</script>

<span class="qw-removable-chip">
  {@render children?.()}
  <button type="button" aria-label={removeLabel} onclick={onremove}>
    <svg viewBox="0 0 10 10" aria-hidden="true"><path d="M2 2 L8 8 M8 2 L2 8" /></svg>
  </button>
</span>

<style>
  .qw-removable-chip {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    height: 26px;
    padding: 0 4px 0 10px;
    border-radius: 999px;
    border: 1px solid var(--rule);
    background: var(--surface-sunk);
    font-size: 12px;
    color: var(--fg);
    white-space: nowrap;
  }
  button {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 20px;
    height: 20px;
    padding: 0;
    border: none;
    border-radius: 50%;
    background: none;
    color: var(--fg-muted);
    cursor: pointer;
  }
  button:hover {
    background: var(--hover-overlay);
    color: var(--fg);
  }
  button:focus-visible {
    outline: 2px solid var(--gold);
    outline-offset: 1px;
  }
  svg {
    width: 10px;
    height: 10px;
    stroke: currentColor;
    stroke-width: 1.8;
    stroke-linecap: round;
  }
</style>
