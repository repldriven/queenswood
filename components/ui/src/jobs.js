// Scheduler view-model helpers — pure, presentation-only. The wire
// shape is the bank-api `/v1/jobs` Job: { periodicity, run-time-minutes,
// status, last-run-at, next-run-at, ... } where run-time-minutes is
// minutes past midnight (UTC) and periodicity is daily/monthly/yearly.
// The backend fixes the day for non-daily cadences (monthly → the 1st,
// yearly → 1 Jan), so the phrasing and cron below mirror that, not a
// free choice of day.

const MONTHS = [
  "Jan", "Feb", "Mar", "Apr", "May", "Jun",
  "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
];
const pad = (n) => String(n).padStart(2, "0");

// run-time-minutes → "HH:MM" (24h, UTC).
export function hhmm(minutes) {
  const m = minutes ?? 0;
  return `${pad(Math.floor(m / 60))}:${pad(m % 60)}`;
}

// Whether a monthly job fires on the last day (else the first). Defaults
// to first for non-monthly jobs or an unset value.
export function isLastDay(job) {
  return job["monthly-day"] === "last";
}

// Human phrasing: "Daily · 02:00 UTC" | "Monthly · 1st · 02:00 UTC" |
// "Monthly · last day · 02:00 UTC" | "Annually · 1 Jan · 02:00 UTC".
export function humanSchedule(job) {
  const t = `${hhmm(job["run-time-minutes"])} UTC`;
  switch (job.periodicity) {
    case "daily":
      return `Daily · ${t}`;
    case "monthly":
      return `Monthly · ${isLastDay(job) ? "last day" : "1st"} · ${t}`;
    case "yearly":
      return `Annually · 1 Jan · ${t}`;
    default:
      return t;
  }
}

// Standard 5-field unix cron mirroring the server's Quartz schedule —
// the form operators recognise. Monthly uses the Quartz `L` for last
// day. "m h * * *" | "m h 1 * *" | "m h L * *" | "m h 1 1 *".
export function cronOf(job) {
  const mins = job["run-time-minutes"] ?? 0;
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  switch (job.periodicity) {
    case "daily":
      return `${m} ${h} * * *`;
    case "monthly":
      return `${m} ${h} ${isLastDay(job) ? "L" : "1"} * *`;
    case "yearly":
      return `${m} ${h} 1 1 *`;
    default:
      return "";
  }
}

// Next fire time (epoch ms, UTC) strictly after `fromMs`, derived from
// the periodicity model. Used as a client-side fallback when the job
// carries no server-computed next-run-at (e.g. before the runner has
// registered its trigger).
export function nextRunAt(job, fromMs) {
  const mins = job["run-time-minutes"] ?? 0;
  const hh = Math.floor(mins / 60);
  const mm = mins % 60;
  const from = new Date(fromMs);
  const y = from.getUTCFullYear();
  const mo = from.getUTCMonth();
  const d = from.getUTCDate();
  if (job.periodicity === "daily") {
    let t = Date.UTC(y, mo, d, hh, mm);
    while (t <= fromMs) t += 86400000;
    return t;
  }
  if (job.periodicity === "monthly") {
    const last = isLastDay(job);
    for (let i = 0; i < 120; i++) {
      // Day 0 of the next month is the last day of this one.
      const t = last
        ? Date.UTC(y, mo + i + 1, 0, hh, mm)
        : Date.UTC(y, mo + i, 1, hh, mm);
      if (t > fromMs) return t;
    }
  }
  if (job.periodicity === "yearly") {
    for (let i = 0; i < 12; i++) {
      const t = Date.UTC(y + i, 0, 1, hh, mm);
      if (t > fromMs) return t;
    }
  }
  return null;
}

export function fmtDur(secs) {
  if (secs == null) return "—";
  if (secs < 60) return `${secs}s`;
  const m = Math.floor(secs / 60);
  const s = secs % 60;
  return s ? `${m}m ${pad(s)}s` : `${m}m`;
}

// "36ms" / "4.2s" / "2m 05s" — the span between a start and a finish.
// Takes the two timestamps rather than a seconds count, and keeps
// sub-second precision: a bank with a handful of accounts accrues in
// milliseconds, and fmtDur's whole seconds render all of that as a
// uniform "0s". Null when either end is missing.
export function fmtElapsed(startedAt, finishedAt) {
  const start = toMs(startedAt);
  const finish = toMs(finishedAt);
  if (start == null || finish == null) return null;
  const ms = Math.max(0, finish - start);
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
  return fmtDur(Math.round(ms / 1000));
}

// Coerce an API timestamp to epoch milliseconds, or null. bank-api
// serialises timestamps as ISO-8601 strings (the Timestamp schema's
// encode), not epoch-ms numbers, so anything doing arithmetic on them
// (fmtRel, durations) must normalise first or it gets NaN.
export function toMs(v) {
  if (v == null) return null;
  if (typeof v === "number") return v;
  const t = Date.parse(v);
  return Number.isNaN(t) ? null : t;
}

