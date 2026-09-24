<script>
  /* Ledger Accounts page — the bank's chart of accounts (GL accounts),
     rendered as a tree-table: each account row decomposes into the
     balances that comprise it. Read-only by design; creation of GL
     accounts happens when a bank is provisioned, not from here.

     Each row leads with its gl-code and shows class (the account's role —
     control accounts roll up a sub-ledger, the emphasised chip) and type
     (accounting family) via the <GlClass>/<GlType> chips.

     The list endpoint carries each account's derived `posted-balance`
     ({value, currency}) plus a per-currency `trial-balance` (Σ debits vs
     Σ credits, equal when the books balance), both computed server-side
     (bank-balance does the aggregation). So one call paints the headline
     figures and the trial-balance band; we only fetch an account's full
     balances lazily, on expand, to render its decomposition. Each balance is
     keyed by (balance-type, balance-status) and carries credit/debit in
     minor units; its signed net is credit − debit (credit-positive),
     which is what the per-bucket rows show. */

  import {
    PageHeader,
    Button,
    Table,
    Thead,
    Tbody,
    Tr,
    Th,
    Td,
    Expander,
    MoneyCell,
    Phase,
    GlClass,
    GlType,
    TrialBalance,
    CCY_SYMBOLS,
  } from "@queenswood/ui";
  import {
    list_ledger_accounts,
    list_ledger_account_balances,
  } from "./api.mjs";

  let { user, memberships } = $props();

  let loading = $state(true);
  let error = $state(null);
  let accounts = $state([]);
  // Per-currency trial balance from the list response: the server
  // (bank-balance) does the debit/credit aggregation; this is the band's
  // source of truth — [{currency, debit, credit, accounts}].
  let trial = $state([]);
  // Snapshot time of the loaded data, shown beside the trial-balance
  // heading ("as of HH:MM UTC").
  let asOf = $state(null);
  // Open-state map keyed by account id. Accounts start collapsed;
  // expanding an account lazily fetches its balance decomposition.
  let open = $state({});

  const kicker = $derived(memberships?.[0]?.["bank-name"]);

  // The band only adds presentation (currency symbol + name) to each
  // server-computed block; the figures themselves are not re-derived.
  const currencyNames = new Intl.DisplayNames(["en"], { type: "currency" });

  const trialBlocks = $derived(
    trial.map((t) => ({
      ccy: t.currency,
      sym: CCY_SYMBOLS[t.currency] ?? t.currency,
      name: currencyNames.of(t.currency) ?? t.currency,
      accounts: t.accounts,
      debitMinor: t.debit,
      creditMinor: t.credit,
    })),
  );

  const allExpanded = $derived(
    accounts.length > 0 && accounts.every((a) => open[a.id]),
  );

  // A balance's signed net (credit-positive minor units) — matches the
  // backend's credit − debit convention, so liabilities read positive
  // and asset/overdraft positions read negative (→ danger tone).
  function netMinor(b) {
    return (b.credit ?? 0) - (b.debit ?? 0);
  }

  function mapBalance(b) {
    return {
      type: b["balance-type"],
      phase: b["balance-status"],
      currency: b.currency,
      minor: netMinor(b),
    };
  }

  async function load() {
    loading = true;
    error = null;
    try {
      const res = await list_ledger_accounts();
      if (res.status < 200 || res.status >= 300) {
        error = res.body?.detail ?? `HTTP ${res.status}`;
        accounts = [];
        trial = [];
        return;
      }
      const list = res.body?.items ?? [];
      // One call: shape each account from the list. `balances` is null
      // until the row is expanded (lazily fetched then), since the
      // headline figure comes from the backend-derived posted-balance.
      accounts = list.map((a) => ({
        id: a["account-id"],
        name: a.name,
        gl: a["gl-code"],
        ccy: a.currency,
        // Chart-of-accounts classification (short forms from the API):
        // glClass = role in the hierarchy (control/summary/detail),
        // glType = accounting family. subLedgerKind is set on controls.
        glClass: a["gl-account-class"],
        glType: a["gl-account-type"],
        subLedgerKind: a["sub-ledger-kind"],
        postedMinor: a["posted-balance"]?.value ?? 0,
        balances: null,
        balancesLoading: false,
      }));
      trial = res.body?.["trial-balance"] ?? [];
      asOf =
        new Date().toLocaleTimeString("en-GB", {
          hour: "2-digit",
          minute: "2-digit",
          timeZone: "UTC",
          hour12: false,
        }) + " UTC";
      open = Object.fromEntries(accounts.map((a) => [a.id, false]));
    } catch (err) {
      error = err.message;
      accounts = [];
      trial = [];
    } finally {
      loading = false;
    }
  }

  // Fetch an account's balance decomposition once, on first expand.
  async function ensureBalances(acc) {
    if (acc.balances !== null || acc.balancesLoading) return;
    acc.balancesLoading = true;
    try {
      const bres = await list_ledger_account_balances(acc.id);
      acc.balances =
        bres.status >= 200 && bres.status < 300
          ? (bres.body?.items ?? []).map(mapBalance)
          : [];
    } finally {
      acc.balancesLoading = false;
    }
  }

  $effect(() => {
    load();
  });

  function toggle(id) {
    open[id] = !open[id];
    if (open[id]) {
      const acc = accounts.find((a) => a.id === id);
      if (acc) ensureBalances(acc);
    }
  }

  function onKey(e, id) {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      toggle(id);
    }
  }

  function toggleAll() {
    const next = !allExpanded;
    open = Object.fromEntries(accounts.map((a) => [a.id, next]));
    if (next) accounts.forEach(ensureBalances);
  }
