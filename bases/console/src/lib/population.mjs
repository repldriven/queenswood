// The live cash-account population, tallied client-side.
//
// Nothing on the product read model carries an account count, and a
// migration run records `account-id` alone — no number, no owner. Both
// are things an operator reads a product or a preview for, so the
// console sweeps /v1/cash-accounts once and derives them.
//
// This is a stopgap with a real cost: it pages the whole population to
// answer a question the API could answer with a number. It belongs on
// the product/version read model and on the run-accounts response; when
// it lands there, delete this and read the field.
//
// Callers render "—" while it's absent rather than a fabricated figure.
// A failed sweep resolves to null for the same reason.

import { list_cash_accounts, list_parties } from "./api.mjs";

function shortScheme(x) {
  return String(x ?? "").replace(/^:/, "").replace(/^payment-address-scheme-/, "");
}

function shortStatus(x) {
  return String(x ?? "").replace(/^:/, "").replace(/^account-status-/, "");
}

// `owners` opts into the per-account index the migration outcomes table
// needs — an extra /v1/parties call for the display names. A caller that
// only wants counts shouldn't pay for it.
export async function loadPopulation({ owners = false } = {}) {
  const byVersion = {};
  const byProduct = {};
  const accountById = owners ? {} : null;

  try {
    const partyName = {};
    if (owners) {
      const res = await list_parties();
      for (const p of res.body?.items ?? []) {
        partyName[p["party-id"]] = p["display-name"];
      }
    }

    let after = null;
    do {
      const res = await list_cash_accounts({ after });
      if (res.status < 200 || res.status >= 300) return null;

      for (const a of res.body?.items ?? []) {
        byVersion[a["version-id"]] = (byVersion[a["version-id"]] ?? 0) + 1;
        byProduct[a["product-id"]] = (byProduct[a["product-id"]] ?? 0) + 1;
        if (!owners) continue;

        const scan = (a["payment-addresses"] ?? []).find(
          (x) => shortScheme(x.scheme) === "scan",
        )?.scan;
        accountById[a["account-id"]] = {
          number: scan?.["account-number"] ?? a["account-id"],
          ccy: a.currency ?? "",
          status: shortStatus(a["account-status"]),
          owner: partyName[a["party-id"]] ?? a["party-id"],
        };
      }

      const next = res.body?.links?.next;
      after = next
        ? new URL(next, location.origin).searchParams.get("page[after]")
        : null;
    } while (after);

    return { byVersion, byProduct, accountById };
  } catch {
    return null;
  }
}
