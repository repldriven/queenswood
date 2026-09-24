<script>
  /* People page — who can read and administer the bank. Three tabs over
     the access routes: Members, Invitations (pending, expired and
     accepted) and History (every access change, newest first, paged).
     Every role reads all three; the actions shown follow the signed-in
     person's role from `/v1/me`, and PeopleDrawer makes the writes. */

  import {
    PageHeader,
    Panel,
    PanelHead,
    Table,
    Thead,
    Tbody,
    Tr,
    Th,
    Td,
    Badge,
    Button,
    Chip,
    SearchField,
    Tabs,
    RolePill,
    Tag,
    toast,
    accessEnum,
    grantableBy,
    canManagePeople,
    canActOn,
    EVENT_LABEL,
    EVENT_FAMILY,
    fmtUtcDate,
    fmtUtcDateTime,
    fmtFromNow,
    expiryUrgency,
  } from "@queenswood/ui";
  import { tick } from "svelte";
  import {
    list_members,
    list_invitations,
    list_audit_events,
    resend_invitation,
  } from "./api.mjs";
  import PeopleDrawer from "./PeopleDrawer.svelte";

  let { user, memberships, onAccessChanged } = $props();

  const membership = $derived(memberships?.[0]);
  const kicker = $derived(membership?.["bank-name"]);
  const myRole = $derived(accessEnum(membership?.role));
  const myUserId = $derived(user?.["user-id"]);
  const admin = $derived(canManagePeople(myRole));

  const INV_FILTERS = ["all", "pending", "expired", "accepted"];

  // `#/people?tab=invitations` opens on that tab, which is how the
  // scenarios show the invitations they just sent.
  const tabFromHash = () => {
    const t = new URLSearchParams(location.hash.split("?")[1] ?? "").get("tab");
    return ["members", "invitations", "history"].includes(t) ? t : "members";
  };
  let tab = $state(tabFromHash());
  let memberQuery = $state("");
  let invFilter = $state("all");

  let members = $state([]);
  let invitations = $state([]);
  let events = $state([]);
  let nextEvents = $state(null);
  let loading = $state(true);
  let loadingOlder = $state(false);
  let error = $state(null);

  let drawerOpen = $state(false);
  let drawerMode = $state("member");
  let drawerMember = $state(null);
  let drawerInvitation = $state(null);
  let drawerIssued = $state(null);
  let returnFocus = null;

  function failure(res) {
    return res.body?.detail ?? `HTTP ${res.status}`;
  }

  async function load() {
    loading = true;
    error = null;
    try {
      const [m, i, e] = await Promise.all([
        list_members(),
        list_invitations(),
        list_audit_events(),
      ]);
      const bad = [m, i, e].find((r) => r.status < 200 || r.status >= 300);
      if (bad) {
        error = failure(bad);
        return;
      }
      members = m.body?.items ?? [];
      invitations = i.body?.items ?? [];
      events = e.body?.items ?? [];
      nextEvents = e.body?.links?.next ?? null;
    } catch (err) {
      error = err.message;
    } finally {
      loading = false;
    }
  }

  async function loadOlder() {
    if (!nextEvents) return;
    loadingOlder = true;
    try {
      const res = await list_audit_events({ next: nextEvents });
      if (res.status >= 200 && res.status < 300) {
        events = [...events, ...(res.body?.items ?? [])];
        nextEvents = res.body?.links?.next ?? null;
      } else {
        toast("Could not load older history", failure(res));
      }
    } finally {
      loadingOlder = false;
    }
  }

  $effect(() => {
    load();
  });

  const membersShown = $derived.by(() => {
    const q = memberQuery.trim().toLowerCase();
    if (!q) return members;
    return members.filter((m) =>
      [m.name, m.email, accessEnum(m.role), m["invited-by"]?.name]
        .filter(Boolean)
        .join(" ")
        .toLowerCase()
        .includes(q),
    );
  });

  const invitationsShown = $derived(
    invitations.filter(
      (i) => invFilter === "all" || accessEnum(i.status) === invFilter,
    ),
  );

  const pendingCount = $derived(
    invitations.filter((i) => accessEnum(i.status) === "pending").length,
  );

  const tabs = $derived([
    { id: "members", label: "Members", count: loading ? "" : members.length },
    {
      id: "invitations",
      label: "Invitations",
      count: loading ? "" : pendingCount ? `${pendingCount} pending` : invitations.length,
    },
    {
      id: "history",
      label: "History",
      count: loading ? "" : `${events.length}${nextEvents ? "+" : ""}`,
    },
  ]);

  function isMe(m) {
    return m["user-id"] === myUserId;
  }

  function openDrawer(mode, { member = null, invitation = null, issued = null } = {}) {
    if (!drawerOpen) returnFocus = document.activeElement;
    drawerMode = mode;
    drawerMember = member ?? drawerMember;
    drawerInvitation = invitation ?? drawerInvitation;
    drawerIssued = issued ?? drawerIssued;
    drawerOpen = true;
  }

  async function closeDrawer() {
    drawerOpen = false;
    await tick();
    if (returnFocus?.isConnected) returnFocus.focus();
    returnFocus = null;
  }

  function navigate(mode, patch = {}) {
    openDrawer(mode, patch);
  }

  function changed({ self = false, tab: nextTab } = {}) {
    if (nextTab) tab = nextTab;
    load();
    if (self) onAccessChanged?.();
  }

  async function resend(invitation) {
    const res = await resend_invitation(invitation["invitation-id"]);
    if (res.status >= 200 && res.status < 300) {
      load();
      openDrawer("sent", { issued: { invitation: res.body, resend: true } });
    } else {
      toast("Could not resend", `${res.status} · ${res.body?.type ?? "HTTP"}`);
    }
  }

  function stop(e, fn) {
    e.stopPropagation();
    fn();
  }
