<script>
  /* Tag — a small read-only marker beside a name.

       you       pine   — the signed-in person's own row
       founder   gold   — the member who created the organisation
       operator  gold   — a change made by a Queenswood operator

         <Tag tone="you">you</Tag>

     Distinct from Chip, which is a pressable filter. */

  let { tone = "neutral", children } = $props();
</script>

<span class="qw-tag {tone}">{@render children?.()}</span>

<style>
  .qw-tag {
    display: inline-flex;
    align-items: center;
    height: 18px;
    padding: 0 7px;
    border-radius: 4px;
    font-family: var(--mono);
    font-size: 10px;
    font-weight: 400;
    letter-spacing: 0.04em;
    color: var(--fg-muted);
    background: var(--surface-sunk);
    border: 1px solid var(--rule-2);
    white-space: nowrap;
  }
  .qw-tag.you {
    color: var(--pine-3);
    border-color: light-dark(oklch(0.86 0.05 145), oklch(0.36 0.055 145));
  }
  .qw-tag.founder {
    color: var(--gold-deep);
    border-color: light-dark(oklch(0.88 0.07 78), oklch(0.40 0.07 72));
  }
  .qw-tag.operator {
    color: var(--gold-deep);
    background: light-dark(oklch(0.96 0.03 82), oklch(0.28 0.045 75));
    border-color: light-dark(oklch(0.88 0.07 78), oklch(0.40 0.07 72));
  }
</style>
