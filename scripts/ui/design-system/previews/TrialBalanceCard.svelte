<script>
  import { TrialBalanceCard } from "@queenswood/ui";
</script>

<div class="pv-grid" style="grid-template-columns:repeat(2,minmax(0,320px))">
  <TrialBalanceCard ccy="GBP" sym="£" name="Sterling" accounts={14} debitMinor={2_431_077_542_000} creditMinor={2_431_077_542_000} />
  <TrialBalanceCard ccy="USD" sym="$" name="US Dollar" accounts={5} debitMinor={215_000_000_000} creditMinor={183_979_955_000} />
</div>
