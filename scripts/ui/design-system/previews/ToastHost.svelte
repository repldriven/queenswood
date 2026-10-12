<script>
  import { onMount } from "svelte";
  import { ToastHost, Button, toast } from "@queenswood/ui";
  const withDetail = () => toast("Migration approved", "mig.01kz3wyzcjhkab9h91x9ngedr");
  onMount(withDetail);
</script>

<div class="pv-row">
  <Button onclick={withDetail}>Toast with detail</Button>
  <Button variant="ghost" onclick={() => toast("Saved — preview discarded because the scope changed")}>Toast, no detail</Button>
</div>
<ToastHost />
