<script>
  import { Table, Thead, Tbody, Tr, Th, Td, MoneyCell } from "@queenswood/ui";
</script>

<Table>
  <Thead>
    <Tr><Th>Balance</Th><Th align="right">Amount</Th></Tr>
  </Thead>
  <Tbody>
    <Tr><Td emphasized>Account total</Td><MoneyCell minor={478_646_035} ccy="GBP" emphasized meta="3 balances" /></Tr>
    <Tr><Td>Posted</Td><MoneyCell minor={482_014_055} ccy="GBP" /></Tr>
    <Tr><Td>Suspense</Td><MoneyCell minor={0} ccy="GBP" /></Tr>
    <Tr><Td>Pending outgoing</Td><MoneyCell minor={-4598020} ccy="GBP" /></Tr>
  </Tbody>
</Table>
