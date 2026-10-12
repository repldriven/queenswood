<script>
  import { ProductPicker } from "@queenswood/ui";
  const PRODUCTS = [
    { id: "prd.instant-access-savings", name: "Instant Access Savings", type: "savings", publishedCount: 4, accountCount: 9588 },
    { id: "prd.youth-saver", name: "Youth Saver", type: "savings", publishedCount: 2, accountCount: 1204 },
    { id: "prd.fixed-term-12m", name: "12-Month Fixed Term", type: "deposit", publishedCount: 3, accountCount: 431 },
  ];
  let open = $state(true);
  let value = $state("prd.instant-access-savings");
</script>

<div style="max-width:460px">
  <ProductPicker
    products={PRODUCTS}
    {value}
    {open}
    ontoggle={() => (open = !open)}
    onselect={(p) => { value = p.id; open = false; }}
    hiddenNote="1 product hidden — a migration's target must be the same product type as its source (savings)."
  />
</div>
