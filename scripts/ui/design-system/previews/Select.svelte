<script>
  import { Select } from "@queenswood/ui";
  let ccy = $state("GBP");
</script>

<div style="max-width:420px">
  <Select bind:value={ccy} aria-label="Currency">
    <option>GBP</option>
    <option>EUR</option>
    <option>USD</option>
  </Select>
</div>
