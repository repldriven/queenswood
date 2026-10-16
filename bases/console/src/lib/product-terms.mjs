// A product version's terms as the version page edits them, and back.
// Pure: no fetches and no Svelte, so the page holds only layout.
//
// The form keeps amounts in minor units and rates in basis points, as
// the API does; inputs convert at their own edge. A relative period's
// start is a count of the schedule's unit, a fixed one's an ISO date.

import { CCY_SYMBOLS } from "@queenswood/ui/money.js";

export const MAX_BANDS = 10;
export const MAX_PERIODS = 12;

export const UNITS = [
  { value: "day", one: "day", many: "days" },
  { value: "week", one: "week", many: "weeks" },
  { value: "month", one: "month", many: "months" },
];

export const FREQUENCIES = [
  { value: "daily", label: "Daily" },
  { value: "weekly", label: "Weekly" },
  { value: "monthly", label: "Monthly" },
  { value: "quarterly", label: "Every 3 months" },
  { value: "half-yearly", label: "Every 6 months" },
  { value: "annually", label: "Annually" },
  { value: "at-close", label: "When the account closes" },
];

const MONTHS_EVERY = { monthly: 1, quarterly: 3, "half-yearly": 6, annually: 12 };

export const MONTH_NAMES = [
  "January", "February", "March", "April", "May", "June",
  "July", "August", "September", "October", "November", "December",
];

export const DAY_COUNTS = {
  "actual-365": "Actual/365",
  "actual-actual": "Actual/actual",
  "actual-360": "Actual/360",
};

// The calendars a payment may count its days on, every day by default.
export const CALENDARS = {
  none: "every day",
  weekends: "weekdays",
  GBLO: "London business days",
  EUTA: "TARGET business days",
};

// The calendars `template` offers a version in `currency`, every day
// first, keeping `current` where the version names one the template no
// longer lists.
export function offeredCalendars(template, currency, current) {
  const entry = (template?.["currency-calendars"] ?? []).find(
    (c) => c.currency === currency,
  );
  const offered = entry?.calendars ?? ["none"];
  return current && !offered.includes(current) ? [...offered, current] : offered;
}

export const CONVENTIONS = {
  unadjusted: "unadjusted",
  following: "following",
  "modified-following": "modified following",
  preceding: "preceding",
  "modified-preceding": "modified preceding",
};

// ---------------------------------------------------------------------------
// Numbers

export function pct(bps) {
  return `${((bps ?? 0) / 100).toFixed(2)}%`;
}

export function bpsFromPercent(text) {
  const n = Number(String(text).replace(/[%\s]/g, ""));
  return Number.isFinite(n) ? Math.round(n * 100) : null;
}

export function minorFromMajor(text) {
  const n = Number(String(text).replace(/[^0-9.-]/g, ""));
  return Number.isFinite(n) && String(text).trim() !== "" ? Math.round(n * 100) : null;
}

export function major(minor) {
  return minor == null ? "" : (minor / 100).toFixed(2);
}

export function money(minor, currency = "GBP") {
  const symbol = CCY_SYMBOLS?.[currency] ?? `${currency} `;
  const n = (minor ?? 0) / 100;
  const sign = n < 0 ? "-" : "";
  return `${sign}${symbol}${Math.abs(n).toLocaleString("en-GB", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`;
}

export function ordinal(n) {
  const s = n % 100 >= 11 && n % 100 <= 13 ? "th" : ["th", "st", "nd", "rd"][n % 10] ?? "th";
  return `${n}${s}`;
}

// ---------------------------------------------------------------------------
// Dates, as ISO `YYYY-MM-DD`, in UTC

function parseDay(iso) {
  const [y, m, d] = iso.split("-").map(Number);
  return { y, m, d };
}

function isoDay(y, m, d) {
  return `${String(y).padStart(4, "0")}-${String(m).padStart(2, "0")}-${String(d).padStart(2, "0")}`;
}

function lastDay(y, m) {
  return new Date(Date.UTC(y, m, 0)).getUTCDate();
}

// `iso` plus `n` of `unit`. Adding months keeps the day of the month,
// or the month's last day where it has fewer, as the platform does.
export function addUnits(iso, n, unit) {
  const { y, m, d } = parseDay(iso);
  if (unit === "month") {
    const total = y * 12 + (m - 1) + n;
    const ny = Math.floor(total / 12);
    const nm = (total % 12) + 1;
    return isoDay(ny, nm, Math.min(d, lastDay(ny, nm)));
  }
  const days = unit === "week" ? n * 7 : n;
  const t = new Date(Date.UTC(y, m - 1, d) + days * 86400000);
  return isoDay(t.getUTCFullYear(), t.getUTCMonth() + 1, t.getUTCDate());
}

