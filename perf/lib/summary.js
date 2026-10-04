// The end-of-run summary: the run's parameters, its headline figures and
// k6's data as one JSON document between markers on stdout, which
// `just perf-run` lifts out of the Job's log.

export const BEGIN = "==== perf summary begin ====";
export const END = "==== perf summary end ====";

// The statuses a rejected payment is counted under: a policy cap, a
// failure, FDB contention, and no response at all.
export const REJECTIONS = ["429", "500", "503", "0"];

// Why a followed payment did not settle: a final status other than
// settled, or the follow timing out.
export const UNSETTLED = ["failed", "returned", "timeout"];

export function rejectionThresholds() {
  const t = {};
  REJECTIONS.forEach((s) => {
    t[`payments_rejected{status:${s}}`] = ["count>=0"];
  });
  return t;
}

// For a scenario that follows payments, whose `unsettled` counter k6
// would refuse a threshold on anywhere else.
export function settlementThresholds() {
  const t = {};
  UNSETTLED.forEach((r) => {
    t[`unsettled{reason:${r}}`] = ["count>=0"];
  });
  return t;
}

function values(data, name) {
  return (data.metrics[name] || {}).values || {};
}

function round(x) {
  return x === undefined ? undefined : Math.round(x * 100) / 100;
}

function latency(v) {
  return { avg: round(v.avg), p50: round(v.med), p95: round(v["p(95)"]), p99: round(v["p(99)"]), max: round(v.max) };
}

function step(data, s, i) {
  const tag = `{phase:load,step:${i}}`;
  const count = values(data, `payments${tag}`).count || 0;
  return Object.assign(
    {
      asked: s.rate,
      achieved: round(count / s.seconds),
      payments: count,
      failed: round(values(data, `http_req_failed${tag}`).rate || 0),
    },
    latency(values(data, `http_req_duration${tag}`)),
  );
}

// The payments a scenario followed to settlement: how many, how many
// never settled by why, and the time from submit to settled.
function settlement(data) {
  const followed = values(data, "followed").count || 0;
  if (followed === 0) return undefined;
  const unsettled = {};
  Object.keys(data.metrics)
    .filter((k) => k.startsWith("unsettled{reason:"))
    .forEach((k) => {
      unsettled[k.slice("unsettled{reason:".length, -1)] = values(data, k).count;
    });
  return Object.assign(
    { followed, unsettled },
    latency(values(data, "settle_time")),
  );
}

function headline(data, run) {
  const rejected = {};
  REJECTIONS.forEach((s) => {
    const n = values(data, `payments_rejected{status:${s}}`).count;
    if (n) rejected[s] = n;
  });
  return {
    payments: values(data, "payments").count || 0,
    rejected,
    dropped: values(data, "dropped_iterations").count || 0,
    vus: values(data, "vus_max").max,
    settlement: settlement(data),
    steps: run.steps.map((s, i) => step(data, s, i)),
  };
}

export function summary(data, run) {
  const doc = Object.assign(
    { run, headline: headline(data, run), finished: new Date().toISOString() },
    data,
  );
  return { stdout: `\n${BEGIN}\n${JSON.stringify(doc)}\n${END}\n` };
}
