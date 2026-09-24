<script>
  /* Migrations — master/detail over planned moves of cash accounts from
     one product version to another.

     A migration is a statement of intent. Authoring one moves nothing
     and neither does approving it; only the scheduler's migration task
     moves accounts. So the screen's centre of gravity is the preview —
     a dry run that decides about every account in the cohort and moves
     none — and the job of the layout is to make three things
     unmissable before anything commits: which accounts are in scope,
     which are held back and why, and whether customers have been told.

     Per-version and per-product account counts have no API equivalent
     yet, so they're tallied from one sweep of /v1/cash-accounts,
     fetched after first paint. Until it lands the counts read "—"
     rather than a fabricated figure. */

  import {
    PageHeader,
    Button,
    SearchField,
    MigrationStatusBadge,
    toast,
    activeVersion,
    fmtDay,
    shortEnum,
  } from "@queenswood/ui";
  import {
    list_cash_account_migrations,
    list_cash_account_products,
    create_cash_account_migration,
    preview_cash_account_migration,
    approve_cash_account_migration,
    cancel_cash_account_migration,
  } from "./api.mjs";
  import { loadPopulation } from "./population.mjs";
  import MigrationDetail from "./MigrationDetail.svelte";
  import MigrationDrawer from "./MigrationDrawer.svelte";

  let { user, memberships } = $props();
  const kicker = $derived(memberships?.[0]?.["bank-name"]);

  let loading = $state(true);
  let error = $state(null);
  let migrations = $state([]);
  let products = $state([]);
  let population = $state(null);
  let selectedId = $state(null);
  let query = $state("");
  let drawerOpen = $state(false);
  let drafting = $state(null);

  const productById = $derived(
    Object.fromEntries(products.map((p) => [p.id, p])),
  );
  const versionById = $derived(
    Object.fromEntries(products.flatMap((p) => p.versions.map((v) => [v.id, v]))),
  );

  // A product has no name of its own — its versions carry one. The
  // effective version names the product, falling back to its newest
  // version so a product that is only a draft still reads as itself.
  function normaliseProduct(item) {
    const versions = (item.versions ?? [])
      .filter((v) => shortEnum(v.status) !== "discarded")
      .map((v) => ({
        id: v["version-id"],
        productId: item["product-id"],
        number: v["version-number"],
        status: shortEnum(v.status),
        published: shortEnum(v.status) === "published",
        name: v.name ?? "",
        type: shortEnum(v["product-type"]),
        rateBps: v["interest-rate-bps"],
        currencies: v["allowed-currencies"] ?? [],
        effectiveFrom: v["effective-from"] ?? null,
      }))
      .sort((a, b) => a.number - b.number);
    const headline = activeVersion(item) ?? item.versions?.at(-1) ?? null;
    return {
      id: item["product-id"],
      name: headline?.name ?? versions.at(-1)?.name ?? item["product-id"],
      type: shortEnum(headline?.["product-type"] ?? versions.at(-1)?.type),
      versions,
      publishedCount: versions.filter((v) => v.published).length,
    };
  }

  // `source-version-ids` is absent when a migration takes every version
  // of its source. Widening it here means the rest of the screen only
  // ever reasons about an explicit list.
  function normaliseMigration(m, byProduct) {
    const source = byProduct[m["source-product-id"]];
    return {
      id: m["migration-id"],
      name: m.name ?? "",
      status: shortEnum(m.status),
      sourceProductId: m["source-product-id"],
      sourceVersionIds:
        m["source-version-ids"] ?? (source?.versions ?? []).map((v) => v.id),
      targetProductId: m["target-product-id"],
      targetVersionId: m["target-version-id"],
      notifiedOn: m["notified-on"] ?? null,
      dueOn: m["due-on"] ?? null,
      createdAt: m["created-at"] ?? null,
      approvedAt: m["approved-at"] ?? null,
      completedAt: m["completed-at"] ?? null,
      cancelledAt: m["cancelled-at"] ?? null,
    };
  }

  async function load() {
    loading = true;
    error = null;
    try {
      const [migRes, prodRes] = await Promise.all([
        list_cash_account_migrations(),
        list_cash_account_products(),
      ]);
      if (prodRes.status < 200 || prodRes.status >= 300) {
        error = prodRes.body?.detail ?? `HTTP ${prodRes.status}`;
        return;
      }
      if (migRes.status < 200 || migRes.status >= 300) {
        error = migRes.body?.detail ?? `HTTP ${migRes.status}`;
        return;
      }
      products = (prodRes.body?.items ?? []).map(normaliseProduct);
      const byProduct = Object.fromEntries(products.map((p) => [p.id, p]));
      migrations = (migRes.body?.items ?? []).map((m) =>
        normaliseMigration(m, byProduct),
      );
      selectedId = migrations[0]?.id ?? null;
    } catch (err) {
      error = err.message;
    } finally {
      loading = false;
    }
  }

  // The counts the hero facts and pickers show, plus the account number
  // and owner an outcome row is identified by — a run records
  // account-id alone. Swept after first paint; the screen stands
  // without it, showing "—" rather than a fabricated figure.
  $effect(() => {
    load().then(async () => {
      population = await loadPopulation({ owners: true });
    });
  });

  function versionLabel(id) {
    const v = versionById[id];
    return v ? `v${v.number}` : "—";
  }

  function timing(m) {
    if (m.status === "completed" && m.completedAt) {
      return `completed ${fmtDay(m.completedAt.slice(0, 10))}`;
    }
    if (m.status === "cancelled" && m.cancelledAt) {
      return `cancelled ${fmtDay(m.cancelledAt.slice(0, 10))}`;
    }
    return m.dueOn ? `due ${fmtDay(m.dueOn)}` : "no due date";
  }

  function productLine(m) {
    const from = productById[m.sourceProductId]?.name ?? m.sourceProductId;
    const to = productById[m.targetProductId]?.name ?? m.targetProductId;
    return m.sourceProductId === m.targetProductId ? from : `${from} → ${to}`;
  }

  function versionLine(m) {
    const from = m.sourceVersionIds.map(versionLabel).join(", ") || "—";
    return `${from} → ${versionLabel(m.targetVersionId)}`;
  }

  function matches(m, q) {
    if (!q) return true;
    const hay = [
      m.name,
      m.id,
      m.status,
      productById[m.sourceProductId]?.name,
      productById[m.targetProductId]?.name,
    ]
      .join(" ")
      .toLowerCase();
    return hay.includes(q.toLowerCase().trim());
  }

  const filtered = $derived(migrations.filter((m) => matches(m, query)));
  const selected = $derived(
    migrations.find((m) => m.id === selectedId) ?? null,
  );

  function onSearchKey(e) {
    if (e.key === "Enter" && filtered.length) selectedId = filtered[0].id;
  }

  function openDrawer() {
    drafting = null;
    drawerOpen = true;
  }

  async function saveMigration(draft) {
    const body = {
      name: draft.name,
      "source-product-id": draft.sourceProductId,
      "source-version-ids": draft.sourceVersionIds,
      "target-product-id": draft.targetProductId,
      "target-version-id": draft.targetVersionId,
    };
    if (draft.notifiedOn) body["notified-on"] = draft.notifiedOn;
    if (draft.dueOn) body["due-on"] = draft.dueOn;

    const res = await create_cash_account_migration(body);
    if (res.status < 200 || res.status >= 300) {
      toast("Could not create the migration", res.body?.type ?? `HTTP ${res.status}`);
      return false;
    }
    const created = normaliseMigration(res.body, productById);
    migrations = [created, ...migrations];
    selectedId = created.id;
    drawerOpen = false;
    toast("Migration created", created.id);
    return true;
  }

  // Approve and cancel return the updated migration, so the rail and
  // detail re-render off the response rather than a refetch.
  function replace(updated) {
    const next = normaliseMigration(updated, productById);
    migrations = migrations.map((m) => (m.id === next.id ? next : m));
    return next;
  }

  async function approve(migration) {
    const res = await approve_cash_account_migration(migration.id);
    if (res.status < 200 || res.status >= 300) {
      toast("Could not approve", res.body?.type ?? `HTTP ${res.status}`);
      return;
    }
    replace(res.body);
    toast("Migration approved", migration.id);
  }

  async function cancel(migration) {
    const res = await cancel_cash_account_migration(migration.id);
    if (res.status < 200 || res.status >= 300) {
      toast("Could not cancel", res.body?.type ?? `HTTP ${res.status}`);
      return;
    }
    replace(res.body);
    toast("Migration cancelled", "no accounts will move");
  }

  async function runPreview(migration) {
    const res = await preview_cash_account_migration(migration.id);
    if (res.status < 200 || res.status >= 300) {
      toast("Preview failed", res.body?.type ?? `HTTP ${res.status}`);
      return null;
    }
    const held = res.body?.["accounts-ineligible"] ?? 0;
    toast("Preview complete", `${held.toLocaleString("en-GB")} held back`);
    return res.body;
  }
