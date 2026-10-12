<script>
  import { SearchField } from "@queenswood/ui";
  let q = $state("41926819");
  let q2 = $state("");
</script>

<div class="pv-stack" style="max-width:420px">
  <SearchField bind:value={q} placeholder="Search by account number…" />
  <SearchField bind:value={q2} size="sm" placeholder="Search transactions…" />
</div>
