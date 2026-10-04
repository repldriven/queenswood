// The API as a load test calls it: tokens, requests with a fresh
// Idempotency-Key, and polling until a resource reaches a state.

import http from "k6/http";
import { sleep } from "k6";

export function env(name, fallback) {
  const v = __ENV[name];
  return v === undefined || v === "" ? fallback : v;
}

export const BASE_URL = env("BASE_URL", "http://queenswood-api-service:8080");

const TEST_AUDIENCE = "queenswood-api-test";

// A token is renewed this long before it expires, plus up to
// RENEW_SPREAD_MS more, so VUs sharing one renew it at different times.
const RENEW_MARGIN_MS = 30000;
const RENEW_SPREAD_MS = 300000;

export function token(clientId, clientSecret, scope) {
  const res = http.post(
    `${BASE_URL}/oauth/token`,
    {
      grant_type: "client_credentials",
      client_id: clientId,
      client_secret: clientSecret,
      scope: scope || TEST_AUDIENCE,
    },
    { tags: { name: "oauth/token", phase: "setup" } },
  );
  if (res.status !== 200) {
    throw new Error(`token refused for ${clientId}: ${res.status} ${res.body}`);
  }
  const body = res.json();
  return {
    value: body.access_token,
    expiresAt: Date.now() + body.expires_in * 1000,
  };
}

// The operator's token, from the client a local cluster's realm adds
// for load tests.
export function adminToken() {
  return token(
    env("ADMIN_CLIENT_ID", ""),
    env("ADMIN_CLIENT_SECRET", ""),
    `${TEST_AUDIENCE} realm-roles`,
  ).value;
}

// The bank's token from setup, which every VU starts with, so a run asks
// for one token rather than one per VU, as a client holding one token
// per process would. A VU renews its own copy before it expires.
export function bankTokenSource(bank) {
  let current = bank.token || null;
  const margin = RENEW_MARGIN_MS + Math.random() * RENEW_SPREAD_MS;
  return () => {
    if (!current || current.expiresAt - Date.now() < margin) {
      current = token(bank.clientId, bank.clientSecret);
    }
    return current.value;
  };
}

function headers(bearer, extra) {
  return Object.assign(
    {
      "Content-Type": "application/json",
      Authorization: `Bearer ${bearer}`,
    },
    extra || {},
  );
}

export function post(path, body, bearer, opts) {
  const o = opts || {};
  return http.post(`${BASE_URL}${path}`, body ? JSON.stringify(body) : null, {
    headers: headers(
      bearer,
      Object.assign({ "Idempotency-Key": crypto.randomUUID() }, o.headers),
    ),
    tags: o.tags,
  });
}

// `post` under one Idempotency-Key, sent again while the platform
// answers 500 or 503, as it does while its consumers rebalance.
export function postRetried(path, body, bearer, opts) {
  const o = opts || {};
  const key = crypto.randomUUID();
  let res;
  for (let attempt = 0; attempt < 6; attempt++) {
    res = post(path, body, bearer, {
      tags: o.tags,
      headers: Object.assign({ "Idempotency-Key": key }, o.headers),
    });
    if (res.status !== 500 && res.status !== 503) return res;
    sleep(10);
  }
  return res;
}

// Whether a payment's answer leaves its outcome unknown: no answer, a
// 409 while the first request with its key is still in flight, or a 5xx.
function unknown(res) {
  return res.status === 0 || res.status === 409 || res.status >= 500;
}

// How long a client keeps asking under one Idempotency-Key, and the
// longest wait between asks.
export const KNOWN_WITHIN_S = 180;
const KNOWN_WITHIN_MS = KNOWN_WITHIN_S * 1000;
const RETRY_MAX_S = 10;

// `post` sent again under its Idempotency-Key while its outcome is
// unknown, backing off, as the API's 5xx asks a client to: a payment
// the first request posted is answered with that request's response.
// Retries are tagged `phase: retry`, outside the load's figures.
export function postUntilKnown(path, body, bearer, opts) {
  const o = opts || {};
  const key = crypto.randomUUID();
  const deadline = Date.now() + KNOWN_WITHIN_MS;
  let tags = o.tags;
  let wait = 0.5;
  for (;;) {
    const res = post(path, body, bearer(), {
      tags,
      headers: Object.assign({ "Idempotency-Key": key }, o.headers),
    });
    if (!unknown(res) || Date.now() > deadline) return res;
    sleep(wait);
    wait = Math.min(wait * 2, RETRY_MAX_S);
    tags = Object.assign({}, o.tags, { phase: "retry" });
  }
}

export function get(path, bearer, opts) {
  const o = opts || {};
  return http.get(`${BASE_URL}${path}`, {
    headers: headers(bearer, o.headers),
    tags: o.tags,
  });
}

// Every request in `reqs` ({method, path, body}) at once, in order.
export function batch(reqs, bearer, tags) {
  return http.batch(
    reqs.map((r) => ({
      method: r.method,
      url: r.url || `${BASE_URL}${r.path}`,
      body: r.body ? JSON.stringify(r.body) : null,
      params: {
        headers: headers(
          bearer,
          r.method === "POST"
            ? { "Idempotency-Key": r.key || crypto.randomUUID() }
            : {},
        ),
        tags,
      },
    })),
  );
}

export function expect(res, status, what) {
  if (res.status !== status) {
    throw new Error(`${what}: ${res.status} ${res.body}`);
  }
  return res.json();
}

// Calls `read` every `everyS` seconds until `done` holds of its result,
// failing after `timeoutS`.
export function until(read, done, what, timeoutS, everyS) {
  const deadline = Date.now() + (timeoutS || 120) * 1000;
  for (;;) {
    const v = read();
    if (done(v)) return v;
    if (Date.now() > deadline) {
      throw new Error(`timed out waiting for ${what}: ${JSON.stringify(v)}`);
    }
    sleep(everyS || 0.5);
  }
}
