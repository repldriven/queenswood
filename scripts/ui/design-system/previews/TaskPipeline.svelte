<script>
  import { TaskPipeline } from "@queenswood/ui";
</script>

<div class="pv-stack">
  <TaskPipeline
    steps={[
      { name: "Mint admin token", status: "exception" },
      { name: "Open accounts", status: "ok" },
      { name: "Fund · inbound FPS", status: "running" },
      { name: "Trial balance ties", status: "pending" },
    ]}
  />
  <TaskPipeline
    dense
    steps={[
      { name: "accrue", status: "ok", detail: "12,480 processed · 3.4s" },
      { name: "capitalize", status: "ok", detail: "12,480 processed · 8.1s", alert: "400 failed" },
      { name: "migrate", status: "skipped", detail: "never ran" },
    ]}
  />
</div>
