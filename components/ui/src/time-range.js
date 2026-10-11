// A log's time filter: a preset relative to now, or a custom range of
// UTC instants, `from` inclusive and `to` exclusive, either open. The
// rail section is TimeRangeFilter.svelte; these turn a range into what
// the API takes, what the URL holds and what a chip says.

const HOUR = 3600e3;
const DAY = 86400e3;

// Each preset's URL key, its rail label, its chip label and how far back
// it reaches.
export const TIME_PRESETS = [
  { key: "1h", short: "Hour", label: "Last hour", span: HOUR },
  { key: "24h", short: "24 hours", label: "Last 24 hours", span: DAY },
  { key: "7d", short: "7 days", label: "Last 7 days", span: 7 * DAY },
];

const preset = (key) => TIME_PRESETS.find((p) => p.key === key);

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const DAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
const pad = (n) => String(n).padStart(2, "0");
const dayKey = (ms) => new Date(ms).toISOString().slice(0, 10);

// An instant to the second, as the API and the URL take it.
export function isoSeconds(ms) {
  return new Date(ms).toISOString().replace(/\.\d{3}Z$/, "Z");
}

// A range's instants now: `{ from, to }`, epoch milliseconds or null. A
// preset reaches back from `now`, with no end.
export function rangeBounds(range, now = Date.now()) {
  if (!range) return { from: null, to: null };
  if (range.preset) return { from: now - preset(range.preset).span, to: null };
  return { from: range.from ?? null, to: range.to ?? null };
}

// The range a URL's query holds: `range` for a preset, else `from` and
// `to`; null for none.
export function rangeFromParams(params) {
  const key = params.get("range");
  if (preset(key)) return { preset: key };
  const from = Date.parse(params.get("from") ?? "");
  const to = Date.parse(params.get("to") ?? "");
  if (Number.isNaN(from) && Number.isNaN(to)) return null;
  return {
    from: Number.isNaN(from) ? null : from,
    to: Number.isNaN(to) ? null : to,
  };
}

// The URL query parts that hold `range`: a preset stays relative.
export function rangeParams(range) {
  if (!range) return [];
  if (range.preset) return [`range=${range.preset}`];
  return [
    ...(range.from != null ? [`from=${isoSeconds(range.from)}`] : []),
    ...(range.to != null ? [`to=${isoSeconds(range.to)}`] : []),
  ];
}

// The UTC day `range` covers exactly, as its "YYYY-MM-DD", or null.
export function rangeDay(range) {
  if (!range || range.preset || range.from == null || range.to == null) return null;
  return range.to - range.from === DAY && range.from % DAY === 0 ? dayKey(range.from) : null;
}

// The range covering the UTC day `key`, "YYYY-MM-DD".
export function dayRange(key) {
  const from = Date.parse(`${key}T00:00:00Z`);
  return { from, to: from + DAY };
}

function point(ms) {
  const d = new Date(ms);
  return `${d.getUTCDate()} ${MONTHS[d.getUTCMonth()]} ${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`;
}

function hhmm(ms) {
  const d = new Date(ms);
  return `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`;
}

// What a chip says: "Last 24 hours", "Sat 10 Oct", "10 Oct 14:00–15:00",
// "From 10 Oct 14:00", "Until …". Times are UTC.
export function rangeLabel(range) {
  if (!range) return "";
  if (range.preset) return preset(range.preset).label;
  const { from, to } = range;
  if (rangeDay(range)) {
    const d = new Date(from);
    return `${DAYS[d.getUTCDay()]} ${d.getUTCDate()} ${MONTHS[d.getUTCMonth()]}`;
  }
  if (from != null && to != null) {
    return dayKey(from) === dayKey(to - 1)
      ? `${point(from)}–${hhmm(to)}`
      : `${point(from)} – ${point(to)}`;
  }
  return from != null ? `From ${point(from)}` : `Until ${point(to)}`;
}

// How an empty state names the range: "in the last hour", "between …".
export function rangePhrase(range) {
  if (!range) return "";
  if (range.preset) return `in the ${preset(range.preset).label.toLowerCase()}`;
  const { from, to } = range;
  if (from != null && to != null) return `between ${point(from)} and ${point(to)} UTC`;
  return from != null ? `since ${point(from)} UTC` : `before ${point(to)} UTC`;
}

// An instant as a datetime-local input shows it, in UTC, to the minute.
export function utcInputValue(ms) {
  return ms == null ? "" : new Date(ms).toISOString().slice(0, 16);
}

// A datetime-local input's value, read as UTC, or null when empty.
export function fromUtcInput(value) {
  return value ? Date.parse(`${value}:00Z`) : null;
}
