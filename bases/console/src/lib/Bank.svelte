<script>
  /* Bank — the bank this console acts on, the others the person
     belongs to, and the danger zone where a fresh one comes from.

     The platform never deletes a bank. What a fresh sandbox bank does
     is leave the old one standing, with its books intact, and move the
     console onto a new, empty bank: the old one stays in the list here
     and switching back is how it is restored. Every page reads
     `memberships[0]` as the bank it acts on, so switching is App's
     job; this page only asks for it. */

  import { PageHeader, Panel, PanelHead, Button, Badge, RolePill, accessEnum } from "@queenswood/ui";

  let { user, memberships = [], onSwitch, onFreshBank } = $props();

  const current = $derived(memberships?.[0]);
  const kicker = $derived(current?.["bank-name"]);
  const others = $derived(memberships.slice(1));
  const isCurrent = (m) => m["bank-id"] === current?.["bank-id"];

  const fmtDate = (iso) =>
    iso
      ? new Date(iso).toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" })
      : "—";
</script>

<PageHeader
  {kicker}
  title="Bank"
  sub="The bank this console acts on, the others you belong to, and where a fresh sandbox bank comes from."
/>

<Panel>
  <PanelHead title="This bank" />
  <dl class="facts body">
    <div><dt>Name</dt><dd>{current?.["bank-name"] ?? "—"}</dd></div>
    <div><dt>Bank id</dt><dd class="mono">{current?.["bank-id"] ?? "—"}</dd></div>
    <div><dt>Your role</dt><dd><RolePill role={accessEnum(current?.role)} /></dd></div>
    <div><dt>Member since</dt><dd>{fmtDate(current?.["created-at"])}</dd></div>
  </dl>
</Panel>

<Panel>
  <PanelHead
    title="Your banks"
    count={memberships.length}
    note="switching changes the bank every page acts on"
  />
  <div class="body">
  <ul class="banks">
    {#each memberships as m (m["membership-id"] ?? m["bank-id"])}
      <li class="bank" class:current={isCurrent(m)}>
        <div class="bank-main">
          <span class="bank-name">{m["bank-name"] ?? m["bank-id"]}</span>
          <span class="bank-sub">
            <RolePill role={accessEnum(m.role)} />
            <span class="sep">·</span>
            <span>since {fmtDate(m["created-at"])}</span>
            <span class="sep">·</span>
            <span class="mono">{m["bank-id"]}</span>
          </span>
        </div>
        {#if isCurrent(m)}
          <Badge tone="published">current</Badge>
        {:else}
          <Button variant="line" size="sm" onclick={() => onSwitch?.(m["bank-id"])}>Switch to this bank</Button>
        {/if}
      </li>
    {/each}
  </ul>
  {#if others.length === 0}
    <p class="hint">You belong to one bank. A fresh sandbox bank, started below, appears here beside it.</p>
  {/if}
  </div>
</Panel>

<section class="danger" aria-labelledby="danger-title">
  <h3 id="danger-title">Danger zone</h3>
  <div class="danger-row">
    <div class="danger-text">
      <p class="danger-lead">Start a fresh sandbox bank</p>
      <p class="danger-body">
        Bind a new, empty bank to a legal entity and move the console onto it. Nothing here is deleted:
        <strong>{current?.["bank-name"] ?? "this bank"}</strong> keeps its products, parties, accounts and
        books, stays in the list above, and switching back is how you restore it.
      </p>
    </div>
    <Button variant="danger" onclick={onFreshBank}>Start a fresh bank</Button>
  </div>
</section>

<style>
  .body {
    padding: 14px 20px 18px;
  }
  .facts {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 14px 24px;
    margin: 0;
  }
  .facts div {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
  .facts dt {
    font-size: 11px;
    font-weight: 600;
    letter-spacing: 0.14em;
    text-transform: uppercase;
    color: var(--fg-muted);
  }
  .facts dd {
    margin: 0;
    font-size: 14px;
    color: var(--fg);
  }
  .mono {
    font-family: var(--mono);
    font-size: 12.5px;
  }
  .banks {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
  }
  .bank {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    padding: 12px 0;
    border-top: 1px solid var(--rule-2);
  }
  .bank:first-child {
    border-top: 0;
    padding-top: 2px;
  }
  .bank-main {
    display: flex;
    flex-direction: column;
    gap: 4px;
    min-width: 0;
  }
  .bank-name {
    font-size: 14px;
    font-weight: 500;
    color: var(--fg);
  }
  .bank-sub {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 6px;
    font-size: 12.5px;
    color: var(--fg-muted);
  }
  .sep {
    opacity: 0.6;
  }
  .hint {
    margin: 12px 0 0;
    font-size: 12.5px;
    color: var(--fg-muted);
    line-height: 1.5;
  }
  .danger {
    border: 1px solid var(--danger);
    border-radius: 12px;
    padding: 20px 24px;
    display: flex;
    flex-direction: column;
    gap: 14px;
  }
  .danger h3 {
    margin: 0;
    font-size: 13px;
    font-weight: 600;
    letter-spacing: 0.12em;
    text-transform: uppercase;
    color: var(--danger);
  }
  .danger-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 24px;
  }
  .danger-text {
    display: flex;
    flex-direction: column;
    gap: 6px;
    max-width: 62ch;
  }
  .danger-lead {
    margin: 0;
    font-size: 14px;
    font-weight: 500;
    color: var(--fg);
  }
  .danger-body {
    margin: 0;
    font-size: 13px;
    line-height: 1.55;
    color: var(--fg-muted);
  }
  @media (max-width: 720px) {
    .facts {
      grid-template-columns: 1fr;
    }
    .danger-row {
      flex-direction: column;
      align-items: flex-start;
    }
  }
</style>
