<script>
  /* Jobs page — the scheduled work the platform runs on a cadence.
     Each job is a preset, ordered pipeline of tasks (accrue →
     capitalize) that fires on a schedule. The table answers the
     operator's standing question at a glance — did the last run
     succeed, when does it run next — and expands to the task pipeline
     and recent run history. The kebab and schedule drawer drive the
     mutations: run now, pause/resume, and edit cadence/time.

     The list endpoint carries each job's schedule but not run outcome,
     so we fetch each job's runs (newest-first) — the head drives the
     status badge and the live pipeline, the tail is the history. Jobs
     are a small fixed set per bank, so the per-job fetch is cheap.

     All times are UTC. */

  import {
    PageHeader,
    Button,
    Badge,
    Table,
    Thead,
    Tbody,
    Tr,
    Th,
    Td,
    Expander,
    Drawer,
    Field,
    Input,
    Select,
    Menu,
    JobStatusBadge,
    JobKindChip,
    TaskPipeline,
    humanSchedule,
    cronOf,
    nextRunAt,
    nextRuns,
    fmtAbs,
    fmtRel,
    lastOutcome,
    fmtElapsed,
    runPipelineSteps,
    pipelineSteps,
    hhmm,
    minutesFromHHMM,
  } from "@queenswood/ui";

  const FREQ_LABEL = { hourly: "Hourly", daily: "Daily", monthly: "Monthly", yearly: "Annually" };
  const freqLabel = (p) => FREQ_LABEL[p] ?? p;
  import {
    list_jobs,
    list_job_runs,
    force_start_job,
    update_job_schedule,
  } from "./api.mjs";

  let { user, memberships } = $props();

  let loading = $state(true);
  let error = $state(null);
  let jobs = $state([]);
  // Anchor for every relative time within a load, so the column reads
  // consistently.
  let now = $state(Date.now());

  let open = $state({}); // job-id → expanded?
  // `#/jobs?open=hourly-rewards` lands with that job's row expanded,
  // which is how the scenarios show the run they forced.
  const openFromHash = () => new URLSearchParams(location.hash.split("?")[1] ?? "").get("open");
  let busy = $state({}); // job-id → a run/edit is in flight

  // Kebab menu (one open at a time) and its anchor rect.
  let menuFor = $state(null);
  let menuAnchor = $state(null);

  // Schedule drawer working copy.
  let editing = $state(null);
  let draft = $state({ periodicity: "daily", monthlyDay: "first", time: "00:00" });
  let saving = $state(false);
  let saveError = $state(null);

  const kicker = $derived(memberships?.[0]?.["bank-name"]);
  const menuJob = $derived(jobs.find((j) => j.id === menuFor) ?? null);

  const draftJob = $derived({
    periodicity: draft.periodicity,
    "monthly-day": draft.monthlyDay,
    "run-time-minutes": minutesFromHHMM(draft.time) ?? 0,
  });

  function nextRun(job) {
    if (!job.enabled) return null;
    return job["next-run-at"] ?? nextRunAt(job, now);
  }

  function toView(job, runs) {
    const latest = runs[0] ?? null;
    return {
      id: job["job-id"],
      name: job.name,
      kind: job.kind ?? "user",
      isSystem: job.kind === "system",
      taskKinds: job["task-kinds"] ?? [],
      tasksLabel: (job["task-kinds"] ?? []).join(" → "),
      schedule: humanSchedule(job),
      cron: cronOf(job),
      periodicity: job.periodicity,
      monthlyDay: job["monthly-day"] ?? "first",
      allowedPeriodicities: job["allowed-periodicities"] ?? [job.periodicity],
      runTimeMinutes: job["run-time-minutes"],
      enabled: job.enabled,
      outcome: lastOutcome(latest),
      running: latest?.status === "running",
      latest,
      runs,
      lastAt: latest?.["started-at"] ?? job["last-run-at"] ?? null,
      nextAt: nextRun(job),
    };
  }

  async function load() {
    loading = true;
    error = null;
    now = Date.now();
    try {
      const res = await list_jobs();
      if (res.status < 200 || res.status >= 300) {
        error = res.body?.detail ?? `HTTP ${res.status}`;
        jobs = [];
        return;
      }
      const list = res.body?.items ?? [];
      jobs = await Promise.all(
        list.map(async (job) => {
          const runsRes = await list_job_runs(job["job-id"]);
          const runs =
            runsRes.status >= 200 && runsRes.status < 300
              ? (runsRes.body?.items ?? [])
              : [];
          return toView(job, runs);
        }),
      );
      const wanted = openFromHash();
      if (wanted) for (const j of jobs) if (`${j.id} ${j.name}`.includes(wanted)) open[j.id] = true;
    } catch (err) {
      error = err.message;
      jobs = [];
    } finally {
      loading = false;
    }
  }

  $effect(() => {
    load();
  });

  const stop = (e) => e.stopPropagation();

  function toggle(id) {
    open[id] = !open[id];
  }

  function onKey(e, id) {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      toggle(id);
    }
  }

  function openMenu(e, job) {
    e.stopPropagation();
    menuAnchor = e.currentTarget.getBoundingClientRect();
    menuFor = job.id;
  }

  function closeMenu() {
    menuFor = null;
    menuAnchor = null;
  }

  async function runNow(job) {
    if (job.running || busy[job.id]) return;
    busy[job.id] = true;
    try {
      const res = await force_start_job(job.id);
      // Reload first — it resets `error` — then surface the outcome so the
      // message survives. A rejected run (e.g. the daily-limit 429 on a
      // same-day re-accrual) still records a failed run server-side, so the
      // badge and history update too; the banner explains why.
      await load();
      if (res.status < 200 || res.status >= 300) {
        error = `${job.name} run failed — ${res.body?.detail ?? `HTTP ${res.status}`}`;
      }
    } catch (err) {
      error = err.message;
    } finally {
      busy[job.id] = false;
    }
  }

  async function pauseResume(job) {
    busy[job.id] = true;
    try {
      const res = await update_job_schedule(job.id, { enabled: !job.enabled });
      await load();
      if (res.status < 200 || res.status >= 300) {
        error = res.body?.detail ?? `HTTP ${res.status}`;
      }
    } catch (err) {
      error = err.message;
    } finally {
      busy[job.id] = false;
    }
  }

  function openEdit(job) {
    editing = job;
    draft = {
      periodicity: job.periodicity,
      monthlyDay: job.monthlyDay,
      time: hhmm(job.runTimeMinutes),
    };
    saveError = null;
  }

  function closeDrawer() {
    editing = null;
    saveError = null;
  }

  async function saveSchedule() {
    saving = true;
    saveError = null;
    try {
      const minutes = minutesFromHHMM(draft.time) ?? 0;
      // A system job's cadence is locked — send only the time. A user job
      // sends its cadence too, with the day-of-month when monthly.
      const body = editing.isSystem
        ? { "run-time-minutes": minutes }
        : {
            periodicity: draft.periodicity,
            "run-time-minutes": minutes,
            ...(draft.periodicity === "monthly"
              ? { "monthly-day": draft.monthlyDay }
              : {}),
          };
      const res = await update_job_schedule(editing.id, body);
      if (res.status >= 200 && res.status < 300) {
        closeDrawer();
        await load();
      } else {
        saveError = res.body?.detail ?? `Save failed (HTTP ${res.status})`;
      }
    } catch (err) {
      saveError = err.message;
    } finally {
      saving = false;
    }
  }

  function menuItems(job) {
    return [
      {
        label: "Run now",
        onClick: () => runNow(job),
        disabled: job.running || busy[job.id],
      },
      { label: "Edit schedule", onClick: () => openEdit(job), divider: true },
      {
        label: job.enabled ? "Pause schedule" : "Resume schedule",
        onClick: () => pauseResume(job),
        // System jobs can't be paused — the cadence is platform-fixed.
        disabled: job.isSystem || busy[job.id],
      },
    ];
  }
