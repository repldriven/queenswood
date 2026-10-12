<script>
  import { Logo } from "@queenswood/ui";
  const VARIANTS = ["A", "B", "C", "D", "E"];
  const SIZES = [120, 80, 48, 32, 24];
</script>

<div class="pv-stack">
  <div class="pv-row">
    {#each VARIANTS as v (v)}
      <div class="pv-cell"><Logo variant={v} size={96} idPrefix="pv-{v}" /><span class="pv-label">Mark {v}</span></div>
    {/each}
  </div>
  <div class="pv-row" style="align-items:flex-end">
    {#each SIZES as s (s)}
      <div class="pv-cell"><Logo variant="A" size={s} idPrefix="pv-sz-{s}" /><span class="pv-label">{s}px</span></div>
    {/each}
  </div>
</div>
