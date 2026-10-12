<script>
  import { HttpStatus } from "@queenswood/ui";
</script>

<div class="pv-row" style="gap:24px">
  <HttpStatus status={200} />
  <HttpStatus status={201} />
  <HttpStatus status={404} />
  <HttpStatus status={409} />
  <HttpStatus status={500} />
</div>