</script>

<PageHeader
  {kicker}
  title="Jobs"
  sub="Scheduled work the platform runs on a cadence. Each job runs an ordered sequence of predefined tasks — if one fails, the rest are skipped. All times UTC."
>
  {#snippet actions()}
    <Button variant="ghost" onclick={load}>Refresh</Button>
  {/snippet}
</PageHeader>

{#if error}
  <div class="alert" role="alert">{error}</div>
{/if}

{#if loading}
  <div class="loading">Loading…</div>
{:else if jobs.length === 0}
  <div class="empty">
    <p>No scheduled jobs.</p>
    <p class="hint">A bank's default jobs are seeded when the bank is provisioned.</p>
  </div>
{:else}
  <Table tree>
    <Thead>
      <Tr>
        <Th />
        <Th>Job</Th>
        <Th>Schedule</Th>
        <Th>Last run</Th>
        <Th>Next run</Th>
        <Th align="right">Actions</Th>
      </Tr>
    </Thead>
    <Tbody>
      {#each jobs as job (job.id)}
        <Tr
          expandable
          expanded={open[job.id]}
          onclick={() => toggle(job.id)}
          onkeydown={(e) => onKey(e, job.id)}
        >
          <Td expander><Expander /></Td>
          <Td emphasized>
            <span class="job-name">{job.name}<JobKindChip kind={job.kind} /></span>
            {#if job.tasksLabel}<span class="sub mono">{job.tasksLabel}</span>{/if}
          </Td>
          <Td>
            {job.schedule}
            <span class="sub mono">{job.cron}</span>
          </Td>
          <Td>
            <JobStatusBadge outcome={job.outcome} />
            {#if job.lastAt}
              <span class="sub mono">{fmtAbs(job.lastAt)} · {fmtRel(job.lastAt, now)}</span>
            {/if}
          </Td>
          <Td>
            {#if !job.enabled}
              <Badge tone="archived">paused</Badge>
            {:else if job.nextAt}
              {fmtRel(job.nextAt, now)}
              <span class="sub mono">{fmtAbs(job.nextAt)}</span>
            {:else}
              <span class="muted">—</span>
            {/if}
          </Td>
          <Td align="right">
            <div class="row-actions" onclick={stop} role="presentation">
              <Button
                variant="line"
                size="sm"
                disabled={job.running || busy[job.id]}
                onclick={(e) => {
                  stop(e);
                  runNow(job);
                }}
              >
                Run now
              </Button>
              <button
                class="kebab"
                type="button"
                aria-label="More actions"
                onclick={(e) => openMenu(e, job)}
              >
                <svg viewBox="0 0 16 16" aria-hidden="true">
                  <circle cx="8" cy="3" r="1.3" />
                  <circle cx="8" cy="8" r="1.3" />
                  <circle cx="8" cy="13" r="1.3" />
                </svg>
              </button>
            </div>
          </Td>
        </Tr>
        {#if open[job.id]}
          <Tr>
            <Td />
            <Td colspan="5">
              <div class="detail">
                <div class="detail-block">
                  <div class="detail-head">
                    <span class="detail-title">Task pipeline — runs in sequence</span>
                    <span class="detail-meta mono">{job.tasksLabel}</span>
                  </div>
                  <TaskPipeline steps={pipelineSteps(job.taskKinds, job.latest)} />
                </div>

                <div class="detail-block">
                  <div class="detail-head">
                    <span class="detail-title">Recent runs</span>
                  </div>
                  {#if job.runs.length === 0}
                    <p class="muted">No runs yet.</p>
                  {:else}
                    <div class="runs">
                      {#each job.runs as run (run["run-id"])}
                        <!-- Hovering a run reveals what each of its tasks did
                             — which was slow, which failed, which never ran.
                             tabindex makes the same detail reachable by
                             keyboard, via :focus-within. -->
                        <!-- svelte-ignore a11y_no_noninteractive_tabindex -->
                        <!-- Focusable on purpose, and not interactive: the
                             tabstop exists so the task detail below is
                             reachable without a pointer. Without it the
                             detail would be hover-only. -->
                        <div
                          class="run"
                          class:current={run.status === "running"}
                          role="group"
                          aria-label="Run started {fmtAbs(run['started-at'])}"
                          tabindex="0"
                        >
                          <div class="run-row">
                            <span class="r-when mono">{fmtAbs(run["started-at"])}</span>
                            <span class="r-dur mono">
                              {run.status === "running"
                                ? fmtRel(run["started-at"], now)
                                : (fmtElapsed(run["started-at"], run["finished-at"]) ?? "—")}
                            </span>
                            <span class="r-status">
                              <JobStatusBadge outcome={run.status} />
                              {#if run.status === "failed" && run["current-task"]}
                                <span class="ft mono">at {run["current-task"]}</span>
                              {/if}
                            </span>
                            <span class="r-trigger mono">{run["trigger-source"]}</span>
                          </div>
                          {#if (run.tasks ?? []).length}
                            <div class="run-tasks">
                              <TaskPipeline steps={runPipelineSteps(run)} dense />
                            </div>
                          {/if}
                        </div>
                      {/each}
                    </div>
                  {/if}
                </div>
              </div>
            </Td>
          </Tr>
        {/if}
      {/each}
    </Tbody>
  </Table>
{/if}

{#if menuJob}
  <Menu anchor={menuAnchor} items={menuItems(menuJob)} onClose={closeMenu} />
{/if}

<Drawer
  open={editing != null}
  onClose={closeDrawer}
  kicker="Schedule"
  title={editing?.name}
  sub={editing?.isSystem
    ? "System job. The platform fixes the cadence; you can set the time of day."
    : "Choose how often the job runs and at what UTC time."}
  width={460}
>
  {#if editing}
    {#if editing.isSystem}
      <div class="locked-note">
        🔒 System job — the cadence is fixed by the platform. You can change
        the time it fires, but not how often.
      </div>
      <Field label="Frequency">
        <Select disabled value={editing.periodicity}>
          <option value={editing.periodicity}>{freqLabel(editing.periodicity)} (fixed)</option>
        </Select>
      </Field>
      {#if editing.periodicity === "monthly"}
        <Field label="Day of month">
          <Select disabled value={editing.monthlyDay}>
            <option value={editing.monthlyDay}>
              {editing.monthlyDay === "last" ? "Last day" : "First day"} (fixed)
            </option>
          </Select>
        </Field>
      {/if}
    {:else}
      <Field label="Frequency">
        <Select bind:value={draft.periodicity}>
          {#each editing.allowedPeriodicities as p (p)}
            <option value={p}>{freqLabel(p)}</option>
          {/each}
        </Select>
      </Field>
      {#if draft.periodicity === "monthly"}
        <Field label="Day of month" hint="Fires on the first or last day each month.">
          <Select bind:value={draft.monthlyDay}>
            <option value="first">First day</option>
            <option value="last">Last day</option>
          </Select>
        </Field>
      {/if}
    {/if}

    <Field label="Time of day" hint="When the job fires each scheduled day (UTC).">
      <Input type="time" step="60" affix="UTC" bind:value={draft.time} />
    </Field>

    <div class="preview">
      <div class="preview-row">
        <span class="preview-label">cron</span>
        <code>{cronOf(draftJob)}</code>
      </div>
      {#each nextRuns(draftJob, now, 3) as t (t)}
        <div class="preview-row">
          <span class="mono">{fmtAbs(t)}</span>
          <span class="muted">{fmtRel(t, now)}</span>
        </div>
      {/each}
    </div>

    {#if saveError}
      <div class="alert" role="alert">{saveError}</div>
    {/if}
  {/if}

  {#snippet footer()}
    <Button variant="primary" size="lg" block disabled={saving} onclick={saveSchedule}>
      {saving ? "Saving…" : "Save schedule"}
    </Button>
  {/snippet}
</Drawer>

<style>
  .alert {
    padding: 12px 16px;
    border: 1px solid var(--rule);
    border-radius: 6px;
    background: var(--surface-sunk);
    color: var(--fg);
    font-size: 14px;
  }
  .loading,
  .empty {
    padding: 48px 16px;
    text-align: center;
    color: var(--fg-muted);
    border: 1px dashed var(--rule);
    border-radius: 12px;
  }
  .empty p { margin: 0; }
  .empty .hint { margin-top: 6px; font-size: 13px; }

  .sub {
    display: block;
    margin-top: 3px;
    font-size: 12px;
    color: var(--fg-muted);
  }
  .sub.mono,
  .mono { font-family: var(--mono); }
  .muted { color: var(--fg-muted); }

  .job-name { display: inline-flex; align-items: center; gap: 8px; }

  /* Drawer notice for a locked (system) job. */
  .locked-note {
    padding: 10px 12px;
    border: 1px solid var(--rule-2);
    border-radius: 8px;
    background: var(--surface-sunk);
    color: var(--fg-2);
    font-size: 12.5px;
    line-height: 1.45;
  }

  .row-actions {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    justify-content: flex-end;
  }
  .kebab {
    width: 28px;
    height: 26px;
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
  .kebab:hover { background: var(--hover-overlay); color: var(--fg); }
  .kebab svg { width: 15px; height: 15px; fill: currentColor; }

  /* Expanded detail — indented to align under the Job column. */
  .detail {
    display: flex;
    flex-direction: column;
    gap: 22px;
    padding: 8px 4px 14px;
  }
  .detail-head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 12px;
  }
  .detail-title {
    font-family: var(--grotesk);
    font-size: 13px;
    font-weight: 500;
    color: var(--fg);
  }
  .detail-meta { font-size: 12px; color: var(--fg-muted); }

  .runs {
    display: flex;
    flex-direction: column;
    border: 1px solid var(--rule-2);
    border-radius: 8px;
    overflow: hidden;
    background: var(--surface-raised);
  }
  .run { border-bottom: 1px solid var(--rule-2); }
  .run:last-child { border-bottom: none; }
  .run:focus { outline: none; }
  .run:focus-visible { outline: 2px solid var(--fg-muted); outline-offset: -2px; }
  .run-row {
    display: grid;
    grid-template-columns: 190px 96px 1fr auto;
    gap: 14px;
    align-items: center;
    padding: 10px 14px;
    font-size: 12.5px;
  }
  .run.current { background: light-dark(oklch(0.97 0.02 84), oklch(0.30 0.04 80)); }

  /* Per-task detail, revealed inline on hover. Hidden rather than
     collapsed: the row is already inside an expanded job panel, and a
     second nested expand is one level too many. */
  .run-tasks { display: none; }
  .run:hover .run-tasks,
  .run:focus-within .run-tasks { display: block; padding: 0 14px 11px 14px; }
  .run-row .r-when { font-size: 12px; color: var(--fg-2); }
  .run-row .r-dur {
    font-size: 12px;
    color: var(--fg-muted);
    font-variant-numeric: tabular-nums;
  }
  .run-row .r-status { display: flex; align-items: center; gap: 9px; }
  .run-row .r-status .ft { font-size: 11px; color: var(--danger); }
  .run-row .r-trigger { justify-self: end; font-size: 11px; color: var(--fg-muted); }

  /* Drawer preview — live cron + next fire times. */
  .preview {
    border: 1px solid var(--rule-2);
    border-radius: 8px;
    background: var(--surface-sunk);
    padding: 12px 14px;
    display: flex;
    flex-direction: column;
    gap: 7px;
  }
  .preview-row {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 12px;
    font-size: 12.5px;
  }
  .preview-label {
    font-family: var(--mono);
    font-size: 11px;
    color: var(--fg-muted);
    text-transform: uppercase;
    letter-spacing: 0.06em;
  }
  .preview code {
    font-family: var(--mono);
    font-size: 12px;
    color: var(--fg);
  }
</style>
