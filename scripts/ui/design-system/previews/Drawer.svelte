<script>
  import { Drawer, Field, Input, Select, Button } from "@queenswood/ui";
  let open = $state(true);
  let type = $state("Current Account");
  let name = $state("GBP Current Account");
  let rate = $state(265);
</script>

<Button variant="primary" onclick={() => (open = true)}>Open drawer</Button>

<Drawer
  {open}
  onClose={() => (open = false)}
  kicker="Define"
  title="New product"
  sub="Drafts are iterable—save, come back, edit again. Publishing commits a version and releases it to onboarding flows."
>
  <Field label="Account type" htmlFor="pv-dd-type">
    <Select id="pv-dd-type" bind:value={type}>
      <option>Current Account</option>
      <option>Settlement Account</option>
      <option>Savings</option>
    </Select>
  </Field>
  <Field label="Product name" htmlFor="pv-dd-name">
    <Input id="pv-dd-name" bind:value={name} />
  </Field>
  <Field label="Interest rate" htmlFor="pv-dd-rate" hint="Basis points. 100 bps = 1.00%.">
    <Input id="pv-dd-rate" type="number" affix="bps" bind:value={rate} />
  </Field>
  {#snippet footer()}
    <Button variant="primary" size="lg" block onclick={() => (open = false)}>Create product</Button>
  {/snippet}
</Drawer>
