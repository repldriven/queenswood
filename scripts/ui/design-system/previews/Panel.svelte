<script>
  import { Panel, PanelHead, Table, Thead, Tbody, Tr, Th, Td } from "@queenswood/ui";
</script>

<div class="pv-stack">
  <Panel>
    <PanelHead title="Runs" count={2} note="one run per business day" />
    <Table flush>
      <Thead>
        <Tr><Th>Business day</Th><Th>Kind</Th><Th align="right">Seen</Th></Tr>
      </Thead>
      <Tbody>
        <Tr><Td mono>30 Jul 2026</Td><Td>dry run</Td><Td mono tabular align="right">9,588</Td></Tr>
        <Tr><Td mono>2 Jul 2026</Td><Td>dry run</Td><Td mono tabular align="right">9,588</Td></Tr>
      </Tbody>
    </Table>
  </Panel>
  <Panel pad="lg">A <code>pad="lg"</code> panel — 22px by 24px, what a hero uses.</Panel>
</div>
