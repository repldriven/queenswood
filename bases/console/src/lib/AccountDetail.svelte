<script>
  /* AccountDetail — the three stacked panels for the selected account:
     identity hero, balance band (headline available + breakdown by
     phase), and the transaction history.

     Balances come embedded on the account (so the breakdown and headline
     agree). Transactions are fetched per selection; the running balance
     is walked client-side newest→oldest from the posted balance — pending
     postings carry no settled balance. */

  import {
    AccountStatusBadge,
    Phase,
    SearchField,
    formatMoney,
    formatSigned,
    moneyTone,
  } from "@queenswood/ui";
  import { get_cash_account_transactions } from "./api.mjs";

  let { account } = $props();

  let txns = $state([]);
  let txnQuery = $state("");

  function shortEnum(x) {
    return String(x ?? "")
      .replace(/^:/, "")
      .replace(
        /^(balance-type|balance-status|transaction-type|transaction-status|leg-side|side)-/,
        "",
      );
  }
  function prettyType(t) {
    const s = shortEnum(t);
    return s ? s.replace(/-/g, " ").replace(/\b\w/g, (c) => c.toUpperCase()) : "—";
  }
  function fmtDate(iso) {
    if (!iso) return "—";
    return new Date(iso).toLocaleDateString("en-GB", {
      day: "2-digit",
      month: "short",
      year: "numeric",
    });
  }
  const bucketMinor = (b) => (b.credit ?? 0) - (b.debit ?? 0);

  // Breakdown — the DEFAULT balance-type buckets by phase; they sum to the
  // available balance (which the API also returns as account.available).
  function defaultBucket(status) {
    const b = (account.balances ?? []).find(
      (x) =>
        shortEnum(x["balance-type"]) === "default" &&
        shortEnum(x["balance-status"]) === status,
    );
    return b ? bucketMinor(b) : 0;
  }
  const bdRows = $derived([
    { label: "posted", phase: "posted", minor: defaultBucket("posted") },
    { label: "pending_in", phase: "pending", minor: defaultBucket("pending-incoming") },
    { label: "pending_out", phase: "pending", minor: defaultBucket("pending-outgoing") },
  ]);

  // Transactions for the selected account. A leg's phase is its
  // balance-status (the immutable accounting fact), not a transaction
  // lifecycle: `posted` legs are settled; `pending-outgoing` /
  // `pending-incoming` are in-flight. We keep default-balance legs only
  // (capitalisation's other leg sits on the interest-accrued bucket, and
  // accrual writes no leg at all) so the running balance reconciles to
  // the posted balance.
  //
  // An in-flight outbound reserves in pending-outgoing at submit, then on
  // settlement clears that reservation (a pending-outgoing credit) and
  // posts the real debit. So a settled outbound leaves three customer
  // legs; net the reservation against its clearing so it collapses to one
  // pending row in-flight, then one settled (posted) row once it settles.
  const at = (t) => t["created-at"] ?? "";
  const byAtAsc = (a, b) => (at(a) < at(b) ? -1 : at(a) > at(b) ? 1 : 0);

  function toRow(t, phase) {
    const minor =
      shortEnum(t.side) === "credit" ? (t.amount ?? 0) : -(t.amount ?? 0);
    return {
      id: t["leg-id"],
      date: fmtDate(t["created-at"]),
      at: at(t),
      desc: t.reference || prettyType(t["transaction-type"]),
      type: prettyType(t["transaction-type"]),
      minor,
      phase,
    };
  }

  $effect(() => {
    const id = account?.id;
    if (!id) {
      txns = [];
      return;
    }
    txnQuery = "";
    txns = [];
    get_cash_account_transactions(id).then((r) => {
      if (account?.id !== id) return;
      if (r.status < 200 || r.status >= 300) return;
      const def = (r.body?.items ?? []).filter((t) => {
        const bt = shortEnum(t["balance-type"]);
        return bt === "default" || bt === "";
      });

      const posted = def.filter((t) => shortEnum(t["balance-status"]) === "posted");
      const pendIn = def.filter((t) => shortEnum(t["balance-status"]) === "pending-incoming");
      const pendOut = def.filter((t) => shortEnum(t["balance-status"]) === "pending-outgoing");

      // Pair each reservation (pending-outgoing debit) with its clearing
      // (pending-outgoing credit) by amount, oldest first; an unmatched
      // reservation is still in-flight and shown as one pending row.
      const clearings = pendOut.filter((t) => shortEnum(t.side) === "credit").sort(byAtAsc);
      const used = new Set();
      const outstanding = [];
      for (const res of pendOut.filter((t) => shortEnum(t.side) === "debit").sort(byAtAsc)) {
        const match = clearings.find(
          (c) => !used.has(c["leg-id"]) && (c.amount ?? 0) === (res.amount ?? 0),
        );
        if (match) used.add(match["leg-id"]);
        else outstanding.push(res);
      }

      const legs = [
        ...posted.map((t) => toRow(t, "posted")),
        ...outstanding.map((t) => toRow(t, "pending")),
        ...pendIn.map((t) => toRow(t, "pending")),
      ].sort((a, b) => (a.at < b.at ? 1 : a.at > b.at ? -1 : 0));

      // Running balance: newest settled posting sits on the posted
      // balance, each older settled posting steps back by its amount.
      // Pending rows carry no settled balance yet.
      let bal = account.posted ?? 0;
      txns = legs.map((t) => {
        if (t.phase === "pending") return { ...t, balanceAfter: null };
        const row = { ...t, balanceAfter: bal };
        bal -= t.minor;
        return row;
      });
    });
  });

  function txnMatches(t, q) {
    if (!q) return true;
    const hay = [t.desc, t.type, formatSigned(t.minor, account.ccy), String(Math.abs(t.minor) / 100)]
      .join(" ")
      .toLowerCase();
    return hay.includes(q.toLowerCase().trim());
  }
  const visibleTxns = $derived(txns.filter((t) => txnMatches(t, txnQuery)));
