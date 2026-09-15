<script>
  /* Accept an invitation — the screen the emailed link lands on, once
     the person is signed in. It reads the invitation with the link's
     token, names the organisation, the role, who invited and when the
     link expires, and offers Accept and Decline.

       loading   reading the invitation
       open      a pending invitation to accept or decline
       gone      the link reaches no invitation, or one no longer pending
       accepted  the person is now a member
       declined  the person turned it down

     `onDone` hands back whether the person became a member, so the app
     can reload their organisations. */

  import {
    AppNav,
    Button,
    Callout,
    RolePill,
    accessEnum,
    ROLE_BLURB,
    fmtUtcDateTime,
    fmtFromNow,
  } from "@queenswood/ui";
  import {
    get_my_invitation,
    accept_invitation,
    decline_invitation,
  } from "./api.mjs";

  let { invitationId, token, user, onDone, onSignOut } = $props();

  const REFUSAL_COPY = {
    ":invitation/not-found": [
      "This link doesn’t open an invitation",
      "It may have been withdrawn, sent again with a newer link, or sent to a different address from the one you signed in with.",
    ],
    ":invitation/invalid-status": [
      "This invitation is no longer open",
      "It has expired, or has already been accepted or declined. Ask whoever invited you to send it again.",
    ],
    ":membership/already-exists": [
      "You’re already a member",
      "You already belong to this organisation, so there’s nothing to accept.",
    ],
  };

  let stage = $state("loading");
  let invitation = $state(null);
  let refusal = $state(null);
  let busy = $state(false);

  const role = $derived(accessEnum(invitation?.role));
  const orgName = $derived(invitation?.["bank-name"] ?? "the organisation");
  const inviter = $derived(
    invitation?.["invited-by"]?.name ?? invitation?.["bank-name"] ?? "Someone",
  );

  $effect(() => {
    load();
  });

  function refusalOf(res) {
    const type = res.body?.type;
    const [title, body] = REFUSAL_COPY[type] ?? [
      "Something went wrong",
      res.body?.detail ?? `The API answered ${res.status}.`,
    ];
    return { title, body, line: `${res.status} · ${type ?? "HTTP"}` };
  }

  async function load() {
    const res = await get_my_invitation(invitationId, token);
    if (res.status !== 200) {
      refusal = refusalOf(res);
      stage = "gone";
      return;
    }
    invitation = res.body;
    if (accessEnum(invitation.status) !== "pending") {
      refusal = refusalOf({
        status: 409,
        body: { type: ":invitation/invalid-status" },
      });
      stage = "gone";
      return;
    }
    stage = "open";
  }

  async function accept() {
    busy = true;
    refusal = null;
    const res = await accept_invitation(invitationId, token);
    busy = false;
    if (res.status === 201) {
      stage = "accepted";
    } else {
      refusal = refusalOf(res);
    }
  }

  async function decline() {
    busy = true;
    refusal = null;
    const res = await decline_invitation(invitationId, token);
    busy = false;
    if (res.status === 200) {
      stage = "declined";
    } else {
      refusal = refusalOf(res);
    }
  }
</script>

<div class="page">
  <AppNav {onSignOut} />

  <main class="wrap">
    <header class="head">
      <span class="eyebrow">Console · invitation</span>
      {#if stage === "accepted"}
        <h1>Welcome to <em>{orgName}.</em></h1>
        <p class="lede">You’re now a member, as <RolePill {role} />.</p>
      {:else if stage === "declined"}
        <h1>Invitation <em>declined.</em></h1>
        <p class="lede">You won’t be added to {orgName}.</p>
      {:else if stage === "open"}
        <h1>Join <em>{orgName}.</em></h1>
        <p class="lede">{inviter} invited you to join {orgName} on Queenswood.</p>
      {:else if stage === "gone"}
        <h1>Invitation <em>unavailable.</em></h1>
      {:else}
        <h1>Opening your <em>invitation…</em></h1>
      {/if}
    </header>

    <section class="card">
      {#if stage === "open"}
        <dl class="facts">
          <dt>Role</dt>
          <dd><RolePill {role} /> <span class="sub">{ROLE_BLURB[role] ?? ""}</span></dd>
          <dt>Sent to</dt>
          <dd>{invitation.email}</dd>
          <dt>Signed in as</dt>
          <dd>{user?.email ?? "—"}</dd>
          <dt>Expires</dt>
          <dd>
            {fmtFromNow(invitation["expires-at"])}
            <span class="sub">{fmtUtcDateTime(invitation["expires-at"])} UTC</span>
          </dd>
        </dl>

        {#if refusal}
          <Callout tone="danger" title={refusal.title} type={refusal.line}>
            {refusal.body}
          </Callout>
        {/if}

        <div class="actions">
          <Button variant="line" disabled={busy} onclick={decline}>Decline</Button>
          <Button variant="primary" disabled={busy} onclick={accept}>
            Accept invitation
          </Button>
        </div>
      {:else if stage === "gone"}
        <Callout tone="danger" title={refusal.title} type={refusal.line}>
          {refusal.body}
        </Callout>
        <div class="actions">
          <Button variant="primary" onclick={() => onDone(false)}>Continue to the console</Button>
        </div>
      {:else if stage === "accepted"}
        <div class="actions">
          <Button variant="primary" onclick={() => onDone(true)}>Go to {orgName}</Button>
        </div>
      {:else if stage === "declined"}
        <div class="actions">
          <Button variant="primary" onclick={() => onDone(false)}>Continue to the console</Button>
        </div>
      {:else}
        <p class="sub">Reading the invitation…</p>
      {/if}
    </section>
  </main>
</div>

<style>
  .page { min-height: 100vh; background: var(--surface); color: var(--fg); font-family: var(--grotesk); }
  .wrap { max-width: 620px; margin: 0 auto; padding: 56px 32px 80px; }

  .head { margin-bottom: 26px; }
  .eyebrow {
    font-family: var(--mono); font-size: 11px; letter-spacing: 0.2em;
    text-transform: uppercase; color: var(--fg-muted);
    display: inline-flex; align-items: center; gap: 8px;
  }
  .eyebrow::before { content: ""; width: 18px; height: 1px; background: var(--gold-deep); }
  h1 { font-family: var(--serif); font-weight: 500; font-size: 44px; line-height: 1.05; letter-spacing: -0.008em; margin: 12px 0 12px; overflow-wrap: anywhere; }
  h1 em { font-style: italic; color: var(--gold-deep); font-weight: 500; }
  .lede { font-size: 16px; line-height: 1.5; color: var(--fg-2); margin: 0; }

  .card {
    background: var(--surface-raised); border: 1px solid var(--rule);
    border-radius: 16px; padding: 30px;
    display: flex; flex-direction: column; gap: 20px;
  }

  .facts { display: grid; grid-template-columns: max-content 1fr; gap: 12px 20px; margin: 0; }
  .facts dt { font-size: 12px; font-weight: 600; letter-spacing: 0.16em; text-transform: uppercase; color: var(--gold-deep); padding-top: 2px; }
  .facts dd { margin: 0; display: flex; flex-wrap: wrap; align-items: center; gap: 8px; overflow-wrap: anywhere; }
  .sub { font-size: 12.5px; color: var(--fg-muted); line-height: 1.5; }

  .actions { display: flex; justify-content: flex-end; gap: 10px; flex-wrap: wrap; }

  @media (max-width: 480px) {
    .wrap { padding: 32px 16px 56px; }
    h1 { font-size: 34px; }
    .facts { grid-template-columns: 1fr; gap: 4px; }
    .facts dd { margin-bottom: 10px; }
  }
</style>
