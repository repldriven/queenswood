// A customer's app reading a fresh bank's accounts at a fixed arrival
// rate: an account with its balances, the first page of its transactions,
// or its balances, for accounts setup funded and gave a history. See
// docs/tdd/performance-testing.md.

import { Counter } from "k6/metrics";
import { bankTokenSource, env, get, token } from "./lib/api.js";
import { build, history } from "./lib/bank.js";
import { chosen, options as loadOptions, stepNow, steps } from "./lib/load.js";
import { readThresholds, summary } from "./lib/summary.js";

const PROFILES = {
  smoke: { accounts: 10, steps: [[1, "1m"]] },
  challenger: { accounts: 200, steps: [[200, "10m"]] },
  knee: {
    accounts: 200,
    steps: [40, 80, 160, 320, 640].map((r) => [r, "1m"]),
  },
};

const [PROFILE, profile] = chosen(PROFILES);
const STEPS = steps(profile);
const ACCOUNTS = parseInt(env("ACCOUNTS", String(profile.accounts)));

// The payments each account sends, and so receives, in setup.
const HISTORY = parseInt(env("HISTORY", "20"));

// Each read, by its request's `name` tag, and its share of the reads.
const READS = [
  ["cash-accounts/{account-id}?embed[balances]", 0.4],
  ["cash-accounts/{account-id}/transactions", 0.4],
  ["cash-accounts/{account-id}/balances", 0.2],
];

const PATHS = {
  "cash-accounts/{account-id}?embed[balances]": (a) =>
    `/v1/cash-accounts/${a}?embed[balances]=true`,
  "cash-accounts/{account-id}/transactions": (a) =>
    `/v1/cash-accounts/${a}/transactions?page[size]=20`,
  "cash-accounts/{account-id}/balances": (a) =>
    `/v1/cash-accounts/${a}/balances`,
};

const reads = new Counter("reads");
const rejected = new Counter("reads_rejected");

export const options = loadOptions("reads", STEPS, profile, {
  unit: "reads",
  thresholds: readThresholds(READS.map(([name]) => name)),
});

export function setup() {
  const bank = build(ACCOUNTS, 10000);
  history(bank.accounts, HISTORY, token(bank.clientId, bank.clientSecret).value);
  return bank;
}

function pick() {
  let r = Math.random();
  for (const [name, share] of READS) {
    if (r < share) return name;
    r -= share;
  }
  return READS[READS.length - 1][0];
}

let bearer = null;

export default function (bank) {
  if (!bearer) bearer = bankTokenSource(bank);
  const step = String(stepNow(STEPS));
  const account = bank.accounts[Math.floor(Math.random() * bank.accounts.length)];
  const name = pick();
  reads.add(1, { step });
  const res = get(PATHS[name](account), bearer(), { tags: { name, step } });
  if (res.status !== 200) rejected.add(1, { status: String(res.status), step });
}

export function handleSummary(data) {
  return summary(data, {
    scenario: "reads",
    profile: PROFILE,
    unit: "reads",
    books: false,
    accounts: ACCOUNTS,
    history: HISTORY,
    reads: READS.map(([name]) => name),
    steps: STEPS,
  });
}
