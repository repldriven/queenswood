<script>
  /* Access log — every call to the API for the bank by a caller
     authenticated for it, newest first, read a page at a time from
     `GET /v1/bank/access-log`. The rail narrows it by time and by API
     group, the groups being the tags of the API's own OpenAPI document;
     "Find a call" looks one up by the request id, idempotency key or
     correlation id it carried. Every filter is in the hash's query, so a
     filtered log is a link and back and forward step through filter
     changes. Clicking a call opens it in a drawer. All times UTC. */

  import {
    PageHeader,
    Button,
    Drawer,
    Table,
    Tag,
    Checkbox,
    MemberFilter,
    RemovableChip,
    HttpStatus,
    TimeRangeFilter,
    toast,
    PERFORMER_LABEL,
    isWrite,
    statusWord,
    formatMicros,
    isSlow,
    routeParts,
    matchedAs,
    bankTags,
    isoSeconds,
    rangeBounds,
    rangeFromParams,
    rangeParams,
    rangeDay,
    dayRange,
    rangeLabel,
    rangePhrase,
    shortId,
    utcDayKey,
    utcDayLabel,
  } from "@queenswood/ui";
  import { list_access_log, list_api_tags, list_members } from "./api.mjs";

  let { myMembers } = $props();
  const kicker = $derived(myMembers?.[0]?.["bank-name"]);

  const PAGE_SIZE = 20;
  const RETENTION_DAYS = 90;
  const DAY_MS = 86400e3;

  let groups = $state([]);
  let members = $state([]);
  let tags = $state([]);
  let callers = $state([]);
  let range = $state(null);
  let ref = $state("");
  let refInput = $state("");

  let entries = $state([]);
  let next = $state(null);
  let loading = $state(true);
  let loadingOlder = $state(false);
  let error = $state(null);
  let now = $state(Date.now());
  let currentId = $state(null);
  let groupsReady = $state(false);

  const groupNames = $derived(groups.map((g) => g.name));
  const orderedTags = $derived(
    groupsReady ? groupNames.filter((n) => tags.includes(n)) : tags,
  );

  function readHash() {
    const params = new URLSearchParams(
      (location.hash.split("?")[1] ?? "").replace(/\+/g, "%2B"),
    );
    return {
      tags: (params.get("tag") ?? "")
        .split(",")
        .map((s) => s.trim())
        .filter(Boolean),
      range: rangeFromParams(params),
      ref: (params.get("ref") ?? "").trim(),
      callers: (params.get("requested-by") ?? "")
        .split(",")
        .map((s) => s.trim())
        .filter(Boolean),
    };
  }

  function hashFor(f) {
    const known = groupsReady ? groupNames.filter((n) => f.tags.includes(n)) : f.tags;
    const params = [
      ...(known.length ? [`tag=${known.map(encodeURIComponent).join(",")}`] : []),
      ...(f.callers.length ? [`requested-by=${f.callers.join(",")}`] : []),
      ...rangeParams(f.range),
      ...(f.ref ? [`ref=${encodeURIComponent(f.ref)}`] : []),
    ];
    return `#/access-log${params.length ? `?${params.join("&")}` : ""}`;
  }

  // Sets the filters by moving to their URL, which `hashchange` then
  // applies, so the change takes a step in the browser's history.
  function go(patch) {
    const hash = hashFor({ tags, callers, range, ref, ...patch });
    if (location.hash !== hash) location.hash = hash;
    else load();
  }

  function apply() {
    const f = readHash();
    tags = f.tags;
    range = f.range;
    ref = f.ref;
    callers = f.callers;
    refInput = f.ref;
    currentId = null;
    load();
  }

  const current = $derived(entries.find((e) => e["access-entry-id"] === currentId));
  const currentIndex = $derived(
    entries.findIndex((e) => e["access-entry-id"] === currentId),
  );

  const rows = $derived.by(() => {
    let day = null;
    return entries.flatMap((e) => {
      const key = utcDayKey(e["requested-at"]);
      const out =
        key === day ? [] : [{ day: utcDayLabel(e["requested-at"], now), key }];
      day = key;
      return [...out, { entry: e }];
    });
  });

  const oneDay = $derived(rangeDay(range));

  const query = $derived.by(() => {
    const { from, to } = rangeBounds(range, now);
    return {
      tags: orderedTags,
      from: from != null ? isoSeconds(from) : null,
      to: to != null ? isoSeconds(to) : null,
      ref,
    };
  });

  // The query's parts, each value apart, for the request line.
  const requestParts = $derived(
    [
      orderedTags.length && ["tag", orderedTags.map(encodeURIComponent).join(",")],
      callers.length && ["requested-by", callers.join(",")],
      query.from && ["from", query.from],
      query.to && ["to", query.to],
      ref && ["ref", encodeURIComponent(ref)],
    ].filter(Boolean),
  );

  let seq = 0;

  async function load() {
    const mine = ++seq;
    loading = true;
    error = null;
    now = Date.now();
    try {
      const { from, to } = rangeBounds(range, now);
      const res = await list_access_log({
        tags: orderedTags,
        requestedBy: callers,
        from: from != null ? isoSeconds(from) : null,
        to: to != null ? isoSeconds(to) : null,
        ref,
        size: PAGE_SIZE,
      });
      if (mine !== seq) return;
      if (res.status < 200 || res.status >= 300) {
        error = { status: res.status, type: res.body?.type };
        entries = [];
        next = null;
        return;
      }
      entries = res.body?.items ?? [];
      next = res.body?.links?.next ?? null;
    } catch (err) {
      if (mine === seq) error = { status: "no answer", type: err.message };
    } finally {
      if (mine === seq) loading = false;
    }
  }

  async function loadOlder() {
    if (!next || loadingOlder) return false;
    const mine = seq;
    loadingOlder = true;
    try {
      const res = await list_access_log({ next });
      if (mine !== seq) return false;
      if (res.status < 200 || res.status >= 300) {
        toast("Could not load older calls", res.body?.detail ?? `HTTP ${res.status}`);
        return false;
      }
      entries = [...entries, ...(res.body?.items ?? [])];
      next = res.body?.links?.next ?? null;
      return true;
    } finally {
      loadingOlder = false;
    }
  }

  $effect(() => {
    const onHash = () => {
      if (location.hash.startsWith("#/access-log")) apply();
    };
    window.addEventListener("hashchange", onHash);
    list_members()
      .then((res) => {
        members = res.status === 200 ? (res.body?.items ?? []) : [];
      })
      .catch(() => {});
    list_api_tags()
      .then((t) => {
        groups = bankTags(t);
      })
      .catch(() => {
        groups = [];
      })
      .finally(() => {
        groupsReady = true;
        apply();
      });
    return () => window.removeEventListener("hashchange", onHash);
  });

  function toggleTag(name) {
    go({
      tags: tags.includes(name) ? tags.filter((t) => t !== name) : [...tags, name],
    });
  }

  function lookUp(e) {
    e?.preventDefault();
    go({ ref: refInput.trim() });
  }

  function onRefInput() {
    if (!refInput && ref) go({ ref: "" });
  }

  function toggleRow(id) {
    currentId = currentId === id ? null : id;
  }

  function rowKey(e, id) {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      currentId = id;
    }
  }

  async function step(by) {
    if (currentIndex < 0) return;
    const to = currentIndex + by;
    if (to < 0) return;
    if (to >= entries.length && !(await loadOlder())) return;
    const entry = entries[to];
    if (entry) currentId = entry["access-entry-id"];
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

  function hms(iso) {
    return new Date(iso).toISOString().slice(11, 19);
  }

  function callerText(by) {
    if (by?.kind === "bank") return PERFORMER_LABEL.bank;
    if (by?.kind === "member") return by.name;
    return `${by?.name} (${PERFORMER_LABEL[by?.kind] ?? by?.kind})`;
  }

  const memberName = (id) => {
    const m = members.find((x) => x["user-id"] === id);
    return m ? m.name || m.email : shortId(id);
  };

  const scope = $derived(
    [
      callers.length ? `by ${callers.map(memberName).join(", ")}` : "",
      orderedTags.length ? `in ${orderedTags.join(", ")}` : "",
      rangePhrase(range),
    ]
      .filter(Boolean)
      .join(", "),
  );
  const keep = `The log keeps calls for ${RETENTION_DAYS} days; older calls are deleted.`;

  const drawerKicker = $derived(
    current
      ? `${current.tag ? `${current.tag} · ` : ""}${current["http-status"]} ${statusWord(current["http-status"])}`
      : "",
  );
  const drawerSub = $derived(
    current
      ? `${utcDayLabel(current["requested-at"], now)} · ${hms(current["requested-at"])} UTC · ${callerText(current["requested-by"])}`
      : "",
  );
  const onlyGroup = $derived(
    !current?.tag || (orderedTags.length === 1 && orderedTags[0] === current.tag),
  );
</script>

<PageHeader
  {kicker}
  title="Access log"
  sub="Every authenticated call the bank’s people and systems made to the Queenswood API, newest first: who called, which operation, what came back and how long it took. Narrow it by time, member and kind of call, or find a call by an id you hold."
>
  {#snippet actions()}
    <Button variant="line" onclick={load}>
      <svg class="refresh" viewBox="0 0 16 16" aria-hidden="true"><path d="M13.5 8 A 5.5 5.5 0 1 1 11.5 4" /><path d="M13.5 2.5 V5 H11" /></svg>
      Refresh
    </Button>
  {/snippet}
</PageHeader>

<div class="layout">
  <aside class="rail" aria-label="Filter the log">
    <TimeRangeFilter
      {range}
      onChange={(r) => go({ range: r })}
      oldest={now - RETENTION_DAYS * DAY_MS}
      note="Either end may be left open. Calls older than {RETENTION_DAYS} days are deleted."
    />
    <MemberFilter {members} selected={callers} onChange={(ids) => go({ callers: ids })} />
    <div class="head">
      <span class="label">
        {#if orderedTags.length}
          <b>{orderedTags.length}</b> of {groups.length} kinds
        {:else}
          Kind of call · all {groups.length}
        {/if}
      </span>
      <button type="button" class="clear" disabled={!orderedTags.length} onclick={() => go({ tags: [] })}>Clear</button>
    </div>
    <div class="body">
      {#each groups as g (g.name)}
        <Checkbox checked={orderedTags.includes(g.name)} title={g.description} onclick={() => toggleTag(g.name)}>
          {g.name}
        </Checkbox>
      {/each}
    </div>
    <div class="foot-note">With nothing selected the log lists every kind. The kinds are the API reference’s groups.</div>
  </aside>

  <section class="panel">
    <div class="panel-head">
      <form class="find" role="search" onsubmit={lookUp}>
        <label class="find-box">
          <svg viewBox="0 0 16 16" aria-hidden="true"><circle cx="7" cy="7" r="4.5" /><path d="M10.5 10.5 L13.5 13.5" /></svg>
          <input
            type="search"
            placeholder="Find a call"
            aria-label="Find a call"
            aria-describedby="find-hint"
            spellcheck="false"
            autocomplete="off"
            bind:value={refInput}
            oninput={onRefInput}
          />
        </label>
        <Button variant="line" type="submit">Look up</Button>
        <span class="hint" id="find-hint">Request id, idempotency key or correlation id</span>
      </form>

      <div class="active">
        {#if range || orderedTags.length || callers.length || ref}
          <span class="lead">Showing</span>
          {#if range}
            <RemovableChip removeLabel="Clear the time range" onremove={() => go({ range: null })}>
              {rangeLabel(range)} <span class="utc">UTC</span>
            </RemovableChip>
          {/if}
          {#each callers as c (c)}
            <RemovableChip removeLabel="Remove {memberName(c)}" onremove={() => go({ callers: callers.filter((x) => x !== c) })}>
              <span class="muted">By</span> {memberName(c)}
            </RemovableChip>
          {/each}
          {#each orderedTags as t (t)}
            <RemovableChip removeLabel="Remove {t}" onremove={() => toggleTag(t)}>{t}</RemovableChip>
          {/each}
          {#if ref}
            <RemovableChip removeLabel="Clear the lookup" onremove={() => go({ ref: "" })}>
              <span class="muted">Carrying</span> <span class="mono-id">{shortId(ref)}</span>
            </RemovableChip>
            {#if !loading && entries.length}
              <span class="muted">
                {entries.length} {entries.length === 1 ? "call" : "calls"} · as {matchedAs(ref, entries)}
              </span>
            {/if}
          {/if}
        {:else}
          <span class="lead"><b>Every call</b> · newest first</span>
        {/if}
      </div>

      <div class="req">
        <span class="method">GET</span>
        <span>
          <span class="path">/v1/bank/access-log</span>?{#each requestParts as [k, v] (k)}{k}=<span class="v">{v}</span>&amp;{/each}page[size]={PAGE_SIZE}
        </span>
      </div>
    </div>

    {#if error}
      <div class="banner" role="alert">
        <span><b>Couldn’t load the access log.</b> The request failed with {error.status}{error.type ? ` ${error.type}` : ""}.</span>
        <span class="spacer"></span>
        <Button variant="line" size="sm" onclick={load}>Retry</Button>
      </div>
    {:else}
      <div class="scroll">
        <Table flush>
          <thead>
            <tr>
              <th>Time (UTC)</th>
              <th>Call</th>
              <th>Status</th>
              <th>Called by</th>
              <th class="r">Duration</th>
            </tr>
          </thead>
          <tbody>
            {#if loading}
              {#each [[52, 30, 34, 22], [64, 24, 28, 20], [46, 38, 40, 24], [58, 26, 30, 18], [40, 30, 36, 22], [70, 24, 26, 20], [50, 34, 32, 24], [56, 28, 38, 18]] as widths, i (i)}
                <tr class="skeleton">
                  <td><span class="sk" style="width: 44px"></span></td>
                  {#each widths as w, j (j)}
                    <td class:r={j === 3}><span class="sk" class:right={j === 3} style="width: {w}%"></span></td>
                  {/each}
                </tr>
              {/each}
            {:else if entries.length === 0}
              <tr class="empty-row">
                <td colspan="5">
                  <div class="empty">
                    {#if ref}
                      <strong>No call carries this id</strong>
                      <code>{ref}</code>
                      <p>
                        {#if scope}Looked {scope}. {/if}Check it’s a request id, idempotency key or correlation id from a call to this bank. {keep}
                      </p>
                    {:else if scope}
                      <strong>No calls {scope}</strong>
                      {keep}
                    {:else}
                      <strong>No calls yet</strong>
                      Calls made to the Queenswood API for this bank appear here as they’re made.
                    {/if}
                    {#if ref || orderedTags.length || callers.length || range}
                      <div class="empty-acts">
                        {#if ref}
                          <Button variant="line" size="sm" onclick={() => go({ ref: "" })}>Clear the lookup</Button>
                        {/if}
                        {#if callers.length}
                          <Button variant={ref ? "ghost" : "line"} size="sm" onclick={() => go({ callers: [] })}>Everyone</Button>
                        {/if}
                        {#if orderedTags.length}
                          <Button variant={ref || callers.length ? "ghost" : "line"} size="sm" onclick={() => go({ tags: [] })}>Every kind</Button>
                        {/if}
                        {#if range}
                          <Button variant={ref || callers.length || orderedTags.length ? "ghost" : "line"} size="sm" onclick={() => go({ range: null })}>Any time</Button>
                        {/if}
                      </div>
                    {/if}
                  </div>
                </td>
              </tr>
            {:else}
              {#each rows as row (row.key ?? row.entry["access-entry-id"])}
                {#if row.day}
                  <tr class="day">
                    <td colspan="5">
                      {row.day}
                      {#if !oneDay}
                        <button type="button" class="day-pick" onclick={() => go({ range: dayRange(row.key) })}>Only this day</button>
                      {/if}
                    </td>
                  </tr>
                {:else}
                  {@const e = row.entry}
                  {@const id = e["access-entry-id"]}
                  {@const by = e["requested-by"]}
                  <tr
                    class="entry"
                    class:open={currentId === id}
                    tabindex="0"
                    aria-expanded={currentId === id}
                    onclick={() => toggleRow(id)}
                    onkeydown={(ev) => rowKey(ev, id)}
                  >
                    <td><span class="time">{hms(e["requested-at"])}</span></td>
                    <td>
                      <span class="call">
                        <span class="mth" class:write={isWrite(e.method)}>{e.method}</span>
                        {#if e["operation-id"]}
                          <span class="op" title={e.route}>{e["operation-id"]}</span>
                        {:else}
                          <span class="none-route">No matching route</span>
                        {/if}
                      </span>
                    </td>
                    <td>
                      <span class="st">
                        <HttpStatus status={e["http-status"]} />
                        {#if e.replayed}
                          <span class="replay" title="Answered from the idempotency cache">
                            <svg viewBox="0 0 12 12" aria-hidden="true"><path d="M2.5 6 A3.5 3.5 0 1 0 3.6 3.4" /><path d="M2.3 1.8 V3.8 H4.3" /></svg>
                            Replay
                          </span>
                        {/if}
                        {#if e["problem-type"]}
                          <span class="ptype" title={e["problem-type"]}>{e["problem-type"]}</span>
                        {/if}
                      </span>
                    </td>
                    <td>
                      <span class="who">
                        {#if by?.kind !== "bank"}{by?.name}{/if}
                        {#if by?.kind !== "member"}
                          <Tag tone={by?.kind}>{PERFORMER_LABEL[by?.kind] ?? by?.kind}</Tag>
                        {/if}
                      </span>
                    </td>
                    <td class="r"><span class="dur" class:slow={isSlow(e.duration)}>{formatMicros(e.duration)}</span></td>
                  </tr>
                {/if}
              {/each}
            {/if}
          </tbody>
        </Table>
      </div>
      {#if !loading && entries.length}
        <div class="foot">
          <span class="count">
            {entries.length} {entries.length === 1 ? "call" : "calls"}{#if next} · older calls on the next page{:else if query.from} · start of the range{:else} · beginning of the log · calls are kept for {RETENTION_DAYS} days{/if}
          </span>
          {#if next}
            <Button variant="line" size="sm" disabled={loadingOlder} onclick={loadOlder}>
              {loadingOlder ? "Loading…" : "Load older"}
            </Button>
          {/if}
        </div>
      {/if}
    {/if}
  </section>
</div>

<Drawer
  open={!!current}
  onClose={() => (currentId = null)}
  width={520}
  mono
  kicker={drawerKicker}
  title={current ? (current["operation-id"] ?? "No matching route") : ""}
  sub={drawerSub}
>
  {#if current}
    {@const by = current["requested-by"]}
    <section class="section">
      <h3>Call</h3>
      <dl class="meta">
        <dt>Call</dt>
        <dd class="block">
          {current.method}
          {#if current.route}
            {#each routeParts(current.route, current["path-ids"]) as part, i (i)}{#if part.name}<span class="pid">{part.id}</span>{:else}{part.text}{/if}{/each}
          {:else}
            <span class="none">No route matched</span>
          {/if}
        </dd>
        {#each Object.entries(current["path-ids"] ?? {}) as [name, value] (name)}
          <dt>{name}</dt>
          <dd>
            {value}
            <button type="button" class="copy" aria-label="Copy {name}" onclick={() => copy(value)}>
              <svg viewBox="0 0 16 16" aria-hidden="true"><rect x="5" y="5" width="8.5" height="8.5" rx="1.5" /><path d="M3 10.5 V3.5 A1 1 0 0 1 4 2.5 H10.5" /></svg>
            </button>
          </dd>
        {/each}
        <dt>Kind of call</dt>
        <dd>{#if current.tag}{current.tag}{:else}<span class="none">none, no route matched</span>{/if}</dd>
        <dt>Status</dt>
        <dd><HttpStatus status={current["http-status"]} />{current["problem-type"] ?? ""}</dd>
        <dt>Replay</dt>
        <dd>{current.replayed ? "Yes · answered from the idempotency cache" : "No"}</dd>
        <dt>Duration</dt>
        <dd>{formatMicros(current.duration)} <span class="note">{current.duration.toLocaleString("en-GB")} µs</span></dd>
        <dt>At</dt>
        <dd>{current["requested-at"]}</dd>
        <dt>Caller</dt>
        <dd class="block">
          <span class="who">
            {#if by?.kind !== "bank"}{by?.name}{/if}
            {#if by?.kind !== "member"}<Tag tone={by?.kind}>{PERFORMER_LABEL[by?.kind] ?? by?.kind}</Tag>{/if}
          </span>
          <span class="note">{by?.kind} · </span>{by?.["principal-id"]}
        </dd>
        <dt>Client</dt>
        <dd>{current["client-address"] ?? ""}</dd>
      </dl>
    </section>

    <section class="section">
      <h3>Ids</h3>
      <dl class="meta">
        <dt>Request id</dt>
        <dd>
          {current["request-id"]}
          <button type="button" class="copy" aria-label="Copy request id" onclick={() => copy(current["request-id"])}>
            <svg viewBox="0 0 16 16" aria-hidden="true"><rect x="5" y="5" width="8.5" height="8.5" rx="1.5" /><path d="M3 10.5 V3.5 A1 1 0 0 1 4 2.5 H10.5" /></svg>
          </button>
        </dd>
        {#each [["Idempotency key", current["idempotency-key"]], ["Correlation id", current["correlation-id"]]] as [label, value] (label)}
          <dt>{label}</dt>
          <dd>
            {#if !value}
              <span class="none">none sent</span>
            {:else}
              {#if value !== ref}
                <button type="button" class="idlink" title="Find every call carrying this {label.toLowerCase()}" onclick={() => go({ ref: value })}>{value}</button>
              {:else}
                {value}
              {/if}
              <button type="button" class="copy" aria-label="Copy {label.toLowerCase()}" onclick={() => copy(value)}>
                <svg viewBox="0 0 16 16" aria-hidden="true"><rect x="5" y="5" width="8.5" height="8.5" rx="1.5" /><path d="M3 10.5 V3.5 A1 1 0 0 1 4 2.5 H10.5" /></svg>
              </button>
            {/if}
          </dd>
        {/each}
        <dt>Trace id</dt>
        <dd>
          {#if current["trace-id"]}
            {current["trace-id"]}
            <button type="button" class="copy" aria-label="Copy trace id" onclick={() => copy(current["trace-id"])}>
              <svg viewBox="0 0 16 16" aria-hidden="true"><rect x="5" y="5" width="8.5" height="8.5" rx="1.5" /><path d="M3 10.5 V3.5 A1 1 0 0 1 4 2.5 H10.5" /></svg>
            </button>
          {:else}
            <span class="none">none recorded</span>
          {/if}
        </dd>
      </dl>
      <span class="note">Click an idempotency key or correlation id to find every call carrying it. The trace id is for Queenswood support.</span>
    </section>

    <section class="section">
      <h3>What it changed</h3>
      {#if isWrite(current.method)}
        <p class="para">A write. If this call changed any of the bank’s records, the Audit log lists the changes under its request id. Whether it changed any isn’t known here.</p>
        <div>
          <Button
            variant="line"
            size="sm"
            onclick={() => (location.hash = `#/audit-log?request-id=${encodeURIComponent(current["request-id"])}`)}
          >Open in the Audit log</Button>
        </div>
      {:else}
        <p class="para">A {current.method} reads and changes no records.</p>
      {/if}
    </section>
  {/if}

  {#snippet footer()}
    {#if current}
      <div class="drawer-actions">
        {#if !onlyGroup}
          <Button variant="line" size="sm" onclick={() => go({ tags: [current.tag] })}>Only this kind</Button>
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
    grid-template-columns: 220px minmax(0, 1fr);
    gap: 20px;
    align-items: start;
  }
  .rail {
    position: sticky;
    top: 77px;
    max-height: calc(100vh - 97px);
    display: flex;
    flex-direction: column;
    background: var(--surface-raised);
    border: 1px solid var(--rule-2);
    border-radius: 8px;
    overflow: hidden;
  }
  .head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    padding: 12px 10px 10px 14px;
    border-bottom: 1px solid var(--rule-2);
  }
  .label {
    font-family: var(--mono);
    font-size: 10px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--fg-muted);
    white-space: nowrap;
  }
  .label b {
    color: var(--fg);
    font-weight: 500;
  }
  .clear {
    padding: 2px 4px;
    border: none;
    border-radius: 4px;
    background: none;
    font-family: var(--mono);
    font-size: 11px;
    color: light-dark(var(--pine-3), var(--pine-5));
    cursor: pointer;
  }
  .clear:hover:not(:disabled) {
    background: var(--hover-overlay);
    color: var(--fg);
  }
  .clear:disabled {
    color: var(--fg-muted);
    opacity: 0.5;
    cursor: default;
  }
  .body {
    display: flex;
    flex-direction: column;
    gap: 1px;
    padding: 6px;
    overflow-y: auto;
  }
  .body :global(.qw-checkbox:hover) {
    background: var(--hover-overlay);
    color: var(--fg);
  }
  .foot-note {
    padding: 10px 14px;
    border-top: 1px solid var(--rule-2);
    background: var(--surface-sunk);
    font-size: 11.5px;
    line-height: 1.45;
    color: var(--fg-muted);
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
  .find {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
  }
  .find-box {
    position: relative;
    flex: 1 1 320px;
    max-width: 620px;
  }
  .find-box svg {
    position: absolute;
    left: 11px;
    top: 50%;
    transform: translateY(-50%);
    width: 14px;
    height: 14px;
    fill: none;
    stroke: currentColor;
    stroke-width: 1.6;
    stroke-linecap: round;
    color: var(--fg-muted);
    pointer-events: none;
  }
  .find-box input {
    width: 100%;
    height: 36px;
    padding: 0 12px 0 33px;
    border: 1px solid var(--rule);
    border-radius: 6px;
    background: var(--surface);
    color: var(--fg);
    font-family: var(--mono);
    font-size: 12.5px;
  }
  .find-box input::placeholder {
    font-family: var(--grotesk);
    font-size: 13px;
    color: var(--fg-muted);
  }
  .find-box input:focus {
    outline: 2px solid var(--gold);
    outline-offset: -1px;
    border-color: var(--gold);
  }
  .hint {
    font-size: 12px;
    color: var(--fg-muted);
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
    font-size: 12.5px;
    color: var(--fg-muted);
  }
  .utc {
    font-family: var(--mono);
    font-size: 10px;
    color: var(--fg-muted);
  }
  .mono-id {
    font-family: var(--mono);
    font-size: 11.5px;
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
  .req .v {
    color: var(--pine-3);
  }
  .banner {
    display: flex;
    align-items: center;
    gap: 12px;
    margin: 14px 18px;
    padding: 10px 12px 10px 14px;
    border: 1px solid light-dark(rgba(160, 50, 30, 0.2), rgba(220, 110, 80, 0.25));
    border-radius: 6px;
    background: light-dark(rgba(160, 50, 30, 0.07), rgba(220, 110, 80, 0.12));
    font-size: 13px;
    color: var(--fg-2);
  }
  .banner b {
    font-weight: 500;
    color: var(--danger);
  }
  .spacer {
    flex: 1;
  }
  .scroll {
    overflow-x: auto;
  }
  .scroll :global(.qw-table) {
    min-width: 680px;
  }
  .scroll :global(.qw-table thead th) {
    padding: 8px 14px;
  }
  th.r,
  td.r {
    text-align: right;
  }
  tr.skeleton td {
    padding: 12px 14px;
  }
  .sk {
    display: block;
    height: 9px;
    border-radius: 4px;
    background: var(--rule);
    animation: pulse 1.2s ease-in-out infinite;
  }
  .sk.right {
    margin-left: auto;
  }
  @keyframes pulse {
    50% {
      opacity: 0.45;
    }
  }
  :global(.qw-table tbody) tr.empty-row:hover td,
  :global(.qw-table tbody) tr.skeleton:hover td,
  :global(.qw-table tbody) tr.day:hover td {
    background: none;
  }
  .empty {
    padding: 48px 20px;
    text-align: center;
    font-size: 13px;
    color: var(--fg-muted);
    white-space: normal;
  }
  .empty strong {
    display: block;
    margin-bottom: 6px;
    font-size: 14px;
    font-weight: 500;
    color: var(--fg-2);
  }
  .empty code {
    padding: 2px 6px;
    border-radius: 4px;
    background: var(--surface-sunk);
    font-family: var(--mono);
    font-size: 12px;
    color: var(--fg);
    overflow-wrap: anywhere;
  }
  .empty p {
    margin: 14px auto 0;
    max-width: 60ch;
  }
  .empty-acts {
    display: flex;
    justify-content: center;
    gap: 8px;
    margin-top: 14px;
  }
  tr.day td {
    position: relative;
    padding: 12px 14px 5px;
    font-family: var(--mono);
    font-size: 10px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--gold-deep);
  }
  .day-pick {
    position: absolute;
    right: 10px;
    bottom: 3px;
    height: 20px;
    padding: 0 8px;
    border: none;
    border-radius: 4px;
    background: none;
    font-family: var(--grotesk);
    font-size: 11.5px;
    letter-spacing: 0;
    text-transform: none;
    color: light-dark(var(--pine-3), var(--pine-5));
    cursor: pointer;
    opacity: 0;
    transition: opacity 0.1s;
  }
  tr.day:hover .day-pick,
  .day-pick:focus-visible {
    opacity: 1;
  }
  .day-pick:hover {
    background: var(--hover-overlay);
    color: var(--fg);
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
  .call {
    display: flex;
    align-items: baseline;
    gap: 10px;
  }
  .mth {
    flex: 0 0 auto;
    width: 44px;
    font-family: var(--mono);
    font-size: 10.5px;
    font-weight: 500;
    letter-spacing: 0.04em;
    color: var(--fg-muted);
  }
  .mth.write {
    color: var(--fg);
  }
  .op {
    font-size: 13px;
    font-weight: 500;
    color: var(--fg);
  }
  .none-route {
    font-family: var(--mono);
    font-size: 12px;
    color: var(--fg-muted);
  }
  .st {
    display: flex;
    align-items: center;
    gap: 9px;
  }
  .replay {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    height: 18px;
    padding: 0 6px;
    border: 1px solid var(--rule);
    border-radius: 4px;
    font-family: var(--mono);
    font-size: 10px;
    letter-spacing: 0.04em;
    color: var(--fg-muted);
  }
  .replay svg {
    width: 10px;
    height: 10px;
    fill: none;
    stroke: currentColor;
    stroke-width: 1.6;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
  .ptype {
    max-width: 30ch;
    overflow: hidden;
    text-overflow: ellipsis;
    font-family: var(--mono);
    font-size: 11px;
    color: var(--fg-muted);
  }
  .who {
    display: flex;
    align-items: center;
    gap: 7px;
    font-size: 13px;
    color: var(--fg);
  }
  .dur {
    font-family: var(--mono);
    font-size: 12px;
    color: var(--fg-2);
  }
  .dur.slow {
    font-weight: 500;
    color: var(--gold-deep);
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
  .meta dd.block {
    display: block;
  }
  .meta dd.block .who {
    margin-bottom: 3px;
    font-family: var(--grotesk);
  }
  .pid {
    color: light-dark(var(--pine-3), var(--pine-5));
  }
  .none {
    color: var(--fg-muted);
    opacity: 0.5;
  }
  .note {
    font-family: var(--grotesk);
    font-size: 12px;
    color: var(--fg-muted);
  }
  .para {
    margin: 0;
    font-size: 13px;
    line-height: 1.55;
    color: var(--fg-2);
  }
  .idlink {
    padding: 0;
    border: none;
    background: none;
    font: inherit;
    text-align: left;
    color: light-dark(var(--pine-3), var(--pine-5));
    text-decoration: underline;
    text-decoration-color: var(--rule);
    text-underline-offset: 3px;
    overflow-wrap: anywhere;
    cursor: pointer;
  }
  .idlink:hover {
    color: var(--gold-deep);
    text-decoration-color: currentColor;
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
  @media (max-width: 980px) {
    .layout {
      grid-template-columns: minmax(0, 1fr);
    }
    .rail {
      position: static;
      max-height: none;
    }
    .body {
      flex-direction: row;
      flex-wrap: wrap;
    }
    .body :global(.qw-checkbox) {
      width: auto;
      flex: 0 0 auto;
    }
    .body :global(.qw-checkbox .label) {
      flex: none;
      overflow: visible;
    }
  }
</style>