</script>

<PageHeader
  {kicker}
  title="People"
  sub="Who can read and administer this organisation. A member holds one role, and each role carries every level below it. The history keeps every change, including an operator’s."
>
  {#snippet actions()}
    <Button variant="line" onclick={load}>
      <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <path d="M13.5 8 A 5.5 5.5 0 1 1 11.5 4" />
        <path d="M13.5 2.5 V5 H11" />
      </svg>
      Refresh
    </Button>
    <Button
      variant="primary"
      disabled={!admin}
      title={admin ? undefined : "Inviting people needs admin or owner"}
      onclick={() => openDrawer("invite")}
    >
      <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <path d="M8 3.5 V12.5" />
        <path d="M3.5 8 H12.5" />
      </svg>
      Invite
    </Button>
  {/snippet}
</PageHeader>

{#if error}
  <div class="alert" role="alert">{error}</div>
{/if}

<div class="people">
  <Tabs label="People" {tabs} bind:value={tab} />

  <div id="pane-members" role="tabpanel" aria-labelledby="tab-members" hidden={tab !== "members"}>
    <Panel>
      <PanelHead
        title="Members"
        count={membersShown.length === members.length
          ? `${members.length} active`
          : `${membersShown.length} of ${members.length}`}
      >
        {#snippet actions()}
          <div class="search">
            <SearchField bind:value={memberQuery} size="sm" placeholder="Search members…" ariaLabel="Search members" />
          </div>
        {/snippet}
      </PanelHead>
      <Table flush>
        <Thead>
          <Tr>
            <Th>Person</Th>
            <Th>Role</Th>
            <Th>Joined</Th>
            <Th>Invited by</Th>
            <Th align="right">{admin ? "Actions" : ""}</Th>
          </Tr>
        </Thead>
        <Tbody>
          {#if loading}
            <Tr><Td colspan="5"><div class="empty">Loading…</div></Td></Tr>
          {:else if membersShown.length === 0}
            <Tr>
              <Td colspan="5">
                <div class="empty">
                  <strong>No member matches “{memberQuery}”</strong>
                  Search by name, email, role or who invited them.
                </div>
              </Td>
            </Tr>
          {:else}
            {#each membersShown as m (m["membership-id"])}
              {@const role = accessEnum(m.role)}
              {@const mayAct = canActOn(myRole, role)}
              <Tr class="clickable" onclick={() => openDrawer("member", { member: m })}>
                <Td>
                  <div class="person">
                    <span class="name">
                      {m.name ?? m.email ?? m["user-id"]}
                      {#if isMe(m)}<Tag tone="you">you</Tag>{/if}
                      {#if m["created-organisation"]}<Tag tone="founder">created organisation</Tag>{/if}
                    </span>
                    {#if m.email}<span class="mail">{m.email}</span>{/if}
                  </div>
                </Td>
                <Td><RolePill {role} /></Td>
                <Td mono muted>{fmtUtcDate(m["created-at"])}</Td>
                <Td>
                  {#if m["invited-by"]}
                    <span class="actor">
                      {m["invited-by"].name}
                      {#if accessEnum(m["invited-by"].kind) === "operator"}<Tag tone="operator">operator</Tag>{/if}
                    </span>
                    {#if m["invited-email"] && m["invited-email"] !== m.email}
                      <span class="sub">sent to {m["invited-email"]}</span>
                    {/if}
                  {:else}
                    <span class="em">—</span>
                  {/if}
                </Td>
                <Td align="right">
                  <div class="acts">
                    {#if mayAct}
                      <Button size="sm" variant="line" onclick={(e) => stop(e, () => openDrawer("role", { member: m }))}>
                        Change role
                      </Button>
                    {/if}
                    {#if mayAct || isMe(m)}
                      <Button size="sm" variant="danger" onclick={(e) => stop(e, () => openDrawer("remove", { member: m }))}>
                        {isMe(m) ? "Leave" : "Remove"}
                      </Button>
                    {/if}
                  </div>
                </Td>
              </Tr>
            {/each}
          {/if}
        </Tbody>
      </Table>
      <div class="note">
        {@render infoIcon()}
        <span>
          {#if admin}
            A removed member stays in the history and is never deleted. The organisation always has an owner.
          {:else}
            Your role — {myRole} — reads this list. Managing people needs admin or owner.
          {/if}
        </span>
      </div>
    </Panel>
  </div>

  <div id="pane-invitations" role="tabpanel" aria-labelledby="tab-invitations" hidden={tab !== "invitations"}>
    <Panel>
      <PanelHead
        title="Invitations"
        count={invitationsShown.length === invitations.length
          ? invitations.length
          : `${invitationsShown.length} of ${invitations.length}`}
      >
        {#snippet actions()}
          <div class="filters" role="group" aria-label="Filter by status">
            {#each INV_FILTERS as f (f)}
              <Chip pressed={invFilter === f} onclick={() => (invFilter = f)}>{f}</Chip>
            {/each}
          </div>
          {#if admin}
            <Button size="sm" variant="line" onclick={() => openDrawer("invite")}>Invite</Button>
          {/if}
        {/snippet}
      </PanelHead>
      <Table flush>
        <Thead>
          <Tr>
            <Th>Email</Th>
            <Th>Role</Th>
            <Th>Status</Th>
            <Th>Expires</Th>
            <Th>Invited by</Th>
            <Th>Reason</Th>
            <Th align="right">{admin ? "Actions" : ""}</Th>
          </Tr>
        </Thead>
        <Tbody>
          {#if loading}
            <Tr><Td colspan="7"><div class="empty">Loading…</div></Td></Tr>
          {:else if invitationsShown.length === 0}
            <Tr>
              <Td colspan="7">
                <div class="empty">
                  <strong>No {invFilter === "all" ? "" : `${invFilter} `}invitations</strong>
                  {admin
                    ? "Invite a colleague and hand them the link — the platform doesn’t send email yet."
                    : "An admin or owner invites people to this organisation."}
                </div>
              </Td>
            </Tr>
          {:else}
            {#each invitationsShown as i (i["invitation-id"])}
              {@const status = accessEnum(i.status)}
              {@const role = accessEnum(i.role)}
              {@const mayGrant = grantableBy(myRole).includes(role)}
              <Tr>
                <Td>
                  <div class="person">
                    <span class="name">{i.email}</span>
                    {#if i["accepted-email"] && i["accepted-email"] !== i.email}
                      <span class="mail">accepted as {i["accepted-email"]}</span>
                    {/if}
                  </div>
                </Td>
                <Td><RolePill {role} /></Td>
                <Td><Badge tone={status}>{status}</Badge></Td>
                <Td>
                  {#if status === "accepted"}
                    <span class="em">—</span>
                  {:else}
                    <span class="expiry {expiryUrgency(i['expires-at'])}">{fmtFromNow(i["expires-at"])}</span>
                    <span class="sub">{fmtUtcDateTime(i["expires-at"])}</span>
                  {/if}
                </Td>
                <Td>
                  <span class="actor">
                    {i["invited-by"]?.name ?? "—"}
                    {#if accessEnum(i["invited-by"]?.kind) === "operator"}<Tag tone="operator">operator</Tag>{/if}
                  </span>
                  <span class="sub">{fmtUtcDate(i["created-at"])}</span>
                </Td>
                <Td>
                  {#if i.reason}
                    <span class="reason" title={i.reason}>{i.reason}</span>
                  {:else}
                    <span class="em">—</span>
                  {/if}
                </Td>
                <Td align="right">
                  {#if admin}
                    <div class="acts">
                      {#if status === "pending"}
                        <Button
                          size="sm"
                          variant="line"
                          disabled={!mayGrant}
                          title={mayGrant ? undefined : "Your role can’t grant this role"}
                          onclick={() => openDrawer("withdraw", { invitation: i })}
                        >
                          Withdraw
                        </Button>
                      {/if}
                      {#if status === "pending" || status === "expired"}
                        <Button
                          size="sm"
                          variant="line"
                          disabled={!mayGrant}
                          title={mayGrant ? undefined : "Your role can’t grant this role"}
                          onclick={() => resend(i)}
                        >
                          Resend
                        </Button>
                      {/if}
                    </div>
                  {/if}
                </Td>
              </Tr>
            {/each}
          {/if}
        </Tbody>
      </Table>
      <div class="note">
        {@render infoIcon()}
        <span>
          An invitation expires seven days after it was sent, and nothing is written when it does. Declined and withdrawn invitations appear only in the history.
        </span>
      </div>
    </Panel>
  </div>

  <div id="pane-history" role="tabpanel" aria-labelledby="tab-history" hidden={tab !== "history"}>
    <Panel>
      <PanelHead title="History" count="every access change, newest first" />
      <Table flush>
        <Thead>
          <Tr>
            <Th>When (UTC)</Th>
            <Th>Event</Th>
            <Th>By</Th>
            <Th>About</Th>
            <Th>Role</Th>
            <Th>Reason</Th>
          </Tr>
        </Thead>
        <Tbody>
          {#if loading}
            <Tr><Td colspan="6"><div class="empty">Loading…</div></Td></Tr>
          {:else if events.length === 0}
            <Tr><Td colspan="6"><div class="empty">No access changes yet.</div></Td></Tr>
          {:else}
            {#each events as e (e["audit-event-id"])}
              {@const kind = accessEnum(e.kind)}
              {@const before = e["role-before"] ? accessEnum(e["role-before"]) : null}
              {@const after = e["role-after"] ? accessEnum(e["role-after"]) : null}
              <Tr>
                <Td mono muted>{fmtUtcDateTime(e["occurred-at"])}</Td>
                <Td><span class="kind {EVENT_FAMILY[kind] ?? ''}">{EVENT_LABEL[kind] ?? kind}</span></Td>
                <Td>
                  <span class="actor">
                    {e.actor?.name ?? "—"}
                    {#if accessEnum(e.actor?.kind) === "operator"}<Tag tone="operator">operator</Tag>{/if}
                  </span>
                </Td>
                <Td>
                  {#if e["subject-name"] || e.email}
                    <div class="person">
                      <span class="name">{e["subject-name"] ?? e.email}</span>
                      {#if e["subject-name"] && e.email}<span class="mail">{e.email}</span>{/if}
                    </div>
                  {:else}
                    <span class="em">—</span>
                  {/if}
                </Td>
                <Td>
                  {#if before && after}
                    <span class="delta"><RolePill role={before} /><span class="arrow">→</span><RolePill role={after} /></span>
                  {:else if after}
                    <RolePill role={after} />
                  {:else if before}
                    <span class="delta"><RolePill role={before} /><span class="arrow">→</span><span class="em">ended</span></span>
                  {:else}
                    <span class="em">—</span>
                  {/if}
                </Td>
                <Td>
                  {#if e.reason}
                    <span class="reason" title={e.reason}>{e.reason}</span>
                  {:else}
                    <span class="em">—</span>
                  {/if}
                </Td>
              </Tr>
            {/each}
          {/if}
        </Tbody>
      </Table>
      <div class="hist-foot">
        <span class="shown">{events.length} shown</span>
        {#if nextEvents}
          <Button variant="line" disabled={loadingOlder} onclick={loadOlder}>
            {loadingOlder ? "Loading…" : "Load older"}
          </Button>
        {:else if !loading}
          <span class="shown">Beginning of the organisation’s history</span>
        {/if}
      </div>
    </Panel>
  </div>
</div>

<PeopleDrawer
  open={drawerOpen}
  mode={drawerMode}
  member={drawerMember}
  invitation={drawerInvitation}
  issued={drawerIssued}
  {members}
  {myRole}
  {myUserId}
  bankName={kicker}
  onClose={closeDrawer}
  onNavigate={navigate}
  onChanged={changed}
/>

{#snippet infoIcon()}
  <svg viewBox="0 0 16 16" aria-hidden="true">
    <circle cx="8" cy="8" r="6" />
    <path d="M8 7.2 V11.2" />
    <path d="M8 4.9 V5" />
  </svg>
{/snippet}

<style>
  .people {
    display: flex;
    flex-direction: column;
    gap: 20px;
  }
  .alert {
    padding: 12px 16px;
    border-radius: 6px;
    background: light-dark(oklch(0.97 0.02 30), oklch(0.26 0.04 30));
    color: var(--danger);
    font-size: 13px;
  }
  .search {
    width: 260px;
    max-width: 46vw;
  }
  .filters {
    display: flex;
    gap: 4px;
    align-items: center;
  }
  .people :global(tr.clickable) {
    cursor: pointer;
  }
  .person {
    display: flex;
    flex-direction: column;
    gap: 3px;
    min-width: 0;
  }
  .name {
    font-size: 13px;
    font-weight: 500;
    color: var(--fg);
    display: flex;
    align-items: center;
    gap: 7px;
    flex-wrap: wrap;
  }
  .mail {
    font-family: var(--mono);
    font-size: 11px;
    color: var(--fg-muted);
    overflow-wrap: anywhere;
  }
  .actor {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    flex-wrap: wrap;
  }
  .sub {
    display: block;
    font-family: var(--mono);
    font-size: 11px;
    color: var(--fg-muted);
    margin-top: 3px;
  }
  .em {
    color: var(--fg-muted);
    opacity: 0.5;
  }
  .reason {
    display: block;
    max-width: 30ch;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 12.5px;
    color: var(--fg-muted);
  }
  .expiry {
    font-family: var(--mono);
    font-size: 12px;
    color: var(--fg-muted);
    white-space: nowrap;
  }
  .expiry.soon {
    color: var(--gold-deep);
  }
  .expiry.gone {
    opacity: 0.75;
  }
  .acts {
    display: flex;
    gap: 6px;
    justify-content: flex-end;
    white-space: nowrap;
  }
  .empty {
    padding: 30px 20px;
    text-align: center;
    color: var(--fg-muted);
    font-size: 13px;
  }
  .empty strong {
    display: block;
    color: var(--fg-2);
    font-weight: 500;
    font-size: 14px;
    margin-bottom: 6px;
  }
  .note {
    padding: 11px 20px;
    font-size: 12.5px;
    color: var(--fg-muted);
    background: var(--surface-sunk);
    border-top: 1px solid var(--rule-2);
    border-radius: 0 0 8px 8px;
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .note svg {
    width: 13px;
    height: 13px;
    flex: 0 0 auto;
    stroke: currentColor;
    fill: none;
    stroke-width: 1.5;
    opacity: 0.7;
  }
  .kind {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-size: 13px;
    color: var(--fg);
    white-space: nowrap;
  }
  .kind::before {
    content: "";
    width: 6px;
    height: 6px;
    border-radius: 50%;
    flex: 0 0 auto;
    background: var(--fg-muted);
  }
  .kind.created::before {
    background: var(--gold);
  }
  .kind.invite::before {
    background: light-dark(oklch(0.55 0.10 270), oklch(0.72 0.10 270));
  }
  .kind.accept::before {
    background: var(--pos);
  }
  .kind.role::before {
    background: var(--pine-4);
  }
  .kind.end::before {
    background: var(--danger);
  }
  .delta {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    white-space: nowrap;
  }
  .arrow {
    color: var(--fg-muted);
    font-family: var(--mono);
    font-size: 12px;
  }
  .hist-foot {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    padding: 12px 20px;
    border-top: 1px solid var(--rule-2);
    background: var(--surface-sunk);
    border-radius: 0 0 8px 8px;
  }
  .shown {
    font-family: var(--mono);
    font-size: 11px;
    color: var(--fg-muted);
  }
</style>
