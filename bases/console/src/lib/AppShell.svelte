<script>
  /* Authenticated chrome: AppNav across the top, Sidenav down the
     left, page content on the right. Every authenticated route in
     console renders inside this shell.

     The shell uses `class="app-shell"` because ui's Sidenav
     looks for that exact class to disable the grid-columns transition
     while the user is drag-resizing the rail — without it the layout
     lags behind the pointer. The `--sidenav-w` custom property is
     published on <html> by the Sidenav itself; we just consume it. */

  import {
    AppNav,
    Sidenav,
    SidenavGroup,
    SidenavItem,
    ToastHost,
  } from "@queenswood/ui";
  import { router } from "svelte-spa-router";

  let { user, onSignOut, children } = $props();

  // Highlight the active rail item from the live route. Each entry
  // is treated as a prefix so /products/foo still highlights Products
  // when we add nested routes later. `router` is svelte-spa-router
  // v5's reactive state object — `router.location` updates on every
  // hash change.
  function isCurrent(prefix) {
    const loc = router.location;
    return loc === prefix || loc.startsWith(prefix + "/");
  }
</script>

<AppNav {user} {onSignOut} />

<div class="app-shell">
  <Sidenav>
    <SidenavGroup title="Manage">
      <SidenavItem href="#/products" title="Products" current={isCurrent("/products")}>
        {#snippet icon()}
          <svg viewBox="0 0 16 16" aria-hidden="true">
            <path d="M2 4 L14 4" />
            <path d="M2 8 L14 8" />
            <path d="M2 12 L14 12" />
          </svg>
        {/snippet}
        Products
      </SidenavItem>
      <SidenavItem href="#/parties" title="Parties" current={isCurrent("/parties")}>
        {#snippet icon()}
          <svg viewBox="0 0 16 16" aria-hidden="true">
            <circle cx="8" cy="6" r="2.6" />
            <path d="M3 14c0.8-3 2.6-4.6 5-4.6s4.2 1.6 5 4.6" />
          </svg>
        {/snippet}
        Parties
      </SidenavItem>
      <SidenavItem href="#/ledger" title="Ledger Accounts" current={isCurrent("/ledger")}>
        {#snippet icon()}
          <svg viewBox="0 0 16 16" aria-hidden="true">
            <path d="M3 2.5 H11 A1.5 1.5 0 0 1 12.5 4 V13.5 H4.5 A1.5 1.5 0 0 1 3 12 Z" />
            <path d="M3 12 A1.5 1.5 0 0 1 4.5 10.5 H12.5" />
            <path d="M5.5 5.5 H10" />
          </svg>
        {/snippet}
        Ledger
      </SidenavItem>
      <SidenavItem href="#/accounts" title="Accounts" current={isCurrent("/accounts")}>
        {#snippet icon()}
          <svg viewBox="0 0 16 16" aria-hidden="true">
            <rect x="2.5" y="3.5" width="11" height="9" rx="1" />
            <path d="M2.5 7.5 L13.5 7.5" />
            <circle cx="11" cy="10" r="0.8" />
          </svg>
        {/snippet}
        Accounts
      </SidenavItem>
      <SidenavItem href="#/migrations" title="Migrations" current={isCurrent("/migrations")}>
        {#snippet icon()}
          <svg viewBox="0 0 16 16" aria-hidden="true">
            <path d="M2.5 5.5 H12 M9.5 3 L12 5.5 L9.5 8" />
            <path d="M13.5 10.5 H4 M6.5 8 L4 10.5 L6.5 13" />
          </svg>
        {/snippet}
        Migrations
      </SidenavItem>
    </SidenavGroup>
    <SidenavGroup title="Operations">
      <SidenavItem href="#/jobs" title="Jobs" current={isCurrent("/jobs")}>
        {#snippet icon()}
          <svg viewBox="0 0 16 16" aria-hidden="true">
            <circle cx="8" cy="8" r="5.5" />
            <path d="M8 5.2 V8 L10 9.4" />
          </svg>
        {/snippet}
        Jobs
      </SidenavItem>
    </SidenavGroup>
    <SidenavGroup title="Sandbox">
      <SidenavItem href="#/scenarios" title="Scenarios" current={isCurrent("/scenarios")}>
        {#snippet icon()}
          <svg viewBox="0 0 16 16" aria-hidden="true">
            <path d="M5 3.5 L12.5 8 L5 12.5 Z" />
          </svg>
        {/snippet}
        Scenarios
      </SidenavItem>
    </SidenavGroup>
    <SidenavGroup title="Organisation">
      <SidenavItem href="#/people" title="People" current={isCurrent("/people")}>
        {#snippet icon()}
          <svg viewBox="0 0 16 16" aria-hidden="true">
            <circle cx="6" cy="6" r="2.4" />
            <path d="M1.8 14c0.7-2.7 2.3-4.2 4.2-4.2S9.5 11.3 10.2 14" />
            <path d="M10.4 4.1a2.4 2.4 0 0 1 0 4.5" />
            <path d="M11.6 9.9c1.4 0.4 2.3 1.8 2.7 4.1" />
          </svg>
        {/snippet}
        People
      </SidenavItem>
      <SidenavItem href="#/policies" title="Policies" current={isCurrent("/policies")}>
        {#snippet icon()}
          <svg viewBox="0 0 16 16" aria-hidden="true">
            <path d="M8 2 L13 4 V8 C13 11 10.5 13 8 14 C5.5 13 3 11 3 8 V4 Z" />
            <path d="M5.8 8 L7.4 9.6 L10.4 6.6" />
          </svg>
        {/snippet}
        Policies
      </SidenavItem>
    </SidenavGroup>
  </Sidenav>

  <main class="page">
    {@render children?.()}
  </main>
</div>

<ToastHost />

<style>
  .app-shell {
    display: grid;
    grid-template-columns: var(--sidenav-w, 220px) minmax(0, 1fr);
    min-height: calc(100vh - 57px);
    transition: grid-template-columns 0.2s ease;
    background: var(--surface);
    color: var(--fg);
    font-family: var(--grotesk);
  }
  .page {
    padding: 28px 36px 80px;
    display: flex;
    flex-direction: column;
    gap: 24px;
    min-width: 0;
  }
</style>
