<script>
  /* JobStatusBadge — maps a job's run state to the matching Badge tone.

     `outcome` is one of completed | failed | running | scheduled (see
     lastOutcome() in jobs.js), and the badge reads it as its label. A
     completed run takes the `succeeded` tone. */

  import Badge from "./Badge.svelte";

  let { outcome = "scheduled" } = $props();
</script>

<Badge tone={outcome === "completed" ? "succeeded" : outcome}>{outcome}</Badge>
