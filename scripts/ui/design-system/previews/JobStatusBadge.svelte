<script>
  import { JobStatusBadge } from "@queenswood/ui";
</script>

<div class="pv-row">
  <JobStatusBadge outcome="scheduled" />
  <JobStatusBadge outcome="running" />
  <JobStatusBadge outcome="completed" />
  <JobStatusBadge outcome="failed" />
</div>
