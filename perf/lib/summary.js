// The end-of-run summary: the run's parameters, its headline figures and
// k6's data as one JSON document between markers on stdout, which
// `just perf-run` lifts out of the Job's log.

export const BEGIN = "==== perf summary begin ====";
export const END = "==== perf summary end ====";

// The statuses a rejected payment, or customer, is counted under: a
// policy cap, a failure, FDB contention, and no response at all.
export const REJECTIONS = ["429", "500", "503", "0"];

// The stages of onboarding a customer, which one lost is counted under.
export const STAGES = ["party", "session", "verified", "account", "opened"];

// The stages of opening an account, which one lost is counted under: the
// request, and a followed account opening at the provider.
export const OPENING = ["account", "opened"];

// Why a followed payment did not settle: a final status other than
// settled, or the follow timing out.
export const UNSETTLED = ["failed", "returned", "timeout"];

// `unit` is what a scenario sends one of per iteration, `payments`
// unless it says otherwise.
export function rejectionThresholds(unit) {
  const t = {};
  REJECTIONS.forEach((s) => {
    t[`${unit || "payments"}_rejected{status:${s}}`] = ["count>=0"];
  });
  return t;
}

// For the onboarding scenario, whose `customers_lost` counter k6 would
// refuse a threshold on anywhere else.
export function onboardingThresholds() {
  const t = {};
  STAGES.forEach((s) => {
    t[`customers_lost{stage:${s}}`] = ["count>=0"];
  });
  return t;
}

// For the accounts scenario, whose `accounts_lost` counter k6 would
// refuse a threshold on anywhere else.
export function openingThresholds() {
  const t = {};
  OPENING.forEach((s) => {
    t[`accounts_lost{stage:${s}}`] = ["count>=0"];
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

function step(data, s, i, unit) {
  const tag = `{phase:load,step:${i}}`;
  const count = values(data, `${unit}${tag}`).count || 0;
  return Object.assign(
    {
      asked: s.rate,
      achieved: round(count / s.seconds),
      [unit]: count,
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

// What the books held once they stopped moving, against what setup
// injected plus what the run sent in and less what it paid out: 1100,
// the controls and the provider's balances should each equal it, and
// the trial balance tie.
function books(data) {
  const v = (name) => values(data, name).value;
  if (v("books_injected") === undefined) return undefined;
  const amountIn = values(data, "books_amount_in").count || 0;
  const amountOut = values(data, "books_amount_out").count || 0;
  const expected = v("books_injected") + amountIn - amountOut;
  const tied = v("books_trial_balance_tied") === 1;
  return {
    injected: v("books_injected"),
    in: amountIn,
    out: amountOut,
    expected,
    cash: v("books_cash"),
    controls: v("books_controls"),
    provider: v("books_provider"),
    trialBalanceTied: tied,
    settleS: round(v("books_settle_s")),
    held:
      tied &&
      v("books_cash") === expected &&
      v("books_controls") === expected &&
      v("books_provider") === expected,
  };
}

// The customers a scenario onboarded: how many, how many it lost at
// each stage, and each stage's time, the person's think time excluded.
function onboarding(data) {
  if (values(data, "customers").count === undefined) return undefined;
  const lost = {};
  STAGES.forEach((s) => {
    const n = values(data, `customers_lost{stage:${s}}`).count;
    if (n) lost[s] = n;
  });
  return {
    onboarded: values(data, "onboarded").count || 0,
    lost,
    sessionReady: latency(values(data, "session_ready")),
    partyActive: latency(values(data, "party_active")),
    accountOpened: latency(values(data, "account_opened")),
    onboardTime: latency(values(data, "onboard_time")),
  };
}

// Each kind of read a scenario named, by its request's `name` tag, whose
// tagged duration k6 summarises only where a threshold names it.
export function readThresholds(names) {
  const t = {};
  names.forEach((n) => {
    t[`http_req_duration{phase:load,name:${n}}`] = ["max>=0"];
  });
  return t;
}

function reads(data, names) {
  if (!names) return undefined;
  const out = {};
  names.forEach((n) => {
    out[n] = latency(values(data, `http_req_duration{phase:load,name:${n}}`));
  });
  return out;
}

// The accounts a scenario opened: how many it followed, how many it lost
// at each stage, and the time from the request to opened.
function opening(data) {
  if (values(data, "accounts_followed").count === undefined) return undefined;
  const lost = {};
  OPENING.forEach((s) => {
    const n = values(data, `accounts_lost{stage:${s}}`).count;
    if (n) lost[s] = n;
  });
  return Object.assign(
    { followed: values(data, "accounts_followed").count, lost },
    latency(values(data, "account_opened")),
  );
}

function headline(data, run) {
  const unit = run.unit || "payments";
  const rejected = {};
  REJECTIONS.forEach((s) => {
    const n = values(data, `${unit}_rejected{status:${s}}`).count;
    if (n) rejected[s] = n;
  });
  return {
    [unit]: values(data, unit).count || 0,
    rejected,
    dropped: values(data, "dropped_iterations").count || 0,
    vus: values(data, "vus_max").max,
    settlement: settlement(data),
    books: books(data),
    onboarding: onboarding(data),
    opening: opening(data),
    reads: reads(data, run.reads),
    steps: run.steps.map((s, i) => step(data, s, i, unit)),
  };
}

export function summary(data, run) {
  const doc = Object.assign(
    { run, headline: headline(data, run), finished: new Date().toISOString() },
    data,
  );
  return { stdout: `\n${BEGIN}\n${JSON.stringify(doc)}\n${END}\n` };
}