</script>

<PageHeader
  {kicker}
  title="Migrations"
  sub="Move live cash accounts from one product version to another. Preview a migration as a dry run to see exactly which accounts move — and which are held back — before you approve it."
>
  {#snippet actions()}
    <Button variant="ghost" onclick={load}>Refresh</Button>
    <Button variant="primary" onclick={openDrawer}>
      <svg
        viewBox="0 0 16 16"
        fill="none"
        stroke="currentColor"
        stroke-width="1.6"
        stroke-linecap="round"
        aria-hidden="true"
      >
        <path d="M8 3.5 V12.5 M3.5 8 H12.5" />
      </svg>
      New migration
    </Button>
  {/snippet}
</PageHeader>

{#if error}
  <div class="alert" role="alert">{error}</div>
{/if}

{#if loading}
  <div class="loading">Loading…</div>
{:else if migrations.length === 0}
  <div class="empty">
    <p>No migrations yet.</p>
    <p class="hint">
      Author one to move a product's live accounts onto a different version —
      a repricing, a retirement, or an age-out onto a successor product.
    </p>
  </div>
{:else}
  <div class="mig-layout">
    <aside class="mig-rail">
      <SearchField
        bind:value={query}
        placeholder="Search migrations…"
        ariaLabel="Search migrations"
        onkeydown={onSearchKey}
      />
      <div class="mig-rail-count">
        {query
          ? `${filtered.length} of ${migrations.length} migrations`
          : `${migrations.length} migration${migrations.length === 1 ? "" : "s"}`}
      </div>
      <div class="mig-list" role="listbox" aria-label="Migrations">
        {#if filtered.length === 0}
          <div class="mig-empty">No migration matches that search.</div>
        {:else}
          {#each filtered as m (m.id)}
            <button
              type="button"
              class="mig-item"
              role="option"
              aria-selected={m.id === selectedId}
              aria-current={m.id === selectedId}
              onclick={() => (selectedId = m.id)}
            >
              <span class="mi-top">
                <span class="mi-name">{m.name}</span>
                <MigrationStatusBadge status={m.status} />
              </span>
              <span class="mi-products">{productLine(m)}</span>
              <span class="mi-foot">
                <span>{versionLine(m)}</span>
                <span>{timing(m)}</span>
              </span>
            </button>
          {/each}
        {/if}
      </div>
    </aside>

    <div class="mig-detail">
      {#if selected}
        {#key selected.id}
          <MigrationDetail
            migration={selected}
            {productById}
            {versionById}
            {population}
            onpreview={() => runPreview(selected)}
            onapprove={() => approve(selected)}
            oncancel={() => cancel(selected)}
          />
        {/key}
      {:else}
        <div class="panel-empty">Select a migration to see its scope and preview.</div>
      {/if}
    </div>
  </div>
{/if}

<MigrationDrawer
  open={drawerOpen}
  migration={drafting}
  {products}
  {population}
  onclose={() => (drawerOpen = false)}
  onsave={saveMigration}
/>

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
    max-width: 52ch;
    margin-inline: auto;
    line-height: 1.5;
  }

  .mig-layout {
    display: grid;
    grid-template-columns: 340px minmax(0, 1fr);
    gap: 24px;
    align-items: start;
  }

  .mig-rail {
    position: sticky;
    top: 81px;
    display: flex;
    flex-direction: column;
    gap: 12px;
  }
  .mig-rail-count {
    font-family: var(--mono);
    font-size: 11px;
    letter-spacing: 0.04em;
    color: var(--fg-muted);
    padding: 0 2px;
  }
  .mig-list {
    background: var(--surface-raised);
    border: 1px solid var(--rule-2);
    border-radius: 8px;
    overflow: hidden;
    max-height: calc(100vh - 220px);
    overflow-y: auto;
  }
  .mig-empty {
    padding: 28px 16px;
    text-align: center;
    color: var(--fg-muted);
    font-size: 13px;
  }
  .mig-item {
    width: 100%;
    display: flex;
    flex-direction: column;
    gap: 7px;
    padding: 12px 14px;
    border: none;
    border-bottom: 1px solid var(--rule-2);
    background: transparent;
    text-align: left;
    cursor: pointer;
    transition: background 0.1s;
  }
  .mig-item:last-child {
    border-bottom: none;
  }
  .mig-item:hover {
    background: var(--hover-overlay);
  }
  .mig-item:focus-visible {
    outline: 2px solid var(--gold);
    outline-offset: -2px;
  }
  .mig-item[aria-current="true"] {
    background: light-dark(oklch(0.95 0.02 145), oklch(0.24 0.035 145));
  }
  .mi-top {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
  }
  .mi-name {
    font-size: 13px;
    font-weight: 500;
    color: var(--fg);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .mi-products {
    font-family: var(--mono);
    font-size: 11px;
    color: var(--fg-muted);
    line-height: 1.35;
  }
  .mi-foot {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    font-family: var(--mono);
    font-size: 10.5px;
    color: var(--fg-muted);
  }

  .mig-detail {
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
    .mig-layout {
      grid-template-columns: 1fr;
    }
    .mig-rail {
      position: static;
    }
    .mig-list {
      max-height: 300px;
    }
  }
</style>
