// A fresh test bank on the perf tier, with `accounts` opened accounts,
// each funded with `each` minor units where it is above zero, built over
// the API as the scenario fixtures build one.

import http from "k6/http";
import { sleep } from "k6";
import {
  adminToken,
  batch,
  env,
  expect,
  post,
  postRetried,
  token,
} from "./api.js";

export const ZYPHE_SIMULATOR_URL = env(
  "ZYPHE_SIMULATOR_URL",
  "http://queenswood-external-simulators-service:8086",
);

const BATCH = parseInt(env("SETUP_BATCH", "20"));

// The accounts are spread across at most this many verified parties, as
// each party takes a verification to set up.
const PARTIES = parseInt(env("PARTIES", "50"));

const SETUP = { phase: "setup" };

const ZYPHE_RUN = /[?&]zypheVr=([^&]+)/;

// The simulator's verification request a session's hand-off URL names.
export function zypheRun(url) {
  const run = ZYPHE_RUN.exec(url);
  if (!run) throw new Error(`no Zyphe run in ${url}`);
  return run[1];
}

function chunks(xs, n) {
  const out = [];
  for (let i = 0; i < xs.length; i += n) out.push(xs.slice(i, i + n));
  return out;
}

// GETs each item's resource until `done` holds of every one, a batch at
// a time, returning the bodies in the items' order.
function settleAll(items, pathOf, done, bearer, what, timeoutS) {
  const bodies = new Array(items.length);
  let pending = items.map((_, i) => i);
  const deadline = Date.now() + (timeoutS || 180) * 1000;
  while (pending.length > 0) {
    const still = [];
    for (const group of chunks(pending, BATCH)) {
      const res = batch(
        group.map((i) => ({ method: "GET", path: pathOf(items[i]) })),
        bearer,
        SETUP,
      );
      res.forEach((r, k) => {
        const i = group[k];
        const body = r.status === 200 ? r.json() : null;
        if (body && done(body)) bodies[i] = body;
        else still.push(i);
      });
    }
    pending = still;
    if (pending.length === 0) break;
    if (Date.now() > deadline) {
      throw new Error(`timed out waiting for ${pending.length} ${what}`);
    }
    sleep(1);
  }
  return bodies;
}

// POSTs each item a batch at a time, sending again under the same
// Idempotency-Key any the platform answers with 500 or 503, as it does
// while its consumers rebalance.
function postAll(items, pathOf, bodyOf, bearer, status, what) {
  const out = [];
  for (const group of chunks(items, BATCH)) {
    const reqs = group.map((x) => ({
      method: "POST",
      path: pathOf(x),
      body: bodyOf(x),
      key: crypto.randomUUID(),
    }));
    const res = new Array(reqs.length);
    let pending = reqs.map((_, i) => i);
    for (let attempt = 0; pending.length > 0; attempt++) {
      const sent = batch(
        pending.map((i) => reqs[i]),
        bearer,
        SETUP,
      );
      const again = [];
      sent.forEach((r, k) => {
        res[pending[k]] = r;
        if ((r.status === 500 || r.status === 503) && attempt < 5) {
          again.push(pending[k]);
        }
      });
      pending = again;
      if (pending.length > 0) sleep(10);
    }
    res.forEach((r) => out.push(expect(r, status, what)));
  }
  return out;
}

function createBank(admin) {
  const res = postRetried(
    "/v1/banks",
    {
      name: `Perf ${new Date().toISOString()}`,
      status: "test",
      tier: env("TIER", "perf"),
      currencies: ["GBP"],
      providers: { payment: "modulr", idv: "zyphe" },
    },
    admin,
    { tags: SETUP },
  );
  return expect(res, 201, "creating the bank");
}

function createProduct(bearer, rateBps) {
  const product = expect(
    post(
      "/v1/cash-account-products",
      {
        name: "Perf Current Account",
        "template-id": "tpl.00000000000000000000000001",
        currency: "GBP",
        "interest-rate-bps": rateBps || 0,
        "effective-from": "2026-01-01",
      },
      bearer,
      { tags: SETUP },
    ),
    201,
    "creating the product",
  );
  expect(
    post(
      `/v1/cash-account-products/${product["product-id"]}/versions/${product["version-id"]}/publish`,
      null,
      bearer,
      { tags: SETUP },
    ),
    200,
    "publishing the product",
  );
  return product["product-id"];
}

