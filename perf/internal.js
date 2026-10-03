// Internal payments between a fresh bank's accounts, sent at a fixed
// arrival rate in steps. See docs/tdd/performance-testing.md.

import { check } from "k6";
import exec from "k6/execution";
import { Counter } from "k6/metrics";
import { bankTokenSource, env, post } from "./lib/api.js";
import { build } from "./lib/bank.js";
import { rejectionThresholds, summary } from "./lib/summary.js";

// `spread`: every account pays a random other one. `hot`: the first
// account pays every other one.
const PROFILES = {
  smoke: { mode: "spread", accounts: 10, steps: [[1, "1m"]] },
  challenger: {
    mode: "spread",
    accounts: 200,
    steps: [
      [50, "1h"],
      [150, "5m"],
    ],
  },
  knee: {
    mode: "spread",
    accounts: 200,
    abortAbove: 0.05,
    steps: [5, 10, 20, 40, 80, 160, 320].map((r) => [r, "1m"]),
  },
  hot: {
    mode: "hot",
    accounts: 50,
    steps: [1, 2, 5, 10, 20, 40].map((r) => [r, "1m"]),
  },
};

const PROFILE = env("PROFILE", "smoke");
const profile = PROFILES[PROFILE];
if (!profile) throw new Error(`unknown PROFILE ${PROFILE}`);

function seconds(d) {
  const m = /^(\d+)(s|m|h)$/.exec(d);
  if (!m) throw new Error(`duration ${d} is not <n>s, <n>m or <n>h`);
  return parseInt(m[1]) * { s: 1, m: 60, h: 3600 }[m[2]];
}

// An overridden rate or duration runs as one-minute steps at that rate,
// so the summary shows whether a sustained rate holds.
function overridden() {
  const rate = parseInt(env("RATE", profile.steps[0][0]));
  const total = seconds(env("DURATION", profile.steps[0][1]));
  const steps = [];
  for (let left = total; left > 0; left -= 60) {
    steps.push({ rate, seconds: Math.min(60, left) });
  }
  return steps;
}

const STEPS =
  env("RATE") || env("DURATION")
    ? overridden()
    : profile.steps.map(([rate, d]) => ({ rate, seconds: seconds(d) }));

const ACCOUNTS = parseInt(env("ACCOUNTS", profile.accounts));

// A step reaches its rate over this many seconds, then holds it.
const RAMP = 5;

// Payments are 1p to £1. Every account is funded for twice the run's
// worst case, the hot account's every payment included.
const MAX_AMOUNT = 100;
const PAYMENTS = STEPS.reduce((n, s) => n + s.rate * s.seconds, 0);
const FUNDING =
  2 *
  MAX_AMOUNT *
  (profile.mode === "hot" ? PAYMENTS : Math.ceil(PAYMENTS / ACCOUNTS) + 1);

const TOP = Math.max(...STEPS.map((s) => s.rate));

const payments = new Counter("payments");
const rejected = new Counter("payments_rejected");

// A threshold per step, which nothing fails, so the summary carries
// each step's figures.
function stepThresholds() {
  const t = {};
  STEPS.forEach((_, i) => {
    const tag = `{phase:load,step:${i}}`;
    t[`http_req_duration${tag}`] = ["max>=0"];
    t[`http_req_failed${tag}`] = ["rate<=1"];
    t[`payments${tag}`] = ["count>=0"];
  });
  return t;
}

export const options = {
  setupTimeout: env("SETUP_TIMEOUT", "30m"),
  scenarios: {
    internal: {
      executor: "ramping-arrival-rate",
      startRate: STEPS[0].rate,
      timeUnit: "1s",
      preAllocatedVUs: Math.max(10, TOP * 2),
      maxVUs: Math.min(2000, Math.max(50, TOP * 10)),
      stages: STEPS.flatMap((s) => [
        { target: s.rate, duration: `${RAMP}s` },
        { target: s.rate, duration: `${s.seconds - RAMP}s` },
      ]),
      tags: { phase: "load" },
    },
  },
  thresholds: Object.assign(
    {
      "http_req_failed{phase:load}": [
        {
          threshold: `rate<${profile.abortAbove || 0.001}`,
          abortOnFail: Boolean(profile.abortAbove),
          delayAbortEval: "30s",
        },
      ],
      "http_req_duration{phase:load}": [{ threshold: "p(99)<1000", abortOnFail: false }],
    },
    stepThresholds(),
    rejectionThresholds(),
  ),
  summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "p(99)", "max"],
};

export async function setup() {
  return build(ACCOUNTS, FUNDING);
}

function stepNow() {
  let elapsed = (Date.now() - exec.scenario.startTime) / 1000;
  for (let i = 0; i < STEPS.length; i++) {
    if (elapsed < STEPS[i].seconds) return i;
    elapsed -= STEPS[i].seconds;
  }
  return STEPS.length - 1;
}

function pair(n) {
  if (profile.mode === "hot") return [0, 1 + Math.floor(Math.random() * (n - 1))];
  const d = Math.floor(Math.random() * n);
  return [d, (d + 1 + Math.floor(Math.random() * (n - 1))) % n];
}

let bearer = null;

export default function (bank) {
  if (!bearer) bearer = bankTokenSource(bank.clientId, bank.clientSecret);
  const step = String(stepNow());
  const [d, c] = pair(bank.accounts.length);
  const res = post(
    "/v1/payments/internal",
    {
      "debtor-account-id": bank.accounts[d],
      "creditor-account-id": bank.accounts[c],
      currency: "GBP",
      amount: 1 + Math.floor(Math.random() * MAX_AMOUNT),
      reference: "Perf",
    },
    bearer(),
    { tags: { name: "payments/internal", step } },
  );
  payments.add(1, { step });
  if (!check(res, { "payment settled": (r) => r.status === 201 })) {
    rejected.add(1, { status: String(res.status), step });
  }
}

export function handleSummary(data) {
  return summary(data, {
    scenario: "internal",
    profile: PROFILE,
    mode: profile.mode,
    accounts: ACCOUNTS,
    steps: STEPS,
  });
}
