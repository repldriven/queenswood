<script>
  /* TimeRangeFilter — a log rail's time section: preset pills relative
     to now, and a custom range of UTC instants, either end open.

         <TimeRangeFilter {range} onChange={(r) => (range = r)}
           note="Either end may be left open." />

     `range` is null, `{ preset }` or `{ from, to }`, epoch milliseconds,
     as time-range.js reads and writes it; `onChange` receives the next
     one. `oldest` bounds the custom inputs from below. */

  import Chip from "./Chip.svelte";
  import Button from "./Button.svelte";
  import { TIME_PRESETS, utcInputValue, fromUtcInput } from "./time-range.js";

  let { range, onChange, note, oldest } = $props();

  let customOpen = $state(false);
  let from = $state("");
  let to = $state("");
  let error = $state("");
  let box = $state();

  const isCustom = $derived(!!range && !range.preset);
  const showCustom = $derived(customOpen || isCustom);
  const current = $derived(!range ? "any" : (range.preset ?? "custom"));

  // The inputs follow the range applied, unless someone is typing in them.
  $effect(() => {
    if (!showCustom) return;
    if (box?.contains(document.activeElement)) return;
    from = utcInputValue(isCustom ? range.from : null);
    to = utcInputValue(isCustom ? range.to : null);
  });

  function pick(key) {
    error = "";
    if (key === "custom") {
      customOpen = isCustom ? true : !customOpen;
      return;
    }
    customOpen = false;
    onChange(key === "any" ? null : { preset: key });
  }

  function apply() {
    const f = fromUtcInput(from);
    const t = fromUtcInput(to);
    if (f == null && t == null) {
      error = "Set a start, an end or both.";
      return;
    }
    if (f != null && t != null && f >= t) {
      error = "The end must be after the start.";
      return;
    }
    error = "";
    customOpen = true;
    onChange({ from: f, to: t });
  }

  function clear() {
    customOpen = false;
    error = "";
    onChange(null);
  }

  function onKey(e) {
    if (e.key === "Enter" && e.target instanceof HTMLInputElement) {
      e.preventDefault();
      apply();
    }
  }

  const max = $derived(utcInputValue(Date.now() + 60e3));
  const min = $derived(oldest != null ? utcInputValue(oldest) : undefined);
</script>

<div class="qw-time-range">
  <div class="top">
    <span class="label">Time (UTC)</span>
    <button type="button" class="clear" disabled={!range} onclick={clear}>Clear</button>
  </div>
  <div class="presets">
    <Chip pressed={current === "any" && !showCustom} onclick={() => pick("any")}>Any time</Chip>
    {#each TIME_PRESETS as p (p.key)}
      <Chip pressed={current === p.key && !showCustom} onclick={() => pick(p.key)}>{p.short}</Chip>
    {/each}
    <Chip pressed={showCustom} onclick={() => pick("custom")}>Custom</Chip>
  </div>
  {#if showCustom}
    <!-- svelte-ignore a11y_no_static_element_interactions -->
    <div class="custom" bind:this={box} onkeydown={onKey}>
      <label class="dt">
        <span>From</span>
        <input type="datetime-local" step="60" {min} {max} bind:value={from} />
      </label>
      <label class="dt">
        <span>To</span>
        <input type="datetime-local" step="60" {min} {max} bind:value={to} />
      </label>
      {#if error}<div class="err" role="alert">{error}</div>{/if}
      <Button variant="line" size="sm" block onclick={apply}>Apply</Button>
      {#if note}<span class="note">{note}</span>{/if}
    </div>
  {/if}
</div>

<style>
  .qw-time-range {
    display: flex;
    flex-direction: column;
    gap: 9px;
    padding: 12px 12px 12px 14px;
    border-bottom: 1px solid var(--rule-2);
  }
  .top {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
  }
  .label {
    font-family: var(--mono);
    font-size: 10px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--fg-muted);
    white-space: nowrap;
  }
  .clear {
    padding: 2px 4px;
    border: none;
    border-radius: 4px;
    background: none;
    font-family: var(--mono);
    font-size: 11px;
    color: light-dark(var(--pine-3), var(--pine-5));
    cursor: pointer;
  }
  .clear:hover:not(:disabled) {
    background: var(--hover-overlay);
    color: var(--fg);
  }
  .clear:disabled {
    color: var(--fg-muted);
    opacity: 0.5;
    cursor: default;
  }
  .presets {
    display: flex;
    flex-wrap: wrap;
    gap: 5px;
  }
  .custom {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding-top: 2px;
  }
  .dt {
    display: flex;
    flex-direction: column;
    gap: 4px;
    font-size: 11.5px;
    color: var(--fg-muted);
  }
  .dt input {
    width: 100%;
    height: 30px;
    padding: 0 8px;
    border: 1px solid var(--rule);
    border-radius: 6px;
    background: var(--surface);
    color: var(--fg);
    font-family: var(--mono);
    font-size: 11.5px;
  }
  .dt input:focus {
    outline: 2px solid var(--gold);
    outline-offset: -1px;
    border-color: var(--gold);
  }
  .err {
    font-size: 11.5px;
    color: var(--danger);
  }
  .note {
    font-size: 11.5px;
    line-height: 1.45;
    color: var(--fg-muted);
  }
</style>
