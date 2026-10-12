<script>
  import { Checkbox } from "@queenswood/ui";
  const GROUP = ["invitation-created", "invitation-accepted", "member-removed"];
  let kinds = $state(["invitation-accepted"]);
  const toggle = (k) => (kinds = kinds.includes(k) ? kinds.filter((x) => x !== k) : [...kinds, k]);
  const group = () => (kinds.length === 0 ? false : kinds.length === GROUP.length ? true : "mixed");
</script>

<div style="max-width:240px">
  <Checkbox checked={group()} onclick={() => (kinds = group() === true ? [] : [...GROUP])}>
    People
    {#snippet aside()}{kinds.length}/{GROUP.length}{/snippet}
  </Checkbox>
  {#each GROUP as k (k)}
    <Checkbox checked={kinds.includes(k)} title={k} onclick={() => toggle(k)}>{k}</Checkbox>
  {/each}
</div>
