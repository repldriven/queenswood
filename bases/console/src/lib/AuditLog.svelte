<script>
  /* Audit log — every act a member, an operator or the bank's
     credential performed on the bank's records, newest first, read a
     page at a time from `GET /v1/bank/audit-log`. The rail narrows it
     by time, by kind of act and by kind of performer, and a link from
     the access log by request, all sent to the API and kept in the
     hash's query (`#/audit-log?kind=…&performed-by-kind=…&range=24h`)
     so a filtered log is a link. A selection holding every option is
     sent as none, which reads the log itself rather than one range per
     option. Clicking an entry opens it in a drawer: its identifiers,
     then its changeset. All times UTC. */

  import {
    PageHeader,
    Button,
    Drawer,
    Table,
    Tag,
    RemovableChip,
    ChangesetTable,
    toast,
    AUDIT_GROUPS,
    AUDIT_KINDS,
    AUDIT_GROUP_OF,
    PERFORMER_KINDS,
    PERFORMER_LABEL,
    auditKindLabel,
    auditKindTone,
    changedFields,
    shortId,
    utcDayKey,
    utcDayLabel,
    utcHhmm,
    fmtRel,
    isoSeconds,
    rangeBounds,
    rangeFromParams,
    rangeParams,
    rangeLabel,
  } from "@queenswood/ui";
  import AuditKindRail from "./AuditKindRail.svelte";
  import { list_audit_log, list_members } from "./api.mjs";

  let { myMembers } = $props();
  const kicker = $derived(myMembers?.[0]?.["bank-name"]);

  const PAGE_SIZE = 20;
  const PERFORMERS = PERFORMER_KINDS.map((p) => p.kind);

  // The filters the hash's query carries, each value checked against
  // the options the rail offers.
  function fromHash() {
    const params = new URLSearchParams(location.hash.split("?")[1] ?? "");
    const listed = (name, allowed) => {
      const given = new Set((params.get(name) ?? "").split(",").filter(Boolean));
      return allowed.filter((v) => given.has(v));
    };
    return {
      kinds: listed("kind", AUDIT_KINDS),
      performers: listed("performed-by-kind", PERFORMERS),
      range: rangeFromParams(params),
      requestId: (params.get("request-id") ?? "").trim(),
      people: (params.get("performed-by") ?? "")
        .split(",")
        .map((s) => s.trim())
        .filter(Boolean),
    };
  }

  const initial = fromHash();
  let kinds = $state(initial.kinds);
  let performers = $state(initial.performers);
  let range = $state(initial.range);
  let requestId = $state(initial.requestId);
  let people = $state(initial.people);
  let members = $state([]);

  $effect(() => {
    list_members()
      .then((res) => {
        members = res.status === 200 ? (res.body?.items ?? []) : [];
      })
      .catch(() => {});
  });

  const memberName = (id) => {
    const m = members.find((x) => x["user-id"] === id);
    return m ? m.name || m.email : shortId(id);
  };

  let entries = $state([]);
  let next = $state(null);
  let loading = $state(true);
  let loadingOlder = $state(false);
  let error = $state(null);
  let now = $state(Date.now());
  let currentId = $state(null);
  let rail = $state();

  // What the API is sent: a filter holding every option narrows nothing.
  const sentKinds = $derived(kinds.length === AUDIT_KINDS.length ? [] : kinds);
  const sentPerformers = $derived(
    performers.length === PERFORMERS.length ? [] : performers,
  );
  const sentRange = $derived.by(() => {
    const { from, to } = rangeBounds(range, now);
    return {
      from: from != null ? isoSeconds(from) : null,
      to: to != null ? isoSeconds(to) : null,
    };
  });

  const current = $derived(entries.find((e) => e["audit-entry-id"] === currentId));
  const currentIndex = $derived(
    entries.findIndex((e) => e["audit-entry-id"] === currentId),
  );

  // Each loaded entry after the day separator it opens, where it opens one.
  const rows = $derived.by(() => {
    let day = null;
    return entries.flatMap((e) => {
      const key = utcDayKey(e["performed-at"]);
      const out = key === day ? [] : [{ day: utcDayLabel(e["performed-at"], now), key }];
      day = key;
      return [...out, { entry: e }];
    });
  });

  // Applied filters as chips: the time range, performers, then each
  // group whose kinds are all selected as one chip, each other kind alone,
  // and the request.
  const chips = $derived([
    ...(range ? [{ range }] : []),
    ...performers.map((p) => ({ performer: p })),
    ...people.map((p) => ({ person: p })),
    ...AUDIT_GROUPS.flatMap((g) => {
      const on = g.kinds.filter((k) => kinds.includes(k));
      if (on.length === g.kinds.length) return [{ group: g }];
      return on.map((k) => ({ kind: k }));
    }),
    ...(requestId ? [{ requestId }] : []),
  ]);

  function failure(res) {
    return res.body?.detail ?? `HTTP ${res.status}`;
  }

  let seq = 0;

  async function load() {
    const mine = ++seq;
    loading = true;
    error = null;
    now = Date.now();
    try {
      const { from, to } = rangeBounds(range, now);
      const res = await list_audit_log({
        kinds: sentKinds,
        performerKinds: sentPerformers,
        performedBy: people,
        requestId,
        from: from != null ? isoSeconds(from) : null,
        to: to != null ? isoSeconds(to) : null,
        size: PAGE_SIZE,
      });
      if (mine !== seq) return;
      if (res.status < 200 || res.status >= 300) {
        error = failure(res);
        entries = [];
        next = null;
        return;
      }
      entries = res.body?.items ?? [];
      next = res.body?.links?.next ?? null;
      if (currentId && !entries.some((e) => e["audit-entry-id"] === currentId)) {
        currentId = null;
      }
    } catch (err) {
      if (mine === seq) error = err.message;
    } finally {
      if (mine === seq) loading = false;
    }
  }

  async function loadOlder() {
    if (!next || loadingOlder) return false;
    const mine = seq;
    loadingOlder = true;
    try {
      const res = await list_audit_log({ next });
      if (mine !== seq) return false;
      if (res.status < 200 || res.status >= 300) {
        toast("Could not load older entries", failure(res));
        return false;
      }
      entries = [...entries, ...(res.body?.items ?? [])];
      next = res.body?.links?.next ?? null;
      return true;
    } finally {
      loadingOlder = false;
    }
  }

  function writeHash() {
    const params = [];
    if (kinds.length) params.push(`kind=${kinds.join(",")}`);
    if (performers.length) params.push(`performed-by-kind=${performers.join(",")}`);
    if (people.length) params.push(`performed-by=${people.join(",")}`);
    params.push(...rangeParams(range));
    if (requestId) params.push(`request-id=${encodeURIComponent(requestId)}`);
    const hash = `#/audit-log${params.length ? `?${params.join("&")}` : ""}`;
    if (location.hash !== hash) history.replaceState(history.state, "", hash);
  }

  // Any change of filter reads the first page again.
  $effect(() => {
    kinds;
    performers;
    range;
    requestId;
    people;
    writeHash();
    load();
  });

  function setKinds(next) {
    kinds = next;
  }

  function setPerformers(next) {
    performers = next;
  }

  function setRange(next) {
    range = next;
  }

  function setPeople(next) {
    people = next;
  }

  function clearFilters() {
    kinds = [];
    performers = [];
    range = null;
    requestId = "";
    people = [];
  }

  function removeChip(chip) {
    if (chip.range) range = null;
    else if (chip.requestId) requestId = "";
    else if (chip.person) people = people.filter((p) => p !== chip.person);
    else if (chip.performer) performers = performers.filter((p) => p !== chip.performer);
    else if (chip.group) kinds = kinds.filter((k) => !chip.group.kinds.includes(k));
    else kinds = kinds.filter((k) => k !== chip.kind);
  }

  function onlyKind(kind) {
    kinds = [kind];
    rail?.reveal(kind);
  }

  function addKind(kind) {
    kinds = AUDIT_KINDS.filter((k) => k === kind || kinds.includes(k));
    rail?.reveal(kind);
  }

  function toggleRow(id) {
    currentId = currentId === id ? null : id;
  }

  function rowKey(e, id) {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      toggleRow(id);
    }
  }

  async function step(by) {
    if (currentIndex < 0) return;
    const to = currentIndex + by;
    if (to < 0) return;
    if (to >= entries.length && !(await loadOlder())) return;
    const entry = entries[to];
    if (entry) currentId = entry["audit-entry-id"];
  }

  $effect(() => {
    if (!currentId) return;
    const onKey = (e) => {
      if (e.target instanceof HTMLInputElement) return;
      if (e.key === "ArrowDown" || e.key === "j") {
        e.preventDefault();
        step(1);
      } else if (e.key === "ArrowUp" || e.key === "k") {
        e.preventDefault();
        step(-1);
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  });

  function copy(value) {
    navigator.clipboard?.writeText(value).catch(() => {});
    toast(`Copied ${shortId(value)}`);
  }

  function performerSuffix(by) {
    return by?.kind === "member" ? "" : ` (${PERFORMER_LABEL[by?.kind] ?? by?.kind})`;
  }

  const drawerSub = $derived(
    current
      ? `${utcDayLabel(current["performed-at"], now)} · ${utcHhmm(current["performed-at"])} UTC · ${current["performed-by"]?.name}${performerSuffix(current["performed-by"])}`
      : "",
  );
</script>

<PageHeader
  {kicker}
  title="Audit log"
  sub="Every act a member, an operator or the bank’s credential performed on the bank’s records, newest first. Each names the record, who performed it, the request that asked for it and the fields it changed. Select any number of kinds to narrow the log."
>
  {#snippet actions()}
    <Button variant="line" onclick={load}>
      <svg class="refresh" viewBox="0 0 16 16" aria-hidden="true"><path d="M13.5 8 A 5.5 5.5 0 1 1 11.5 4" /><path d="M13.5 2.5 V5 H11" /></svg>
      Refresh
    </Button>
  {/snippet}
</PageHeader>

<div class="layout">
  <AuditKindRail
    bind:this={rail}
    {kinds}
    {performers}
    {range}
    {members}
    {people}
    onKinds={setKinds}
    onPerformers={setPerformers}
    onRange={setRange}
    onPeople={setPeople}
    onClear={clearFilters}
  />

  <section class="panel">
    <div class="panel-head">
      <div class="active">
        {#if chips.length}
          <span class="lead">Showing</span>
          {#each chips as chip (chip.range ? "range" : chip.requestId ? "request" : chip.person ? `person-${chip.person}` : (chip.performer ?? chip.group?.id ?? chip.kind))}
            <RemovableChip onremove={() => removeChip(chip)}>
              {#if chip.range}
                {rangeLabel(chip.range)} <span class="all">UTC</span>
              {:else if chip.person}
                <span class="muted">By</span> {memberName(chip.person)}
              {:else if chip.requestId}
                <span class="muted">Request</span> <span class="mono-id">{shortId(chip.requestId)}</span>
              {:else if chip.performer}
                <span class="muted">By</span> {PERFORMER_LABEL[chip.performer]}
              {:else if chip.group}
                {chip.group.label} <span class="all">all {chip.group.kinds.length}</span>
              {:else}
                {auditKindLabel(chip.kind)}
              {/if}
            </RemovableChip>
          {/each}
        {:else}
          <span class="lead"><b>Every kind of act</b> · newest first</span>
        {/if}
      </div>
      <div class="req">
        <span class="method">GET</span>
        <span>
          <span class="path">/v1/bank/audit-log</span>?{#if sentPerformers.length}performed-by-kind=<span class="performers">{sentPerformers.join(",")}</span>&amp;{/if}{#if people.length}performed-by=<span class="kinds">{people.join(",")}</span>&amp;{/if}{#if sentKinds.length}kind=<span class="kinds">{sentKinds.join(",")}</span>&amp;{/if}{#if requestId}request-id=<span class="kinds">{requestId}</span>&amp;{/if}{#if sentRange.from}from=<span class="kinds">{sentRange.from}</span>&amp;{/if}{#if sentRange.to}to=<span class="kinds">{sentRange.to}</span>&amp;{/if}page[size]={PAGE_SIZE}
        </span>
      </div>
    </div>

    {#if error}
      <div class="alert" role="alert">
        <span>{error}</span>
        <Button variant="line" size="sm" onclick={load}>Retry</Button>
      </div>
    {/if}

    {#if loading && entries.length === 0}
      <div class="loading">Loading…</div>
    {:else if !error && entries.length === 0}
      <div class="empty">
        <strong>No acts match this selection</strong>
        Nothing matching these filters has been performed on this bank’s records yet.
        {#if kinds.length || performers.length || people.length || range || requestId}
          <div><Button variant="line" size="sm" onclick={clearFilters}>Clear selection</Button></div>
        {/if}
      </div>
    {:else if entries.length}
      <div class="scroll" class:stale={loading}>
        <Table flush>
          <thead>
            <tr>
              <th>Time (UTC)</th>
              <th>Act</th>
              <th>Record</th>
              <th>Performed by</th>
              <th>Changed</th>
            </tr>
          </thead>
          <tbody>
            {#each rows as row (row.key ?? row.entry["audit-entry-id"])}
              {#if row.day}
                <tr class="day"><td colspan="5">{row.day}</td></tr>
              {:else}
                {@const e = row.entry}
                {@const id = e["audit-entry-id"]}
                {@const by = e["performed-by"]}
                {@const fields = changedFields(e.changeset)}
                <tr
                  class="entry"
                  class:open={currentId === id}
                  tabindex="0"
                  aria-expanded={currentId === id}
                  onclick={() => toggleRow(id)}
                  onkeydown={(ev) => rowKey(ev, id)}
                >
                  <td title={fmtRel(e["performed-at"], now)}><span class="time">{utcHhmm(e["performed-at"])}</span></td>
                  <td><span class="act {auditKindTone(e.kind)}">{auditKindLabel(e.kind)}</span></td>
                  <td><span class="record" title={e["subject-id"]}>{shortId(e["subject-id"])}</span></td>
                  <td>
                    <span class="who">
                      {by?.name}
                      {#if by?.kind !== "member"}
                        <Tag tone={by?.kind}>{PERFORMER_LABEL[by?.kind] ?? by?.kind}</Tag>
                      {/if}
                    </span>
                  </td>
                  <td>
                    {#if !e.changeset}
                      <span class="none">—</span>
                    {:else}
                      <span class="changed">{#if !e.changeset.before}{"created · "}{/if}<span class="fields">{fields.join(", ")}</span></span>
                    {/if}
                  </td>
                </tr>
              {/if}
            {/each}
          </tbody>
        </Table>
      </div>
      <div class="foot">
        <span class="count">
          {entries.length} {entries.length === 1 ? "entry" : "entries"} · {next ? "older entries on the next page" : sentRange.from ? "start of the range" : "beginning of the log"}
        </span>
        {#if next}
          <Button variant="line" size="sm" disabled={loadingOlder} onclick={loadOlder}>
            {loadingOlder ? "Loading…" : "Load older"}
          </Button>
        {/if}
      </div>
    {/if}
  </section>
</div>

<Drawer
  open={!!current}
  onClose={() => (currentId = null)}
  width={520}
  kicker={current?.kind}
  title={current ? auditKindLabel(current.kind) : ""}
  sub={drawerSub}
>
  {#if current}
    <section class="section">
      <h3>Entry</h3>
      <dl class="meta">
        <dt>Entry</dt>
        <dd>{current["audit-entry-id"]}</dd>
        <dt>Subject</dt>
        <dd>
          {current["subject-kind"]} · {current["subject-id"]}
          <button type="button" class="copy" aria-label="Copy subject id" onclick={() => copy(current["subject-id"])}>
            <svg viewBox="0 0 16 16" aria-hidden="true"><rect x="5" y="5" width="8.5" height="8.5" rx="1.5" /><path d="M3 10.5 V3.5 A1 1 0 0 1 4 2.5 H10.5" /></svg>
          </button>
        </dd>
        <dt>At</dt>
        <dd>{current["performed-at"]}</dd>
        <dt>By</dt>
        <dd>{current["performed-by"]?.kind} · {current["performed-by"]?.["principal-id"]}</dd>
        <dt>Request</dt>
        <dd>
          {#if current["request-id"]}
            {current["request-id"]}
            <button type="button" class="copy" aria-label="Copy request id" onclick={() => copy(current["request-id"])}>
              <svg viewBox="0 0 16 16" aria-hidden="true"><rect x="5" y="5" width="8.5" height="8.5" rx="1.5" /><path d="M3 10.5 V3.5 A1 1 0 0 1 4 2.5 H10.5" /></svg>
            </button>
            <a class="find-call" href="#/access-log?ref={encodeURIComponent(current['request-id'])}">Find the call</a>
          {:else}
            <span class="none">none recorded</span>
          {/if}
        </dd>
      </dl>
    </section>
    <section class="section">
      <h3>Changeset</h3>
      <ChangesetTable changeset={current.changeset} />
    </section>
  {/if}

  {#snippet footer()}
    {#if current}
      <div class="drawer-actions">
        {#if !(kinds.length === 1 && kinds[0] === current.kind)}
          <Button variant="line" size="sm" onclick={() => onlyKind(current.kind)}>Only this kind</Button>
        {/if}
        {#if kinds.length && !kinds.includes(current.kind)}
          <Button variant="ghost" size="sm" onclick={() => addKind(current.kind)}>Add kind to filter</Button>
        {/if}
        <span class="spacer"></span>
        <Button variant="line" size="sm" disabled={currentIndex <= 0} onclick={() => step(-1)}>Newer</Button>
        <Button
          variant="line"
          size="sm"
          disabled={currentIndex < 0 || (currentIndex >= entries.length - 1 && !next)}
          onclick={() => step(1)}
        >Older</Button>
      </div>
    {/if}
  {/snippet}
</Drawer>

<style>
  .refresh {
    width: 16px;
    height: 16px;
    fill: none;
    stroke: currentColor;
    stroke-width: 1.6;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
  .layout {
    display: grid;
    grid-template-columns: 236px minmax(0, 1fr);
    gap: 20px;
    align-items: start;
  }
  .panel {
    min-width: 0;
    background: var(--surface-raised);
    border: 1px solid var(--rule-2);
    border-radius: 8px;
    overflow: hidden;
  }
  .panel-head {
    display: flex;
    flex-direction: column;
    gap: 10px;
    padding: 14px 18px;
    border-bottom: 1px solid var(--rule-2);
  }
  .active {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 6px;
    min-height: 26px;
  }
  .lead {
    margin-right: 4px;
    font-size: 13px;
    color: var(--fg-2);
  }
  .lead b {
    font-weight: 500;
    color: var(--fg);
  }
  .muted {
    color: var(--fg-muted);
  }
  .all {
    font-family: var(--mono);
    font-size: 10px;
    color: var(--fg-muted);
  }
  .req {
    display: flex;
    gap: 8px;
    align-items: baseline;
    padding: 7px 10px;
    border-radius: 6px;
    background: var(--surface-sunk);
    font-family: var(--mono);
    font-size: 11.5px;
    line-height: 1.5;
    color: var(--fg-muted);
    overflow-wrap: anywhere;
  }
  .req .method {
    flex: 0 0 auto;
    font-weight: 500;
    color: var(--gold-deep);
  }
  .req .path {
    color: var(--fg-2);
  }
  .req .kinds {
    color: var(--pine-3);
  }
  .req .performers {
    color: var(--gold-deep);
  }
  .alert {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin: 14px 18px 0;
    padding: 10px 14px;
    border: 1px solid var(--rule);
    border-radius: 6px;
    background: var(--surface-sunk);
    font-size: 13px;
    color: var(--fg);
  }
  .loading,
  .empty {
    padding: 48px 20px;
    text-align: center;
    font-size: 13px;
    color: var(--fg-muted);
  }
  .empty strong {
    display: block;
    margin-bottom: 6px;
    font-size: 14px;
    font-weight: 500;
    color: var(--fg-2);
  }
  .empty div {
    margin-top: 14px;
  }
  .scroll {
    overflow-x: auto;
    transition: opacity 0.12s;
  }
  .scroll.stale {
    opacity: 0.55;
  }
  .scroll :global(.qw-table) {
    min-width: 680px;
  }
  .scroll :global(.qw-table thead th) {
    padding: 8px 14px;
  }
  tr.day td {
    padding: 12px 14px 5px;
    font-family: var(--mono);
    font-size: 10px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--gold-deep);
  }
  :global(.qw-table tbody) tr.day:hover td {
    background: none;
  }
  tr.entry {
    cursor: pointer;
  }
  tr.entry > td {
    padding: 7px 14px;
    white-space: nowrap;
  }
  tr.entry.open > td {
    background: var(--hover-overlay);
  }
  tr.entry:focus-visible {
    outline: 2px solid var(--gold);
    outline-offset: -2px;
  }
  .time {
    font-family: var(--mono);
    font-size: 12px;
    color: var(--fg);
  }
  .act {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    font-size: 13px;
    font-weight: 500;
    color: var(--fg);
  }
  .act::before {
    content: "";
    flex: 0 0 auto;
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--pine-4);
  }
  .act.new::before {
    background: var(--gold);
  }
  .act.end::before {
    background: var(--danger);
  }
  .act.run::before {
    background: light-dark(oklch(0.55 0.10 270), oklch(0.72 0.10 270));
  }
  .record {
    font-family: var(--mono);
    font-size: 11.5px;
    color: var(--fg-muted);
  }
  .who {
    display: flex;
    align-items: center;
    gap: 7px;
    font-size: 13px;
    color: var(--fg);
  }
  .changed {
    display: block;
    max-width: 30ch;
    overflow: hidden;
    text-overflow: ellipsis;
    font-family: var(--mono);
    font-size: 11.5px;
    color: var(--fg-muted);
  }
  .changed .fields {
    color: var(--fg-2);
  }
  .none {
    color: var(--fg-muted);
    opacity: 0.5;
  }
  .foot {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    padding: 12px 18px;
    border-top: 1px solid var(--rule-2);
    background: var(--surface-sunk);
  }
  .count {
    font-family: var(--mono);
    font-size: 11px;
    color: var(--fg-muted);
  }
  .section {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }
  .section + .section {
    padding-top: 22px;
    border-top: 1px solid var(--rule-2);
  }
  .section h3 {
    margin: 0;
    font-family: var(--mono);
    font-size: 10px;
    font-weight: 500;
    letter-spacing: 0.1em;
    text-transform: uppercase;
    color: var(--gold-deep);
  }
  .meta {
    display: grid;
    grid-template-columns: auto minmax(0, 1fr);
    gap: 7px 14px;
    margin: 0;
    font-size: 12px;
  }
  .meta dt {
    color: var(--fg-muted);
  }
  .meta dd {
    display: flex;
    align-items: center;
    gap: 6px;
    margin: 0;
    font-family: var(--mono);
    font-size: 11.5px;
    color: var(--fg);
    overflow-wrap: anywhere;
  }
  .copy {
    flex: 0 0 auto;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 22px;
    height: 22px;
    padding: 0;
    border: none;
    border-radius: 5px;
    background: none;
    color: var(--fg-muted);
    cursor: pointer;
  }
  .mono-id {
    font-family: var(--mono);
    font-size: 11.5px;
  }
  .find-call {
    font-family: var(--grotesk);
    font-size: 12px;
    color: light-dark(var(--pine-3), var(--pine-5));
    text-decoration: underline;
    text-decoration-color: var(--rule);
    text-underline-offset: 3px;
    white-space: nowrap;
  }
  .find-call:hover {
    color: var(--gold-deep);
    text-decoration-color: currentColor;
  }
  .copy:hover {
    background: var(--hover-overlay);
    color: var(--fg);
  }
  .copy svg {
    width: 12px;
    height: 12px;
    fill: none;
    stroke: currentColor;
    stroke-width: 1.5;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
  .drawer-actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
  }
  .spacer {
    flex: 1;
  }
  @media (max-width: 980px) {
    .layout {
      grid-template-columns: minmax(0, 1fr);
    }
  }
</style>
