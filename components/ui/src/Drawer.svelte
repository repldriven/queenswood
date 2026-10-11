<script>
  /* Drawer — right-side slide-in panel.

     Use for create/edit forms, detail panes, secondary content that
     doesn't deserve its own route. Esc / scrim click / close button
     all call `onClose`. `mono` sets the title in the mono face, for a
     title that is an identifier.

         <Drawer open={drawerOpen} onClose={() => drawerOpen = false}
           kicker="Define" title="New product"
           sub="Drafts are iterable; publishing commits a version.">
           <Field label="Account type"> <Select bind:value={t}>...</Select> </Field>
           ...
           {#snippet footer()}
             <Button variant="primary" size="lg" block>Create product</Button>
           {/snippet}
         </Drawer>
  */

  let {
    open = false,
    onClose,
    kicker,
    title,
    sub,
    children,
    footer,
    width = 420,
    label,
    mono = false,
  } = $props();

  function handleKey(e) {
    if (e.key === "Escape" && open && onClose) onClose();
  }

  $effect(() => {
    document.addEventListener("keydown", handleKey);
    return () => document.removeEventListener("keydown", handleKey);
  });
</script>

<div
  class="drawer-scrim"
  class:open
  onclick={() => onClose?.()}
  aria-hidden="true"
></div>

<aside
  class="drawer"
  class:open
  role="dialog"
  aria-label={label ?? title}
  aria-hidden={!open}
  style:width="{width}px"
>
  <header class="drawer-head">
    <div class="drawer-titlewrap">
      {#if kicker}<span class="drawer-kicker">{kicker}</span>{/if}
      {#if title}<h2 class="drawer-title" class:mono>{title}</h2>{/if}
      {#if sub}<p class="drawer-sub">{sub}</p>{/if}
    </div>
    <button
      class="drawer-close"
      type="button"
      onclick={() => onClose?.()}
      aria-label="Close"
    >
      <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round">
        <path d="M4 4 L12 12" />
        <path d="M12 4 L4 12" />
      </svg>
    </button>
  </header>

  <div class="drawer-body">
    {@render children?.()}
  </div>

  {#if footer}
    <footer class="drawer-foot">
      {@render footer()}
    </footer>
  {/if}
</aside>

<style>
  .drawer-scrim {
    position: fixed;
    inset: 0;
    background: light-dark(rgba(20, 15, 10, 0.22), rgba(0, 0, 0, 0.42));
    backdrop-filter: blur(2px);
    opacity: 0;
    pointer-events: none;
    transition: opacity 0.18s;
    z-index: 40;
  }
  .drawer-scrim.open { opacity: 1; pointer-events: auto; }
  .drawer {
    position: fixed;
    top: 0;
    right: 0;
    height: 100vh;
    max-width: 92vw;
    background: var(--surface-raised);
    border-left: 1px solid var(--rule);
    box-shadow: light-dark(
      -16px 0 48px -16px rgba(20, 15, 10, 0.12),
      -16px 0 48px -16px rgba(0, 0, 0, 0.5)
    );
    transform: translateX(100%);
    transition: transform 0.22s cubic-bezier(0.3, 0, 0.2, 1);
    z-index: 50;
    display: flex;
    flex-direction: column;
  }
  .drawer.open { transform: translateX(0); }
  .drawer-head {
    padding: 22px 28px 18px;
    border-bottom: 1px solid var(--rule-2);
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
  }
  .drawer-titlewrap { display: flex; flex-direction: column; gap: 4px; }
  .drawer-kicker {
    font-family: var(--mono);
    font-size: 10px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--gold-deep);
  }
  .drawer-title {
    font-family: var(--grotesk);
    font-weight: 500;
    font-size: 22px;
    letter-spacing: -0.005em;
    line-height: 1.15;
    margin: 0;
    color: var(--fg);
  }
  .drawer-title.mono {
    font-family: var(--mono);
    font-size: 16px;
    line-height: 1.4;
    overflow-wrap: anywhere;
  }
  .drawer-sub {
    font-size: 13px;
    color: var(--fg-muted);
    margin: 4px 0 0 0;
    max-width: 38ch;
  }
  .drawer-close {
    width: 32px; height: 32px;
    padding: 0;
    border-radius: 6px;
    border: 1px solid var(--rule);
    background: transparent;
    color: var(--fg-2);
    cursor: pointer;
    display: inline-flex;
    align-items: center;
    justify-content: center;
  }
  .drawer-close:hover { background: var(--hover-overlay); color: var(--fg); }
  .drawer-body {
    flex: 1;
    overflow-y: auto;
    padding: 24px 28px;
    display: flex;
    flex-direction: column;
    gap: 18px;
  }
  .drawer-foot {
    padding: 16px 28px 22px;
    border-top: 1px solid var(--rule-2);
    background: var(--surface-raised);
  }
</style>
