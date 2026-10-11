// The access log's view-model: what a call's status, duration, method
// and route say, and which of its ids a lookup matched. The page itself
// lives in console.

const WRITE_METHODS = new Set(["POST", "PUT", "PATCH", "DELETE"]);

// True for a method that may change a record.
export function isWrite(method) {
  return WRITE_METHODS.has(method);
}

// A status's class, 2, 4 or 5, and what it means for the call.
export function statusClass(status) {
  return Math.floor(status / 100);
}

export function statusWord(status) {
  if (status < 400) return "Succeeded";
  if (status < 500) return "Refused";
  return "Failed";
}

// A duration in microseconds as people read it: "<1 ms", "41 ms",
// "1.2 s", "30 s".
export function formatMicros(us) {
  const ms = us / 1000;
  if (ms < 1) return "<1 ms";
  if (ms < 1000) return `${Math.round(ms)} ms`;
  const s = ms / 1000;
  return `${s < 10 ? s.toFixed(1) : Math.round(s)} s`;
}

// A duration of a second or more reads as slow.
export function isSlow(us) {
  return us >= 1e6;
}

// A route's template split into its literal parts and its path ids, each
// `{ text }` or `{ name, id }`, the id from `pathIds` where it has one.
export function routeParts(route, pathIds = {}) {
  const parts = [];
  let last = 0;
  for (const m of route.matchAll(/\{([^}]+)\}/g)) {
    if (m.index > last) parts.push({ text: route.slice(last, m.index) });
    parts.push({ name: m[1], id: pathIds[m[1]] ?? m[0] });
    last = m.index + m[0].length;
  }
  if (last < route.length) parts.push({ text: route.slice(last) });
  return parts;
}

// Which of a call's ids `ref` matched across `entries`: "request id",
// "idempotency key and correlation id", and so on.
export function matchedAs(ref, entries) {
  const as = [
    ["request-id", "request id"],
    ["idempotency-key", "idempotency key"],
    ["correlation-id", "correlation id"],
  ]
    .filter(([k]) => entries.some((e) => e[k] === ref))
    .map(([, label]) => label);
  if (as.length < 3) return as.join(" and ");
  return `${as[0]}, ${as[1]} and ${as[2]}`;
}

// The groups a bank's access log can hold: the API document's tags, in
// order, less those whose calls never name a bank.
const NO_BANK_TAGS = new Set(["OAuth", "Companies"]);

export function bankTags(tags = []) {
  return tags.filter((t) => !NO_BANK_TAGS.has(t.name));
}
