<script>
  import { SceneCard, TaskPipeline } from "@queenswood/ui";
  let open = $state(true);
</script>

<SceneCard
  num="03"
  title="Money in, double-entry out"
  story="Open accounts for two customers and fund one with an inbound Faster Payment. The books move — debits equal credits, to the penny."
  status="ready"
  payoffLabel="ledger"
  {open}
  onToggle={() => (open = !open)}
  onRun={() => {}}
>
  {#snippet body()}
    <TaskPipeline
      steps={[
        { name: "Mint admin token", status: "ok" },
        { name: "Open accounts", status: "ok" },
        { name: "Fund · inbound FPS", status: "running" },
        { name: "Trial balance ties", status: "pending" },
      ]}
    />
  {/snippet}
</SceneCard>
