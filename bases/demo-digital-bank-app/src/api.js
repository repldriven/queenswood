// The app's edge: the bank's routes, the session the app keeps, and the
// mapping from what /me answers onto the shape the screens read. Money
// arrives in minor units and times as RFC 3339; the screens take pounds
// and the labels the fixture data carried, so the conversion lives here
// and the screens stay as designed.
import { dayLabel, gbp, productCopy } from "./ui.jsx";

const base = import.meta.env.VITE_API_URL || "http://localhost:8100";
const KEY = "xepha.session";

// The bearer the bank minted, kept across reloads.
export const session = {
  get: () => localStorage.getItem(KEY),
  set: (token) => localStorage.setItem(KEY, token),
  clear: () => localStorage.removeItem(KEY),
};

// A refusal, carrying the problem details the bank answered with. A 400
// is a shape the screens should never send, so its detail stays inside.
export class ApiError extends Error {
  constructor(status, problem) {
    super(
      status === 400
        ? "Something in what you entered isn't right."
        : problem?.detail ||
            problem?.title ||
            (status ? `The bank answered ${status}.` : "Can't reach the bank."),
    );
    this.status = status;
    this.type = problem?.type;
  }
}

async function call(method, path, body, key) {
  const token = session.get();
  let res;
  try {
    res = await fetch(base + path, {
      method,
      headers: {
        ...(body ? { "content-type": "application/json" } : {}),
        ...(token ? { authorization: "Bearer " + token } : {}),
        ...(key ? { "idempotency-key": key } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
    });
  } catch {
    throw new ApiError(0, null);
  }
  const json = res.status === 204 ? null : await res.json().catch(() => null);
  if (!res.ok) throw new ApiError(res.status, json);
  return json;
}

export const startSignUp = (phone) => call("POST", "/sign-up", { phone });
export const verifyCode = (id, code) =>
  call("POST", `/sign-up/${id}/code`, { code });
export const registerDetails = (id, details) =>
  call("POST", `/sign-up/${id}/details`, details);
export const choosePasscode = (id, passcode) =>
  call("POST", `/sign-up/${id}/passcode`, { passcode });
export const signIn = (phone, passcode) =>
  call("POST", "/sign-in", { phone, passcode });
export const signOut = () => call("POST", "/sign-out");
export const me = () => call("GET", "/me");

// The bank's event stream: what the customer is told, as they are told
// it. Read with fetch rather than EventSource, which cannot carry the
// session as a bearer. Each event's data is a notification —
// { id, kind, at, headline, detail, account } — handed to onEvent;
// a stream that drops is opened again after a moment. Returns a
// function that stops it.
export function events(onEvent) {
  let stopped = false;
  let controller = null;
  const parse = (block) => {
    const data = block
      .split("\n")
      .filter((l) => l.startsWith("data:"))
      .map((l) => l.slice(5).trim())
      .join("\n");
    if (!data) return null;
    try {
      return JSON.parse(data);
    } catch {
      return null;
    }
  };
  const open = async () => {
    while (!stopped) {
      controller = new AbortController();
      try {
        const res = await fetch(base + "/events", {
          headers: {
            accept: "text/event-stream",
            authorization: "Bearer " + session.get(),
          },
          signal: controller.signal,
        });
        if (res.status === 401) return;
        if (!res.ok || !res.body) throw new Error(`events ${res.status}`);
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          let at;
          while ((at = buffer.indexOf("\n\n")) >= 0) {
            const event = parse(buffer.slice(0, at));
            buffer = buffer.slice(at + 2);
            if (event) onEvent(event);
          }
        }
      } catch {
        // The stream dropped, or was stopped: either way, fall through.
      }
      if (!stopped) await new Promise((r) => setTimeout(r, 3000));
    }
  };
  open();
  return () => {
    stopped = true;
    controller?.abort();
  };
}

// A key minted once per submission, so a repeated tap is answered once.
export const idempotencyKey = () => crypto.randomUUID();
const minor = (pounds) => Math.round(pounds * 100);

// The payee's name checked against the one their bank holds:
// { outcome: "match" | "close-match" | "no-match" | "unavailable",
//   nameHeld }.
export const checkPayee = async ({ name, sort, num }) => {
  const r = await call("POST", "/payee-checks", {
    name,
    "sort-code": sort,
    "account-number": num,
  });
  return { outcome: r.outcome, nameHeld: r["name-held"] };
};

// The screens' pounds become minor units on the way out. A payee is one
// of the customer's by id, or a new name, sort code and account number.
export const pay = ({ from, payee, amt, ref }, key) =>
  call(
    "POST",
    "/payments",
    {
      from,
      payee:
        payee.id === "new"
          ? {
              name: payee.name,
              "sort-code": payee.sort,
              "account-number": payee.num,
            }
          : { id: payee.id },
      amount: minor(amt),
      ...(ref ? { reference: ref } : {}),
    },
    key,
  );
export const transfer = ({ from, to, amt }, key) =>
  call("POST", "/transfers", { from, to, amount: minor(amt) }, key);
export const openAccount = ({ productId, dep }, key) =>
  call(
    "POST",
    "/accounts",
    { "product-id": productId, ...(dep ? { deposit: minor(dep) } : {}) },
    key,
  );

const pounds = (minor) => minor / 100;
const KINDS = ["cur", "sav", "fix"];
const startOfDay = (d) =>
  new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();

// "Today, 08:12" grouped under "Today", the same for yesterday, and
// "Mon 14 Sep" for both otherwise.
const when = (iso) => {
  const d = new Date(iso);
  const ago = Math.round((startOfDay(new Date()) - startOfDay(d)) / 86400000);
  const date = ago === 0 ? "Today" : ago === 1 ? "Yesterday" : dayLabel(d);
  return {
    when: ago <= 1 ? `${date}, ${d.toTimeString().slice(0, 5)}` : date,
    date,
  };
};

// The me read as the screens take it.
export const fromMe = (me) => ({
  user: {
    first: me.user.first,
    last: me.user.last,
    phone: me.user.phone,
    verification: me.user.verification,
    memberSince: me.user["member-since"],
  },
  accounts: me.accounts.map((a) => ({
    id: a.id,
    kind: a.kind,
    name: a.name,
    type: a.type,
    bal: pounds(a.balance),
    sort: a.sort,
    num: a.num,
    spark: a.spark.map(pounds),
  })),
  txns: me.txns.map((t) => ({
    id: t.id,
    acct: t.acct,
    who: t.who,
    cat: t.cat,
    amt: pounds(t.amount),
    status: t.status,
    ...when(t.at),
    ref: t.ref,
  })),
  payees: me.payees.map((p) => ({
    id: p.id,
    name: p.name,
    sort: p.sort,
    num: p.num,
    last: p["last-paid-at"]
      ? `${gbp(pounds(p["last-paid-amount"]))} · ${when(p["last-paid-at"]).date.toLowerCase()}`
      : "never",
  })),
  products: me.products
    .map((p) => ({
      id: p.id,
      kind: p.kind,
      name: p.name,
      rateBps: p["rate-bps"],
      ...productCopy(p.kind, p["rate-bps"]),
    }))
    .sort((a, b) => KINDS.indexOf(a.kind) - KINDS.indexOf(b.kind)),
});