// Whole months from `fromIso` to `toIso`, at least 1.
export function monthsBetween(fromIso, toIso) {
  const a = parseDay(fromIso);
  const b = parseDay(toIso);
  const months = (b.y - a.y) * 12 + (b.m - a.m) - (b.d < a.d ? 1 : 0);
  return Math.max(1, months);
}

export function longDate(iso) {
  if (!iso) return "";
  const { y, m, d } = parseDay(iso);
  return `${d} ${MONTH_NAMES[m - 1]} ${y}`;
}

export function todayIso() {
  return new Date().toISOString().slice(0, 10);
}

// ---------------------------------------------------------------------------
// Payment, as the page's sentence states it

// The sentence's three selects from a stored payment: `frequency`,
// `on` ("account" or a day of the month, 31 for the last) and `inMonth`.
// A payment the sentence cannot say, such as every two weeks, is
// `custom` and kept as it is.
export function paymentChoice(payment) {
  const kind = payment?.kind;
  if (kind === "at-close") return { frequency: "at-close", on: "account", inMonth: 1 };
  if (kind === "account-day") {
    const { unit, every } = payment;
    if (unit === "day" && every === 1) return { frequency: "daily", on: "account", inMonth: 1 };
    if (unit === "week" && every === 1) return { frequency: "weekly", on: "account", inMonth: 1 };
    if (unit === "month") {
      const frequency = Object.keys(MONTHS_EVERY).find((k) => MONTHS_EVERY[k] === every);
      if (frequency) return { frequency, on: "account", inMonth: 1 };
    }
  }
  if (kind === "calendar-day") {
    const frequency = Object.keys(MONTHS_EVERY).find(
      (k) => MONTHS_EVERY[k] === payment.every,
    );
    if (frequency) {
      return { frequency, on: payment["day-of-month"], inMonth: payment["in-month"] };
    }
  }
  return { frequency: "custom", on: "account", inMonth: 1 };
}

// The stored payment the sentence's selects describe, or `current`
// where the sentence still shows a custom payment.
export function paymentFrom(choice, current) {
  const { frequency, on, inMonth } = choice;
  if (frequency === "custom") return current;
  if (frequency === "daily") return { kind: "account-day", unit: "day", every: 1 };
  if (frequency === "weekly") return { kind: "account-day", unit: "week", every: 1 };
  if (frequency === "at-close") return { kind: "at-close" };
  const every = MONTHS_EVERY[frequency];
  if (on === "account") return { kind: "account-day", unit: "month", every };
  return {
    kind: "calendar-day",
    every,
    "day-of-month": Number(on),
    "in-month": every === 1 ? 1 : Number(inMonth) || 1,
  };
}

// Whether the sentence shows `on`, and whether it shows `starting in`.
export function paymentShows(choice) {
  const { frequency, on } = choice;
  const dated = !["daily", "weekly", "at-close", "custom"].includes(frequency);
  return {
    on: dated,
    inMonth: dated && frequency !== "monthly" && on !== "account",
  };
}

export function onLabel(frequency, on) {
  if (on === "account") {
    if (frequency === "monthly") return "each monthly anniversary of opening";
    if (frequency === "annually") return "each anniversary of opening";
    if (frequency === "quarterly") return "every third monthly anniversary";
    if (frequency === "half-yearly") return "every sixth monthly anniversary";
    return "the day the account opened";
  }
  return Number(on) >= 31 ? "the last day of the month" : `the ${ordinal(Number(on))}`;
}

function unitWords(unit, n) {
  const u = UNITS.find((x) => x.value === unit);
  return n === 1 ? u.one : `${n} ${u.many}`;
}

// The payment in a customer's words: "monthly, on the same date each
// month as the account opened".
export function describePayment(payment) {
  const kind = payment?.kind;
  if (kind === "at-close") return "paid when the account closes";
  if (kind === "account-day") {
    const { unit, every } = payment;
    if (unit === "day" && every === 1) return "paid daily";
    if (unit === "month" && every === 1) {
      return "paid monthly, on the same date each month as the account opened";
    }
    if (unit === "month" && every === 12) return "paid on each anniversary of opening";
    return `paid every ${unitWords(unit, every)} from the day the account opened`;
  }
  if (kind === "calendar-day") {
    const { every } = payment;
    const day = payment["day-of-month"] >= 31 ? "the last day" : `the ${ordinal(payment["day-of-month"])}`;
    if (every === 1) return `paid monthly, on ${day} of each month`;
    const month = MONTH_NAMES[payment["in-month"] - 1];
    if (every === 12) return `paid annually, on ${day} of ${month}`;
    return `paid every ${every} months, on ${day} of the month, from ${month}`;
  }
  return "";
}

