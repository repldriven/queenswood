<script>
  import { TimeRangeFilter } from "@queenswood/ui";
  const now = Date.UTC(2026, 9, 12, 9, 42);
  let range = $state({ from: now - 6 * 3600e3, to: now });
</script>

<div style="max-width:260px">
  <TimeRangeFilter {range} onChange={(r) => (range = r)} note="Either end may be left open." />
</div>
