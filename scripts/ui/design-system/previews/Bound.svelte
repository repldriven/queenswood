<script>
  import { Bound } from "@queenswood/ui";
</script>

<div class="pv-stack">
  <div class="pv-row"><Bound bound={{ kind: "max", aggregate: { type: "count", value: 100000, window: "daily" } }} /></div>
  <div class="pv-row"><Bound bound={{ kind: "min", aggregate: { type: "amount", minor: 0, ccy: "GBP", window: "instant" } }} /></div>
  <div class="pv-row">
    <Bound
      bound={{
        kind: "range",
        min: { type: "amount", minor: 100, ccy: "GBP", window: "instant" },
        max: { type: "amount", minor: 1_000_000, ccy: "GBP", window: "instant" },
      }}
    />
  </div>
</div>
