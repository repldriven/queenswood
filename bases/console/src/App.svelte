<script>
  import Router, { push } from "svelte-spa-router";
  import { wrap } from "svelte-spa-router/wrap";
  import { ensure_session, sign_in, sign_out, token_claims } from "./lib/auth.mjs";
  import { get_me, set_bank_id } from "./lib/api.mjs";
  import Landing from "./lib/Landing.svelte";
  import SignInPage from "./lib/SignInPage.svelte";
  import Onboarding from "./lib/Onboarding.svelte";
  import AppShell from "./lib/AppShell.svelte";
  import Products from "./lib/Products.svelte";
  import Accounts from "./lib/Accounts.svelte";
  import Migrations from "./lib/Migrations.svelte";
  import Parties from "./lib/Parties.svelte";
  import People from "./lib/People.svelte";
  import LedgerAccounts from "./lib/LedgerAccounts.svelte";
  import Jobs from "./lib/Jobs.svelte";
  import Policies from "./lib/Policies.svelte";
  import Scenarios from "./lib/Scenarios.svelte";

  // Unauthenticated surfaces are URL-routed so /#/sign-in is shareable
  // and the marketing landing has a stable home.
  const unauthRoutes = {
    "/": Landing,
    "/sign-in": wrap({ component: SignInPage, props: { onSignIn: sign_in } }),
    "*": Landing,
  };

  // Authenticated routes live inside the AppShell. Products is the
  // default landing and the catch-all.
  let authRoutes = $state({});

  function buildAuthRoutes() {
    // Kicker is the org name when /v1/me has surfaced it. If absent
    // (older bank-api that hasn't been restarted yet), pass undefined
    // — PageHeader hides empty kickers cleanly.
    const kicker = memberships?.[0]?.["bank-name"];
    authRoutes = {
      "/products": wrap({
        component: Products,
        props: { user, memberships },
      }),
      "/parties": wrap({
        component: Parties,
        props: { user, memberships },
      }),
      "/ledger": wrap({
        component: LedgerAccounts,
        props: { user, memberships },
      }),
      "/accounts": wrap({
        component: Accounts,
        props: { user, memberships },
      }),
      "/migrations": wrap({
        component: Migrations,
        props: { user, memberships },
      }),
      "/jobs": wrap({
        component: Jobs,
        props: { user, memberships },
      }),
      "/scenarios": wrap({
        component: Scenarios,
        props: { user, memberships },
      }),
      "/people": wrap({
        component: People,
        props: { user, memberships, onAccessChanged: refresh_me },
      }),
      "/policies": wrap({
        component: Policies,
        props: { user, memberships },
      }),
      // Catch-all: render Products. Anyone landing on /#/ or a bad
      // path sees the default surface, matching what onboarding push.
      "*": wrap({
        component: Products,
        props: { user, memberships },
      }),
    };
  }

  // Three end states (sign-in / onboarding / app) plus a "loading"
  // transient while Keycloak runs its silent SSO check and we hit
  // /v1/me. The state name drives which surface renders.
  let stage = $state("loading");
  let user = $state(null);
  let memberships = $state([]);

  $effect(() => {
    bootstrap();
  });

  async function bootstrap() {
    const session = await ensure_session();
    if (!session.authenticated) {
      stage = "signin";
      return;
    }
    await refresh_me();
  }

  async function refresh_me() {
    const { status, body } = await get_me();
    if (status !== 200) {
      // Unexpected status (5xx, 401 after refresh) — safer to send
      // the user back to sign-in than to render stale state.
      stage = "signin";
      return;
    }
    user = body.user;
    memberships = body.memberships ?? [];
    set_bank_id(memberships[0]?.["bank-id"]);
    if (memberships.length === 0) {
      stage = "onboarding";
    } else {
      buildAuthRoutes();
      stage = "app";
      // Default to /products if the user arrived on the bare app or
      // an unauth path. Push only when nothing meaningful is set.
      if (!location.hash || location.hash === "#" || location.hash === "#/") {
        push("/products");
      }
    }
  }

  function handleOnboardComplete(payload) {
    user = payload.user;
    memberships = [payload.membership];
    set_bank_id(payload.membership?.["bank-id"]);
    buildAuthRoutes();
    stage = "app";
    push("/products");
  }

  function defaultOrgName() {
    const claims = token_claims();
    return claims?.name ? `${claims.name}'s Organization` : "";
  }
</script>

{#if stage === "loading"}
  <div class="splash">Loading…</div>
{:else if stage === "signin"}
  <Router routes={unauthRoutes} />
{:else if stage === "onboarding"}
  <Onboarding
    defaultName={defaultOrgName()}
    onComplete={handleOnboardComplete}
    onSignOut={sign_out}
  />
{:else if stage === "app"}
  <AppShell {user} onSignOut={sign_out}>
    <Router routes={authRoutes} />
  </AppShell>
{/if}

<style>
  .splash {
    display: flex;
    align-items: center;
    justify-content: center;
    height: 100vh;
    color: var(--fg-muted);
    font-family: var(--grotesk);
    background: var(--surface);
  }
</style>
