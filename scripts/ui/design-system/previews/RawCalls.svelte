<script>
  import { RawCalls } from "@queenswood/ui";
</script>

<RawCalls
  show
  backing={["create-product-happy", "publish-draft"]}
  rows={[
    { method: "POST", path: "/v1/cash-account-products", tag: "request" },
    { method: "POST", path: "/v1/cash-account-products/prd…/versions/1/publish", tag: "request" },
    { method: "GET", path: "/v1/cash-account-products/prd…/versions/1", tag: "poll" },
  ]}
/>