// `n` person parties, each verified at the simulator until it is active,
// returning their ids.
export function createParties(n, bearer) {
  const people = Array.from({ length: n }, (_, i) => ({
    "party-type": "person",
    "display-name": `Perf Person ${i}`,
    "legal-name": `Perf Person${i}`,
  }));
  const parties = postAll(
    people,
    () => "/v1/parties",
    (p) => p,
    bearer,
    201,
    "creating a party",
  ).map((p, i) => ({ id: p["party-id"], person: people[i] }));

  const sessions = postAll(
    parties,
    (p) => `/v1/parties/${p.id}/verification-sessions`,
    () => ({
      channel: "web",
      "return-url": "https://app.example.test/verified",
      email: `perf+${crypto.randomUUID()}@example.test`,
    }),
    bearer,
    202,
    "opening a verification session",
  );
  const ready = settleAll(
    parties.map((p, i) => ({
      party: p.id,
      session: sessions[i]["session-id"],
    })),
    (s) => `/v1/parties/${s.party}/verification-sessions/${s.session}`,
    (b) => b.status === "ready",
    bearer,
    "verification sessions to be ready",
  );
  for (const group of chunks(
    parties.map((_, i) => i),
    BATCH,
  )) {
    const res = http.batch(
      group.map((i) => {
        const run = zypheRun(ready[i]["hand-off"].url);
        return {
          method: "POST",
          url: `${ZYPHE_SIMULATOR_URL}/simulator/verification-requests/${run}/decision`,
          body: JSON.stringify({
            outcome: "match",
            givenNames: "Perf",
            familyName: `Person${i}`,
            dateOfBirth: "1970-01-01",
          }),
          params: {
            headers: { "Content-Type": "application/json" },
            tags: SETUP,
          },
        };
      }),
    );
    res.forEach((r) => {
      if (r.status !== 200) {
        throw new Error(`deciding a verification: ${r.status} ${r.body}`);
      }
    });
  }
  settleAll(
    parties,
    (p) => `/v1/parties/${p.id}`,
    (b) => b.status === "active",
    bearer,
    "parties to be active",
  );
  return parties.map((p) => p.id);
}

function openAccounts(n, partyIds, productId, bearer) {
  const accounts = postAll(
    Array.from({ length: n }, (_, i) => partyIds[i % partyIds.length]),
    () => "/v1/cash-accounts",
    (party) => ({
      "party-id": party,
      "product-id": productId,
      name: "Perf Current Account",
      currency: "GBP",
    }),
    bearer,
    201,
    "opening an account",
  ).map((a) => a["account-id"]);
  return settleAll(
    accounts,
    (a) => `/v1/cash-accounts/${a}`,
    (b) => b.status === "opened",
    bearer,
    "accounts to open",
  ).map((b) => ({ id: b["account-id"], bban: b.bban }));
}

// What the bank's own funds keep beyond what they pay into the accounts,
// as a share of it, for the interest and rewards they pay later.
const RESERVE = 0.1;

// The minor units `fund` credits the bank's own funds with.
function credited(accounts, each) {
  return each * accounts.length + Math.ceil(each * accounts.length * RESERVE);
}

// Pays `each` minor units into every account from the bank's own funds,
// after crediting those funds with an inbound transfer.
function fund(accounts, each, bearer) {
  const own = expect(
    postRetried(
      "/v1/simulate/inbound-transfer",
      { amount: credited(accounts, each), currency: "GBP" },
      bearer,
      { tags: SETUP },
    ),
    200,
    "crediting the bank's own funds",
  );
  postAll(
    accounts,
    () => "/v1/payments/internal",
    (account) => ({
      "debtor-account-id": own["account-id"],
      "creditor-account-id": account,
      currency: "GBP",
      amount: each,
      reference: "Funding",
    }),
    bearer,
    201,
    "funding an account",
  );
  return own["account-id"];
}

// A fresh bank and its current-account product, with no customers. The
// product pays `rateBps` a year, nothing when it is omitted.
export function freshBank(rateBps) {
  const bank = createBank(adminToken());
  const clientId = bank["client-id"];
  const clientSecret = bank["client-secret"];
  const bankToken = token(clientId, clientSecret);
  return {
    bankId: bank["bank-id"],
    clientId,
    clientSecret,
    productId: createProduct(bankToken.value, rateBps),
    token: bankToken,
  };
}

// Gives each of `accounts` `perAccount` transactions: rounds in which every
// account pays the next one minor unit, so every account sends and
// receives one payment a round.
export function history(accounts, perAccount, bearer) {
  for (let round = 0; round < perAccount; round++) {
    postAll(
      accounts.map((a, i) => [a, accounts[(i + 1) % accounts.length]]),
      () => "/v1/payments/internal",
      ([debtor, creditor]) => ({
        "debtor-account-id": debtor,
        "creditor-account-id": creditor,
        currency: "GBP",
        amount: 1,
        reference: "History",
      }),
      bearer,
      201,
      "making an account's history",
    );
  }
}

export function build(n, each, rateBps) {
  const { bankId, clientId, clientSecret, productId } = freshBank(rateBps);
  const bearer = token(clientId, clientSecret).value;
  const parties = createParties(Math.min(n, PARTIES), bearer);
  const opened = openAccounts(n, parties, productId, bearer);
  const accounts = opened.map((a) => a.id);
  const ownFunds = each > 0 ? fund(accounts, each, bearer) : null;
  return {
    bankId,
    clientId,
    clientSecret,
    ownFunds,
    injected: each > 0 ? credited(accounts, each) : 0,
    accounts,
    bbans: opened.map((a) => a.bban),
    token: token(clientId, clientSecret),
  };
}
