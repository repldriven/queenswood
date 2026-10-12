<script>
  import { MigrationStatusBadge } from "@queenswood/ui";
</script>

<div class="pv-stack">
  <div class="pv-row">
    <span class="pv-label">migration</span>
    <MigrationStatusBadge status="draft" />
    <MigrationStatusBadge status="approved" />
    <MigrationStatusBadge status="completed" />
    <MigrationStatusBadge status="cancelled" />
  </div>
  <div class="pv-row">
    <span class="pv-label">run</span>
    <MigrationStatusBadge status="running" kind="run" />
    <MigrationStatusBadge status="completed" kind="run" />
    <MigrationStatusBadge status="failed" kind="run" />
  </div>
  <div class="pv-row">
    <span class="pv-label">outcome</span>
    <MigrationStatusBadge status="eligible" kind="outcome" />
    <MigrationStatusBadge status="migrated" kind="outcome" />
    <MigrationStatusBadge status="ineligible" kind="outcome" />
    <MigrationStatusBadge status="failed" kind="outcome" />
  </div>
</div>
