<script>
  import { Table, Thead, Tbody, Tr, Th, Td, Badge, Expander, MoneyCell, Phase, sumMinor } from "@queenswood/ui";

  const ACCOUNTS = [
    {
      id: "led.01jq8wm4zr5k2c7d9f3h6n0pqx", name: "Customer Deposits — GBP Pool", gl: "2100", ccy: "GBP",
      balances: [
        { type: "default", phase: "posted", currency: "GBP", minor: 482_014_055 },
        { type: "default", phase: "pending-incoming", currency: "GBP", minor: 1_230_000 },
        { type: "default", phase: "pending-outgoing", currency: "GBP", minor: -4598020 },
      ],
    },
    {
      id: "led.01jq8wmd2k7n3p6r9t1w4y8b5e", name: "Cash at Correspondent — GBP", gl: "1100", ccy: "GBP",
      balances: [
        { type: "default", phase: "posted", currency: "GBP", minor: -12845000 },
        { type: "interest-accrued", phase: "posted", currency: "GBP", minor: -120466 },
      ],
    },
  ];
  let open = $state({ [ACCOUNTS[0].id]: true });
  const toggle = (id) => (open[id] = !open[id]);
  const key = (e, id) => {
    if (e.key === "Enter" || e.key === " ") { e.preventDefault(); toggle(id); }
  };
</script>

<div class="pv-stack">
  <Table>
    <Thead>
      <Tr><Th>ID</Th><Th>Name</Th><Th>Status</Th><Th align="right">Rate</Th><Th>Currency</Th></Tr>
    </Thead>
    <Tbody>
      <Tr>
        <Td mono muted>prd.01ks5kya</Td><Td emphasized>GBP Current Account</Td>
        <Td><Badge tone="draft">draft</Badge></Td><Td align="right" mono tabular>265</Td><Td mono>GBP</Td>
      </Tr>
      <Tr>
        <Td mono muted>prd.01ks5kxd</Td><Td emphasized>GBP Settlement</Td>
        <Td><Badge tone="published">published</Badge></Td><Td align="right" mono tabular>0</Td><Td mono>GBP</Td>
      </Tr>
      <Tr>
        <Td mono muted>prd.01ks3rwb</Td><Td emphasized>GBP Deposit</Td>
        <Td><Badge tone="archived">archived</Badge></Td><Td align="right" mono tabular>510</Td><Td mono>GBP</Td>
      </Tr>
    </Tbody>
  </Table>

  <Table tree>
    <Thead>
      <Tr><Th /><Th>ID</Th><Th>Name</Th><Th>GL Code</Th><Th align="right">Available Balance</Th></Tr>
    </Thead>
    <Tbody>
      {#each ACCOUNTS as acc (acc.id)}
        <Tr expandable expanded={open[acc.id]} onclick={() => toggle(acc.id)} onkeydown={(e) => key(e, acc.id)}>
          <Td expander><Expander /></Td>
          <Td mono muted>{acc.id}</Td>
          <Td emphasized>{acc.name}<span class="qw-denom">{acc.ccy}</span></Td>
          <Td mono>{acc.gl}</Td>
          <MoneyCell minor={sumMinor(acc.balances)} ccy={acc.ccy} emphasized meta={`${acc.balances.length} balances`} />
        </Tr>
        {#if open[acc.id]}
          {#each acc.balances as b, i (b.type + ":" + b.phase)}
            <Tr balance last={i === acc.balances.length - 1}>
              <Td expander /><Td />
              <Td addr>
                <span class="qw-tree-mark"><span class="qw-addr-path">{b.type}</span><Phase phase={b.phase} /></span>
              </Td>
              <Td mono muted>{b.currency}</Td>
              <MoneyCell minor={b.minor} ccy={acc.ccy} />
            </Tr>
          {/each}
        {/if}
      {/each}
    </Tbody>
  </Table>
</div>
