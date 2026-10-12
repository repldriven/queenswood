<script>
  import { Chip } from "@queenswood/ui";
  let filter = $state("all");
</script>

<div class="pv-row">
  <Chip pressed={filter === "all"} count={9588} onclick={() => (filter = "all")}>All</Chip>
  <Chip pressed={filter === "move"} count={9039} onclick={() => (filter = "move")}>Would move</Chip>
  <Chip pressed={filter === "held"} count={549} onclick={() => (filter = "held")}>Held back</Chip>
  <Chip disabled count={0}>Disabled</Chip>
</div>
