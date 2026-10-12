<script>
  import { Field, Input, Select } from "@queenswood/ui";
  let type = $state("Current Account");
  let name = $state("GBP Current Account");
  let rate = $state(265);
</script>

<form class="pv-stack" style="max-width:420px" onsubmit={(e) => e.preventDefault()}>
  <Field label="Account type" htmlFor="pv-type">
    <Select id="pv-type" bind:value={type}>
      <option>Current Account</option>
      <option>Settlement Account</option>
      <option>Savings</option>
    </Select>
  </Field>
  <Field label="Product name" htmlFor="pv-name">
    <Input id="pv-name" bind:value={name} />
    {#snippet hint()}
      Defaults to <code>&#123;currency&#125; &#123;type&#125;</code>. Override if needed.
    {/snippet}
  </Field>
  <Field label="Interest rate" htmlFor="pv-rate" hint="Basis points. 100 bps = 1.00%.">
    <Input id="pv-rate" type="number" affix="bps" bind:value={rate} />
  </Field>
</form>
