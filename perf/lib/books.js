// What a run leaves in the books: the bank's ledger and the provider's
// balances for its accounts, read once they stop moving, against the
// money setup injected and the money the run moved across the bank's
// boundary, which k6 counts as it sends it.

import http from "k6/http";
import { sleep } from "k6";
import { Counter, Gauge } from "k6/metrics";
import { env, expect, get, token } from "./api.js";

const MODULR_SIMULATOR_URL = env(
  "MODULR_SIMULATOR_URL",
  "http://queenswood-external-simulators-service:8081",
);

// Control accounts, by the code the API renders, whose balances sum
// the customers' and the bank's own funds.
const CONTROLS = ["2100", "2200", "2300", "3100"];

const CASH = "1100";

// How long the books are given to stop moving once the load stops.
const SETTLE_TIMEOUT_S = 180;

// Two readings this far apart that agree, with the provider matching
// the ledger, are the books at rest.
const STEADY_S = 5;

const TEARDOWN = { phase: "teardown" };

// Minor units a run sent into the bank from outside, and out of it.
export const amountIn = new Counter("books_amount_in");
export const amountOut = new Counter("books_amount_out");

const injectedG = new Gauge("books_injected");
const cashG = new Gauge("books_cash");
const controlsG = new Gauge("books_controls");
const tiedG = new Gauge("books_trial_balance_tied");
const providerG = new Gauge("books_provider");
const settledG = new Gauge("books_settle_s");

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

function same(a, b) {
  return a.cash === b.cash && a.controls === b.controls && a.tied === b.tied;
}

// Reads the books once they stop moving — two readings STEADY_S apart
// that agree, the provider matching the ledger — or the timeout passes,
// and records them as gauges. Whether they held is the summary's to
// say, since only it sees what the run sent across the boundary.
export function checkBooks(bank) {
  const bearer = token(bank.clientId, bank.clientSecret).value;
  const bbans = new Set(bank.bbans);
  if (bank.ownFunds) {
    const house = expect(
      get(`/v1/cash-accounts/${bank.ownFunds}`, bearer, { tags: TEARDOWN }),
      200,
      "reading the own-funds account",
    );
    bbans.add(house.bban);
  }
  const started = Date.now();
  let books = ledger(bearer);
  let held = provider(bbans);
  for (;;) {
    if (Date.now() - started > SETTLE_TIMEOUT_S * 1000) break;
    sleep(STEADY_S);
    const next = ledger(bearer);
    const nextHeld = provider(bbans);
    const steady = same(books, next) && nextHeld === next.cash;
    books = next;
    held = nextHeld;
    if (steady) break;
  }
  injectedG.add(bank.injected);
  cashG.add(books.cash);
  controlsG.add(books.controls);
  tiedG.add(books.tied ? 1 : 0);
  providerG.add(held === null ? -1 : held);
  settledG.add((Date.now() - started) / 1000);
}
