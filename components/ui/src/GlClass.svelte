<script>
  /* GlClass — squared mono chip for a ledger account's role in the
     chart hierarchy (account-type):

       control  a sub-ledger rollup (customer cash-accounts / own funds).
                Emphasised — the crown/gold accent — so the rollup
                accounts stand out from the leaves at a glance.
       summary  a structural parent that rolls up children, no legs.
       detail   a posting leaf.

     Same chip family as <Phase>/<GlType>: a ledger dimension, not a
     record status (so a squared mono chip, not a rounded <Badge>).
     Tones come from the --gl-<class>-* tokens; an unknown value falls
     back to the detail tone. */

  let { value = "detail" } = $props();
</script>

<span class="qw-glclass qw-glclass--{value}">{value}</span>

<style>
  .qw-glclass {
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
    /* default → detail tone, so unknown values still render */
    background: var(--gl-detail-bg);
    color: var(--gl-detail-fg);
  }
  .qw-glclass--control {
    background: var(--gl-control-bg);
    color: var(--gl-control-fg);
    font-weight: 600;
  }
  .qw-glclass--summary {
    background: var(--gl-summary-bg);
    color: var(--gl-summary-fg);
  }
  .qw-glclass--detail {
    background: var(--gl-detail-bg);
    color: var(--gl-detail-fg);
  }
</style>
