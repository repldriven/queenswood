<script>
  /* Accounts — search-first master/detail over customer cash accounts
     (product instances held by a party; distinct from the GL Ledger).
     The rail lists accounts with their available balance and filters
     client-side; selecting one renders the detail panels.

     One paginated GET /v1/cash-accounts?embed[balances] carries each
     account's balances + available/posted aggregates (enough for the
     rail AND the detail balance band). Owner and product names aren't on
     the account, so we join party display-names and product version
     names fetched alongside. */

  import { PageHeader, Button, SearchField, formatMoney } from "@queenswood/ui";
  import {
    list_cash_accounts,
    list_parties,
    list_cash_account_products,
  } from "./api.mjs";
  import AccountDetail from "./AccountDetail.svelte";

  let { user, memberships } = $props();
  const kicker = $derived(memberships?.[0]?.["bank-name"]);

  let loading = $state(true);
  let error = $state(null);
  let accounts = $state([]);
  let selectedId = $state(null);
  let query = $state("");
  // `#/accounts?account=<id>` selects that account, on landing and on a
  // hash change while the page is up — the scenarios walk from one
  // account to the next this way.
  const accountFromHash = () => new URLSearchParams(location.hash.split("?")[1] ?? "").get("account");
  function selectFromHash() {
    const id = accountFromHash();
    if (id && accounts.some((a) => a.id === id)) selectedId = id;
  }

  // Enum values arrive either short ("opened") or as a namespaced
  // keyword (":account-status-opened"); strip a known prefix so either
  // spelling compares cleanly.
  function shortEnum(x) {
    return String(x ?? "")
      .replace(/^:/, "")
      .replace(/^(account-status|product-type|balance-type|balance-status)-/, "");
  }
  function prettyType(t) {
    const s = shortEnum(t);
    return s ? s.replace(/-/g, " ").replace(/\b\w/g, (c) => c.toUpperCase()) : "";
  }
  function fmtSortCode(sc) {
    const d = String(sc ?? "").replace(/\D/g, "");
    return d.length === 6 ? `${d.slice(0, 2)}-${d.slice(2, 4)}-${d.slice(4, 6)}` : d;
  }
  function fmtDate(iso) {
    if (!iso) return "—";
    return new Date(iso).toLocaleDateString("en-GB", {
      day: "2-digit",
      month: "short",
      year: "numeric",
    });
  }
  function scanOf(addresses) {
    return (addresses ?? []).find((a) => shortEnum(a.scheme) === "scan")?.scan ?? {};
  }
  const bucketMinor = (b) => (b.credit ?? 0) - (b.debit ?? 0);
  function accruedOf(balances) {
    return (balances ?? [])
      .filter(
        (b) =>
          shortEnum(b["balance-type"]) === "interest-accrued" &&
          shortEnum(b["balance-status"]) === "posted",
      )
      .reduce((sum, b) => sum + bucketMinor(b), 0);
  }

  async function load() {
    loading = true;
    error = null;
    try {
      const [pRes, prodRes] = await Promise.all([
        list_parties(),
        list_cash_account_products(),
      ]);
      const partyName = {};
      for (const p of pRes.body?.parties ?? []) {
        partyName[p["party-id"]] = p["display-name"];
      }
      const versionName = {};
      for (const prod of prodRes.body?.items ?? []) {
        for (const v of prod.versions ?? []) versionName[v["version-id"]] = v.name;
      }

      // Rail search is client-side, so pull every page.
      const raw = [];
      let after = null;
      do {
        const res = await list_cash_accounts({ embed: ["balances"], after });
        if (res.status < 200 || res.status >= 300) {
          error = res.body?.detail ?? `HTTP ${res.status}`;
          accounts = [];
          return;
        }
        raw.push(...(res.body?.["cash-accounts"] ?? []));
        const next = res.body?.links?.next;
        after = next
          ? new URL(next, location.origin).searchParams.get("page[after]")
          : null;
      } while (after);

      accounts = raw.map((a) => {
        const scan = scanOf(a["payment-addresses"]);
        return {
          id: a["account-id"],
          number: scan["account-number"] ?? "",
          sortCode: fmtSortCode(scan["sort-code"]),
          rawSortCode: String(scan["sort-code"] ?? "").replace(/\D/g, ""),
          name: a.name ?? "",
          product: versionName[a["version-id"]] ?? prettyType(a["product-type"]),
          ccy: a.currency ?? "GBP",
          status: shortEnum(a["account-status"]),
          opened: fmtDate(a["created-at"]),
          owner: { name: partyName[a["party-id"]] ?? a["party-id"], id: a["party-id"] },
          available: a["available-balance"]?.value ?? 0,
          posted: a["posted-balance"]?.value ?? 0,
          accrued: accruedOf(a.balances),
          balances: a.balances ?? [],
        };
      });
      selectedId = accounts[0]?.id ?? null;
      selectFromHash();
    } catch (err) {
      error = err.message;
      accounts = [];
    } finally {
      loading = false;
    }
  }

  $effect(() => {
    load();
  });
  $effect(() => {
    addEventListener("hashchange", selectFromHash);
    return () => removeEventListener("hashchange", selectFromHash);
  });

  function matches(a, q) {
    if (!q) return true;
    const hay = [a.number, a.sortCode, a.rawSortCode, a.name, a.product, a.owner.name]
      .join(" ")
      .toLowerCase();
    return hay.includes(q.toLowerCase().trim());
  }
  const filtered = $derived(accounts.filter((a) => matches(a, query)));
  const selected = $derived(accounts.find((a) => a.id === selectedId) ?? null);

  function onSearchKey(e) {
    if (e.key === "Enter" && filtered.length) selectedId = filtered[0].id;
  }
