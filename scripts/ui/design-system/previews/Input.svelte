<script>
  import { Input } from "@queenswood/ui";
  let name = $state("GBP Current Account");
  let rate = $state(265);
</script>

<div class="pv-stack" style="max-width:420px">
  <Input bind:value={name} aria-label="Product name" />
  <Input type="number" affix="bps" bind:value={rate} aria-label="Interest rate" />
  <Input placeholder="Sort code" aria-label="Sort code" />
</div>