</script>

<PageHeader
  {kicker}
  title="Ledger Accounts"
  sub="The bank's chart of accounts. Class marks each account's role — control accounts roll up a sub-ledger — and type its accounting family. Accounts decompose into their balances; the headline figure is the posted balance."
>
  {#snippet actions()}
    <Button variant="ghost" onclick={load}>Refresh</Button>
    {#if accounts.length > 0}
      <Button variant="line" onclick={toggleAll}>
        {allExpanded ? "Collapse all" : "Expand all"}
      </Button>
    {/if}
  {/snippet}
</PageHeader>

{#if error}
  <div class="alert" role="alert">{error}</div>
{/if}

{#if loading}
  <div class="loading">Loading…</div>
{:else if accounts.length === 0}
  <div class="empty">
    <p>No ledger accounts.</p>
    <p class="hint">A bank's chart of accounts is seeded when the bank is provisioned.</p>
  </div>
{:else}
  <div class="tb-wrap">
    <TrialBalance blocks={trialBlocks} {asOf} />
  </div>
  <Table tree>
    <Thead>
      <Tr>
        <Th />
        <Th>Code</Th>
        <Th>Name</Th>
        <Th>Class</Th>
        <Th>Type</Th>
        <Th align="right">Posted Balance</Th>
      </Tr>
    </Thead>
    <Tbody>
      {#each accounts as acc (acc.id)}
        <Tr
          expandable
          expanded={open[acc.id]}
          onclick={() => toggle(acc.id)}
          onkeydown={(e) => onKey(e, acc.id)}
        >
          <Td expander><Expander /></Td>
          <Td mono>{acc.gl}</Td>
          <Td emphasized>{acc.name}<span class="qw-denom">{acc.ccy}</span></Td>
          <Td><GlClass value={acc.glClass} /></Td>
          <Td><GlType value={acc.glType} /></Td>
          <MoneyCell minor={acc.postedMinor} ccy={acc.ccy} emphasized />
        </Tr>
        {#if open[acc.id]}
          {#if acc.balances}
            {#each acc.balances as b, i (b.type + ":" + b.phase)}
              <Tr balance last={i === acc.balances.length - 1}>
                <Td expander />
                <Td mono muted>{b.currency}</Td>
                <Td addr>
                  <span class="qw-tree-mark">
                    <span class="qw-addr-path">{b.type}</span>
                    <Phase phase={b.phase} />
                  </span>
                </Td>
                <Td />
                <Td />
                <MoneyCell minor={b.minor} ccy={acc.ccy} />
              </Tr>
            {/each}
          {:else}
            <Tr balance last>
              <Td expander />
              <Td />
              <Td muted>Loading…</Td>
              <Td />
              <Td />
              <Td />
            </Tr>
          {/if}
        {/if}
      {/each}
    </Tbody>
  </Table>
{/if}

<style>
  .alert {
    padding: 12px 16px;
    border: 1px solid var(--rule);
    border-radius: 6px;
    background: var(--surface-sunk);
    color: var(--fg);
    font-size: 14px;
  }
  .loading,
  .empty {
    padding: 48px 16px;
    text-align: center;
    color: var(--fg-muted);
    border: 1px dashed var(--rule);
    border-radius: 12px;
  }
  .empty p {
    margin: 0;
  }
  .empty .hint {
    margin-top: 6px;
    font-size: 13px;
  }

  /* The trial-balance band sits above the account list. */
  .tb-wrap {
    margin-bottom: 22px;
  }
</style>
