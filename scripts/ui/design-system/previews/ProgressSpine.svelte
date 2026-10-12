<script>
  import { ProgressSpine } from "@queenswood/ui";
</script>

<ProgressSpine
  title="A bank opening its doors"
  progressLabel="scenes run"
  steps={[
    { num: "01", label: "Stock the shelves", status: "done" },
    { num: "02", label: "Identity decides the account", status: "done" },
    { num: "03", label: "Money in, double-entry out", status: "running" },
    { num: "04", label: "Nothing is ever lost", status: "ready" },
    { num: "05", label: "Send it out", status: "locked" },
    { num: "06", label: "Runs itself overnight", status: "locked" },
  ]}
/>
