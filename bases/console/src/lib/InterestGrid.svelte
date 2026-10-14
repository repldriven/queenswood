<script>
  /* InterestGrid — a version's accrual as a grid: a column for each
     band of the balance, headed by its lowest balance, and a row for
     each period of an account's life, headed by its start. Each cell is
     a rate a year.

     The first column starts at £0.00 and the first row at opening, or on
     the version's effective-from for a fixed schedule, so neither head
     is editable. "Add band" adds a column and "Add period" a row, each
     copying its neighbour's rates. `onchange` receives the whole
     interest each time. */

  import { Button, Input, Select } from "@queenswood/ui";
  import {
    MAX_BANDS,
    MAX_PERIODS,
    UNITS,
    addBand,
    addPeriod,
    bpsFromPercent,
    longDate,
    major,
    minorFromMajor,
    money,
    removeBand,
    removePeriod,
  } from "./product-terms.mjs";

  let { interest, currency, effectiveFrom, disabled = false, onchange } = $props();

  const relative = $derived(interest.basis === "relative");
  const unit = $derived(UNITS.find((u) => u.value === interest.unit) ?? UNITS[2]);

  function setBand(j, text) {
    const minor = minorFromMajor(text);
    if (minor == null) return;
    const bands = [...interest.bands];
    bands[j] = minor;
    onchange({ ...interest, bands });
  }

  function setRate(i, j, text) {
    const bps = bpsFromPercent(text);
    if (bps == null) return;
    const periods = interest.periods.map((p, k) =>
      k === i ? { ...p, rates: p.rates.map((r, m) => (m === j ? bps : r)) } : p,
    );
    onchange({ ...interest, periods });
  }

  function setStart(i, value) {
    const start = relative ? Number(value) : value;
    if (relative && !Number.isFinite(start)) return;
    const periods = interest.periods.map((p, k) => (k === i ? { ...p, start } : p));
    onchange({ ...interest, periods });
  }
</script>

<div class="grid-wrap">
  <table class="grid">
    <thead>
      <tr>
        <th class="corner">
          {#if relative}
            <Select
              aria-label="Periods count in"
              value={interest.unit}
              {disabled}
              onchange={(e) => onchange({ ...interest, unit: e.target.value })}
            >
              {#each UNITS as u (u.value)}
                <option value={u.value}>{u.many}</option>
              {/each}
            </Select>
          {:else}
            <span class="muted">From</span>
          {/if}
        </th>
        {#each interest.bands as band, j (j)}
          <th class="band">
            <span class="label">Balance from</span>
            {#if j === 0}
              <span class="fixed mono">{money(0, currency)}</span>
            {:else}
              <span class="band-edit">
                <Input
                  aria-label="Band {j + 1} starts at"
                  value={major(band)}
                  inputmode="decimal"
                  affix={currency}
                  {disabled}
                  onchange={(e) => setBand(j, e.target.value)}
                />
                {#if !disabled}
                  <button
                    type="button"
                    class="remove"
                    aria-label="Remove band {j + 1}"
                    onclick={() => onchange(removeBand(interest, j))}>×</button
                  >
                {/if}
              </span>
            {/if}
            <span class="to mono">
              {interest.bands[j + 1] != null
                ? `to ${money(interest.bands[j + 1], currency)}`
                : "no upper limit"}
            </span>
          </th>
        {/each}
      </tr>
    </thead>
    <tbody>
      {#each interest.periods as period, i (i)}
        <tr>
          <th class="period">
            {#if i === 0}
              <span class="fixed">
                {relative ? "Opening" : longDate(effectiveFrom)}
              </span>
            {:else}
              <span class="period-edit">
                {#if relative}
                  <Input
                    aria-label="Period {i + 1} starts after"
                    type="number"
                    min="1"
                    value={String(period.start)}
                    {disabled}
                    onchange={(e) => setStart(i, e.target.value)}
                  />
                  <span class="muted after">{unit.many} after</span>
                {:else}
                  <Input
                    aria-label="Period {i + 1} starts on"
                    type="date"
                    value={period.start}
                    {disabled}
                    onchange={(e) => setStart(i, e.target.value)}
                  />
                {/if}
                {#if !disabled}
                  <button
                    type="button"
                    class="remove"
                    aria-label="Remove period {i + 1}"
                    onclick={() => onchange(removePeriod(interest, i))}>×</button
                  >
                {/if}
              </span>
            {/if}
          </th>
          {#each period.rates as rate, j (j)}
            <td>
              <Input
                aria-label="Rate in period {i + 1}, band {j + 1}"
                value={(rate / 100).toFixed(2)}
                inputmode="decimal"
                affix="%"
                {disabled}
                onchange={(e) => setRate(i, j, e.target.value)}
              />
            </td>
          {/each}
        </tr>
      {/each}
    </tbody>
  </table>
</div>

{#if !disabled}
  <div class="grid-actions">
    <Button
      size="sm"
      variant="ghost"
      disabled={interest.bands.length >= MAX_BANDS}
      onclick={() => onchange(addBand(interest))}>Add band</Button
    >
    <Button
      size="sm"
      variant="ghost"
      disabled={interest.periods.length >= MAX_PERIODS}
      onclick={() => onchange(addPeriod(interest, effectiveFrom))}>Add period</Button
    >
    <span class="muted caps">
      {interest.bands.length} of {MAX_BANDS} bands · {interest.periods.length} of
      {MAX_PERIODS} periods
    </span>
  </div>
{/if}

<style>
  .grid-wrap {
    overflow-x: auto;
    border: 1px solid var(--rule);
    border-radius: 8px;
  }
  .grid {
    border-collapse: collapse;
    width: 100%;
    font-size: 13px;
  }
  .grid th,
  .grid td {
    padding: 8px 10px;
    border-bottom: 1px solid var(--rule);
    border-right: 1px solid var(--rule);
    vertical-align: top;
    text-align: left;
    font-weight: 400;
  }
  .grid tr > :last-child {
    border-right: 0;
  }
  .grid tbody tr:last-child > * {
    border-bottom: 0;
  }
  .grid thead th {
    background: var(--surface-sunk);
  }
  .corner {
    width: 170px;
  }
  .band {
    min-width: 140px;
  }
  .label {
    display: block;
    font-size: 10.5px;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--fg-muted);
    margin-bottom: 4px;
  }
  .to {
    display: block;
    margin-top: 4px;
    font-size: 11px;
    color: var(--fg-muted);
  }
  .fixed {
    display: inline-flex;
    align-items: center;
    height: 40px;
  }
  .band-edit,
  .period-edit {
    display: flex;
    align-items: center;
    gap: 6px;
  }
  .period-edit :global(.input) {
    width: 72px;
  }
  .after {
    white-space: nowrap;
  }
  .remove {
    flex: none;
    width: 24px;
    height: 24px;
    border: 0;
    border-radius: 4px;
    background: transparent;
    color: var(--fg-muted);
    font-size: 16px;
    line-height: 1;
    cursor: pointer;
  }
  .remove:hover {
    background: var(--hover-overlay);
    color: var(--fg);
  }
  .grid-actions {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 10px;
  }
  .muted {
    color: var(--fg-muted);
  }
  .caps {
    font-size: 11.5px;
    margin-left: auto;
  }
  .mono {
    font-family: var(--mono);
  }
</style>
