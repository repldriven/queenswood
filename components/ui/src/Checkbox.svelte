<script>
  /* Checkbox — a labelled box, ticked, empty or mixed.

         <Checkbox checked={on} onclick={toggle}>Invitation accepted</Checkbox>
         <Checkbox checked="mixed" onclick={selectAll}>People</Checkbox>

     `checked` is true, false or "mixed", published as `aria-checked`;
     mixed shows a dash, for a group whose items are partly ticked. The
     label sits beside the box, and anything in `aside` at the far end. */

  let { checked = false, title, onclick, children, aside } = $props();
</script>

<button
  type="button"
  class="qw-checkbox"
  role="checkbox"
  aria-checked={checked === "mixed" ? "mixed" : checked ? "true" : "false"}
  {title}
  {onclick}
>
  <span class="box" aria-hidden="true">
    {#if checked === "mixed"}
      <svg viewBox="0 0 10 10"><path d="M2.5 5 H7.5" /></svg>
    {:else if checked}
      <svg viewBox="0 0 10 10"><path d="M2 5.2 L4.2 7.3 L8 3" /></svg>
    {/if}
  </span>
  <span class="label">{@render children?.()}</span>
  {#if aside}<span class="aside">{@render aside()}</span>{/if}
</button>

<style>
  .qw-checkbox {
    display: flex;
    align-items: center;
    gap: 9px;
    width: 100%;
    min-width: 0;
    padding: 6px 8px;
    border: none;
    border-radius: 6px;
    background: none;
    text-align: left;
    font: inherit;
    font-size: 13px;
    color: var(--fg-2);
    cursor: pointer;
  }
  .qw-checkbox:focus-visible {
    outline: 2px solid var(--gold);
    outline-offset: -2px;
  }
  .box {
    flex: 0 0 auto;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 15px;
    height: 15px;
    border-radius: 4px;
    border: 1.5px solid light-dark(rgba(20, 15, 10, 0.28), rgba(244, 241, 234, 0.3));
    background: var(--surface);
    transition: background 0.1s, border-color 0.1s;
  }
  .qw-checkbox[aria-checked="true"] .box,
  .qw-checkbox[aria-checked="mixed"] .box {
    background: var(--primary);
    border-color: var(--primary);
  }
  .box svg {
    width: 9px;
    height: 9px;
    fill: none;
    stroke: #fbf9f4;
    stroke-width: 2.2;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
  .label {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .aside {
    margin-left: auto;
    padding-left: 6px;
    font-family: var(--mono);
    font-size: 10.5px;
    font-weight: 400;
    color: var(--fg-muted);
  }
</style>
