// Presentation mappings for a Companies House company profile (the
// shape returned by /v1/companies/{number}).

const TYPE_LABELS = {
  ltd: "Private limited company",
  plc: "Public limited company",
  llp: "Limited liability partnership",
  "private-limited-guarant-nsc": "Private company limited by guarantee",
  "private-limited-guarant-nsc-limited-exemption":
    "Private company limited by guarantee",
  "private-unlimited": "Private unlimited company",
  "limited-partnership": "Limited partnership",
  "old-public-company": "Old public company",
  "community-interest-company": "Community interest company",
};

const JURISDICTION_LABELS = {
  "england-wales": "England & Wales",
  england: "England",
  wales: "Wales",
  scotland: "Scotland",
  "northern-ireland": "Northern Ireland",
  "united-kingdom": "United Kingdom",
};

const MONTHS = [
  "January", "February", "March", "April", "May", "June",
  "July", "August", "September", "October", "November", "December",
];

export function companyTypeLabel(code) {
  return TYPE_LABELS[code] ?? code ?? "—";
}

export function jurisdictionLabel(code) {
  return JURISDICTION_LABELS[code] ?? code ?? "—";
}

// "active" → "Active".
export function statusLabel(status) {
  return status ? status[0].toUpperCase() + status.slice(1) : "—";
}

export function isActive(company) {
  return company?.status === "active";
}

// ISO "1947-11-27" → "27 November 1947".
export function fmtIncorporated(iso) {
  if (!iso) return "—";
  const [y, m, d] = iso.split("-").map(Number);
  if (!y || !m || !d) return iso;
  return `${d} ${MONTHS[m - 1]} ${y}`;
}

// Join the non-empty registered-office address lines.
export function joinAddress(addr) {
  if (!addr) return "—";
  return (
    [
      addr["address-line-1"],
      addr.locality,
      addr["postal-code"],
      addr.country,
    ]
      .filter((s) => s && s.trim())
      .join(", ") || "—"
  );
}

// Sanitise a company-number entry: A–Z0–9 only, upper-cased, max 8.
export function sanitiseNumber(raw) {
  return (raw ?? "").toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 8);
}

export const COMPANY_NUMBER_LENGTH = 8;
