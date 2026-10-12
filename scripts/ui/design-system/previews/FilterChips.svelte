<script>
  import { FilterChips } from "@queenswood/ui";
</script>

<div class="pv-stack">
  <div class="pv-row"><FilterChips filters={[]} /></div>
  <div class="pv-row"><FilterChips filters={[{ key: "scheme", value: "fps" }]} /></div>
  <div class="pv-row">
    <FilterChips
      filters={[
        { key: "computed", value: "available" },
        { key: "txn", value: "internal-transfer" },
        { key: "txn", value: "outbound-transfer" },
        { key: "scheme", value: "fps" },
      ]}
    />
  </div>
</div>