// "21 Apr · 02:00" (UTC), with the year appended only when it differs
// from the current one.
export function fmtAbs(v) {
  const ms = toMs(v);
  if (ms == null) return "—";
  const d = new Date(ms);
  const yr = d.getUTCFullYear();
  const yrPart = yr !== new Date().getUTCFullYear() ? ` ${yr}` : "";
  return `${d.getUTCDate()} ${MONTHS[d.getUTCMonth()]}${yrPart} · ${pad(
    d.getUTCHours(),
  )}:${pad(d.getUTCMinutes())}`;
}

// "in 14h" / "3d ago" — coarse relative phrasing against `nowMs`.
export function fmtRel(targetMs, nowMs) {
  const target = toMs(targetMs);
  if (target == null) return "—";
  const diff = target - nowMs;
  const abs = Math.abs(diff);
  const mins = abs / 60000;
  const hrs = mins / 60;
  const days = hrs / 24;
  let n;
  if (mins < 1) n = "moments";
  else if (mins < 60) n = `${Math.round(mins)}m`;
  else if (hrs < 48) n = `${Math.round(hrs)}h`;
  else if (days < 60) n = `${Math.round(days)}d`;
  else n = `${Math.round(days / 30)}mo`;
  return diff < 0 ? `${n} ago` : `in ${n}`;
}

// The badge state for a job: `running` while a run is in progress, else
// the latest run's outcome, else `scheduled` (never run yet). `run` is
// the newest run from `/v1/jobs/{id}/runs` (status one of
// running/completed/failed), or null when there are none.
export function lastOutcome(run) {
  if (!run) return "scheduled";
  if (run.status === "running") return "running";
  return run.status;
}

// Per-task pipeline view for a run. The API run carries only ordered
// progress (tasks-completed) and a status, not per-task records, so the
// task labels come from the job's ordered task-kinds and each node's
// state is derived: the tasks before the cursor are `ok`, the one at the
// cursor is `running`/`failed` by the run status, and the rest are
// `pending` (a live run) or `skipped` (a failed run — the first failure
// ends it). With no run yet, every task is `pending`.
export function pipelineSteps(taskKinds, run) {
  const tasks = taskKinds ?? [];
  if (!run) return tasks.map((name) => ({ name, status: "pending" }));
  const done = run["tasks-completed"] ?? 0;
  const { status } = run;
  return tasks.map((name, i) => {
    if (i < done) return { name, status: "ok" };
    if (i === done) {
      if (status === "failed") return { name, status: "failed" };
      if (status === "running") return { name, status: "running" };
      return { name, status: "ok" };
    }
    return { name, status: status === "failed" ? "skipped" : "pending" };
  });
}

const TASK_STATUS = {
  completed: "ok",
  failed: "failed",
  running: "running",
  skipped: "skipped",
};

// What a task did, as the line beneath its name: "5 processed · 121ms".
// A task the run never reached says so instead — it has no figures and
// no timings, and "0 processed" would read as work that found nothing.
function taskDetail(task) {
  if (task.status === "skipped") return "never ran";
  if (task.status === "running") return "running…";
  const parts = [];
  if (task["processed-count"] != null) {
    parts.push(`${Number(task["processed-count"]).toLocaleString()} processed`);
  }
  const elapsed = fmtElapsed(task["started-at"], task["finished-at"]);
  if (elapsed) parts.push(elapsed);
  return parts.length ? parts.join(" · ") : null;
}

// Pipeline nodes from a run's own per-task records. Unlike
// pipelineSteps, nothing here is inferred — the run states what each
// task did, including which ones it never reached, so this is the shape
// to use wherever a recorded run is in hand.
//
// Failed records ride on `alert` rather than into the detail line: a
// task can succeed while failing accounts, and that figure is the one
// an operator needs to see when everything else says the run was fine.
export function runPipelineSteps(run) {
  return (run?.tasks ?? []).map((task) => {
    const failed = task["failed-count"] ?? 0;
    const step = {
      name: task.label,
      status: TASK_STATUS[task.status] ?? "pending",
      detail: taskDetail(task),
    };
    if (failed > 0) step.alert = `${Number(failed).toLocaleString()} failed`;
    if (task["failure-reason"]) step.alert = task["failure-reason"];
    return step;
  });
}

// The next `n` fire times (epoch ms, UTC) strictly after `fromMs`, for
// the drawer's schedule preview. Walks `nextRunAt` forward.
export function nextRuns(job, fromMs, n) {
  const out = [];
  let from = fromMs;
  for (let i = 0; i < n; i += 1) {
    const t = nextRunAt(job, from);
    if (t == null) break;
    out.push(t);
    from = t;
  }
  return out;
}

// "HH:MM" → minutes past midnight, or null when unparseable.
export function minutesFromHHMM(s) {
  const [h, m] = (s ?? "").split(":").map(Number);
  if (Number.isNaN(h) || Number.isNaN(m)) return null;
  return h * 60 + m;
}
