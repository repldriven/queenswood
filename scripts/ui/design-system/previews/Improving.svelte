<script>
  import { Improving, Bound } from "@queenswood/ui";
</script>

<div class="pv-row">
  <Bound bound={{ kind: "min", aggregate: { type: "amount", minor: 0, ccy: "GBP", window: "instant" } }} />
  <Improving />
</div>
