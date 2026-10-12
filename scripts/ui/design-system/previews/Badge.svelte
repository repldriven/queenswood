<script>
  import { Badge } from "@queenswood/ui";
  const GROUPS = [
    ["record", ["draft", "published", "archived", "pending", "rejected", "neutral"]],
    ["policy", ["standard", "restricted", "emergency"]],
    ["job run", ["succeeded", "failed", "running", "scheduled"]],
    ["invitation", ["pending", "accepted", "expired", "withdrawn", "declined"]],
  ];
</script>

<div class="pv-stack">
  {#each GROUPS as [label, tones] (label)}
    <div class="pv-row">
      <span class="pv-label">{label}</span>
      {#each tones as tone (tone)}<Badge {tone}>{tone}</Badge>{/each}
    </div>
  {/each}
</div>
