<script>
  import { Wordmark } from "@queenswood/ui";
</script>

<div class="pv-row" style="gap:48px;align-items:flex-start">
  <div class="pv-stack">
    {#each [32, 24, 18, 14] as s (s)}
      <Wordmark variant="grotesk" size={s} />
    {/each}
  </div>
  <div class="pv-stack">
    {#each [48, 32, 24, 18] as s (s)}
      <Wordmark variant="serif" size={s} />
    {/each}
  </div>
</div>
