<script>
  import { PolicyMatrix } from "@queenswood/ui";
  const cap = (effect, domain, action, reason, filters = []) => ({ effect, domain, action, reason, filters });
  const lim = (domain, bound, reason, extra = {}) => ({ domain, bound, reason, filters: extra.filters ?? [], allow: extra.allow ?? null });
  const POLICY = {
    policyId: "pol.0000000000000000000000000a",
    name: "Standard tier policy",
    enabled: true,
    category: "standard",
    capabilities: [
      cap("allow", "balance", "create", "Allow creating balances"),
      cap("allow", "cash_account", "open", "Allow opening cash accounts"),
      cap("allow", "party", "create", "Register natural-person customers.", [{ key: "type", value: "natural-person" }]),
      cap("allow", "outbound_payment", "send", "Send via UK Faster Payments.", [{ key: "scheme", value: "fps" }]),
      cap("deny", "cash_account_product", "publish", "Standard tenants cannot publish their own products."),
    ],
    limits: [
      lim("balance", { kind: "min", aggregate: { type: "amount", minor: 0, ccy: "GBP", window: "instant" } }, "Available balance must stay non-negative on user-driven transfers.", {
        allow: "improving",
        filters: [{ key: "computed", value: "available" }],
      }),
      lim("outbound_payment", { kind: "max", aggregate: { type: "amount", minor: 2_500_000, ccy: "GBP", window: "daily" } }, "Up to £25,000 sent per day."),
    ],
  };
</script>

<PolicyMatrix policy={POLICY} />