// Payments a year, for the AER; at close compounds nothing within a
// year, so it counts as one.
export function paymentsPerYear(payment) {
  const kind = payment?.kind;
  if (kind === "account-day") {
    const { unit, every } = payment;
    if (unit === "day") return 365 / every;
    if (unit === "week") return 52 / every;
    return 12 / every;
  }
  if (kind === "calendar-day") return 12 / payment.every;
  return 1;
}

// The annual equivalent rate of `bps` paid `n` times a year, in basis
// points.
export function aer(bps, n) {
  const r = bps / 10000;
  return Math.round((Math.pow(1 + r / n, n) - 1) * 10000);
}

// ---------------------------------------------------------------------------
// The accrual grid

export function defaultInterest() {
  return {
    on: false,
    banding: "marginal",
    basis: "relative",
    unit: "month",
    bands: [0],
    periods: [{ start: 0, rates: [0] }],
    payment: { kind: "account-day", unit: "month", every: 1 },
    calendar: "none",
  };
}

export function addBand(interest) {
  if (interest.bands.length >= MAX_BANDS) return interest;
  const last = interest.bands.at(-1);
  return {
    ...interest,
    bands: [...interest.bands, last > 0 ? last * 2 : 100000],
    periods: interest.periods.map((p) => ({ ...p, rates: [...p.rates, p.rates.at(-1)] })),
  };
}

export function removeBand(interest, j) {
  if (j === 0 || interest.bands.length <= 1) return interest;
  return {
    ...interest,
    bands: interest.bands.filter((_, k) => k !== j),
    periods: interest.periods.map((p) => ({ ...p, rates: p.rates.filter((_, k) => k !== j) })),
  };
}

export function addPeriod(interest, effectiveFrom) {
  if (interest.periods.length >= MAX_PERIODS) return interest;
  const last = interest.periods.at(-1);
  const start =
    interest.basis === "relative"
      ? last.start + (interest.unit === "month" ? 12 : interest.unit === "week" ? 4 : 30)
      : addUnits(interest.periods.length === 1 ? effectiveFrom : last.start, 12, "month");
  return { ...interest, periods: [...interest.periods, { start, rates: [...last.rates] }] };
}

export function removePeriod(interest, i) {
  if (i === 0 || interest.periods.length <= 1) return interest;
  return { ...interest, periods: interest.periods.filter((_, k) => k !== i) };
}

// The grid under the other basis: a relative start becomes the date it
// falls on for an account opened on `effectiveFrom`, and a date the
// whole months it falls after it.
export function switchBasis(interest, basis, effectiveFrom) {
  if (basis === interest.basis) return interest;
  const periods = interest.periods.map((p, i) => {
    if (i === 0) return { ...p, start: basis === "relative" ? 0 : effectiveFrom };
    return {
      ...p,
      start:
        basis === "fixed"
          ? addUnits(effectiveFrom, p.start, interest.unit)
          : monthsBetween(effectiveFrom, p.start),
    };
  });
  return { ...interest, basis, unit: basis === "relative" ? "month" : interest.unit, periods };
}

// One period's rates in a customer's words.
export function describeRates(bands, rates, banding, currency) {
  if (rates.every((r) => r === rates[0])) return `${pct(rates[0])} on the whole balance`;
  const parts = rates.map((r, j) => {
    const from = bands[j];
    const to = bands[j + 1];
    if (banding === "whole-balance") {
      if (j === 0) return `${pct(r)} on the whole balance below ${money(to, currency)}`;
      if (to == null) return `${pct(r)} on all of it from ${money(from, currency)}`;
      return `${pct(r)} on all of it from ${money(from, currency)} to ${money(to, currency)}`;
    }
    if (j === 0) return `${pct(r)} on the first ${money(to, currency)}`;
    if (to == null) return `${pct(r)} above ${money(from, currency)}`;
    return `${pct(r)} from ${money(from, currency)} to ${money(to, currency)}`;
  });
  return parts.join(", ");
}

export function describePeriodStart(interest, period, i) {
  if (i === 0) return "from opening";
  if (interest.basis === "fixed") return `from ${longDate(period.start)}`;
  return `from ${unitWords(interest.unit, period.start)} after opening`;
}

// ---------------------------------------------------------------------------
// Limits

export function defaultLimit() {
  return { direction: "in", measure: "amount", max: 0, period: "month", anchor: "account" };
}

