<script>
  /* ProductVersion — one version of a product with all of its terms, at
     #/products/:productId/versions/:versionId.

     A draft is edited in place: Save draft sends the whole form as the
     draft's update, Publish saves first where the form has changed, and
     Discard returns to the products list. A published version reads the
     same, every control held, with New version opening a draft that
     starts as a copy of it. A refused save shows the API's problem in a
     danger callout, with its status and type.

     Sections stack in the main column; the rail beside them holds an
     index with each section's summary, the rate schedule and the terms
     in a customer's words. A draft started from an earlier published
     version says which, and marks each section that differs. */

  import { push } from "svelte-spa-router";
  import {
    Badge,
    Button,
    Callout,
    Field,
    Input,
    PageHeader,
    Panel,
    PanelHead,
    Segmented,
    Select,
  } from "@queenswood/ui";
  import {
    discard_cash_account_product_draft,
    get_cash_account_product,
    list_cash_account_product_templates,
    open_cash_account_product_draft,
    publish_cash_account_product,
    update_cash_account_product_draft,
  } from "./api.mjs";
  import {
    CONVENTIONS,
    DAY_COUNTS,
    FREQUENCIES,
    MONTH_NAMES,
    SECTIONS,
    aer,
    changedSections,
    customerTerms,
    defaultLimit,
    fromVersion,
    limitCounted,
    longDate,
    major,
    minorFromMajor,
    onLabel,
    ordinal,
    paymentChoice,
    paymentFrom,
    paymentShows,
    paymentsPerYear,
    pct,
    refusalType,
    summary,
    switchBasis,
    todayIso,
    toRequest,
  } from "./product-terms.mjs";
  import InterestGrid from "./InterestGrid.svelte";

  let { params = {}, myMembers = [] } = $props();

  const kicker = $derived(myMembers?.[0]?.["bank-name"]);

  let loading = $state(true);
  let loadError = $state(null);
  let form = $state(null);
  let earlier = $state(null);
  let template = $state(null);
  let savedBody = $state("");
  let busy = $state(false);
  let refusal = $state(null);

  const editable = $derived(form?.status === "draft" && !busy);
  const dirty = $derived(form ? JSON.stringify(toRequest(form)) !== savedBody : false);
  const changed = $derived(form && earlier ? changedSections(form, earlier) : []);
  const terms = $derived(form ? customerTerms(form) : []);
  const choice = $derived(form ? paymentChoice(form.interest.payment) : null);
  const shows = $derived(choice ? paymentShows(choice) : {});
  const currencies = $derived(template?.["allowed-currencies"] ?? (form ? [form.currency] : []));

  const TONE = { draft: "draft", published: "published" };

  const DAYS = Array.from({ length: 28 }, (_, i) => i + 1);

  async function load() {
    loading = true;
    loadError = null;
    const [res, templates] = await Promise.all([
      get_cash_account_product(params.productId),
      list_cash_account_product_templates(),
    ]);
    if (res.status !== 200) {
      loadError = res.body?.detail ?? `HTTP ${res.status}`;
      loading = false;
      return;
    }
    const versions = res.body?.versions ?? [];
    const version = versions.find((v) => v["version-id"] === params.versionId);
    if (!version) {
      loadError = "This product has no such version.";
      loading = false;
      return;
    }
    show(version);
    const before = versions
      .filter(
        (v) =>
          v.status === "published" && v["version-number"] < version["version-number"],
      )
      .sort((a, b) => b["version-number"] - a["version-number"])[0];
    earlier = before ? { ...fromVersion(before), source: before } : null;
    template =
      (templates.body?.items ?? []).find(
        (t) => t["template-id"] === version["template-id"],
      ) ?? null;
    loading = false;
  }

  function show(version) {
    form = fromVersion(version);
    savedBody = JSON.stringify(toRequest(form));
  }

  $effect(() => {
    params.productId;
    params.versionId;
    load();
  });

  function refused(res, title) {
    refusal = {
      title,
      detail: res.body?.detail ?? res.body?.title ?? "",
      type: refusalType(res),
    };
  }

  async function save() {
    busy = true;
    refusal = null;
    const res = await update_cash_account_product_draft(
      form.productId,
      form.versionId,
      toRequest(form),
    );
    busy = false;
    if (res.status >= 200 && res.status < 300) {
      show(res.body);
      return true;
    }
    refused(res, "The draft was not saved");
    return false;
  }

  async function publish() {
    if (dirty && !(await save())) return;
    busy = true;
    const res = await publish_cash_account_product(form.productId, form.versionId);
    busy = false;
    if (res.status >= 200 && res.status < 300) {
      await load();
    } else {
      refused(res, `v${form.versionNumber} was not published`);
    }
  }

  async function discard() {
    busy = true;
    const res = await discard_cash_account_product_draft(form.productId, form.versionId);
    busy = false;
    if (res.status >= 200 && res.status < 300) {
      push("/products");
    } else {
      refused(res, "The draft was not discarded");
    }
  }

  async function newVersion() {
    busy = true;
    refusal = null;
    const res = await open_cash_account_product_draft(
      form.productId,
      toRequest(form, { "effective-from": todayIso() }),
    );
    busy = false;
    if (res.status >= 200 && res.status < 300) {
      push(`/products/${form.productId}/versions/${res.body["version-id"]}`);
    } else {
      refused(res, "No draft was opened");
    }
  }

  function setPayment(key, value) {
    form.interest.payment = paymentFrom({ ...choice, [key]: value }, form.interest.payment);
  }

  function scrollTo(id) {
    document.getElementById(`section-${id}`)?.scrollIntoView({ behavior: "smooth" });
  }

  function setMoney(key, text, { optional = false } = {}) {
    if (optional && String(text).trim() === "") {
      form[key] = null;
      return;
    }
    const minor = minorFromMajor(text);
    if (minor != null) form[key] = minor;
  }

  function setLimit(i, patch) {
    form.paymentLimits[i] = { ...form.paymentLimits[i], ...patch };
  }

  function setLimitMax(i, text) {
    const limit = form.paymentLimits[i];
    const max =
      limit.measure === "count" ? Number.parseInt(text, 10) : minorFromMajor(text);
    if (Number.isFinite(max)) setLimit(i, { max });
  }

  // The schedule as bars: one group a period, a bar a band, each as
  // tall as its rate against the highest rate in the grid.
  const schedule = $derived.by(() => {
    if (!form?.interest.on) return null;
    const rates = form.interest.periods.flatMap((p) => p.rates);
    const top = Math.max(1, ...rates);
    return form.interest.periods.map((p) => ({
      rates: p.rates.map((r) => ({ r, h: Math.max(4, (r / top) * 100) })),
    }));
  });

  const firstAer = $derived.by(() => {
    if (!form?.interest.on) return null;
    const bps = form.interest.periods[0].rates[0];
    const n = paymentsPerYear(form.interest.payment);
    return { bps, aer: aer(bps, n) };
  });
