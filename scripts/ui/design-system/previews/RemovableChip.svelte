<script>
  import { RemovableChip } from "@queenswood/ui";
  let chips = $state(["By Bank", "Invitation accepted", "Last 24 hours"]);
</script>

<div class="pv-row">
  {#each chips as c (c)}
    <RemovableChip onremove={() => (chips = chips.filter((x) => x !== c))}>{c}</RemovableChip>
  {/each}
</div>
