<script>
  /* Products page — the first thing a signed-in console user lands on.
     One row per product, showing the version in force today, with the
     rest of that product's timeline in the expandable row beneath.

     A product is a timeline rather than a record: several versions can
     be published at once, each owning an effective-from/to window, so
     listing every version flat showed one product as several rows all
     reading "published" and left the reader to work out which one
     actually applies. The mainline row answers that directly — it is
     the version an account opened today would pin to.

     Per-version actions are gated on status, and are the same wherever
     the version is rendered:

       draft     → Publish (brand), Edit (ghost), Discard (danger)
       published → New version (ghost)   — opens a fresh draft
       discarded → no actions (the row is a tombstone)

     A pending draft stays out of the mainline but is advertised on it,
     because hiding it would hide its publish and discard actions.

     Create / new-version / edit all push the user into ProductDrawer
     with the appropriate mode. The drawer owns the form; we own the
     list and refetch when the drawer reports a successful mutation. */

  import {
    PageHeader,
    Table,
    Thead,
    Tbody,
    Tr,
    Th,
    Td,
    Badge,
    Button,
    Expander,
    productRows,
  } from "@queenswood/ui";
  import {
    list_cash_account_products,
    list_cash_account_product_templates,
    publish_cash_account_product,
    discard_cash_account_product_draft,
  } from "./api.mjs";
  import { loadPopulation } from "./population.mjs";
  import ProductDrawer from "./ProductDrawer.svelte";

  let { user, members } = $props();

  let loading = $state(true);
  let error = $state(null);
  let rows = $state([]);
  let population = $state(null);
  // product-id → history expanded?
  let open = $state({});
  let templates = $state([]);

  // Drawer state. `mode` decides the form's behaviour; `target`
  // carries the version being edited when in 'edit-draft' mode.
  let drawerOpen = $state(false);
  let drawerMode = $state("create");
  let drawerTarget = $state(null);

  // The kicker is the org's name when /v1/me has surfaced it.
  // When absent we leave it undefined; PageHeader hides empty
  // kickers cleanly, which beats a stand-in "Console" placeholder.
  const kicker = $derived(members?.[0]?.["bank-name"]);

  // Backend statuses map straight to ui badge tones except for
  // `discarded`, which is conceptually archived from the UI's POV.
  const TONE = {
    draft: "draft",
    published: "published",
    discarded: "archived",
  };

  function toneFor(status) {
    return TONE[status] ?? "neutral";
  }

  // A mainline row toggles its history on click, so an action button
  // inside it has to stop the event or every press also expands the row.
  const stop = (fn) => (e) => {
    e.stopPropagation();
    fn();
  };

  async function loadTemplates() {
    const res = await list_cash_account_product_templates();
    if (res.status >= 200 && res.status < 300) {
      templates = res.body?.items ?? [];
    }
    // Non-fatal: drawer falls back to a minimal template set.
  }

  async function load() {
    loading = true;
    error = null;
    try {
      const res = await list_cash_account_products();
      if (res.status >= 200 && res.status < 300) {
        // Response shape: {items: [{product-id, versions: [...]}]}.
        // One row per product; `productRows` picks the version in
        // force today and keeps the rest as history. A product may be
        // revised only when it has no open draft — a second draft would
        // give the publish action two candidates.
        const items = res.body?.items ?? [];
        rows = productRows(items).map((row) => ({
          ...row,
          canRevise: row.draftCount === 0 && row.mainline?.status === "published",
        }));
      } else {
        error = res.body?.detail ?? `HTTP ${res.status}`;
        rows = [];
      }
    } catch (err) {
      error = err.message;
      rows = [];
    } finally {
      loading = false;
    }
  }

  $effect(() => {
    load();
    loadTemplates();
    loadCounts();
  });

  // Swept after first paint so the table isn't held up by it; the
  // Accounts column reads "—" until it lands.
  async function loadCounts() {
    population = await loadPopulation();
  }

  const count = (n) =>
    typeof n === "number" ? n.toLocaleString("en-GB") : null;

  // Per version on every row, because every other cell on a row belongs
  // to one version.
  const versionCount = (v) => population?.byVersion?.[v?.["version-id"]] ?? 0;

  // What the mainline row doesn't already account for. Disjoint from the
  // figure above it on purpose — a product total would repeat the
  // version's own count, which reads as the same number stated twice on
  // a single-version product. Zero hides the line.
  const othersCount = (row) =>
    (population?.byProduct?.[row.productId] ?? 0) - versionCount(row.mainline);

  function openCreate() {
    drawerMode = "create";
    drawerTarget = null;
    drawerOpen = true;
  }

  function openEdit(version) {
    drawerMode = "edit-draft";
    drawerTarget = version;
    drawerOpen = true;
  }

  function openNewVersion(version) {
    drawerMode = "new-version";
    drawerTarget = version;
    drawerOpen = true;
  }

  async function publish(version) {
    const res = await publish_cash_account_product(
      version["product-id"],
      version["version-id"],
    );
    if (res.status >= 200 && res.status < 300) {
      load();
    } else {
      error = res.body?.detail ?? `Publish failed (${res.status})`;
    }
  }

  async function discard(version) {
    const res = await discard_cash_account_product_draft(
      version["product-id"],
      version["version-id"],
    );
    if (res.status >= 200 && res.status < 300) {
      load();
    } else {
      error = res.body?.detail ?? `Discard failed (${res.status})`;
    }
  }

  function formatRelative(iso) {
    if (!iso) return "—";
    const then = new Date(iso).getTime();
    const diff = (Date.now() - then) / 1000;
    if (diff < 60) return "just now";
    if (diff < 3600) return `${Math.floor(diff / 60)} min ago`;
    if (diff < 86400) return `${Math.floor(diff / 3600)} h ago`;
    if (diff < 86400 * 7) return `${Math.floor(diff / 86400)} d ago`;
    return new Date(iso).toLocaleDateString();
  }

  function currenciesLabel(v) {
    return v.currency ?? "—";
  }

