<script>
  /* People drawer — one Drawer, seven modes, driven by the People page:

       member    read a member; Change role / Remove / Leave when permitted
       role      change a member's role
       remove    remove a member, or leave when the member is you
       invite    invite someone by email
       refused   an invitation the API refused, with a way back to the form
       sent      an invitation just emailed, by an invite or a resend
       withdraw  withdraw a pending invitation

     The drawer makes the writes itself and tells the page through
     `onChanged`, passing `self: true` when the change was to the
     signed-in person's own membership. */

  import {
    Drawer,
    Button,
    Badge,
    Field,
    Input,
    Select,
    RolePill,
    Tag,
    Callout,
    Textarea,
    toast,
    accessEnum,
    ROLE_BLURB,
    grantableBy,
    canManagePeople,
    canActOn,
    isLastOwner,
    fmtUtcDateTime,
    fmtFromNow,
  } from "@queenswood/ui";
  import { untrack } from "svelte";
  import {
    change_member_role,
    remove_member,
    leave_membership,
    create_invitation,
    withdraw_invitation,
  } from "./api.mjs";

  let {
    open = false,
    mode = "member",
    member = null,
    invitation = null,
    issued = null,
    members = [],
    myRole,
    myUserId,
    bankName = "your organisation",
    onClose,
    onNavigate,
    onChanged,
  } = $props();

  const LAST_OWNER = ":membership/last-owner";

  const REFUSAL_COPY = {
    ":invitation/already-member": (email) => [
      "Already a member",
      `${email} already holds an active membership in ${bankName}. Change their role instead of inviting them again.`,
    ],
    ":invitation/already-exists": (email) => [
      "Already invited",
      `${email} has a pending invitation to ${bankName}. Resend it for a fresh link, or withdraw it first.`,
    ],
    ":membership/role-not-granted": (_email, role) => [
      "Your role can’t grant this role",
      `An admin grants admin, developer and viewer. The ${role} role is an owner’s to give.`,
    ],
  };

  let busy = $state(false);
  let problem = $state(null);
  let reason = $state("");
  let newRole = $state("viewer");
  let email = $state("");
  let inviteRole = $state("viewer");
  let refusal = $state(null);

  const role = $derived(accessEnum(member?.role));
  const isMe = $derived(!!member && member["user-id"] === myUserId);
  const lastOwner = $derived(!!member && isLastOwner(member, members));
  const mayAct = $derived(!!member && canActOn(myRole, role));
  const grantable = $derived(grantableBy(myRole));

  // Set on the way to and from a refusal, so the invite form reopens as
  // it was sent.
  let keepInvite = false;

  // Each mode opens on a clean form.
  $effect(() => {
    void mode;
    void member;
    void invitation;
    if (!open) return;
    untrack(() => {
      problem = null;
      busy = false;
      newRole = grantable.includes(role) ? role : (grantable[0] ?? "viewer");
      if (keepInvite) {
        keepInvite = false;
        return;
      }
      reason = "";
      if (mode === "invite") {
        email = "";
        inviteRole = "viewer";
        refusal = null;
      }
    });
  });

  function problemLine(res) {
    return `${res.status} · ${res.body?.type ?? "HTTP"}`;
  }

  function nameOf(m) {
    return m?.name ?? m?.email ?? "This member";
  }

  async function confirmRole() {
    if (newRole === role) {
      onClose?.();
      return;
    }
    busy = true;
    problem = null;
    const res = await change_member_role(member["membership-id"], {
      role: newRole,
      reason: reason.trim() || undefined,
    });
    busy = false;
    if (res.status >= 200 && res.status < 300) {
      toast("Role changed", `${nameOf(member)} is now ${newRole}`);
      onChanged?.({ self: isMe });
      onClose?.();
    } else {
      problem = res;
    }
  }

  async function confirmRemove() {
    busy = true;
    problem = null;
    const res = isMe
      ? await leave_membership(member["membership-id"])
      : await remove_member(member["membership-id"], {
          reason: reason.trim() || undefined,
        });
    busy = false;
    if (res.status >= 200 && res.status < 300) {
      toast(isMe ? `You left ${bankName}` : "Member removed", nameOf(member));
      onChanged?.({ self: isMe });
      onClose?.();
    } else {
      problem = res;
    }
  }

  async function sendInvite() {
    busy = true;
    const res = await create_invitation({
      email: email.trim(),
      role: inviteRole,
      reason: reason.trim() || undefined,
    });
    busy = false;
    if (res.status >= 200 && res.status < 300) {
      onChanged?.({ tab: "invitations" });
      onNavigate?.("sent", { issued: { invitation: res.body, resend: false } });
    } else {
      refusal = { res, email: email.trim(), role: inviteRole };
      keepInvite = true;
      onNavigate?.("refused");
    }
  }

  function backToInvite() {
    keepInvite = true;
    onNavigate?.("invite");
  }

  async function confirmWithdraw() {
    busy = true;
    problem = null;
    const res = await withdraw_invitation(invitation["invitation-id"], {
      reason: reason.trim() || undefined,
    });
    busy = false;
    if (res.status >= 200 && res.status < 300) {
      toast("Invitation withdrawn", invitation.email);
      onChanged?.({});
      onClose?.();
    } else {
      problem = res;
    }
  }

  const refusalCopy = $derived.by(() => {
    if (!refusal) return null;
    const type = refusal.res.body?.type;
    const copy = REFUSAL_COPY[type]?.(refusal.email, refusal.role);
    return copy ?? [
      "Invitation refused",
      refusal.res.body?.detail ?? `The API answered ${refusal.res.status}.`,
    ];
  });

  const kicker = $derived(
    {
      member: "Member",
      role: "Change role",
      remove: isMe ? "Leave organisation" : "Remove member",
      invite: "Invite",
      refused: "Invite",
      sent: issued?.resend ? "Resent" : "Invitation sent",
      withdraw: "Withdraw",
    }[mode],
  );

  const title = $derived(
    {
      member: nameOf(member),
      role: nameOf(member),
      remove: nameOf(member),
      invite: `Invite someone to ${bankName}`,
      refused: "Invitation refused",
      sent: issued?.invitation?.email ?? "",
      withdraw: invitation?.email ?? "",
    }[mode],
  );

  const sub = $derived(
    mode === "invite"
      ? "They get a link to accept. Queenswood doesn’t send the email yet, so you hand the link over yourself."
      : undefined,
  );
