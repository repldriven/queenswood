// Internal payments between a fresh bank's accounts, sent at a fixed
// arrival rate in steps. See docs/tdd/performance-testing.md.

import { check } from "k6";
import { Counter } from "k6/metrics";
import { bankTokenSource, env, post } from "./lib/api.js";
import { build } from "./lib/bank.js";
import { checkBooks } from "./lib/books.js";
import { chosen, options as loadOptions, stepNow, steps } from "./lib/load.js";
import { summary } from "./lib/summary.js";

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
    steps: [20, 40, 80, 160, 320].map((r) => [r, "1m"]),
  },
  hot: {
    mode: "hot",
    accounts: 50,
    steps: [1, 2, 5, 10, 20, 40].map((r) => [r, "1m"]),
  },
};

const [PROFILE, profile] = chosen(PROFILES);
const STEPS = steps(profile);
const ACCOUNTS = parseInt(env("ACCOUNTS", profile.accounts));

// Payments are 1p to £1. Every account is funded for twice the run's
// worst case, the hot account's every payment included.
const MAX_AMOUNT = 100;
const PAYMENTS = STEPS.reduce((n, s) => n + s.rate * s.seconds, 0);
const FUNDING =
  2 *
  MAX_AMOUNT *
  (profile.mode === "hot" ? PAYMENTS : Math.ceil(PAYMENTS / ACCOUNTS) + 1);

const payments = new Counter("payments");
const rejected = new Counter("payments_rejected");

export const options = loadOptions("internal", STEPS, profile);

export async function setup() {
  return build(ACCOUNTS, FUNDING);
}

// Money only moves between the bank's accounts once setup has injected
// it, so the books end where setup left them.
export function teardown(bank) {
  checkBooks(bank);
}

function pair(n) {
  if (profile.mode === "hot")
    return [0, 1 + Math.floor(Math.random() * (n - 1))];
  const d = Math.floor(Math.random() * n);
  return [d, (d + 1 + Math.floor(Math.random() * (n - 1))) % n];
}

let bearer = null;

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
    scenario: "internal",
    profile: PROFILE,
    mode: profile.mode,
    accounts: ACCOUNTS,
    steps: STEPS,
  });
}
