// Inbound payments to a fresh bank's accounts, sent by another bank over
// the scheme at the Modulr simulator at a fixed arrival rate in steps,
// one in FOLLOW followed until the account is credited. See
// docs/tdd/performance-testing.md.

import http from "k6/http";
import { check, sleep } from "k6";
import exec from "k6/execution";
import { Counter, Trend } from "k6/metrics";
import { bankTokenSource, env, get } from "./lib/api.js";
import { build } from "./lib/bank.js";
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
    abortAbove: 0.05,
    steps: [5, 10, 20, 40, 80, 160, 320].map((r) => [r, "1m"]),
  },
};

const [PROFILE, profile] = chosen(PROFILES);
const STEPS = steps(profile);
const ACCOUNTS = parseInt(env("ACCOUNTS", profile.accounts));

const MODULR_SIMULATOR_URL = env(
  "MODULR_SIMULATOR_URL",
  "http://queenswood-external-simulators-service:8081",
);

const FOLLOW = 20;

const SETTLE_TIMEOUT_S = 120;

// A followed payment goes to one of the first FOLLOWED accounts, which
// nothing else pays, so its arrival is the rise in that account's
// balance.
const FOLLOWED = Math.max(1, Math.floor(ACCOUNTS / 10));

// Payments are 1p to £1.
const MAX_AMOUNT = 100;

const payments = new Counter("payments");
const rejected = new Counter("payments_rejected");
const followed = new Counter("followed");
const unsettled = new Counter("unsettled");
const settleTime = new Trend("settle_time", true);

export const options = loadOptions("inbound", STEPS, profile, {
  gracefulStop: `${SETTLE_TIMEOUT_S}s`,
  thresholds: settlementThresholds(),
});

export async function setup() {
  if (ACCOUNTS <= FOLLOWED) {
    throw new Error(`ACCOUNTS must be above the ${FOLLOWED} followed`);
  }
  return build(ACCOUNTS, 0);
}

let bearer = null;

function posted(accountId) {
  const res = get(`/v1/cash-accounts/${accountId}/balances`, bearer(), {
    tags: { name: "cash-accounts/{account-id}/balances", phase: "follow" },
  });
  return res.status === 200 ? res.json()["posted-balance"].value : null;
}

// Polls the account's balance with backoff until it has risen by
// `amount`.
function follow(accountId, before, amount, sent) {
  followed.add(1);
  const deadline = sent + SETTLE_TIMEOUT_S * 1000;
  let wait = 0.05;
  while (Date.now() < deadline) {
    sleep(wait);
    const now = posted(accountId);
    if (now !== null && now >= before + amount) {
      settleTime.add(Date.now() - sent);
      return;
    }
    wait = Math.min(wait * 1.5, 1);
  }
  unsettled.add(1, { reason: "timeout" });
}

function send(bban, amount, step) {
  return http.post(
    `${MODULR_SIMULATOR_URL}/simulate/inbound-payment`,
    JSON.stringify({
      bban,
      amount: amount / 100,
      currency: "GBP",
      reference: "Perf",
      "debtor-name": "Perf Payer",
    }),
    {
      headers: { "Content-Type": "application/json" },
      tags: { name: "simulate/inbound-payment", step },
    },
  );
}

export default function (bank) {
  if (!bearer) bearer = bankTokenSource(bank.clientId, bank.clientSecret);
  const step = String(stepNow(STEPS));
  const amount = 1 + Math.floor(Math.random() * MAX_AMOUNT);
  const n = exec.scenario.iterationInTest;
  const followIt = n % FOLLOW === 0;
  const i = followIt
    ? Math.floor(n / FOLLOW) % FOLLOWED
    : FOLLOWED + Math.floor(Math.random() * (bank.accounts.length - FOLLOWED));
  const before = followIt ? posted(bank.accounts[i]) : null;
  const sent = Date.now();
  const res = send(bank.bbans[i], amount, step);
  payments.add(1, { step });
  if (!check(res, { "payment sent": (r) => r.status === 202 })) {
    rejected.add(1, { status: String(res.status), step });
    return;
  }
  if (followIt && before !== null) follow(bank.accounts[i], before, amount, sent);
}

export function handleSummary(data) {
  return summary(data, {
    scenario: "inbound",
    profile: PROFILE,
    accounts: ACCOUNTS,
    steps: STEPS,
    follow: FOLLOW,
    followed: FOLLOWED,
  });
}
