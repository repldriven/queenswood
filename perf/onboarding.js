// New customers onboarded into a fresh bank at a fixed arrival rate: a
// person party, its identity verified at the simulator, and a current
// account opened at the provider. See docs/tdd/performance-testing.md.

import { check, sleep } from "k6";
import exec from "k6/execution";
import http from "k6/http";
import { Counter, Trend } from "k6/metrics";
import { bankTokenSource, env, get, postUntilKnown } from "./lib/api.js";
import { freshBank, ZYPHE_SIMULATOR_URL, zypheRun } from "./lib/bank.js";
import { chosen, options as loadOptions, stepNow, steps } from "./lib/load.js";
import { onboardingThresholds, summary } from "./lib/summary.js";

const PROFILES = {
  smoke: { steps: [[1, "1m"]] },
  challenger: { steps: [[10, "10m"]] },
  knee: { steps: [1, 2, 5, 10, 20, 40].map((r) => [r, "1m"]) },
};

const [PROFILE, profile] = chosen(PROFILES);
const STEPS = steps(profile);

// How long the person takes to verify, in seconds, which no stage's time
// includes.
const THINK_S = parseFloat(env("THINK_S", "0"));

// How long a stage may take before the customer is counted lost at it.
const STAGE_TIMEOUT_S = parseInt(env("STAGE_TIMEOUT_S", "120"));

const POLL_S = 0.25;

const customers = new Counter("customers");
const rejected = new Counter("customers_rejected");
const lost = new Counter("customers_lost");
const onboarded = new Counter("onboarded");
const sessionReady = new Trend("session_ready", true);
const partyActive = new Trend("party_active", true);
const accountOpened = new Trend("account_opened", true);
const onboardTime = new Trend("onboard_time", true);

export const options = loadOptions("onboarding", STEPS, profile, {
  unit: "customers",
  gracefulStop: `${4 * STAGE_TIMEOUT_S + THINK_S}s`,
  thresholds: onboardingThresholds(),
});

export function setup() {
  return freshBank();
}

// A national insurance number unique within the run, which is all a
// fresh bank needs.
function nationalInsurance() {
  const vu = String(exec.vu.idInTest).padStart(4, "0");
  const n = String(exec.vu.iterationInScenario).padStart(5, "0");
  return `TN${vu}${n}A`;
}

function person(familyName) {
  return {
    type: "person",
    "display-name": `Perf ${familyName}`,
    "given-name": "Perf",
    "family-name": familyName,
    "date-of-birth": "1970-01-01",
    nationality: "GB",
    address: {
      "building-number": "1",
      street: "Load Lane",
      town: "Testford",
      postcode: "TF1 1AA",
      country: "GBR",
    },
    "national-identifier": {
      type: "national-insurance",
      value: nationalInsurance(),
      "issuing-country": "GB",
    },
  };
}

// Reads `path` until `done` holds of its body, returning the body and
// how long it took in milliseconds, or null once STAGE_TIMEOUT_S passes.
function awaited(path, done, bearer, name) {
  const started = Date.now();
  const deadline = started + STAGE_TIMEOUT_S * 1000;
  for (;;) {
    const res = get(path, bearer(), { tags: { name, phase: "poll" } });
    const body = res.status === 200 ? res.json() : null;
    if (body && done(body)) return { body, ms: Date.now() - started };
    if (Date.now() > deadline) return null;
    sleep(POLL_S);
  }
}

// A request that did not answer `status`, counted as rejected under the
// status it answered and as a customer lost at `stage`.
function refused(res, status, stage, step) {
  if (check(res, { [`${stage} answered ${status}`]: (r) => r.status === status })) {
    return false;
  }
  rejected.add(1, { status: String(res.status), step });
  lost.add(1, { stage, step });
  return true;
}

let bearer = null;

export default function (bank) {
  if (!bearer) bearer = bankTokenSource(bank);
  const step = String(stepNow(STEPS));
  const started = Date.now();
  customers.add(1, { step });

  const familyName = `Customer${exec.vu.idInTest}x${exec.vu.iterationInScenario}`;
  const party = postUntilKnown("/v1/parties", person(familyName), bearer, {
    tags: { name: "parties", step },
  });
  if (refused(party, 201, "party", step)) return;
  const partyId = party.json()["party-id"];

  const session = postUntilKnown(
    `/v1/parties/${partyId}/verification-sessions`,
    {
      channel: "web",
      "return-url": "https://app.example.test/verified",
      email: `perf+${partyId}@example.test`,
    },
    bearer,
    { tags: { name: "parties/verification-sessions", step } },
  );
  if (refused(session, 202, "session", step)) return;
  const sessionId = session.json()["session-id"];

  const ready = awaited(
    `/v1/parties/${partyId}/verification-sessions/${sessionId}`,
    (b) => b.status === "ready",
    bearer,
    "parties/verification-sessions/{id}",
  );
  if (!ready) return lost.add(1, { stage: "session", step });
  sessionReady.add(ready.ms);

  sleep(THINK_S);
  const decided = http.post(
    `${ZYPHE_SIMULATOR_URL}/simulator/verification-requests/${zypheRun(ready.body["hand-off"].url)}/decision`,
    JSON.stringify({
      outcome: "match",
      givenNames: "Perf",
      familyName,
      dateOfBirth: "1970-01-01",
    }),
    {
      headers: { "Content-Type": "application/json" },
      tags: { name: "simulator/decision", phase: "simulator" },
    },
  );
  if (decided.status !== 200) return lost.add(1, { stage: "verified", step });

  const active = awaited(
    `/v1/parties/${partyId}`,
    (b) => b.status === "active",
    bearer,
    "parties/{id}",
  );
  if (!active) return lost.add(1, { stage: "verified", step });
  partyActive.add(active.ms);

  const account = postUntilKnown(
    "/v1/cash-accounts",
    {
      "party-id": partyId,
      "product-id": bank.productId,
      name: "Perf Current Account",
      currency: "GBP",
    },
    bearer,
    { tags: { name: "cash-accounts", step } },
  );
  if (refused(account, 201, "account", step)) return;

  const opened = awaited(
    `/v1/cash-accounts/${account.json()["account-id"]}`,
    (b) => b["account-status"] === "opened",
    bearer,
    "cash-accounts/{id}",
  );
  if (!opened) return lost.add(1, { stage: "opened", step });
  accountOpened.add(opened.ms);

  onboarded.add(1, { step });
  onboardTime.add(Date.now() - started - THINK_S * 1000);
}

export function handleSummary(data) {
  return summary(data, {
    scenario: "onboarding",
    profile: PROFILE,
    unit: "customers",
    books: false,
    thinkS: THINK_S,
    stageTimeoutS: STAGE_TIMEOUT_S,
    steps: STEPS,
  });
}
