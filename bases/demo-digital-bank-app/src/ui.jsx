// Shared pieces: the formatters, the icon set, the small chrome
// components every screen uses, and the copy the app carries for the
// products the bank publishes.

// £1,234.56; a negative takes the U+2212 minus, a credit a + when asked.
export const gbp = (n, { sign = false } = {}) => {
  const a = Math.abs(n).toLocaleString("en-GB", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
  return (n < 0 ? "−" : sign ? "+" : "") + "£" + a;
};

const DAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
const MONTHS = [
  "Jan",
  "Feb",
  "Mar",
  "Apr",
  "May",
  "Jun",
  "Jul",
  "Aug",
  "Sep",
  "Oct",
  "Nov",
  "Dec",
];

// "Mon 14 Sep".
export const dayLabel = (d) =>
  `${DAYS[d.getDay()]} ${d.getDate()} ${MONTHS[d.getMonth()]}`;

// "Sep 2026", from an RFC 3339 instant.
export const monthYear = (iso) => {
  const d = new Date(iso);
  return `${MONTHS[d.getMonth()]} ${d.getFullYear()}`;
};

// "+44 7700 900123", from the E.164 number the bank holds.
export const phoneLabel = (e164) =>
  (e164 || "").replace(/^\+44(\d{4})(\d+)$/, "+44 $1 $2");

// "4.10% AER", from basis points.
export const aer = (bps) => (bps / 100).toFixed(2) + "% AER";

// Inline SVG on a 24 grid, stroke 1.8 to 2.2.
export const Ic = {
  back: (
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M15 18l-6-6 6-6" />
    </svg>
  ),
  x: (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
    >
      <path d="M18 6L6 18M6 6l12 12" />
    </svg>
  ),
  chev: (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M9 18l6-6-6-6" />
    </svg>
  ),
  check: (
    <svg
      width="40"
      height="40"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.5"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M5 12l5 5L20 7" />
    </svg>
  ),
  tick: (
    <svg
      width="16"
      height="16"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="3"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M5 12l5 5L20 7" />
    </svg>
  ),
  up: (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M12 19V5M5 12l7-7 7 7" />
    </svg>
  ),
  swap: (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M7 16V4M3 8l4-4 4 4M17 8v12M13 16l4 4 4-4" />
    </svg>
  ),
  plus: (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.2"
      strokeLinecap="round"
    >
      <path d="M12 5v14M5 12h14" />
    </svg>
  ),
  home: (
    <svg
      width="22"
      height="22"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinejoin="round"
    >
      <path d="M3 11l9-7 9 7v9a1 1 0 01-1 1h-5v-6h-6v6H4a1 1 0 01-1-1z" />
    </svg>
  ),
  pay: (
    <svg
      width="22"
      height="22"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M4 12h16M13 5l7 7-7 7" />
    </svg>
  ),
  act: (
    <svg
      width="22"
      height="22"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M3 12h4l3-8 4 16 3-8h4" />
    </svg>
  ),
  me: (
    <svg
      width="22"
      height="22"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
    >
      <circle cx="12" cy="8" r="4" />
      <path d="M4 21c0-4 3.6-7 8-7s8 3 8 7" />
    </svg>
  ),
  copy: (
    <svg
      width="16"
      height="16"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
    >
      <rect x="9" y="9" width="11" height="11" rx="2" />
      <path d="M5 15V5a1 1 0 011-1h10" />
    </svg>
  ),
  face: (
    <svg
      width="44"
      height="44"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
    >
      <path d="M4 8V6a2 2 0 012-2h2M16 4h2a2 2 0 012 2v2M4 16v2a2 2 0 002 2h2M16 20h2a2 2 0 002-2v-2M9 10v1M15 10v1M12 10v4h-1M9 15.5c1.5 1.3 4.5 1.3 6 0" />
    </svg>
  ),
  del: (
    <svg
      width="26"
      height="26"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M21 6H9l-6 6 6 6h12a1 1 0 001-1V7a1 1 0 00-1-1zM18 9l-6 6M12 9l6 6" />
    </svg>
  ),
  cam: (
    <svg
      width="48"
      height="48"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <rect x="3" y="7" width="18" height="13" rx="2" />
      <path d="M8 7l1.5-3h5L16 7" />
      <circle cx="12" cy="13" r="3.5" />
    </svg>
  ),
  lock: (
    <svg
      width="14"
      height="14"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
    >
      <rect x="4" y="11" width="16" height="10" rx="2" />
      <path d="M8 11V7a4 4 0 018 0v4" />
    </svg>
  ),
};

// The top bar: a back chevron for a pushed step, × for the first step of
// a modal flow, and a 40px spacer where there is neither.
export function Top({ onBack, title, right, close }) {
  return (
    <div className="top">
      {onBack ? (
        <button className="ib" onClick={onBack} aria-label="Back">
          {close ? Ic.x : Ic.back}
        </button>
      ) : (
        <span style={{ width: 40 }}></span>
      )}
      <span className="t">{title}</span>
      {right || <span style={{ width: 40 }}></span>}
    </div>
  );
}

export function Field({ label, hint, style, children }) {
  return (
    <div className="field" style={style}>
      {label && <label>{label}</label>}
      {children}
      {hint && <div className="hint">{hint}</div>}
    </div>
  );
}

// What the bank refused, in red under the thing it refused.
export function Err({ children, center }) {
  return children ? (
    <p className="hint err" style={center ? { textAlign: "center" } : null}>
      {children}
    </p>
  ) : null;
}

export function Pad({ onKey }) {
  const k = ["1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0", "⌫"];
  return (
    <div className="pad">
      {k.map((x) => (
        <button key={x} onClick={() => onKey(x)}>
          {x === "⌫" ? Ic.del : x}
        </button>
      ))}
    </div>
  );
}

export function Sheet({ onClose, children }) {
  return (
    <div className="sheet" onClick={onClose}>
      <div onClick={(e) => e.stopPropagation()}>
        <div className="grab"></div>
        {children}
      </div>
    </div>
  );
}

export function Tabs({ tab, go }) {
  const t = [
    ["home", "Home", Ic.home],
    ["pay", "Pay", Ic.pay],
    ["activity", "Activity", Ic.act],
    ["me", "Me", Ic.me],
  ];
  return (
    <div className="tabs">
      {t.map(([id, l, ic]) => (
        <button
          key={id}
          className={"tab" + (tab === id ? " on" : "")}
          onClick={() => go(id)}
        >
          {ic}
          {l}
        </button>
      ))}
    </div>
  );
}

// A 300×56 sparkline of the points given, scaled to their own range.
export function Spark({ pts, color = "var(--lime)" }) {
  const w = 300,
    h = 56,
    mx = Math.max(...pts),
    mn = Math.min(...pts);
  const d = pts
    .map(
      (p, i) =>
        `${i ? "L" : "M"}${(i / (pts.length - 1)) * w},${h - 4 - ((p - mn) / (mx - mn || 1)) * (h - 8)}`,
    )
    .join(" ");
  return (
    <svg className="spark" viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none">
      <path
        d={d}
        fill="none"
        stroke={color}
        strokeWidth="2"
        strokeLinejoin="round"
      />
    </svg>
  );
}

// The copy the Open screen shows for each kind the bank publishes. The
// id, the name and the rate are the bank's, from the me read.
export const productCopy = (kind, rateBps) =>
  ({
    cur: {
      blurb: "Spend, get paid, pay bills. No monthly fee.",
      pts: [
        "Sort code and account number in seconds",
        "Free UK payments",
        "Card arrives in 3–5 days",
      ],
    },
    sav: {
      blurb: `Easy-access saver, ${aer(rateBps)} variable.`,
      pts: ["Withdraw any time", "Interest paid monthly", "From £1"],
    },
    fix: {
      blurb: `Lock in ${aer(rateBps)} for 12 months.`,
      pts: [
        "£1,000 minimum",
        "No withdrawals until maturity",
        "FSCS protected",
      ],
    },
  })[kind] || { blurb: "", pts: [] };
