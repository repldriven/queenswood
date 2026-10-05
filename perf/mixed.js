// Internal and outbound payments from a fresh bank's accounts, mixed in
// one arrival rate, one outbound in FOLLOW followed until it completes.
// See docs/tdd/performance-testing.md.

import { check, sleep } from "k6";
import exec from "k6/execution";
import { Counter, Trend } from "k6/metrics";
import {
  bankTokenSource,
  env,
  get,
  KNOWN_WITHIN_S,
  post,
  postUntilKnown,
} from "./lib/api.js";
import { build } from "./lib/bank.js";
import { amountOut, checkBooks } from "./lib/books.js";
import { chosen, options as loadOptions, stepNow, steps } from "./lib/load.js";
import { settlementThresholds, summary } from "./lib/summary.js";

const PROFILES = {
  smoke: { accounts: 10, steps: [[1, "1m"]] },
  challenger: {
    accounts: 200,
    steps: [
      [50, "1h"],
      [150, "5m"],
    ],
  },
  knee: {
    accounts: 200,
    steps: [20, 40, 80, 160, 320].map((r) => [r, "1m"]),
  },
};

const [PROFILE, profile] = chosen(PROFILES);
const STEPS = steps(profile);
const ACCOUNTS = parseInt(env("ACCOUNTS", profile.accounts));

// The share of payments sent outbound, the rest internal.
const OUTBOUND_SHARE = parseFloat(env("OUTBOUND_SHARE", "0.5"));

// An address under a sort code no member of the scheme holds, so the
// payment leaves the provider and nothing comes back.
const CREDITOR_BBAN = "20000012345678";

const FOLLOW = 20;

const SETTLE_TIMEOUT_S = 120;

// Payments are 1p to £1, and every account is funded for twice its share
// of the run.
const MAX_AMOUNT = 100;
const PAYMENTS = STEPS.reduce((n, s) => n + s.rate * s.seconds, 0);
const FUNDING = 2 * MAX_AMOUNT * (Math.ceil(PAYMENTS / ACCOUNTS) + 1);

const payments = new Counter("payments");
const rejected = new Counter("payments_rejected");
const followed = new Counter("followed");
const unsettled = new Counter("unsettled");
const settleTime = new Trend("settle_time", true);

export const options = loadOptions("mixed", STEPS, profile, {
  // An iteration may ask until its payment is known, then follow it.
  gracefulStop: `${KNOWN_WITHIN_S + SETTLE_TIMEOUT_S}s`,
  thresholds: settlementThresholds(),
});

export async function setup() {
  return build(ACCOUNTS, FUNDING);
}

// Internal payments move money between the bank's accounts and every
// accepted outbound leaves it, so the books end at what setup injected
// less what was paid out.
export function teardown(bank) {
  checkBooks(bank);
}

let bearer = null;

// Polls the payment with backoff until it reaches a final status.
function follow(paymentId, sent) {
  followed.add(1);
  const deadline = sent + SETTLE_TIMEOUT_S * 1000;
  let wait = 0.05;
  while (Date.now() < deadline) {
    sleep(wait);
    const res = get(`/v1/payments/outbound/${paymentId}`, bearer(), {
      tags: { name: "payments/outbound/{payment-id}", phase: "follow" },
    });
    const status = res.status === 200 ? res.json()["payment-status"] : null;
    if (status === "completed") {
      settleTime.add(Date.now() - sent);
      return;
    }
    if (status === "failed" || status === "returned") {
      unsettled.add(1, { reason: status });
      return;
    }
    wait = Math.min(wait * 1.5, 1);
  }
  unsettled.add(1, { reason: "timeout" });
}

function pick(n) {
  return Math.floor(Math.random() * n);
}

function internal(bank, step) {
  const n = bank.accounts.length;
  const d = pick(n);
  const c = (d + 1 + pick(n - 1)) % n;
  const res = post(
    "/v1/payments/internal",
    {
      "debtor-account-id": bank.accounts[d],
      "creditor-account-id": bank.accounts[c],
      currency: "GBP",
      amount: 1 + pick(MAX_AMOUNT),
      reference: "Perf",
    },
    bearer(),
    { tags: { name: "payments/internal", step } },
  );
  payments.add(1, { step, kind: "internal" });
  if (!check(res, { "internal payment settled": (r) => r.status === 201 })) {
    rejected.add(1, { status: String(res.status), step, kind: "internal" });
  }
}

function outbound(bank, step) {
  const sent = Date.now();
  const amount = 1 + pick(MAX_AMOUNT);
  const res = postUntilKnown(
    "/v1/payments/outbound",
    {
      "debtor-account-id": bank.accounts[pick(bank.accounts.length)],
      "creditor-bban": CREDITOR_BBAN,
      "creditor-name": "Perf Payee",
      currency: "GBP",
      amount,
      scheme: "fps",
      reference: "Perf",
    },
    bearer,
    { tags: { name: "payments/outbound", step } },
  );
  payments.add(1, { step, kind: "outbound" });
  if (!check(res, { "outbound payment accepted": (r) => r.status === 201 })) {
    rejected.add(1, { status: String(res.status), step, kind: "outbound" });
    return;
  }
  amountOut.add(amount);
  if (exec.scenario.iterationInTest % FOLLOW === 0) {
    follow(res.json()["payment-id"], sent);
  }
}

export default function (bank) {
  if (!bearer) bearer = bankTokenSource(bank);
  const step = String(stepNow(STEPS));
  if (Math.random() < OUTBOUND_SHARE) {
    outbound(bank, step);
  } else {
    internal(bank, step);
  }
}

export function handleSummary(data) {
  return summary(data, {
    scenario: "mixed",
    profile: PROFILE,
    accounts: ACCOUNTS,
    steps: STEPS,
    follow: FOLLOW,
    outboundShare: OUTBOUND_SHARE,
  });
}
