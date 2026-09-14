<script>
  /* Table — wrapped table with surface, hairlines, and consistent
     column chrome. Compose with Thead, Tbody, Tr, Th, Td.

         <Table>
           <Thead>
             <Tr>
               <Th>ID</Th>
               <Th>Name</Th>
               <Th align="right">Rate</Th>
             </Tr>
           </Thead>
           <Tbody>
             <Tr>
               <Td mono muted>prd.01...</Td>
               <Td emphasized>Current Account</Td>
               <Td align="right" mono tabular>265</Td>
             </Tr>
           </Tbody>
         </Table>

     Td variants (booleans): mono, muted, emphasized, tabular.
     Alignment via `align="right"` (or "center").

     TREE MODE — `<Table tree>` turns the table into an expandable
     tree-table for decomposable rows (e.g. a ledger account and the
     balances that comprise it). Markup contract:

         <Table tree>
           <Thead>
             <Tr>
               <Th />                 ← expander column header (empty)
               <Th>ID</Th> <Th>Name</Th> <Th>GL Code</Th>
               <Th align="right">Available Balance</Th>
             </Tr>
           </Thead>
           <Tbody>
             {#each accounts as acc}
               <Tr expandable expanded={open[acc.id]}
                   onclick={() => toggle(acc.id)}>
                 <Td expander><Expander /></Td>
                 <Td mono muted>{acc.id}</Td>
                 <Td emphasized>{acc.name}<span class="qw-denom">{acc.ccy}</span></Td>
                 <Td mono>{acc.gl}</Td>
                 <MoneyCell minor={available(acc)} ccy={acc.ccy} emphasized
                            meta={`${acc.balances.length} balances`} />
               </Tr>
               {#if open[acc.id]}
                 {#each acc.balances as b, i}
                   <Tr balance last={i === acc.balances.length - 1}>
                     <Td expander />
                     <Td />
                     <Td addr>
                       <span class="qw-tree-mark">
                         <span class="qw-addr-path">{b.address}<span class="slash">/</span>{b.phase}</span>
                         <Phase phase={b.phase} />
                       </span>
                     </Td>
                     <Td mono muted>{b.asset}</Td>
                     <MoneyCell minor={b.minor} ccy={acc.ccy} />
                   </Tr>
                 {/each}
               {/if}
             {/each}
           </Tbody>
         </Table>

     Wire toggling in the consumer (one `open` map). Keyboard: the
     expandable Tr is role=button + tabindex=0, so add an Enter/Space
     handler that mirrors the click.

     FLUSH MODE — `<Table flush>` drops the wrapper's border and radius
     and scrolls sideways when narrow, for a table that fills a Panel
     under its PanelHead. */

  let { tree = false, flush = false, children, ...rest } = $props();
</script>

<div class="table-wrap" class:flush>
  <table class="qw-table" class:qw-table--tree={tree} {...rest}>
    {@render children?.()}
  </table>
</div>