</script>

<!-- Identity + hero -->
<div class="panel acct-hero">
  <div class="acct-hero-top">
    <div class="acct-ident">
      <span class="product">{account.product}</span>
      <h2 class="acct-name">{account.name}</h2>
      <div class="acct-coords">
        <span><span class="lbl">Sort</span> {account.sortCode}</span>
        <span class="sep">·</span>
        <span><span class="lbl">Acct</span> {account.number}</span>
        <span class="sep">·</span>
        <span class="lbl" title={account.id}>{account.id}</span>
      </div>
    </div>
    <div class="acct-status">
      <AccountStatusBadge status={account.status} />
      <span class="opened">Opened {account.opened}</span>
    </div>
  </div>
  <div class="acct-owner">
    <span class="lbl">Owner</span>
    <span class="owner-name">{account.owner.name}</span>
    <span class="owner-id">{account.owner.id}</span>
  </div>
</div>

<!-- Balance band -->
<div class="panel balance-band">
  <div class="balance-headline">
    <div>
      <div class="bh-label">Available balance</div>
      <div class="bh-figure" class:neg={account.available < 0}>
        {formatMoney(account.available, account.ccy)}<span class="bh-ccy">{account.ccy}</span>
      </div>
    </div>
    <div class="bh-secondary">
      <div class="bh-stat">
        <span class="k">Posted balance</span>
        <span class="v">{formatMoney(account.posted, account.ccy)}</span>
      </div>
      <div class="bh-stat">
        <span class="k">Accrued interest</span>
        <span class="v">{formatMoney(account.accrued, account.ccy)}</span>
      </div>
    </div>
  </div>
  <div class="breakdown">
    <h3 class="bd-title">Balance breakdown · by address / phase</h3>
    <table class="bd-table">
      <tbody>
        {#each bdRows as r (r.label)}
          <tr>
            <td class="bd-addr">default<span class="slash">/</span>{r.label}</td>
            <td class="bd-phase"><Phase phase={r.phase} /></td>
            <td class="bd-amt {moneyTone(r.minor)}">{formatMoney(r.minor, account.ccy)}</td>
          </tr>
        {/each}
        <tr class="bd-total">
          <td class="bd-addr">Available</td>
          <td class="bd-phase"></td>
          <td class="bd-amt" class:neg={account.available < 0}>
            {formatMoney(account.available, account.ccy)}
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</div>

<!-- Transactions -->
<div class="panel txn-panel">
  <div class="txn-head">
    <h3 class="txn-title">
      Transactions
      <span class="txn-count">
        {txnQuery ? `${visibleTxns.length} of ${txns.length}` : `${txns.length} postings`}
      </span>
    </h3>
    <div class="txn-search">
      <SearchField bind:value={txnQuery} size="sm" placeholder="Search transactions…" ariaLabel="Search transactions" />
    </div>
  </div>
  <table class="txn-table">
    <thead>
      <tr>
        <th class="date">Value date</th>
        <th>Description</th>
        <th>Type</th>
        <th class="amt">Amount</th>
        <th class="bal">Balance</th>
      </tr>
    </thead>
    <tbody>
      {#if visibleTxns.length === 0}
        <tr>
          <td colspan="5" class="txn-empty">
            {txns.length === 0 ? "No transactions yet." : `No transactions match “${txnQuery}”.`}
          </td>
        </tr>
      {:else}
        {#each visibleTxns as t (t.id)}
          <tr class:is-pending={t.phase === "pending"}>
            <td class="date">{t.date}</td>
            <td class="desc">
              <span class="desc-main">{t.desc}</span>
              {#if t.phase === "pending"}
                <span class="desc-sub"><Phase phase="pending" /></span>
              {/if}
            </td>
            <td><span class="txn-type">{t.type}</span></td>
            <td class="amt {t.minor < 0 ? 'debit' : 'credit'}">{formatSigned(t.minor, account.ccy)}</td>
            {#if t.balanceAfter === null}
              <td class="bal pending">—</td>
            {:else}
              <td class="bal">{formatMoney(t.balanceAfter, account.ccy)}</td>
            {/if}
          </tr>
        {/each}
      {/if}
    </tbody>
  </table>
</div>

<style>
  .panel {
    background: var(--surface-raised);
    border: 1px solid var(--rule-2);
    border-radius: 8px;
  }

  /* Identity hero */
  .acct-hero {
    padding: 22px 24px;
  }
  .acct-hero-top {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 20px;
    flex-wrap: wrap;
  }
  .acct-ident {
    display: flex;
    flex-direction: column;
    gap: 4px;
    min-width: 0;
  }
  .acct-ident .product {
    font-family: var(--mono);
    font-size: 11px;
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--gold-deep);
  }
  .acct-name {
    font-family: var(--grotesk);
    font-weight: 500;
    font-size: 21px;
    letter-spacing: -0.005em;
    line-height: 1.15;
    margin: 0;
    color: var(--fg);
  }
  .acct-coords {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
    margin-top: 4px;
    font-family: var(--mono);
    font-size: 12px;
    color: var(--fg-2);
  }
  .acct-coords .sep {
    color: var(--fg-muted);
    opacity: 0.5;
  }
  .acct-coords .lbl {
    color: var(--fg-muted);
  }
  .acct-status {
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    gap: 8px;
  }
  .acct-status .opened {
    font-family: var(--mono);
    font-size: 11px;
    color: var(--fg-muted);
    white-space: nowrap;
  }
  .acct-owner {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 14px;
    padding-top: 14px;
    border-top: 1px solid var(--rule-2);
    font-size: 13px;
    color: var(--fg-2);
  }
  .acct-owner .lbl {
    font-family: var(--mono);
    font-size: 10px;
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--fg-muted);
  }
  .acct-owner .owner-name {
    color: var(--fg);
    font-weight: 500;
  }
  .acct-owner .owner-id {
    font-family: var(--mono);
    font-size: 11px;
    color: var(--fg-muted);
  }

  /* Balance band */
  .balance-band {
    display: grid;
    grid-template-columns: minmax(0, 0.85fr) minmax(0, 1.15fr);
  }
  .balance-headline {
    padding: 24px;
    border-right: 1px solid var(--rule-2);
    display: flex;
    flex-direction: column;
    justify-content: center;
    gap: 16px;
  }
  .bh-label {
    font-family: var(--mono);
    font-size: 11px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--fg-muted);
  }
  .bh-figure {
    font-family: var(--mono);
    font-variant-numeric: tabular-nums;
    font-weight: 500;
    font-size: 40px;
    line-height: 1;
    letter-spacing: -0.01em;
    color: var(--fg);
    white-space: nowrap;
  }
  .bh-figure.neg {
    color: var(--danger);
  }
  .bh-ccy {
    font-size: 18px;
    color: var(--fg-muted);
    margin-left: 8px;
    letter-spacing: 0.04em;
  }
  .bh-secondary {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  .bh-stat {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 12px;
    font-size: 12.5px;
  }
  .bh-stat .k {
    color: var(--fg-muted);
  }
  .bh-stat .v {
    font-family: var(--mono);
    font-variant-numeric: tabular-nums;
    color: var(--fg-2);
  }

  /* Breakdown table */
  .breakdown {
    padding: 20px 24px;
    display: flex;
    flex-direction: column;
    gap: 12px;
  }
  .bd-title {
    font-family: var(--mono);
    font-size: 10px;
    letter-spacing: 0.1em;
    text-transform: uppercase;
    color: var(--gold-deep);
    margin: 0;
    font-weight: 500;
  }
  .bd-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 13px;
  }
  .bd-table td {
    padding: 9px 0;
    border-bottom: 1px solid var(--rule-2);
    vertical-align: middle;
  }
  .bd-table tr:last-child td {
    border-bottom: none;
  }
  .bd-addr {
    font-family: var(--mono);
    font-size: 12px;
    color: var(--fg-2);
  }
  .bd-addr .slash {
    color: var(--fg-muted);
    opacity: 0.6;
    margin: 0 1px;
  }
  .bd-phase {
    padding-left: 10px;
    width: 1%;
    white-space: nowrap;
  }
  .bd-amt {
    text-align: right;
    font-family: var(--mono);
    font-variant-numeric: tabular-nums;
    color: var(--fg);
    white-space: nowrap;
    width: 1%;
  }
  .bd-amt.neg {
    color: var(--danger);
  }
  .bd-amt.zero {
    color: var(--fg-muted);
  }
  .bd-amt.pos {
    color: var(--pos);
  }
  .bd-total td {
    border-top: 1.5px solid var(--rule);
    border-bottom: none;
    padding-top: 12px;
    font-weight: 500;
  }
  .bd-total .bd-addr {
    font-family: var(--grotesk);
    font-size: 13px;
    color: var(--fg);
    font-weight: 500;
    letter-spacing: 0;
    text-transform: none;
  }
  .bd-total .bd-amt {
    color: var(--fg);
    font-size: 14px;
  }

  /* Transactions */
  .txn-panel {
    display: flex;
    flex-direction: column;
  }
  .txn-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    padding: 16px 20px;
    border-bottom: 1px solid var(--rule-2);
  }
  .txn-title {
    font-family: var(--grotesk);
    font-weight: 500;
    font-size: 15px;
    color: var(--fg);
    margin: 0;
    display: flex;
    align-items: baseline;
    gap: 8px;
  }
  .txn-count {
    font-family: var(--mono);
    font-size: 11px;
    color: var(--fg-muted);
    font-weight: 400;
  }
  .txn-search {
    width: 280px;
    max-width: 50%;
  }
  .txn-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 13px;
    color: var(--fg);
  }
  .txn-table thead th {
    text-align: left;
    font-family: var(--mono);
    font-size: 10px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--fg-muted);
    font-weight: 500;
    padding: 11px 16px;
    background: var(--surface-sunk);
    border-bottom: 1px solid var(--rule-2);
    white-space: nowrap;
  }
  .txn-table th.amt,
  .txn-table th.bal {
    text-align: right;
  }
  .txn-table tbody td {
    padding: 13px 16px;
    border-bottom: 1px solid var(--rule-2);
    vertical-align: middle;
    color: var(--fg-2);
  }
  .txn-table tbody tr:last-child td {
    border-bottom: none;
  }
  .txn-table tbody tr:hover td {
    background: var(--hover-overlay);
  }
  .txn-table td.date {
    font-family: var(--mono);
    font-size: 12px;
    color: var(--fg-muted);
    white-space: nowrap;
  }
  .txn-table td.desc {
    color: var(--fg);
  }
  .desc-main {
    font-weight: 500;
  }
  .desc-sub {
    display: block;
    margin-top: 4px;
  }
  .txn-type {
    display: inline-flex;
    align-items: center;
    height: 20px;
    padding: 0 8px;
    border-radius: 5px;
    font-family: var(--mono);
    font-size: 11px;
    color: var(--fg-2);
    background: var(--surface-sunk);
    border: 1px solid var(--rule-2);
    white-space: nowrap;
  }
  .txn-table td.amt {
    text-align: right;
    font-family: var(--mono);
    font-variant-numeric: tabular-nums;
    white-space: nowrap;
    color: var(--fg);
  }
  .txn-table td.amt.credit {
    color: var(--pos);
  }
  .txn-table td.amt.debit {
    color: var(--fg);
  }
  .txn-table td.bal {
    text-align: right;
    font-family: var(--mono);
    font-variant-numeric: tabular-nums;
    color: var(--fg-muted);
    white-space: nowrap;
  }
  .txn-table td.bal.pending {
    opacity: 0.6;
  }
  .txn-table tr.is-pending td {
    background: light-dark(rgba(80, 70, 180, 0.035), rgba(140, 130, 240, 0.05));
  }
  .txn-table tr.is-pending:hover td {
    background: light-dark(rgba(80, 70, 180, 0.06), rgba(140, 130, 240, 0.08));
  }
  .txn-empty {
    padding: 40px 16px;
    text-align: center;
    color: var(--fg-muted);
    font-size: 13px;
  }

  @media (max-width: 1080px) {
    .balance-band {
      grid-template-columns: 1fr;
    }
    .balance-headline {
      border-right: none;
      border-bottom: 1px solid var(--rule-2);
    }
  }
</style>
