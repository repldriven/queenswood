// A fresh bank's daily interest run, scheduled a few minutes ahead and
// followed to its end, while internal payments between the same accounts
// run at a fixed rate. See docs/tdd/performance-testing.md.

import { check } from "k6";
import exec from "k6/execution";
import { Counter, Gauge } from "k6/metrics";
import { bankTokenSource, env, expect, get, post, put, until } from "./lib/api.js";
import { build } from "./lib/bank.js";
import { chosen, options as loadOptions, stepNow, steps } from "./lib/load.js";
import { INTEREST_TASKS, interestThresholds, summary } from "./lib/summary.js";

const PROFILES = {
  smoke: { accounts: 100, steps: Array(5).fill([5, "1m"]) },
  challenger: { accounts: 1000, steps: Array(8).fill([50, "1m"]) },
};

const [PROFILE, profile] = chosen(PROFILES);
const STEPS = steps(profile);
const ACCOUNTS = parseInt(env("ACCOUNTS", profile.accounts));

// The product's annual rate, in basis points.
const RATE_BPS = parseInt(env("RATE_BPS", "500"));

// How many minutes ahead the run is scheduled: the scheduler reconciles
// its triggers with the jobs every minute.
const LEAD_MIN = 3;

// How long the run may take, once due, before the scenario gives up.
const RUN_TIMEOUT_S = parseInt(env("RUN_TIMEOUT_S", "1800"));

// Payments are 1p to £1. Every account holds at least £1,000, so a day's
// interest at the product's rate is whole pence, and twice its share of
// the run's payments.
const MAX_AMOUNT = 100;
const PAYMENTS = STEPS.reduce((n, s) => n + s.rate * s.seconds, 0);
const FUNDING = Math.max(
  100000,
  2 * MAX_AMOUNT * (Math.ceil(PAYMENTS / ACCOUNTS) + 1),
);

const JOB = "daily-interest";

const payments = new Counter("payments");
const rejected = new Counter("payments_rejected");
const scheduledAt = new Gauge("interest_scheduled_s");
const startedAt = new Gauge("interest_started_s");
const finishedAt = new Gauge("interest_finished_s");
const succeeded = new Gauge("interest_succeeded");
const taskMs = new Gauge("interest_task_ms");
const taskProcessed = new Gauge("interest_task_processed");
const taskFailed = new Gauge("interest_task_failed");

const base = loadOptions("internal", STEPS, profile, {
  thresholds: interestThresholds(),
});

export const options = Object.assign(base, {
  scenarios: Object.assign(base.scenarios, {
    interest: {
      executor: "per-vu-iterations",
      vus: 1,
      iterations: 1,
      maxDuration: `${LEAD_MIN * 60 + RUN_TIMEOUT_S + 60}s`,
      exec: "interest",
      tags: { phase: "interest" },
    },
  }),
});

export function setup() {
  return build(ACCOUNTS, FUNDING, RATE_BPS);
}

function sinceStart(iso) {
  return (Date.parse(iso) - exec.scenario.startTime) / 1000;
}

function latestRun(bearer) {
  const res = get(`/v1/jobs/${JOB}/runs`, bearer(), {
    tags: { name: "jobs/{job-id}/runs" },
  });
  return res.status === 200 ? res.json().items[0] || null : null;
}

// Moves the job to LEAD_MIN minutes from now, then reads its runs until
// the one that started has ended.
export function interest(bank) {
  const bearer = bankTokenSource(bank);
  const now = new Date();
  const at = (now.getUTCHours() * 60 + now.getUTCMinutes() + LEAD_MIN) % 1440;
  expect(
    put(
      `/v1/jobs/${JOB}/schedule`,
      { "run-time-minutes": at, enabled: true },
      bearer(),
      { tags: { name: "jobs/{job-id}/schedule" } },
    ),
    200,
    "scheduling the interest run",
  );
  scheduledAt.add((Date.now() - exec.scenario.startTime) / 1000);
  const run = until(
    () => latestRun(bearer),
    (r) => r !== null && r.status !== "running",
    "the interest run",
    LEAD_MIN * 60 + RUN_TIMEOUT_S,
    5,
  );
  startedAt.add(sinceStart(run["started-at"]));
  finishedAt.add(sinceStart(run["finished-at"]));
  succeeded.add(run.status === "succeeded" ? 1 : 0);
  run.tasks.forEach((t) => {
    const tags = { task: t.label };
    if (t["started-at"] && t["finished-at"]) {
      taskMs.add(Date.parse(t["finished-at"]) - Date.parse(t["started-at"]), tags);
    }
    taskProcessed.add(t["records-processed"] || 0, tags);
    taskFailed.add(t["records-failed"] || 0, tags);
  });
}

let bearer = null;

function pair(n) {
  const d = Math.floor(Math.random() * n);
  return [d, (d + 1 + Math.floor(Math.random() * (n - 1))) % n];
}

export default function (bank) {
  if (!bearer) bearer = bankTokenSource(bank);
  const step = String(stepNow(STEPS));
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
    scenario: "interest",
    profile: PROFILE,
    books: false,
    accounts: ACCOUNTS,
    rateBps: RATE_BPS,
    tasks: INTEREST_TASKS,
    steps: STEPS,
  });
}
