<script>
  /* Parties page — the bank's customers and the bank's own
     organization. Clicking a row opens the read drawer; the drawer's
     Edit button switches to the form. */

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
  } from "@queenswood/ui";
  import { list_parties } from "./api.mjs";
  import PartyDrawer from "./PartyDrawer.svelte";

  let { user, memberships } = $props();

  let loading = $state(true);
  let error = $state(null);
  let parties = $state([]);

  let drawerOpen = $state(false);
  let drawerMode = $state("read");
  let drawerTarget = $state(null);

  const kicker = $derived(memberships?.[0]?.["bank-name"]);

  // bank-api `PartyStatus` enum → ui Badge tones.
  const TONE = {
    active: "published",
    pending: "pending",
    rejected: "rejected",
  };

  function toneFor(status) {
    return TONE[status] ?? "neutral";
  }

  async function load() {
    loading = true;
    error = null;
    try {
      const res = await list_parties();
      if (res.status >= 200 && res.status < 300) {
        parties = res.body?.items ?? [];
      } else {
        error = res.body?.detail ?? `HTTP ${res.status}`;
        parties = [];
      }
    } catch (err) {
      error = err.message;
      parties = [];
    } finally {
      loading = false;
    }
  }

  $effect(() => {
    load();
  });

  function openRead(party) {
    drawerMode = "read";
    drawerTarget = party;
    drawerOpen = true;
  }

  function openCreate() {
    drawerMode = "create";
    drawerTarget = null;
    drawerOpen = true;
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
</script>

<PageHeader
  {kicker}
  title="Parties"
  sub="Your customers, and your bank's own organization. Your customer stays pending until identity verification passes; your bank's organization is active from the start."
>
  {#snippet actions()}
    <Button variant="ghost" onclick={load}>Refresh</Button>
    <Button variant="primary" onclick={openCreate}>Onboard Customer</Button>
  {/snippet}
</PageHeader>

{#if error}
  <div class="alert" role="alert">{error}</div>
{/if}

{#if loading}
  <div class="loading">Loading…</div>
{:else if parties.length === 0}
  <div class="empty">
    <p>No parties yet.</p>
    <p class="hint">Click <strong>Onboard Customer</strong> to add your first one.</p>
  </div>
{:else}
  <Table>
    <Thead>
      <Tr>
        <Th>ID</Th>
        <Th>Name</Th>
        <Th>Type</Th>
        <Th>Status</Th>
        <Th>Created</Th>
        <Th>Updated</Th>
        <Th align="right">Action</Th>
      </Tr>
    </Thead>
    <Tbody>
      {#each parties as p (p["party-id"])}
        <Tr onclick={() => openRead(p)} class="row-clickable">
          <Td mono muted>{p["party-id"]}</Td>
          <Td emphasized>{p["display-name"]}</Td>
          <Td>{p.type === "person" ? "customer" : (p.type ?? "")}</Td>
          <Td><Badge tone={toneFor(p.status)}>{p.status}</Badge></Td>
          <Td muted>{formatRelative(p["created-at"])}</Td>
          <Td muted>{formatRelative(p["updated-at"])}</Td>
          <Td align="right" muted>—</Td>
        </Tr>
      {/each}
    </Tbody>
  </Table>
{/if}

<PartyDrawer
  open={drawerOpen}
  mode={drawerMode}
  target={drawerTarget}
  onClose={() => (drawerOpen = false)}
  onModeChange={(m) => (drawerMode = m)}
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
  /* Row click affordance — the row opens the read drawer for that
     party. Matches the mockup's `.qw-table.parties tbody tr`. */
  :global(.row-clickable) {
    cursor: pointer;
  }
</style>