</script>

<PageHeader {kicker} title="Products" sub="Drafts are iterable; publishing commits a version from its effective date. Each row shows the version in force today — expand for the rest of its timeline.">
  {#snippet actions()}
    <Button variant="ghost" onclick={load}>Refresh</Button>
    <Button variant="primary" onclick={openCreate}>New product</Button>
  {/snippet}
</PageHeader>

{#if error}
  <div class="alert" role="alert">{error}</div>
{/if}

{#if loading}
  <div class="loading">Loading…</div>
{:else if rows.length === 0}
  <div class="empty">
    <p>No products yet.</p>
    <p class="hint">Click <strong>New product</strong> to create your first one.</p>
  </div>
{:else}
  <Table>
    <Thead>
      <Tr>
        <Th />
        <Th>ID</Th>
        <Th>Name</Th>
        <Th>Type</Th>
        <Th>Status</Th>
        <Th align="right">Rate (bps)</Th>
        <Th>Currency</Th>
        <Th align="right">Accounts</Th>
        <Th>Created</Th>
        <Th align="right">Actions</Th>
      </Tr>
    </Thead>
    <Tbody>
      {#each rows as row (row.productId)}
        {@const v = row.mainline}
        <Tr
          expandable={row.history.length > 0}
          expanded={!!open[row.productId]}
          onclick={() => row.history.length && (open[row.productId] = !open[row.productId])}
        >
          <Td expander>{#if row.history.length}<Expander />{/if}</Td>
          <Td mono muted>{row.productId}</Td>
          <Td emphasized>{v.name}</Td>
          <Td>{v["product-type"]}</Td>
          <Td>
            <span class="status">
              <Badge tone={toneFor(v.status)}>{v.status}</Badge>
              <!-- A published version that isn't yet in force reads as
                   published everywhere else, so say when it starts. -->
              {#if v.status === "published" && !row.live}
                <span class="note mono">from {v["effective-from"]}</span>
              {/if}
              <!-- Advertised, not shown: the draft lives in the history
                   with its actions, and this is what points at it. -->
              {#if row.draftCount > 0}
                <Badge tone="draft">{row.draftCount} draft</Badge>
              {/if}
            </span>
          </Td>
          <Td align="right" mono tabular>{v["interest-rate-bps"] ?? 0}</Td>
          <Td>{currenciesLabel(v)}</Td>
          <Td align="right" mono tabular>
            {#if population}
              {@const others = othersCount(row)}
              {count(versionCount(v))}
              {#if others > 0}
                <span class="others">{count(others)} others</span>
              {/if}
            {:else}
              <span class="pending">—</span>
            {/if}
          </Td>
          <Td muted>{formatRelative(v["created-at"])}</Td>
          <Td align="right">
            <span class="actions">
              {#if v.status === "draft"}
                <Button size="sm" variant="brand" onclick={stop(() => publish(v))}>Publish</Button>
                <Button size="sm" variant="ghost" onclick={stop(() => openEdit(v))}>Edit</Button>
                <Button size="sm" variant="danger" onclick={stop(() => discard(v))}>Discard</Button>
              {:else if row.canRevise}
                <Button size="sm" variant="ghost" onclick={stop(() => openNewVersion(v))}>New version</Button>
              {/if}
            </span>
          </Td>
        </Tr>
        {#if open[row.productId]}
          {#each row.history as h, i (h["version-id"])}
            <Tr balance last={i === row.history.length - 1}>
              <Td />
              <Td mono muted>v{h["version-number"]}</Td>
              <Td>{h.name}</Td>
              <Td>{h["product-type"]}</Td>
              <Td>
                <span class="status">
                  <Badge tone={toneFor(h.status)}>{h.status}</Badge>
                  {#if h["effective-from"]}
                    <span class="note mono">
                      {h["effective-from"]}{h["effective-to"] ? ` – ${h["effective-to"]}` : ""}
                    </span>
                  {/if}
                </span>
              </Td>
              <Td align="right" mono tabular>{h["interest-rate-bps"] ?? 0}</Td>
              <Td>{currenciesLabel(h)}</Td>
              <Td align="right" mono tabular>
                {#if population}
                  {count(versionCount(h))}
                {:else}
                  <span class="pending">—</span>
                {/if}
              </Td>
              <Td muted>{formatRelative(h["created-at"])}</Td>
              <Td align="right">
                <span class="actions">
                  {#if h.status === "draft"}
                    <Button size="sm" variant="brand" onclick={() => publish(h)}>Publish</Button>
                    <Button size="sm" variant="ghost" onclick={() => openEdit(h)}>Edit</Button>
                    <Button size="sm" variant="danger" onclick={() => discard(h)}>Discard</Button>
                  {/if}
                </span>
              </Td>
            </Tr>
          {/each}
        {/if}
      {/each}
    </Tbody>
  </Table>
{/if}

<ProductDrawer
  open={drawerOpen}
  mode={drawerMode}
  target={drawerTarget}
  {templates}
  onClose={() => (drawerOpen = false)}
  onSaved={() => {
    drawerOpen = false;
    load();
  }}
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
  }
  .empty strong {
    color: var(--fg);
    font-weight: 500;
  }
  .actions {
    display: inline-flex;
    gap: 6px;
    justify-content: flex-end;
    white-space: nowrap;
  }
  /* Status cell — the badge, plus whatever qualifies it: the date a
     not-yet-effective version starts, or a count of the drafts waiting
     in the history below. */
  .status {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    white-space: nowrap;
  }
  .status .note {
    font-size: 11px;
    color: var(--fg-muted);
  }
  /* Accounts on the product's other versions — the expandable rows
     below account for them. */
  .others {
    display: block;
    margin-top: 2px;
    font-size: 10.5px;
    font-weight: 400;
    color: var(--fg-muted);
    white-space: nowrap;
  }
  .pending {
    color: var(--fg-muted);
  }
</style>
