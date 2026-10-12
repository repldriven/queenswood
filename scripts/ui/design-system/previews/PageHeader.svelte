<script>
  import { PageHeader, Button } from "@queenswood/ui";
</script>

<PageHeader
  kicker="Galactic Bank"
  title="Products"
  sub="Drafts are iterable; publishing commits a version and auto-archives the one it supersedes."
>
  {#snippet actions()}
    <Button variant="ghost">Refresh</Button>
    <Button variant="primary">New product</Button>
  {/snippet}
</PageHeader>
