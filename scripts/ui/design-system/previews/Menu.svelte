<script>
  import { onMount } from "svelte";
  import { Menu, Button } from "@queenswood/ui";
  let trigger = $state(null);
  let anchor = $state(null);
  const open = () => (anchor = trigger.getBoundingClientRect());
  onMount(open);
</script>

<div class="pv-row" style="justify-content:flex-end;max-width:320px">
  <span bind:this={trigger}><Button variant="ghost" size="sm" onclick={open} aria-label="Job actions">⋯</Button></span>
</div>
{#if anchor}
  <Menu
    {anchor}
    onClose={() => (anchor = null)}
    items={[
      { label: "Run now" },
      { label: "Edit schedule", divider: true },
      { label: "Pause" },
      { label: "Delete job", divider: true, danger: true },
    ]}
  />
{/if}
