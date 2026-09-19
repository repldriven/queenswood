// The app's edge: the bank's routes, the session the app keeps, and the
// mapping from what /me answers onto the shape the screens read. Money
// arrives in minor units and times as RFC 3339; the screens take pounds
// and the labels the fixture data carried, so the conversion lives here
// and the screens stay as designed.
import { dayLabel, productCopy } from "./ui.jsx";

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

async function call(method, path, body) {
  const token = session.get();
  let res;
  try {
    res = await fetch(base + path, {
      method,
      headers: {
        ...(body ? { "content-type": "application/json" } : {}),
        ...(token ? { authorization: "Bearer " + token } : {}),
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
    ...when(t.at),
    ref: t.ref,
  })),
  payees: me.payees,
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
