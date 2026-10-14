<script>
  /* Drawer-hosted form for creating a cash-account product: its type,
     currency, name and the date its first version starts. The draft it
     creates is handed to `onSaved`, and every other term is set on the
     version's page.

     `templates` flows in from Products.svelte (one fetch shared
     across the page). We pick the chosen template's allowed
     currencies to populate the currency select. */

  import {
    Drawer,
    Field,
    Input,
    Select,
    Button,
  } from "@queenswood/ui";
  import { create_cash_account_product } from "./api.mjs";

  let { open, templates = [], onClose, onSaved } = $props();

  // Form state. Reset by the $effect below whenever the drawer opens
  // so re-opening doesn't leak the previous edit's values.
  function todayISO() {
    return new Date().toISOString().slice(0, 10);
  }

  let productType = $state("current");
  let currency = $state("GBP");
  let name = $state("");
  // Effective window (ISO date strings). effective-from defaults to
  // today; effective-to is optional (blank = open-ended).
  let effectiveFrom = $state(todayISO());
  let effectiveTo = $state("");
  let submitting = $state(false);
  let formError = $state(null);
  // Track whether the user has manually overridden the name. If not,
  // the name follows `{currency} {type}` automatically.
  let nameTouched = $state(false);

  $effect(() => {
    if (!open) return;
    formError = null;
    // Compute from locals, not the productType/currency state we're
    // assigning — reading that state here would make this effect
    // depend on it and re-fire (resetting to the default) on every
    // edit, pinning the form to templates[0].
    const t = templates[0]?.["product-type"] ?? "current";
    const allowed = templates[0]?.["allowed-currencies"] ?? ["GBP"];
    productType = t;
    currency = allowed[0];
    effectiveFrom = todayISO();
    effectiveTo = "";
    nameTouched = false;
    name = defaultName(t, allowed[0]);
  });

  function defaultName(t, c) {
    const label = t
      ? t
          .split("-")
          .map((w) => w[0]?.toUpperCase() + w.slice(1))
          .join(" ")
      : "";
    return c ? `${c} ${label}` : label;
  }

  const allowedCurrencies = $derived(
    templates.find((t) => t["product-type"] === productType)?.[
      "allowed-currencies"
    ] ?? ["GBP"],
  );

  const productTypes = $derived(
    templates.length > 0
      ? templates.map((t) => t["product-type"])
      : ["current", "savings", "term-deposit"],
  );

  // Recompute the default name when type/currency change, unless the
  // user has manually edited the field.
  function syncName() {
    if (!nameTouched) name = defaultName(productType, currency);
  }

  function onTypeChange(e) {
    productType = e.target.value;
    // Reset currency if the new type's template doesn't allow the
    // current one.
    const allowed =
      templates.find((t) => t["product-type"] === productType)?.[
        "allowed-currencies"
      ] ?? [];
    if (!allowed.includes(currency)) {
      currency = allowed[0] ?? "GBP";
    }
    syncName();
  }

  function onCurrencyChange(e) {
    currency = e.target.value;
    syncName();
  }

  function onNameInput(e) {
    nameTouched = true;
    name = e.target.value;
  }

  async function submit(e) {
    e.preventDefault();
    if (submitting) return;
    submitting = true;
    formError = null;
    const data = {
      name: name.trim(),
      // The request is tied to a template at creation, not a product-type;
      // one template per type, so resolve the chosen type's template id.
      "template-id": templates.find((t) => t["product-type"] === productType)?.[
        "template-id"
      ],
      currency,
      "effective-from": effectiveFrom,
    };
    if (effectiveTo) data["effective-to"] = effectiveTo;
    try {
      const res = await create_cash_account_product(data);
      if (res.status >= 200 && res.status < 300) {
        onSaved?.(res.body);
      } else {
        formError = res.body?.detail ?? `Save failed (${res.status})`;
      }
    } catch (err) {
      formError = err.message;
    } finally {
      submitting = false;
    }
  }

</script>

<Drawer
  {open}
  {onClose}
  kicker="Define"
  title="New product"
  sub="Creates the product's first draft. Its interest, limits and rewards are set on the draft's page."
>
  <form id="product-form" onsubmit={submit}>
    <Field label="Account type" htmlFor="f-type">
      <Select id="f-type" bind:value={productType} onchange={onTypeChange}>
        {#each productTypes as t (t)}
          <option value={t}>{t}</option>
        {/each}
      </Select>
    </Field>

    <Field label="Currency" htmlFor="f-ccy">
      <Select id="f-ccy" bind:value={currency} onchange={onCurrencyChange}>
        {#each allowedCurrencies as c (c)}
          <option value={c}>{c}</option>
        {/each}
      </Select>
    </Field>

    <Field
      label="Product name"
      htmlFor="f-name"
      hint="Defaults to {currency} {productType}. Override if customers should see a different label."
    >
      <Input id="f-name" value={name} oninput={onNameInput} />
    </Field>

    <Field
      label="Effective from"
      htmlFor="f-eff-from"
      hint="The date this version becomes the active product for new accounts."
    >
      <Input id="f-eff-from" type="date" bind:value={effectiveFrom} />
    </Field>

    <Field
      label="Effective to"
      htmlFor="f-eff-to"
      hint="Optional. Leave blank for no end date."
    >
      <Input id="f-eff-to" type="date" min={effectiveFrom} bind:value={effectiveTo} />
    </Field>

    {#if formError}
      <p class="error" role="alert">{formError}</p>
    {/if}
  </form>

  {#snippet footer()}
    <Button
      variant="primary"
      size="lg"
      block
      type="submit"
      form="product-form"
      disabled={submitting || !name.trim() || !effectiveFrom}
    >
      {submitting ? "Creating…" : "Create draft"}
    </Button>
  {/snippet}
</Drawer>

<style>
  form {
    display: flex;
    flex-direction: column;
    gap: 18px;
  }
  .error {
    margin: 0;
    padding: 10px 12px;
    border-radius: 6px;
    background: var(--surface-sunk);
    color: var(--fg);
    font-size: 13px;
  }
</style>
