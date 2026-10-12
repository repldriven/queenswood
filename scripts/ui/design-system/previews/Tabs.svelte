<script>
  import { Tabs } from "@queenswood/ui";
  let tab = $state("members");
</script>

<Tabs
  label="People"
  bind:value={tab}
  tabs={[
    { id: "members", label: "Members", count: 5 },
    { id: "invitations", label: "Invitations", count: "3 pending" },
    { id: "history", label: "History", count: "8+" },
  ]}
/>
