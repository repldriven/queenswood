<script>
  import { BankStateBand, Button } from "@queenswood/ui";
</script>

<BankStateBand
  cells={[
    { figure: 3, unit: "/ 6", label: "Scenes run" },
    { figure: 2, label: "Products live" },
    { figure: 2, unit: "/ 3", label: "Active customers" },
    { figure: "£2,500.00", label: "Customer money held" },
  ]}
  attentionTone="good"
>
  {#snippet icon()}
    <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="8" cy="8" r="6.4" stroke-opacity="0.4" /><path d="M5.2 8.2 L7.1 10 L10.8 6" /></svg>
  {/snippet}
  {#snippet title()}Books tie — debits equal credits{/snippet}
  {#snippet sub()}2 customers · £2,500.00 held{/snippet}
  {#snippet action()}<Button variant="brand" size="sm">Run Scene 04</Button>{/snippet}
</BankStateBand>
