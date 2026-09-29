<script>
  /* Full-width band under AppNav saying the installation's providers
     are simulated. Unconditional while every installation the console
     reaches is a sandbox. */
</script>

<div class="sandbox" role="note">
  <div class="inner">
    <span class="tag">Sandbox</span>
    <span>
      <strong>Providers are simulated.</strong> Companies House, payments
      and identity checks run against simulators. No real companies, money
      or people are involved.
    </span>
  </div>
</div>

<style>
  .sandbox {
    background: var(--sandbox-bg);
    color: var(--sandbox-fg);
    border-bottom: 1px solid var(--sandbox-rule);
  }
  .inner {
    max-width: 720px;
    margin: 0 auto;
    padding: 12px 28px;
    display: flex;
    align-items: center;
    gap: 14px;
    font-family: var(--grotesk);
    font-size: 13.5px;
    line-height: 1.45;
  }
  .tag {
    flex: none;
    font-family: var(--mono);
    font-size: 11px;
    font-weight: 500;
    letter-spacing: 0.16em;
    text-transform: uppercase;
    border: 1px solid currentColor;
    border-radius: 999px;
    padding: 3px 10px;
  }
  strong { font-weight: 600; }
</style>
