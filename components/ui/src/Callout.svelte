<script>
  /* Callout — a boxed notice with an icon, an optional bold title, a
     body and an optional mono type line.

       danger  rust  — an API refusal; put the problem's type in `type`
       warn    amber — a consequence to read before confirming
       info    plain — context

         <Callout tone="danger" title="Make someone else an owner first"
                  type="409 · :membership/last-owner">
           Ada is this organisation's only owner.
         </Callout>

     Every refusal the API answers takes this shape. */

  let { tone = "info", title, type, children } = $props();
</script>

<div class="qw-callout {tone}" role={tone === "danger" ? "alert" : undefined}>
  {#if tone === "info"}
    <svg viewBox="0 0 16 16" aria-hidden="true">
      <circle cx="8" cy="8" r="6" />
      <path d="M8 7.2 V11.2" />
      <path d="M8 4.9 V5" />
    </svg>
  {:else}
    <svg viewBox="0 0 16 16" aria-hidden="true">
      <path d="M8 2.5 L14.5 13.5 H1.5 Z" />
      <path d="M8 6.5 V9.6" />
      <path d="M8 11.6 V11.7" />
    </svg>
  {/if}
  <div class="qw-callout-body">
    {#if title}<span class="qw-callout-title">{title}</span>{/if}
    {@render children?.()}
    {#if type}<span class="qw-callout-type">{type}</span>{/if}
  </div>
</div>

<style>
  .qw-callout {
    display: flex;
    gap: 11px;
    padding: 13px 15px;
    border-radius: 7px;
    border: 1px solid var(--rule);
    background: var(--surface-sunk);
    font-size: 13px;
    line-height: 1.5;
    color: var(--fg-2);
    text-wrap: pretty;
  }
  .qw-callout svg {
    width: 15px;
    height: 15px;
    flex: 0 0 auto;
    margin-top: 2px;
    stroke: currentColor;
    fill: none;
    stroke-width: 1.6;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
  .qw-callout-body {
    min-width: 0;
  }
  .qw-callout-title {
    display: block;
    font-weight: 500;
    color: var(--fg);
    margin-bottom: 3px;
  }
  .qw-callout-type {
    display: block;
    font-family: var(--mono);
    font-size: 11px;
    margin-top: 7px;
    opacity: 0.75;
  }
  .qw-callout.danger {
    border-color: light-dark(oklch(0.84 0.08 30), oklch(0.42 0.09 30));
    background: light-dark(oklch(0.97 0.02 30), oklch(0.26 0.04 30));
    color: light-dark(oklch(0.38 0.10 30), oklch(0.88 0.07 30));
  }
  .qw-callout.warn {
    border-color: light-dark(oklch(0.86 0.09 80), oklch(0.44 0.08 75));
    background: light-dark(oklch(0.97 0.035 85), oklch(0.27 0.04 75));
    color: light-dark(oklch(0.40 0.09 65), oklch(0.90 0.07 82));
  }
  .qw-callout.danger .qw-callout-title,
  .qw-callout.warn .qw-callout-title {
    color: inherit;
  }
</style>
