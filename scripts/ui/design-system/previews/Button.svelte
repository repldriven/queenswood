<script>
  import { Button } from "@queenswood/ui";
  const ROWS = [
    ["primary", "Save"],
    ["brand", "Publish"],
    ["line", "Refresh"],
    ["ghost", "Edit"],
    ["danger", "Discard"],
  ];
</script>

<div class="pv-stack">
  {#each ROWS as [variant, text] (variant)}
    <div class="pv-row">
      <span class="pv-label">{variant}</span>
      <Button {variant} size="sm">{text}</Button>
      <Button {variant}>{text}</Button>
      <Button {variant} size="lg">{text}</Button>
      <Button {variant} disabled>{text}</Button>
    </div>
  {/each}
  <div class="pv-row">
    <span class="pv-label">solid</span>
    <Button variant="danger" solid>Delete forever</Button>
  </div>
  <div style="max-width:420px"><Button variant="primary" size="lg" block>Create product</Button></div>
</div>
