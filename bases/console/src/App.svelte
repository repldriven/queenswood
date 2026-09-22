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
  import Bank from "./lib/Bank.svelte";
  import AcceptInvitation from "./lib/AcceptInvitation.svelte";
  import {
    capture_invitation_link,
    clear_invitation_link,
  } from "./lib/invitation-link.mjs";

  // Unauthenticated surfaces are URL-routed so /#/sign-in is shareable
  // and the marketing landing has a stable home.
  const unauthRoutes = {
    "/": Landing,
    "/sign-in": wrap({ component: SignInPage, props: { onSignIn: sign_in } }),
    // An emailed invitation link asks for sign-in first, then opens.
    "/invitations/:id": wrap({
      component: SignInPage,
      props: { onSignIn: sign_in },
    }),
    "*": Landing,
  };

  // The bank the console acts on. A person may belong to several — a
  // fresh sandbox bank leaves the old one standing — and every page
  // reads `memberships[0]` as its bank, so the list is kept with the
  // current bank first: the one this browser last chose, where it is
  // still held, else the newest membership.
  const BANK_KEY = "queenswood.console.bank";
  const rememberedBank = () => {
    try {
      return localStorage.getItem(BANK_KEY);
    } catch {
      return null;
    }
  };
  const rememberBank = (id) => {
    try {
      localStorage.setItem(BANK_KEY, id);
    } catch {}
  };
  const when = (iso) => (iso ? new Date(iso).getTime() : 0);
  function currentFirst(list, bankId) {
    const sorted = [...list].sort((a, b) => when(b["created-at"]) - when(a["created-at"]));
    const i = sorted.findIndex((m) => m["bank-id"] === bankId);
    if (i > 0) sorted.unshift(...sorted.splice(i, 1));
    return sorted;
  }

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
      "/bank": wrap({
        component: Bank,
        props: { user, memberships, onSwitch: switchBank, onFreshBank: startFreshBank },
      }),
      // Catch-all: render Products. Anyone landing on /#/ or a bad
      // path sees the default surface, matching what onboarding push.
      "*": wrap({
        component: Products,
        props: { user, memberships },
      }),
    };
  }

  // Four end states (sign-in / invitation / onboarding / app) plus a
  // "loading" transient while Keycloak runs its silent SSO check and we
  // hit /v1/me. The state name drives which surface renders. An emailed
  // invitation link comes before onboarding and the app, whatever the
  // person already belongs to. "fresh-bank" is onboarding again from
  // inside the app: the Bank page's danger zone, which can be backed
  // out of.
  let stage = $state("loading");
  let user = $state(null);
  let memberships = $state([]);
  let invitationLink = $state(null);

  $effect(() => {
    bootstrap();
  });

  async function bootstrap() {
    invitationLink = capture_invitation_link();
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
    memberships = currentFirst(body.memberships ?? [], rememberedBank());
    set_bank_id(memberships[0]?.["bank-id"]);
    if (invitationLink) {
      stage = "invitation";
    } else if (memberships.length === 0) {
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

  async function handleInvitationDone() {
    clear_invitation_link();
    invitationLink = null;
    history.replaceState(null, "", "#/");
    await refresh_me();
  }

  // Lands on the bank just created, first sign-in and fresh bank
  // alike. The onboarding answer carries the bare membership, so the
  // bank's name comes from the bank beside it.
  function handleOnboardComplete(payload) {
    user = payload.user;
    const bankId = payload.membership?.["bank-id"];
    const joined = { ...payload.membership, "bank-name": payload.bank?.name };
    const others = memberships.filter((m) => m["bank-id"] !== bankId);
    rememberBank(bankId);
    memberships = currentFirst([joined, ...others], bankId);
    set_bank_id(bankId);
    buildAuthRoutes();
    stage = "app";
    push("/products");
  }

  function switchBank(bankId) {
    if (!memberships.some((m) => m["bank-id"] === bankId)) return;
    rememberBank(bankId);
    memberships = currentFirst(memberships, bankId);
    set_bank_id(bankId);
    buildAuthRoutes();
    push("/products");
  }

  function startFreshBank() {
    stage = "fresh-bank";
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
{:else if stage === "invitation"}
  <AcceptInvitation
    invitationId={invitationLink.invitationId}
    token={invitationLink.token}
    {user}
    onDone={handleInvitationDone}
    onSignOut={sign_out}
  />
{:else if stage === "onboarding"}
  <Onboarding
    defaultName={defaultOrgName()}
    onComplete={handleOnboardComplete}
    onSignOut={sign_out}
  />
{:else if stage === "fresh-bank"}
  <Onboarding fresh onComplete={handleOnboardComplete} onCancel={() => (stage = "app")} onSignOut={sign_out} />
{:else if stage === "app"}
  <AppShell {user} onSignOut={sign_out}>
    <!-- The router takes its routes at mount, so a bank switch remounts
         it: every page then reads the new bank rather than the props it
         was mounted with. -->
    {#key memberships[0]?.["bank-id"]}
      <Router routes={authRoutes} />
    {/key}
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