</script>

<PageHeader
  {kicker}
  title="Accounts"
  sub="Find a customer account and inspect its available balance, balance composition, and posting history."
>
  {#snippet actions()}
    <Button variant="line" disabled title="Statement export isn't wired up yet">
      Export statement
    </Button>
    <Button variant="ghost" onclick={load}>Refresh</Button>
  {/snippet}
</PageHeader>

{#if error}
  <div class="alert" role="alert">{error}</div>
{/if}

{#if loading}
  <div class="loading">Loading…</div>
{:else if accounts.length === 0}
  <div class="empty">
    <p>No accounts yet.</p>
    <p class="hint">Open a cash account (the Scenarios sandbox can) and it'll appear here.</p>
  </div>
{:else}
  <div class="accounts-layout">
    <aside class="acct-rail">
      <SearchField
        bind:value={query}
        placeholder="Search by account number…"
        ariaLabel="Search accounts"
        onkeydown={onSearchKey}
      />
      <div class="acct-rail-count">
        {query
          ? `${filtered.length} of ${accounts.length} accounts`
          : `${accounts.length} account${accounts.length === 1 ? "" : "s"}`}
      </div>
      <div class="acct-list" role="listbox" aria-label="Accounts">
        {#if filtered.length === 0}
          <div class="acct-empty">No accounts match “{query}”.</div>
        {:else}
          {#each filtered as a (a.id)}
            <button
              type="button"
              class="acct-item"
              role="option"
              aria-selected={a.id === selectedId}
              aria-current={a.id === selectedId}
              onclick={() => (selectedId = a.id)}
            >
              <span class="ai-name">{a.name}</span>
              <span class="ai-num">{a.sortCode} · {a.number}</span>
              <span class="ai-bal" class:neg={a.available < 0}>
                {formatMoney(a.available, a.ccy)}<span class="ai-ccy">{a.ccy}</span>
              </span>
            </button>
          {/each}
        {/if}
      </div>
    </aside>

    <div class="acct-detail">
      {#if selected}
        <AccountDetail account={selected} />
      {:else}
        <div class="panel-empty">Select an account to view its balances and transactions.</div>
      {/if}
    </div>
  </div>
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

  .accounts-layout {
    display: grid;
    grid-template-columns: 340px minmax(0, 1fr);
    gap: 24px;
    align-items: start;
  }

  .acct-rail {
    position: sticky;
    top: 81px;
    display: flex;
    flex-direction: column;
    gap: 12px;
  }
  .acct-rail-count {
    font-family: var(--mono);
    font-size: 11px;
    letter-spacing: 0.04em;
    color: var(--fg-muted);
    padding: 0 2px;
  }
  .acct-list {
    background: var(--surface-raised);
    border: 1px solid var(--rule-2);
    border-radius: 8px;
    overflow: hidden;
    max-height: calc(100vh - 220px);
    overflow-y: auto;
  }
  .acct-empty {
    padding: 28px 16px;
    text-align: center;
    color: var(--fg-muted);
    font-size: 13px;
  }
  .acct-item {
    width: 100%;
    display: grid;
    grid-template-columns: 1fr auto;
    gap: 4px 12px;
    align-items: center;
    padding: 12px 14px;
    border: none;
    border-bottom: 1px solid var(--rule-2);
    background: transparent;
    text-align: left;
    cursor: pointer;
    transition: background 0.1s;
  }
  .acct-item:last-child {
    border-bottom: none;
  }
  .acct-item:hover {
    background: var(--hover-overlay);
  }
  .acct-item:focus-visible {
    outline: 2px solid var(--gold);
    outline-offset: -2px;
  }
  .acct-item[aria-current="true"] {
    background: light-dark(oklch(0.95 0.02 145), oklch(0.24 0.035 145));
  }
  .ai-name {
    font-size: 13px;
    font-weight: 500;
    color: var(--fg);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .ai-num {
    font-family: var(--mono);
    font-size: 11px;
    color: var(--fg-muted);
    letter-spacing: 0.02em;
  }
  .ai-bal {
    grid-row: 1 / 3;
    grid-column: 2;
    align-self: center;
    text-align: right;
    font-family: var(--mono);
    font-variant-numeric: tabular-nums;
    font-size: 12.5px;
    color: var(--fg);
    white-space: nowrap;
  }
  .ai-bal.neg {
    color: var(--danger);
  }
  .ai-ccy {
    display: block;
    font-size: 9.5px;
    color: var(--fg-muted);
    letter-spacing: 0.06em;
    margin-top: 1px;
  }

  .acct-detail {
    display: flex;
    flex-direction: column;
    gap: 18px;
    min-width: 0;
  }
  .panel-empty {
    padding: 40px 16px;
    text-align: center;
    color: var(--fg-muted);
    font-size: 13px;
    background: var(--surface-raised);
    border: 1px solid var(--rule-2);
    border-radius: 8px;
  }

  @media (max-width: 1080px) {
    .accounts-layout {
      grid-template-columns: 1fr;
    }
    .acct-rail {
      position: static;
    }
    .acct-list {
      max-height: 320px;
    }
  }
</style>