</script>

{#if loading}
  <div class="loading">Loading…</div>
{:else if loadError}
  <Callout tone="danger" title="This version could not be read">{loadError}</Callout>
{:else}
  <nav class="crumbs" aria-label="Breadcrumb">
    <a href="#/products">Products</a>
    <span>/</span>
    <span>{form.name}</span>
    <span>/</span>
    <span class="mono">v{form.versionNumber}</span>
  </nav>

  <PageHeader
    {kicker}
    title={form.name}
    sub="Publishing fixes this version's terms. Accounts already open stay on the version they opened on until a migration moves them."
  >
    {#snippet titleAside()}
      <span class="aside">
        <Badge tone={TONE[form.status] ?? "neutral"}>{form.status}</Badge>
        <span class="mono muted">{form.productId} · v{form.versionNumber}</span>
      </span>
    {/snippet}
    {#snippet actions()}
      {#if form.status === "draft"}
        <Button variant="danger" disabled={busy} onclick={discard}>Discard draft</Button>
        <Button variant="line" disabled={busy || !dirty} onclick={save}>Save draft</Button>
        <Button variant="brand" disabled={busy} onclick={publish}>Publish v{form.versionNumber}</Button>
      {:else if form.status === "published"}
        <Button variant="primary" disabled={busy} onclick={newVersion}>New version</Button>
      {/if}
    {/snippet}
  </PageHeader>

  {#if refusal}
    <div class="refusal">
      <Callout tone="danger" title={refusal.title} type={refusal.type}>{refusal.detail}</Callout>
    </div>
  {/if}

  {#if earlier && form.status === "draft"}
    <div class="started">
      <Callout tone="info">
        Started from v{earlier.versionNumber}, in force from
        {longDate(earlier.effectiveFrom)}.
        {#if changed.length}
          {changed.length === 1 ? "One section differs" : `${changed.length} sections differ`} from it:
          {changed.map((id) => SECTIONS.find((s) => s.id === id).title).join(", ")}.
        {:else}
          Nothing differs from it yet.
        {/if}
      </Callout>
    </div>
  {/if}

  <div class="layout">
    <div class="main">
      <!-- Product -->
      <section id="section-product">
        <Panel>
          <PanelHead title="Product">
            {#if changed.includes("product")}<Badge tone="draft">changed</Badge>{/if}
          </PanelHead>
          <div class="body">
            <p class="explain">What the product is and what customers call it.</p>
            <div class="row three">
              <Field label="Type" htmlFor="f-type">
                <Input id="f-type" value={form.productType} disabled />
              </Field>
              <Field label="Name" htmlFor="f-name">
                <Input id="f-name" bind:value={form.name} disabled={!editable} />
              </Field>
              <Field label="Currency" htmlFor="f-ccy">
                <Select id="f-ccy" bind:value={form.currency} disabled={!editable}>
                  {#each currencies as c (c)}
                    <option value={c}>{c}</option>
                  {/each}
                </Select>
              </Field>
            </div>
            <p class="hint">
              The type is fixed when the product is created. The name is what
              end customers see; each version can change it.
            </p>
          </div>
        </Panel>
      </section>

      <!-- Interest -->
      <section id="section-interest">
        <Panel>
          <PanelHead title="Interest">
            {#if changed.includes("interest")}<Badge tone="draft">changed</Badge>{/if}
            {#snippet actions()}
              <Segmented
                label="Interest"
                bind:value={form.interest.on}
                disabled={!editable}
                options={[
                  { value: false, label: "No interest" },
                  { value: true, label: "Accrues interest" },
                ]}
              />
            {/snippet}
          </PanelHead>
          <div class="body">
            {#if !form.interest.on}
              <p class="explain">Accounts on this version earn nothing.</p>
            {:else}
              <h4>Accrual</h4>
              <p class="explain">
                The rate each band of the balance earns in each period.
                Interest accrues daily on the end-of-day balance.
              </p>
              <div class="controls">
                <Segmented
                  label="Periods start"
                  value={form.interest.basis}
                  disabled={!editable}
                  onchange={(basis) =>
                    (form.interest = switchBasis(form.interest, basis, form.effectiveFrom))}
                  options={[
                    { value: "relative", label: "After opening" },
                    { value: "fixed", label: "On dates" },
                  ]}
                />
                <Segmented
                  label="Banding"
                  bind:value={form.interest.banding}
                  disabled={!editable}
                  options={[
                    { value: "marginal", label: "Marginal" },
                    { value: "whole-balance", label: "Whole balance" },
                  ]}
                />
              </div>
              <InterestGrid
                interest={form.interest}
                currency={form.currency}
                effectiveFrom={form.effectiveFrom}
                disabled={!editable}
                onchange={(next) => (form.interest = next)}
              />
              <p class="hint">
                {#if form.interest.banding === "marginal"}
                  Each band's rate applies to the part of the balance within it.
                {:else}
                  The rate of the band the balance reaches applies to all of it.
                {/if}
                {#if firstAer}
                  {pct(firstAer.bps)} {choice.frequency === "custom" ? "paid as stated" : `paid ${FREQUENCIES.find((f) => f.value === choice.frequency)?.label.toLowerCase()}`}
                  is {pct(firstAer.aer)} AER.
                {/if}
              </p>

              <h4>Payment</h4>
              <p class="explain">When accrued interest is paid into the account.</p>
              <div class="sentence">
                <span>Interest paid</span>
                <Select
                  aria-label="How often"
                  value={choice.frequency}
                  disabled={!editable}
                  onchange={(e) => setPayment("frequency", e.target.value)}
                >
                  {#if choice.frequency === "custom"}
                    <option value="custom">As stated through the API</option>
                  {/if}
                  {#each FREQUENCIES as f (f.value)}
                    <option value={f.value}>{f.label}</option>
                  {/each}
                </Select>
                {#if shows.on}
                  <span>on</span>
                  <Select
                    aria-label="On"
                    value={String(choice.on)}
                    disabled={!editable}
                    onchange={(e) =>
                      setPayment("on", e.target.value === "account" ? "account" : Number(e.target.value))}
                  >
                    <option value="account">{onLabel(choice.frequency, "account")}</option>
                    {#each DAYS as d (d)}
                      <option value={String(d)}>the {ordinal(d)}</option>
                    {/each}
                    {#if choice.on === 29 || choice.on === 30}
                      <option value={String(choice.on)}>the {ordinal(choice.on)}</option>
                    {/if}
                    <option value="31">the last day of the month</option>
                  </Select>
                {/if}
                {#if shows.inMonth}
                  <span>starting in</span>
                  <Select
                    aria-label="Starting in"
                    value={String(choice.inMonth)}
                    disabled={!editable}
                    onchange={(e) => setPayment("inMonth", Number(e.target.value))}
                  >
                    {#each MONTH_NAMES as m, i (m)}
                      <option value={String(i + 1)}>{m}</option>
                    {/each}
                  </Select>
                {/if}
              </div>
              <p class="hint">
                {#if form.dayCount}
                  {DAY_COUNTS[form.dayCount] ?? form.dayCount} ·
                  {CONVENTIONS[form.businessDayConvention] ?? form.businessDayConvention},
                  as {form.currency} sets them.
                {:else}
                  The day count and business day convention are set by
                  {form.currency}, and shown once the draft is saved.
                {/if}
                Accrual reads only the first rate and pays daily for now.
              </p>
            {/if}
          </div>
        </Panel>
      </section>

      <!-- Term -->
      <section id="section-term">
        <Panel>
          <PanelHead title="Term" />
          <div class="body">
            <p class="explain">
              How long the version's terms run for each account, and what
              happens when they end.
            </p>
            <Callout tone="info" title="Not available yet">
              An account that matures at the end of a term needs a field the
              version does not have yet. A rate that changes after a time is a
              period in the accrual grid above.
            </Callout>
          </div>
        </Panel>
      </section>

      <!-- Limits -->
      <section id="section-limits">
        <Panel>
          <PanelHead title="Limits">
            {#if changed.includes("limits")}<Badge tone="draft">changed</Badge>{/if}
          </PanelHead>
          <div class="body">
            <p class="explain">
              What may be paid into and out of each account, and what it may
              hold. Interest and rewards are never refused.
            </p>
            <div class="row two">
              <Field label="Lowest balance" htmlFor="f-min" hint="Below zero is an arranged overdraft, not yet allowed by the platform.">
                <Input
                  id="f-min"
                  value={major(form.minBalance)}
                  inputmode="decimal"
                  affix={form.currency}
                  disabled={!editable}
                  onchange={(e) => setMoney("minBalance", e.target.value)}
                />
              </Field>
              <Field label="Highest balance" htmlFor="f-max" hint="Optional. Must be above the lowest balance.">
                <Input
                  id="f-max"
                  value={major(form.maxBalance)}
                  inputmode="decimal"
                  affix={form.currency}
                  placeholder="No limit"
                  disabled={!editable}
                  onchange={(e) => setMoney("maxBalance", e.target.value, { optional: true })}
                />
              </Field>
            </div>

            <h4>Payment limits <span class="count">{form.paymentLimits.length}</span></h4>
            {#if form.paymentLimits.length}
              <div class="limits-wrap">
                <table class="limits">
                  <thead>
                    <tr>
                      <th>Direction</th>
                      <th>Measure</th>
                      <th>At most</th>
                      <th>Each</th>
                      <th>Counted from</th>
                      <th></th>
                    </tr>
                  </thead>
                  <tbody>
                    {#each form.paymentLimits as limit, i (i)}
                      <tr>
                        <td>
                          <Select aria-label="Direction" value={limit.direction} disabled={!editable}
                                  onchange={(e) => setLimit(i, { direction: e.target.value })}>
                            <option value="in">In</option>
                            <option value="out">Out</option>
                          </Select>
                        </td>
                        <td>
                          <Select aria-label="Measure" value={limit.measure} disabled={!editable}
                                  onchange={(e) => setLimit(i, { measure: e.target.value, max: 0 })}>
                            <option value="amount">Amount</option>
                            <option value="count">Count</option>
                          </Select>
                        </td>
                        <td>
                          <Input
                            aria-label="At most"
                            value={limit.measure === "count" ? String(limit.max) : major(limit.max)}
                            inputmode="decimal"
                            affix={limit.measure === "count" ? "" : form.currency}
                            disabled={!editable}
                            onchange={(e) => setLimitMax(i, e.target.value)}
                          />
                        </td>
                        <td>
                          <Select aria-label="Each" value={limit.period} disabled={!editable}
                                  onchange={(e) => setLimit(i, { period: e.target.value })}>
                            {#if limit.measure !== "count"}<option value="payment">Payment</option>{/if}
                            <option value="day">Day</option>
                            <option value="week">Week</option>
                            <option value="month">Month</option>
                            <option value="quarter">Quarter</option>
                            <option value="year">Year</option>
                          </Select>
                        </td>
                        <td>
                          {#if !limitCounted(limit)}
                            <span class="muted">{limit.period === "day" ? "Each business day" : "—"}</span>
                          {:else}
                            <span class="counted">
                              <Select aria-label="Counted from" value={limit.anchor ?? "account"} disabled={!editable}
                                      onchange={(e) => setLimit(i, { anchor: e.target.value })}>
                                <option value="account">Opening</option>
                                <option value="calendar">Date</option>
                              </Select>
                              {#if limit.anchor === "calendar"}
                                <Select aria-label="Day" value={String(limit["starts-day-of-month"] ?? 1)} disabled={!editable}
                                        onchange={(e) => setLimit(i, { "starts-day-of-month": Number(e.target.value) })}>
                                  {#each DAYS as d (d)}<option value={String(d)}>{d}</option>{/each}
                                </Select>
                                <Select aria-label="Month" value={String(limit["starts-month"] ?? 1)} disabled={!editable}
                                        onchange={(e) => setLimit(i, { "starts-month": Number(e.target.value) })}>
                                  {#each MONTH_NAMES as m, k (m)}<option value={String(k + 1)}>{m.slice(0, 3)}</option>{/each}
                                </Select>
                              {/if}
                            </span>
                          {/if}
                        </td>
                        <td>
                          {#if editable}
                            <button type="button" class="remove" aria-label="Remove payment limit {i + 1}"
                                    onclick={() => form.paymentLimits.splice(i, 1)}>×</button>
                          {/if}
                        </td>
                      </tr>
                      <tr class="reason">
                        <td colspan="6">
                          <Input aria-label="Reason" value={limit.reason ?? ""}
                                 placeholder="Reason a refused payment gives; blank for the default"
                                 disabled={!editable}
                                 onchange={(e) => setLimit(i, { reason: e.target.value })} />
                        </td>
                      </tr>
                    {/each}
                  </tbody>
                </table>
              </div>
            {/if}
            {#if editable}
              <div class="add">
                <Button size="sm" variant="ghost" onclick={() => form.paymentLimits.push(defaultLimit())}>
                  Add payment limit
                </Button>
              </div>
            {/if}
          </div>
        </Panel>
      </section>

      <!-- Rewards -->
      <section id="section-rewards">
        <Panel>
          <PanelHead title="Rewards">
            {#if changed.includes("rewards")}<Badge tone="draft">changed</Badge>{/if}
            {#snippet actions()}
              <Segmented
                label="Reward"
                bind:value={form.reward.on}
                disabled={!editable}
                options={[
                  { value: false, label: "No reward" },
                  { value: true, label: "Welcome reward" },
                ]}
              />
            {/snippet}
          </PanelHead>
          <div class="body">
            {#if form.reward.on}
              <div class="row two">
                <Field label="Amount" htmlFor="f-reward" hint="Paid when the account opens. Must be more than nothing.">
                  <Input
                    id="f-reward"
                    value={major(form.reward.amount)}
                    inputmode="decimal"
                    affix={form.currency}
                    disabled={!editable}
                    onchange={(e) => {
                      const minor = minorFromMajor(e.target.value);
                      if (minor != null) form.reward.amount = minor;
                    }}
                  />
                </Field>
              </div>
            {:else}
              <p class="explain">Accounts on this version are paid no welcome reward.</p>
            {/if}
          </div>
        </Panel>
      </section>

      <!-- Availability -->
      <section id="section-availability">
        <Panel>
          <PanelHead title="Availability">
            {#if changed.includes("availability")}<Badge tone="draft">changed</Badge>{/if}
          </PanelHead>
          <div class="body">
            <p class="explain">
              The end date stops openings, not interest on accounts already open.
            </p>
            <div class="row two">
              <Field label="Effective from" htmlFor="f-from">
                <Input id="f-from" type="date" bind:value={form.effectiveFrom} disabled={!editable} />
              </Field>
              <Field label="Effective to" htmlFor="f-to" hint="Optional. Leave blank for no end date.">
                <Input id="f-to" type="date" min={form.effectiveFrom} bind:value={form.effectiveTo} disabled={!editable} />
              </Field>
            </div>
          </div>
        </Panel>
      </section>
    </div>

    <aside class="rail">
      <Panel>
        <PanelHead title="Sections" />
        <ul class="index">
          {#each SECTIONS as s (s.id)}
            <li>
              <button type="button" onclick={() => scrollTo(s.id)}>
                <span>{s.title}</span>
                <span class="mono muted">{summary(form, s.id)}</span>
              </button>
            </li>
          {/each}
        </ul>
      </Panel>

      {#if schedule}
        <Panel>
          <PanelHead title="Rate schedule" count="{form.interest.periods.length} × {form.interest.bands.length}" />
          <div class="chart" role="img" aria-label="Rates by period and band">
            {#each schedule as period, i (i)}
              <div class="period-bars">
                <div class="bars">
                  {#each period.rates as cell, j (j)}
                    <span class="bar" style="height: {cell.h}%" title={pct(cell.r)}></span>
                  {/each}
                </div>
                <span class="mono muted tick">
                  {i === 0
                    ? "open"
                    : form.interest.basis === "relative"
                      ? `+${form.interest.periods[i].start}`
                      : form.interest.periods[i].start.slice(0, 7)}
                </span>
              </div>
            {/each}
          </div>
        </Panel>
      {/if}

      <Panel>
        <PanelHead title="Customer terms" />
        <dl class="terms">
          {#each terms as t (t.section)}
            <dt>{t.section}</dt>
            <dd>{t.text}</dd>
          {/each}
        </dl>
      </Panel>
    </aside>
  </div>
{/if}

<style>
  .loading {
    padding: 48px 16px;
    text-align: center;
    color: var(--fg-muted);
  }
  .crumbs {
    display: flex;
    gap: 6px;
    margin-bottom: 8px;
    font-size: 12.5px;
    color: var(--fg-muted);
  }
  .crumbs a {
    color: inherit;
  }
  .crumbs a:hover {
    color: var(--fg);
  }
  .aside {
    display: inline-flex;
    align-items: center;
    gap: 10px;
  }
  .refusal,
  .started {
    margin-bottom: 16px;
  }
  .layout {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 320px;
    gap: 20px;
    align-items: start;
  }
  .main {
    display: flex;
    flex-direction: column;
    gap: 16px;
    min-width: 0;
  }
  .rail {
    position: sticky;
    top: 16px;
    display: flex;
    flex-direction: column;
    gap: 16px;
  }
  @media (max-width: 1100px) {
    .layout {
      grid-template-columns: minmax(0, 1fr);
    }
    .rail {
      position: static;
    }
  }
  section {
    scroll-margin-top: 16px;
  }
  .body {
    padding: 4px 20px 20px;
  }
  .explain {
    margin: 0 0 14px;
    color: var(--fg-muted);
    font-size: 13px;
  }
  .hint {
    margin: 10px 0 0;
    color: var(--fg-muted);
    font-size: 12.5px;
  }
  h4 {
    margin: 22px 0 4px;
    font-family: var(--grotesk);
    font-size: 14px;
    font-weight: 500;
  }
  h4:first-child {
    margin-top: 6px;
  }
  h4 .count {
    margin-left: 6px;
    font-family: var(--mono);
    font-size: 11px;
    color: var(--fg-muted);
  }
  .row {
    display: grid;
    gap: 14px;
  }
  .row.three {
    grid-template-columns: 1fr 2fr 1fr;
  }
  .row.two {
    grid-template-columns: 1fr 1fr;
  }
  @media (max-width: 700px) {
    .row.three,
    .row.two {
      grid-template-columns: 1fr;
    }
  }
  .controls {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    margin-bottom: 12px;
  }
  .sentence {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
    font-size: 14px;
  }
  .sentence :global(select) {
    width: auto;
  }
  .limits-wrap {
    overflow-x: auto;
    margin-top: 8px;
  }
  .limits {
    width: 100%;
    min-width: 640px;
    border-collapse: collapse;
    font-size: 13px;
  }
  .limits th {
    text-align: left;
    font-weight: 400;
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: 0.05em;
    color: var(--fg-muted);
    padding: 0 6px 6px;
  }
  .limits td {
    padding: 4px 6px;
    vertical-align: middle;
  }
  .limits td:nth-child(3) {
    width: 140px;
  }
  .limits tr.reason td {
    padding-bottom: 12px;
    border-bottom: 1px solid var(--rule);
  }
  .limits tbody tr.reason:last-child td {
    border-bottom: 0;
  }
  .counted :global(select) {
    min-width: 0;
  }
  .counted {
    display: grid;
    grid-template-columns: 104px 64px 80px;
    gap: 6px;
  }
  .remove {
    width: 24px;
    height: 24px;
    border: 0;
    border-radius: 4px;
    background: transparent;
    color: var(--fg-muted);
    font-size: 16px;
    cursor: pointer;
  }
  .remove:hover {
    background: var(--hover-overlay);
    color: var(--fg);
  }
  .add {
    margin-top: 10px;
  }
  .index {
    list-style: none;
    margin: 0;
    padding: 0 8px 8px;
  }
  .index button {
    display: flex;
    justify-content: space-between;
    gap: 10px;
    width: 100%;
    padding: 8px 12px;
    border: 0;
    border-radius: 6px;
    background: transparent;
    color: var(--fg);
    font: inherit;
    font-size: 13px;
    text-align: left;
    cursor: pointer;
  }
  .index button:hover {
    background: var(--hover-overlay);
  }
  .index .mono {
    font-size: 11px;
    text-align: right;
  }
  .chart {
    display: flex;
    gap: 10px;
    height: 110px;
    padding: 6px 20px 16px;
  }
  .period-bars {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 4px;
    min-width: 0;
  }
  .bars {
    flex: 1;
    display: flex;
    align-items: flex-end;
    gap: 2px;
    border-bottom: 1px solid var(--rule);
  }
  .bar {
    flex: 1;
    border-radius: 2px 2px 0 0;
    background: var(--pine-4);
  }
  .tick {
    font-size: 10px;
    text-align: center;
  }
  .terms {
    margin: 0;
    padding: 0 20px 18px;
    font-size: 13px;
  }
  .terms dt {
    margin-top: 10px;
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: 0.05em;
    color: var(--fg-muted);
  }
  .terms dd {
    margin: 2px 0 0;
    line-height: 1.45;
  }
  .mono {
    font-family: var(--mono);
  }
  .muted {
    color: var(--fg-muted);
  }
</style>
