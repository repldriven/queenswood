<script>
  /* GlType — squared mono chip for a ledger account's accounting family
     (account-class): asset / liability / equity / income / expense.
     Asset and expense are debit-normal; liability, equity and income are
     credit-normal. Low-chroma tones so the column reads as a
     classification rather than a status.

     Same chip family as <Phase>/<GlClass>. Tones come from the
     --gl-<type>-* tokens; an unknown value falls back to a neutral
     tone. */

  let { value = "asset" } = $props();
</script>

<span class="qw-gltype qw-gltype--{value}">{value}</span>

<style>
  .qw-gltype {
    display: inline-flex;
    align-items: center;
    height: 18px;
    padding: 0 7px;
    border-radius: 4px;
    font-family: var(--mono);
    font-size: 10px;
    letter-spacing: 0.03em;
    text-transform: lowercase;
    white-space: nowrap;
    /* default → neutral, so unknown values still render */
    background: var(--surface-sunk);
    color: var(--fg-muted);
  }
  .qw-gltype--asset {
    background: var(--gl-asset-bg);
    color: var(--gl-asset-fg);
  }
  .qw-gltype--liability {
    background: var(--gl-liability-bg);
    color: var(--gl-liability-fg);
  }
  .qw-gltype--equity {
    background: var(--gl-equity-bg);
    color: var(--gl-equity-fg);
  }
  .qw-gltype--income {
    background: var(--gl-income-bg);
    color: var(--gl-income-fg);
  }
  .qw-gltype--expense {
    background: var(--gl-expense-bg);
    color: var(--gl-expense-fg);
  }
</style>