<style>
  .table-wrap {
    background: var(--surface-raised);
    border: 1px solid var(--rule-2);
    border-radius: 6px;
    overflow: hidden;
  }
  .table-wrap.flush {
    border: none;
    border-radius: 0;
    background: transparent;
    overflow-x: auto;
  }
  .qw-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 13px;
    color: var(--fg);
  }

  /* These selectors reach into Thead/Tbody/Tr/Th/Td descendants.
     :global is intentional — the Table component owns the visual
     contract; its children just emit semantic HTML. */
  :global(.qw-table thead th) {
    text-align: left;
    font-family: var(--mono);
    font-size: 10px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--fg-muted);
    font-weight: 500;
    padding: 12px 16px;
    background: var(--surface-sunk);
    border-bottom: 1px solid var(--rule-2);
    white-space: nowrap;
  }
  /* The header's default left-align outranks the .qw-cell-right utility
     (more element selectors), so a `<Th align="right">` would otherwise
     stay left while its body cells align right. Re-assert alignment on
     the header with matching specificity so numeric columns line up. */
  :global(.qw-table thead th.qw-cell-right)  { text-align: right; }
  :global(.qw-table thead th.qw-cell-center) { text-align: center; }
  :global(.qw-table tbody td) {
    padding: 14px 16px;
    border-bottom: 1px solid var(--rule-2);
    vertical-align: middle;
    color: var(--fg-2);
  }
  :global(.qw-table tbody tr:last-child td) { border-bottom: none; }
  :global(.qw-table tbody tr:hover td) { background: var(--hover-overlay); }

  /* Td/Th utility classes — toggled by Td.svelte / Th.svelte props. */
  :global(.qw-td-mono)       { font-family: var(--mono); font-size: 12px; }
  :global(.qw-td-muted)      { color: var(--fg-muted); }
  :global(.qw-td-emphasized) { color: var(--fg); font-weight: 500; }
  :global(.qw-td-tabular)    { font-variant-numeric: tabular-nums; }
  :global(.qw-cell-right)    { text-align: right; }
  :global(.qw-cell-center)   { text-align: center; }

  /* ==========================================================
     TREE MODE — expandable account rows + balance child rows.
     ========================================================== */

  /* Expander column — narrow leftmost cell + the chevron button. */
  :global(.qw-table--tree .qw-cell-expander) {
    width: 44px;
    padding-left: 16px;
    padding-right: 0;
  }
  :global(.qw-table--tree .qw-expander-btn) {
    width: 22px;
    height: 22px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border-radius: 5px;
    color: var(--fg-muted);
    transition: background 0.1s, color 0.1s;
  }
  :global(.qw-table--tree tr.qw-account:hover .qw-expander-btn) {
    color: var(--fg);
  }
  :global(.qw-table--tree .qw-expander-btn svg) {
    width: 12px;
    height: 12px;
    transition: transform 0.16s ease;
  }
  :global(.qw-table--tree tr.qw-account[aria-expanded="true"] .qw-expander-btn svg) {
    transform: rotate(90deg);
  }

  /* Account (parent) row — clickable. */
  :global(.qw-table--tree tr.qw-account) { cursor: pointer; }
  :global(.qw-table--tree tr.qw-account:focus-visible) {
    outline: 2px solid var(--gold);
    outline-offset: -2px;
  }
  /* Currency code chip next to the account name. */
  :global(.qw-table--tree .qw-denom) {
    margin-left: 8px;
    font-family: var(--mono);
    font-size: 10px;
    letter-spacing: 0.04em;
    color: var(--fg-muted);
    border: 1px solid var(--rule);
    border-radius: 4px;
    padding: 1px 5px;
    vertical-align: middle;
  }

  /* Balance (child) rows — sunk, tighter, indented. */
  :global(.qw-table--tree tr.qw-balance) { background: var(--surface-sunk); }
  :global(.qw-table--tree tr.qw-balance:hover td) { background: var(--hover-overlay); }
  :global(.qw-table--tree tr.qw-balance td) {
    padding-top: 9px;
    padding-bottom: 9px;
    border-bottom: 1px solid var(--rule-2);
  }
  /* Firmer rule closes the balance group under each account. */
  :global(.qw-table--tree tr.qw-balance-last td) { border-bottom: 1px solid var(--rule); }

  /* Balance address cell — monospace, indented, with a connector elbow. */
  :global(.qw-table--tree td.qw-cell-addr) {
    font-family: var(--mono);
    font-size: 12px;
    color: var(--fg-2);
    padding-left: 20px;
  }
  :global(.qw-table--tree .qw-tree-mark) {
    display: inline-flex;
    align-items: center;
    gap: 9px;
  }
  :global(.qw-table--tree .qw-tree-mark::before) {
    content: "";
    width: 12px;
    height: 9px;
    border-left: 1.5px solid var(--rule);
    border-bottom: 1.5px solid var(--rule);
    border-bottom-left-radius: 3px;
    margin-top: -6px;
    flex: 0 0 auto;
  }
  :global(.qw-table--tree .qw-addr-path) { color: var(--fg); }
  :global(.qw-table--tree .qw-addr-path .slash) {
    color: var(--fg-muted);
    opacity: 0.7;
    margin: 0 1px;
  }
</style>
