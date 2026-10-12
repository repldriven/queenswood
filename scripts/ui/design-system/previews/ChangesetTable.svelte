<script>
  import { ChangesetTable } from "@queenswood/ui";
</script>

<div class="pv-stack">
  <ChangesetTable
    changeset={{
      before: { role: "role-developer" },
      after: { role: "role-admin", "role-reason": "Leads the payments team" },
    }}
  />
  <ChangesetTable
    changeset={{
      after: {
        status: "outbound-payment-status-submitted",
        amount: { value: 125000, currency: "GBP" },
      },
    }}
  />
  <ChangesetTable />
</div>
