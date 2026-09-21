<script>
  /* SceneCard — one sequential-unlock card in a scenario runner. The
     head (number, title, payoff chip, story, status badge + run button
     + chevron) is always shown; the body — supplied by the page as the
     `body` snippet — reveals when `open`.

     `status` is one of locked | ready | running | done and drives the
     border, the badge, and which run button shows. Clicking the head
     toggles open (except when locked); the run button calls `onRun` and
     never toggles. The page owns expand state and run state. */

  import Button from "./Button.svelte";
  import Badge from "./Badge.svelte";

  let {
    num,
    title,
    story,
    status = "locked",
    payoffLabel = "",
    open = false,
    elId,
    onToggle,
    onRun,
    body,
  } = $props();

  function headClick() {
    if (status === "locked") return;
    onToggle?.();
  }
  function headKey(e) {
    if (status === "locked") return;
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      onToggle?.();
    }
  }
  function run(e) {
    e.stopPropagation();
    onRun?.();
  }
</script>

<article class="scene is-{status}" class:open id={elId}>
  <div
    class="scene-head"
    role="button"
    tabindex={status === "locked" ? -1 : 0}
    aria-expanded={open}
    onclick={headClick}
    onkeydown={headKey}
  >
    <div class="scene-num"><span class="n">{num}</span></div>
    <div class="scene-main">
      <div class="scene-titlerow">
        <span class="scene-title">{title}</span>
        <span class="payoff-chip" title="Shown in {payoffLabel}">
          <svg width="10" height="10" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
            <path d="M1.5 8 C3.5 4.5 6 3 8 3 s4.5 1.5 6.5 5 C12.5 11.5 10 13 8 13 s-4.5-1.5-6.5-5 Z" />
            <circle cx="8" cy="8" r="1.8" />
          </svg>
          {payoffLabel}
        </span>
      </div>
      <p class="scene-story">{story}</p>
    </div>
    <div class="scene-aside">
      <span class="scene-run-min">
        {#if status === "running"}
          <Badge tone="running">running</Badge>
        {:else if status === "done"}
          <Badge tone="published">done</Badge>
        {:else if status === "ready"}
          <Badge tone="scheduled">ready</Badge>
        {:else}
          <Badge tone="archived">
            <svg width="11" height="11" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
              <rect x="3.5" y="7" width="9" height="6.5" rx="1.2" />
              <path d="M5.5 7 V5 a2.5 2.5 0 0 1 5 0 V7" />
            </svg>
            locked
          </Badge>
        {/if}
      </span>
      {#if status === "ready"}
        <Button variant="brand" size="sm" onclick={run}>
          <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M5 3.5 L12.5 8 L5 12.5 Z" /></svg>
          <span>Run scene</span>
        </Button>
      {:else if status === "running"}
        <Button variant="line" size="sm" disabled>
          <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M5 3.5 L12.5 8 L5 12.5 Z" /></svg>
          <span>Running…</span>
        </Button>
      {:else if status === "done"}
        <Button variant="ghost" size="sm" onclick={run}>
          <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M13.2 8 A 5.2 5.2 0 1 1 11.4 4.1" /><path d="M13.4 2.6 V5 H11" /></svg>
          <span>Re-run</span>
        </Button>
      {:else}
        <Button variant="line" size="sm" disabled title="Run the previous scene first">
          <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="3.5" y="7" width="9" height="6.5" rx="1.2" /><path d="M5.5 7 V5 a2.5 2.5 0 0 1 5 0 V7" /></svg>
          <span>Locked</span>
        </Button>
      {/if}
      <svg class="scene-chev" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M6 4 L10 8 L6 12" /></svg>
    </div>
  </div>
  {#if open}
    <div class="scene-body">
      <div class="scene-body-inner">{@render body?.()}</div>
    </div>
  {/if}
</article>

<style>
  .scene {
    background: var(--surface-raised);
    border: 1px solid var(--rule-2);
    border-radius: 10px;
    overflow: hidden;
    transition: border-color 0.18s, opacity 0.18s;
    scroll-margin-top: 80px;
  }
  .scene.is-running { border-color: var(--gold); }
  .scene.is-done {
    border-color: light-dark(oklch(0.84 0.04 145), oklch(0.40 0.05 145));
  }
  .scene.is-locked { opacity: 0.62; }

  .scene-head {
    display: grid;
    grid-template-columns: 56px minmax(0, 1fr) auto;
    gap: 18px;
    align-items: center;
    padding: 18px 20px;
    cursor: pointer;
  }
  .scene.is-locked .scene-head { cursor: default; }
  .scene-head:hover { background: var(--hover-overlay); }
  .scene.is-locked .scene-head:hover { background: transparent; }

  .scene-num {
    width: 56px;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 6px;
  }
  .scene-num .n {
    font-family: var(--mono);
    font-size: 26px;
    font-weight: 500;
    line-height: 1;
    color: var(--fg-muted);
    font-variant-numeric: tabular-nums;
    letter-spacing: -0.02em;
  }
  .scene.is-done .scene-num .n { color: var(--ok); }
  .scene.is-running .scene-num .n,
  .scene.is-ready .scene-num .n { color: var(--gold-deep); }

  .scene-main { min-width: 0; display: flex; flex-direction: column; gap: 5px; }
  .scene-titlerow {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
  }
  .scene-title {
    font-size: 15.5px;
    font-weight: 500;
    color: var(--fg);
    letter-spacing: -0.003em;
  }
  .payoff-chip {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    height: 19px;
    padding: 0 8px;
    border-radius: 4px;
    font-family: var(--mono);
    font-size: 10px;
    letter-spacing: 0.03em;
    text-transform: lowercase;
    background: var(--surface-sunk);
    color: var(--fg-muted);
    border: 1px solid var(--rule);
  }
  .scene-story {
    font-size: 13px;
    color: var(--fg-muted);
    line-height: 1.5;
    max-width: 78ch;
    margin: 0;
  }

  .scene-aside {
    display: flex;
    align-items: center;
    gap: 12px;
    justify-content: flex-end;
  }
  .scene-run-min { display: inline-flex; align-items: center; gap: 6px; }
  .scene-aside :global(.badge svg) { width: 11px; height: 11px; }
  .scene-chev {
    width: 16px;
    height: 16px;
    color: var(--fg-muted);
    transition: transform 0.18s;
    flex: 0 0 auto;
  }
  .scene.open .scene-chev { transform: rotate(90deg); }

  .scene-body {
    border-top: 1px solid var(--rule-2);
    background: var(--surface-sunk);
  }
  .scene-body-inner {
    padding: 20px 22px 22px 78px;
    display: flex;
    flex-direction: column;
    gap: 20px;
  }

  @media (max-width: 1000px) {
    .scene-body-inner { padding-left: 22px; }
  }
  @media (max-width: 720px) {
    .scene-head { grid-template-columns: 40px minmax(0, 1fr); }
    .scene-aside {
      grid-column: 1 / -1;
      justify-content: flex-start;
      padding-left: 58px;
    }
  }
</style>
