<script>
  /* TokenBox — a secret the API answers once, with a Copy button.

         <TokenBox label="The link — shown once"
                   base="https://console.example/#/invitations/accept"
                   secret="?i=inv.01…&t=mZzG…" />

     `secret` renders in gold after `base`, and the two are copied as one
     string. Say beside it that the value will not be shown again. */

  let { label, base = "", secret = "" } = $props();

  let copied = $state(false);
  let timer;

  async function copy() {
    try {
      await navigator.clipboard.writeText(base + secret);
    } catch {
      return;
    }
    copied = true;
    clearTimeout(timer);
    timer = setTimeout(() => (copied = false), 1600);
  }
</script>

<div class="qw-token-box">
  {#if label}<span class="qw-token-label">{label}</span>{/if}
  <div class="qw-token-value">{base}<span class="secret">{secret}</span></div>
  <div class="qw-token-actions">
    <button type="button" class="qw-token-copy" onclick={copy}>
      <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <rect x="5.5" y="5.5" width="8" height="8" rx="1.3" />
        <path d="M10.5 3.5 H3.8 A1.3 1.3 0 0 0 2.5 4.8 V11.5" />
      </svg>
      Copy link
    </button>
    <span class="qw-token-copied" class:show={copied} aria-live="polite">
      {copied ? "Copied" : ""}
    </span>
  </div>
</div>

<style>
  .qw-token-box {
    display: flex;
    flex-direction: column;
    gap: 10px;
    padding: 16px;
    border: 1px solid light-dark(oklch(0.86 0.09 80), oklch(0.44 0.08 75));
    border-radius: 8px;
    background: light-dark(oklch(0.98 0.02 85), oklch(0.25 0.03 75));
  }
  .qw-token-label {
    font-family: var(--mono);
    font-size: 10px;
    letter-spacing: 0.1em;
    text-transform: uppercase;
    color: var(--gold-deep);
  }
  .qw-token-value {
    font-family: var(--mono);
    font-size: 12px;
    line-height: 1.55;
    color: var(--fg);
    background: var(--surface);
    border: 1px solid var(--rule);
    border-radius: 6px;
    padding: 11px 12px;
    overflow-wrap: anywhere;
    user-select: all;
  }
  .qw-token-value .secret {
    color: var(--gold-deep);
  }
  .qw-token-actions {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .qw-token-copy {
    height: 26px;
    padding: 0 10px;
    display: inline-flex;
    align-items: center;
    gap: 5px;
    border-radius: 6px;
    border: 1px solid var(--rule);
    background: transparent;
    color: var(--fg);
    font-family: var(--grotesk);
    font-size: 12px;
    font-weight: 500;
    cursor: pointer;
  }
  .qw-token-copy:hover {
    background: var(--hover-overlay);
  }
  .qw-token-copy:focus-visible {
    outline: 2px solid var(--gold);
    outline-offset: 2px;
  }
  .qw-token-copy svg {
    width: 12px;
    height: 12px;
  }
  .qw-token-copied {
    font-family: var(--mono);
    font-size: 11px;
    color: var(--pos);
    opacity: 0;
    transition: opacity 0.15s;
  }
  .qw-token-copied.show {
    opacity: 1;
  }
</style>
