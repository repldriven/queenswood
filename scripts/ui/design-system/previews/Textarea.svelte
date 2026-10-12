<script>
  import { Textarea } from "@queenswood/ui";
  let reason = $state("Joining the payments team");
</script>

<div style="max-width:420px">
  <Textarea bind:value={reason} maxlength={500} placeholder="Kept on the record." aria-label="Reason" />
</div>
