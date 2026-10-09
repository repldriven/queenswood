// Current accounts opened in a fresh bank at a fixed arrival rate, each
// for the next of the parties setup verified, in turn, and one in FOLLOW
// followed until it is opened at the provider. See
// docs/tdd/performance-testing.md.

import { check, sleep } from "k6";
import exec from "k6/execution";
import { Counter, Trend } from "k6/metrics";
import { bankTokenSource, env, get, postUntilKnown, token } from "./lib/api.js";
import { createParties, freshBank } from "./lib/bank.js";
import { chosen, options as loadOptions, stepNow, steps } from "./lib/load.js";
import { openingThresholds, summary } from "./lib/summary.js";

const PROFILES = {
  smoke: { steps: [[1, "1m"]] },
  challenger: { steps: [[50, "10m"]] },
  knee: { steps: [20, 40, 80, 160, 320].map((r) => [r, "1m"]) },
};

const [PROFILE, profile] = chosen(PROFILES);
const STEPS = steps(profile);

// The parties the accounts are opened for, each verified in setup.
const PARTIES = parseInt(env("PARTIES", "50"));

// One account in this many is followed until it is opened.
const FOLLOW = 20;

// How long a followed account may take to open before it is counted lost.
const OPEN_TIMEOUT_S = parseInt(env("OPEN_TIMEOUT_S", "120"));

const accounts = new Counter("accounts");
const rejected = new Counter("accounts_rejected");
const lost = new Counter("accounts_lost");
const followed = new Counter("accounts_followed");
const openTime = new Trend("account_opened", true);

export const options = loadOptions("accounts", STEPS, profile, {
  unit: "accounts",
  gracefulStop: `${OPEN_TIMEOUT_S}s`,
  thresholds: openingThresholds(),
});

export function setup() {
  const bank = freshBank();
  const bearer = token(bank.clientId, bank.clientSecret).value;
  return Object.assign(bank, { parties: createParties(PARTIES, bearer) });
}

let bearer = null;

// Polls the account with backoff until it is opened.
function follow(accountId, sent, step) {
  followed.add(1);
  const deadline = sent + OPEN_TIMEOUT_S * 1000;
  let wait = 0.05;
  while (Date.now() < deadline) {
    sleep(wait);
    const res = get(`/v1/cash-accounts/${accountId}`, bearer(), {
      tags: { name: "cash-accounts/{account-id}", phase: "follow" },
    });
    if (res.status === 200 && res.json().status === "opened") {
      openTime.add(Date.now() - sent);
      return;
    }
    wait = Math.min(wait * 1.5, 1);
  }
  lost.add(1, { stage: "opened", step });
}

export default function (bank) {
  if (!bearer) bearer = bankTokenSource(bank);
  const step = String(stepNow(STEPS));
  const n = exec.scenario.iterationInTest;
  accounts.add(1, { step });

  const sent = Date.now();
  const res = postUntilKnown(
    "/v1/cash-accounts",
    {
      "party-id": bank.parties[n % bank.parties.length],
      "product-id": bank.productId,
      name: "Perf Current Account",
      currency: "GBP",
    },
    bearer,
    { tags: { name: "cash-accounts", step } },
  );
  if (!check(res, { "account answered 201": (r) => r.status === 201 })) {
    rejected.add(1, { status: String(res.status), step });
    lost.add(1, { stage: "account", step });
    return;
  }
  if (n % FOLLOW === 0) follow(res.json()["account-id"], sent, step);
}

export function handleSummary(data) {
  return summary(data, {
    scenario: "accounts",
    profile: PROFILE,
    unit: "accounts",
    books: false,
    parties: PARTIES,
    openTimeoutS: OPEN_TIMEOUT_S,
    steps: STEPS,
  });
}
