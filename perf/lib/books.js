// What a run leaves in the books: the bank's ledger read once the load
// has stopped, against the money its setup injected from outside, and
// the provider's balances for the bank's accounts against the same sum.

import http from "k6/http";
import { sleep } from "k6";
import { Gauge } from "k6/metrics";
import { env, expect, get, token } from "./api.js";

const MODULR_SIMULATOR_URL = env(
  "MODULR_SIMULATOR_URL",
  "http://queenswood-external-simulators-service:8081",
);

// Control accounts, by the code the API renders, whose balances sum
// the customers' and the bank's own funds.
const CONTROLS = ["2100", "2200", "2300", "3100"];

const CASH = "1100";

// How long the provider is given to catch up with the ledger.
const PROVIDER_TIMEOUT_S = 180;

const TEARDOWN = { phase: "teardown" };

const injectedG = new Gauge("books_injected");
const cashG = new Gauge("books_cash");
const controlsG = new Gauge("books_controls");
const tiedG = new Gauge("books_trial_balance_tied");
const providerG = new Gauge("books_provider");
const providerWaitG = new Gauge("books_provider_wait_s");
const heldG = new Gauge("books_held");

export function booksThresholds() {
  return { books_held: ["value==1"] };
}

function ledger(bearer) {
  const body = expect(
    get("/v1/ledger-accounts", bearer, { tags: TEARDOWN }),
    200,
    "reading the ledger",
  );
  const byCode = {};
  for (const a of body.items) {
    byCode[a["gl-code"]] = (a["posted-balance"] || {}).value || 0;
  }
  const gbp = (body["trial-balance"] || []).find((t) => t.currency === "GBP");
  return {
    cash: -(byCode[CASH] || 0),
    controls: CONTROLS.reduce((n, code) => n + (byCode[code] || 0), 0),
    tied: Boolean(gbp) && gbp.debit === gbp.credit,
  };
}

function bban(identifiers) {
  const scan = (identifiers || []).find((i) => i.type === "SCAN");
  return scan ? `${scan.sortCode}${scan.accountNumber}` : null;
}

function provider(bbans) {
  const res = http.get(`${MODULR_SIMULATOR_URL}/simulate/balances`, {
    tags: TEARDOWN,
  });
  if (res.status !== 200) return null;
  return res
    .json()
    .accounts.filter((a) => bbans.has(bban(a.identifiers)))
    .reduce((n, a) => n + Math.round(parseFloat(a.balance) * 100), 0);
}

// Reads the books once the load has stopped and records them as gauges
// the summary reports: the ledger at once, since an internal payment
// settles in its request, and the provider once it matches or the
// timeout passes, since its transfers are mirrored after.
export function checkBooks(bank) {
  const bearer = token(bank.clientId, bank.clientSecret).value;
  const books = ledger(bearer);
  const house = expect(
    get(`/v1/cash-accounts/${bank.ownFunds}`, bearer, { tags: TEARDOWN }),
    200,
    "reading the own-funds account",
  );
  const bbans = new Set(bank.bbans.concat([house.bban]));
  const started = Date.now();
  let held = provider(bbans);
  while (held !== bank.injected && Date.now() - started < PROVIDER_TIMEOUT_S * 1000) {
    sleep(2);
    held = provider(bbans);
  }
  injectedG.add(bank.injected);
  cashG.add(books.cash);
  controlsG.add(books.controls);
  tiedG.add(books.tied ? 1 : 0);
  providerG.add(held === null ? -1 : held);
  providerWaitG.add((Date.now() - started) / 1000);
  heldG.add(
    books.tied &&
      books.cash === bank.injected &&
      books.controls === bank.injected &&
      held === bank.injected
      ? 1
      : 0,
  );
}