const PERIOD_WORDS = {
  payment: "payment",
  day: "day",
  week: "week",
  month: "month",
  quarter: "quarter",
  year: "year",
};

export function limitCounted(limit) {
  return !["payment", "day"].includes(limit.period);
}

export function describeLimit(limit, currency) {
  const dir = limit.direction === "in" ? "in" : "out";
  if (limit.period === "payment") {
    return `No single payment ${dir} over ${money(limit.max, currency)}.`;
  }
  const each = `each ${PERIOD_WORDS[limit.period]}`;
  const head =
    limit.measure === "count"
      ? `At most ${limit.max} payment${limit.max === 1 ? "" : "s"} ${dir} ${each}`
      : `Up to ${money(limit.max, currency)} paid ${dir} ${each}`;
  if (!limitCounted(limit)) return `${head}.`;
  if (limit.anchor === "calendar") {
    const m = limit["starts-month"] ?? 1;
    const d = limit["starts-day-of-month"] ?? 1;
    return `${head}, counted from ${d} ${MONTH_NAMES[m - 1]}.`;
  }
  return `${head}, counted from the opening day.`;
}

// ---------------------------------------------------------------------------
// The version, as the form holds it, and back

export function fromVersion(version) {
  const terms = version["interest-terms"];
  const interest = defaultInterest();
  if (terms) {
    const { accrual, payment } = terms;
    interest.on = true;
    interest.banding = accrual.banding;
    interest.basis = accrual.basis;
    interest.unit = accrual.unit ?? "month";
    interest.bands = [...accrual.bands];
    interest.periods = accrual.periods.map((p) => ({
      start: accrual.basis === "relative" ? p.after : p["from-on"],
      rates: [...p["rates-bps"]],
    }));
    const { calendar, ...kind } = payment;
    interest.payment = kind;
    interest.calendar = calendar ?? "none";
  }
  const reward = version["opening-reward"];
  return {
    productId: version["product-id"],
    versionId: version["version-id"],
    versionNumber: version["version-number"],
    status: version.status,
    productType: version["product-type"],
    templateId: version["template-id"],
    name: version.name ?? "",
    currency: version.currency,
    interest,
    dayCount: version["day-count"],
    businessDayConvention: version["business-day-convention"],
    minBalance: version["min-balance"] ?? 0,
    maxBalance: version["max-balance"] ?? null,
    paymentLimits: (version["payment-limits"] ?? []).map((l) => ({ ...l })),
    reward: { on: !!reward, amount: reward?.amount ?? 0 },
    effectiveFrom: version["effective-from"] ?? todayIso(),
    effectiveTo: version["effective-to"] ?? "",
  };
}

export function interestTerms(form) {
  const { interest, effectiveFrom } = form;
  if (!interest.on) return undefined;
  const relative = interest.basis === "relative";
  const accrual = {
    basis: interest.basis,
    banding: interest.banding,
    bands: [...interest.bands],
    periods: interest.periods.map((p, i) =>
      relative
        ? { after: i === 0 ? 0 : Number(p.start), "rates-bps": [...p.rates] }
        : { "from-on": i === 0 ? effectiveFrom : p.start, "rates-bps": [...p.rates] },
    ),
  };
  if (relative) accrual.unit = interest.unit;
  const payment = { ...interest.payment };
  if (payment.kind !== "at-close") payment.calendar = interest.calendar;
  return { accrual, payment };
}

function limitBody(limit) {
  const body = {
    direction: limit.direction,
    measure: limit.measure,
    max: limit.max,
    period: limit.period,
  };
  if (limitCounted(limit)) {
    body.anchor = limit.anchor ?? "account";
    if (body.anchor === "calendar") {
      body["starts-month"] = limit["starts-month"] ?? 1;
      body["starts-day-of-month"] = limit["starts-day-of-month"] ?? 1;
    }
  }
  if (limit.reason?.trim()) body.reason = limit.reason.trim();
  return body;
}

// The request body for the form: a draft update, or a draft opened from
// it with `overrides`.
export function toRequest(form, overrides = {}) {
  const body = {
    name: form.name.trim(),
    currency: form.currency,
    "effective-from": form.effectiveFrom,
    "min-balance": form.minBalance ?? 0,
    ...overrides,
  };
  if (form.templateId) body["template-id"] = form.templateId;
  if (form.effectiveTo) body["effective-to"] = form.effectiveTo;
  const terms = interestTerms({ ...form, effectiveFrom: body["effective-from"] });
  if (terms) body["interest-terms"] = terms;
  if (form.maxBalance != null) body["max-balance"] = form.maxBalance;
  if (form.paymentLimits.length) body["payment-limits"] = form.paymentLimits.map(limitBody);
  if (form.reward.on) body["opening-reward"] = { amount: form.reward.amount };
  return body;
}