</script>

<Drawer {open} {onClose} {kicker} {title} {sub} width={560}>
  {#if mode === "member" && member}
    <div class="status-row">
      <RolePill {role} />
      {#if member["created-organisation"]}<Tag tone="founder">created organisation</Tag>{/if}
      {#if isMe}<Tag tone="you">you</Tag>{/if}
    </div>

    {#if lastOwner && canManagePeople(myRole)}
      <Callout tone="warn" title="The only owner">
        Make someone else an owner before changing this role or ending this membership.
      </Callout>
    {/if}
    {#if !canManagePeople(myRole)}
      <Callout tone="info">
        Your role — {myRole} — reads people but doesn’t change them.
      </Callout>
    {:else if !mayAct}
      <Callout tone="info" title="Out of your reach">
        An admin acts on admin, developer and viewer roles. Only an owner acts on an owner.
      </Callout>
    {/if}

    <section class="section">
      <h3 class="section-title">Identity</h3>
      <dl class="detail-list">
        <dt>Name</dt>
        <dd class:empty={!member.name}>{member.name ?? "—"}</dd>
        <dt>Email</dt>
        <dd class="mono" class:empty={!member.email}>{member.email ?? "—"}</dd>
        <dt>Role</dt>
        <dd>
          <RolePill {role} />
          <div class="hint">{ROLE_BLURB[role]}</div>
        </dd>
      </dl>
    </section>

    <section class="section">
      <h3 class="section-title">Membership</h3>
      <dl class="detail-list">
        <dt>Joined</dt>
        <dd>{fmtUtcDateTime(member["joined-at"])}</dd>
        <dt>Membership id</dt>
        <dd class="mono">{member["membership-id"]}</dd>
        <dt>User id</dt>
        <dd class="mono">{member["user-id"]}</dd>
        <dt>How they joined</dt>
        <dd>
          {#if member["created-organisation"]}
            Created {bankName}
          {:else if member["invited-by"]}
            Invited by {member["invited-by"].name}
          {:else}
            By invitation
          {/if}
        </dd>
        {#if member["invited-email"]}
          <dt>Invitation sent to</dt>
          <dd class="mono">{member["invited-email"]}</dd>
          {#if member.email && member["invited-email"] !== member.email}
            <dt>Signed in with</dt>
            <dd class="mono">
              {member.email}
              <div class="hint">The invitation was accepted from a different address. Both are kept.</div>
            </dd>
          {/if}
        {/if}
      </dl>
    </section>
  {:else if mode === "role" && member}
    <div class="status-row">
      <RolePill {role} />
      <span class="muted">holds {role} today</span>
    </div>

    {#if lastOwner}
      <Callout tone="danger" title="Make someone else an owner first" type="409 · {LAST_OWNER}">
        {nameOf(member)} is this organisation’s only owner. Grant the owner role to another member, then change this one.
      </Callout>
    {/if}
    {#if problem}
      <Callout
        tone="danger"
        title={problem.body?.type === LAST_OWNER ? "Make someone else an owner first" : "The role was not changed"}
        type={problemLine(problem)}
      >
        {problem.body?.detail ?? "The API refused the change."}
      </Callout>
    {/if}

    <Field label="New role" htmlFor="new-role">
      <Select id="new-role" bind:value={newRole} disabled={lastOwner}>
        {#each grantable as r (r)}
          <option value={r}>{r}</option>
        {/each}
      </Select>
      {#snippet hint()}{ROLE_BLURB[newRole]}{/snippet}
    </Field>
    {#if myRole === "admin"}
      <Callout tone="info">
        As an admin you grant admin, developer and viewer. The owner role is an owner’s to give.
      </Callout>
    {/if}
    {@render reasonField("role-reason")}
  {:else if mode === "remove" && member}
    <div class="status-row"><RolePill {role} /></div>

    {#if lastOwner}
      <Callout tone="danger" title="Make someone else an owner first" type="409 · {LAST_OWNER}">
        {isMe ? "You are" : `${nameOf(member)} is`} this organisation’s only owner, and an organisation always has one.
      </Callout>
    {:else}
      <Callout tone="warn" title={isMe ? `You lose access to ${bankName}` : `${nameOf(member)} loses access to ${bankName}`}>
        The membership is ended, not deleted: it stays in the history with who ended it and when. An open session survives one more request.
      </Callout>
    {/if}
    {#if problem}
      <Callout
        tone="danger"
        title={problem.body?.type === LAST_OWNER ? "Make someone else an owner first" : "The membership was not ended"}
        type={problemLine(problem)}
      >
        {problem.body?.detail ?? "The API refused the change."}
      </Callout>
    {/if}
    {#if !isMe}
      {@render reasonField("remove-reason")}
    {/if}
  {:else if mode === "invite"}
    <Field label="Email" htmlFor="inv-email">
      <Input
        id="inv-email"
        type="email"
        bind:value={email}
        placeholder="colleague@example.com"
        autocomplete="off"
      />
    </Field>
    <Field label="Role" htmlFor="inv-role">
      <Select id="inv-role" bind:value={inviteRole}>
        {#each grantable as r (r)}
          <option value={r}>{r}</option>
        {/each}
      </Select>
      {#snippet hint()}{ROLE_BLURB[inviteRole]}{/snippet}
    </Field>
    {@render reasonField("inv-reason")}
    <Callout tone="info">
      The link expires seven days after it is sent. Until then you can withdraw it, and once it has expired you can resend it under a fresh link.
    </Callout>
  {:else if mode === "refused" && refusal}
    <Callout tone="danger" title={refusalCopy[0]} type={problemLine(refusal.res)}>
      {refusalCopy[1]}
    </Callout>
    <dl class="detail-list">
      <dt>Email</dt>
      <dd class="mono">{refusal.email}</dd>
      <dt>Role</dt>
      <dd><RolePill role={refusal.role} /></dd>
    </dl>
  {:else if mode === "sent" && issued}
    {@const inv = issued.invitation}
    {@const invRole = accessEnum(inv.role)}
    <div class="status-row">
      <RolePill role={invRole} />
      <Badge tone="pending">pending</Badge>
    </div>

    <Callout tone="info" title="On its way">
      We’re emailing {inv.email} a link to accept. It works for seven days.
    </Callout>
    {#if issued.resend}
      <Callout tone="info">The link in any earlier email no longer works, and the seven days start again.</Callout>
    {/if}

    <dl class="detail-list">
      <dt>Role</dt>
      <dd>
        <RolePill role={invRole} />
        <div class="hint">{ROLE_BLURB[invRole]}</div>
      </dd>
      <dt>Expires</dt>
      <dd>
        {fmtUtcDateTime(inv["expires-at"])}
        <div class="hint">{fmtFromNow(inv["expires-at"])}</div>
      </dd>
      <dt>Invited by</dt>
      <dd>{inv["invited-by"]?.name ?? "—"}</dd>
      {#if inv.reason}
        <dt>Reason</dt>
        <dd>{inv.reason}</dd>
      {/if}
      <dt>Invitation id</dt>
      <dd class="mono">{inv["invitation-id"]}</dd>
    </dl>
  {:else if mode === "withdraw" && invitation}
    <div class="status-row">
      <RolePill role={accessEnum(invitation.role)} />
      <Badge tone="pending">pending</Badge>
    </div>
    <Callout tone="warn" title="The link stops working">
      Whoever holds it can no longer accept. A withdrawn invitation leaves this list and appears in the history.
    </Callout>
    {#if problem}
      <Callout tone="danger" title="The invitation was not withdrawn" type={problemLine(problem)}>
        {problem.body?.detail ?? "The API refused the change."}
      </Callout>
    {/if}
    {@render reasonField("wd-reason")}
  {/if}

  {#snippet footer()}
    <div class="foot-row">
      {#if mode === "member"}
        {#if mayAct}
          <Button variant="line" onclick={() => onNavigate?.("role")}>Change role</Button>
        {/if}
        {#if mayAct || isMe}
          <Button variant="danger" onclick={() => onNavigate?.("remove")}>
            {isMe ? "Leave organisation" : "Remove from organisation"}
          </Button>
        {/if}
        <span class="grow"></span>
        <Button variant="ghost" onclick={() => onClose?.()}>Close</Button>
      {:else if mode === "role"}
        <Button variant="primary" disabled={lastOwner || busy} onclick={confirmRole}>
          {busy ? "Changing…" : "Change role"}
        </Button>
        <Button variant="ghost" onclick={() => onClose?.()}>Cancel</Button>
      {:else if mode === "remove"}
        <Button variant="danger" solid disabled={lastOwner || busy} onclick={confirmRemove}>
          {isMe ? "Leave" : "Remove member"}
        </Button>
        <Button variant="ghost" onclick={() => onClose?.()}>Cancel</Button>
      {:else if mode === "invite"}
        <Button
          variant="primary"
          disabled={busy || !email.trim() || reason.length > 500}
          onclick={sendInvite}
        >
          {busy ? "Sending…" : "Send invitation"}
        </Button>
        <Button variant="ghost" onclick={() => onClose?.()}>Cancel</Button>
      {:else if mode === "refused"}
        <Button variant="line" onclick={backToInvite}>Back to the invitation</Button>
        <span class="grow"></span>
        <Button variant="ghost" onclick={() => onClose?.()}>Close</Button>
      {:else if mode === "sent"}
        <span class="grow"></span>
        <Button variant="primary" onclick={() => onClose?.()}>Done</Button>
      {:else if mode === "withdraw"}
        <Button variant="danger" solid disabled={busy} onclick={confirmWithdraw}>
          Withdraw invitation
        </Button>
        <Button variant="ghost" onclick={() => onClose?.()}>Cancel</Button>
      {/if}
    </div>
  {/snippet}
</Drawer>

{#snippet reasonField(id)}
  <div class="field">
    <label class="field-label" for={id}>
      Reason<span class="optional">optional</span>
    </label>
    <Textarea
      {id}
      bind:value={reason}
      maxlength={500}
      placeholder="Kept on the record and shown in the history."
    />
  </div>
{/snippet}

<style>
  .status-row {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
    margin-top: -6px;
  }
  .muted {
    font-size: 13px;
    color: var(--fg-muted);
  }
  .section {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }
  .section + .section {
    margin-top: 8px;
    padding-top: 22px;
    border-top: 1px solid var(--rule-2);
  }
  .section-title {
    font-family: var(--mono);
    font-size: 10px;
    letter-spacing: 0.1em;
    text-transform: uppercase;
    color: var(--gold-deep);
    margin: 0;
    font-weight: 500;
  }
  .detail-list {
    margin: 0;
    display: grid;
    grid-template-columns: 150px 1fr;
    row-gap: 10px;
    column-gap: 16px;
    align-items: baseline;
  }
  .detail-list dt {
    font-family: var(--mono);
    font-size: 11px;
    letter-spacing: 0.04em;
    text-transform: uppercase;
    color: var(--fg-muted);
    margin: 0;
    line-height: 1.5;
  }
  .detail-list dd {
    margin: 0;
    font-size: 14px;
    color: var(--fg);
    line-height: 1.5;
    overflow-wrap: anywhere;
  }
  .detail-list dd.mono {
    font-family: var(--mono);
    font-size: 12.5px;
  }
  .detail-list dd.empty {
    color: var(--fg-muted);
    opacity: 0.55;
  }
  .hint {
    font-family: var(--grotesk);
    font-size: 12px;
    color: var(--fg-muted);
    line-height: 1.4;
    margin-top: 5px;
  }
  .field {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  .field-label {
    font-family: var(--grotesk);
    font-size: 13px;
    font-weight: 500;
    color: var(--fg);
    line-height: 1.2;
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 10px;
  }
  .optional {
    font-family: var(--mono);
    font-size: 10px;
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--fg-muted);
    font-weight: 400;
  }
  .foot-row {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .grow {
    flex: 1;
  }
</style>