// ---------------------------------------------------------------------------
// Sections, their summaries and their differences

export const SECTIONS = [
  { id: "product", title: "Product" },
  { id: "interest", title: "Interest" },
  { id: "term", title: "Term" },
  { id: "limits", title: "Limits" },
  { id: "rewards", title: "Rewards" },
  { id: "availability", title: "Availability" },
];

function sectionBody(form, id) {
  const body = toRequest(form);
  switch (id) {
    case "product":
      return { name: body.name, currency: body.currency };
    case "interest":
      return body["interest-terms"] ?? null;
    case "limits":
      return {
        min: body["min-balance"],
        max: body["max-balance"] ?? null,
        limits: body["payment-limits"] ?? [],
      };
    case "rewards":
      return body["opening-reward"] ?? null;
    case "availability":
      return { from: body["effective-from"], to: body["effective-to"] ?? null };
    default:
      return null;
  }
}

// The sections whose terms differ between two forms.
export function changedSections(form, earlier) {
  if (!earlier) return [];
  return SECTIONS.filter(
    ({ id }) => JSON.stringify(sectionBody(form, id)) !== JSON.stringify(sectionBody(earlier, id)),
  ).map(({ id }) => id);
}

export function summary(form, id) {
  const { interest, currency } = form;
  switch (id) {
    case "product":
      return `${currency} · ${form.productType ?? ""}`;
    case "interest": {
      if (!interest.on) return "no interest";
      const first = interest.periods[0].rates;
      const banded = interest.bands.length > 1;
      const stepped = interest.periods.length > 1;
      return [banded ? `from ${pct(Math.max(...first))}` : pct(first[0]), banded && "banded", stepped && `${interest.periods.length} periods`]
        .filter(Boolean)
        .join(" · ");
    }
    case "term":
      return "no term";
    case "limits": {
      const n = form.paymentLimits.length;
      return n ? `${n} payment limit${n === 1 ? "" : "s"}` : `min ${money(form.minBalance, currency)}`;
    }
    case "rewards":
      return form.reward.on ? money(form.reward.amount, currency) : "none";
    case "availability":
      return form.effectiveTo ? `${form.effectiveFrom} – ${form.effectiveTo}` : `from ${form.effectiveFrom}`;
    default:
      return "";
  }
}

// The version written out for a customer, one entry per section.
export function customerTerms(form) {
  const { interest, currency } = form;
  const out = [];
  if (interest.on) {
    const first = interest.periods[0].rates;
    const paid = describePayment(interest.payment);
    let text;
    if (first.length === 1 && interest.periods.length === 1) {
      const annual = aer(first[0], paymentsPerYear(interest.payment));
      text = `${pct(first[0])} a year gross (${pct(annual)} AER) on the whole balance. Earned daily and ${paid}.`;
    } else {
      const periods = interest.periods.map(
        (p, i) =>
          `${describeRates(interest.bands, p.rates, interest.banding, currency)} ${describePeriodStart(interest, p, i)}`,
      );
      text = `A year gross: ${periods.join("; then ")}. Earned daily and ${paid}.`;
    }
    out.push({ section: "Interest", text });
  } else {
    out.push({ section: "Interest", text: "This account pays no interest." });
  }
  const limits = form.paymentLimits.map((l) => describeLimit(l, currency));
  limits.push(`The balance can't go below ${money(form.minBalance, currency)}.`);
  if (form.maxBalance != null) {
    limits.push(`The balance can't go above ${money(form.maxBalance, currency)}.`);
  }
  out.push({ section: "Limits", text: limits.join(" ") });
  if (form.reward.on) {
    out.push({
      section: "Rewards",
      text: `A welcome reward of ${money(form.reward.amount, currency)} is paid when the account opens.`,
    });
  }
  out.push({
    section: "Availability",
    text: form.effectiveTo
      ? `Open to new accounts from ${longDate(form.effectiveFrom)} until ${longDate(form.effectiveTo)}.`
      : `Open to new accounts from ${longDate(form.effectiveFrom)}.`,
  });
  return out;
}

function capitalise(s) {
  return s ? s[0].toUpperCase() + s.slice(1) : s;
}

// A refusal's mono line: "422 · :cash-account-product/invalid-limit".
export function refusalType(res) {
  const type = res?.body?.type;
  return type ? `${res.status} · ${type}` : `${res?.status ?? ""}`;
}
