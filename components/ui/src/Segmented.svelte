<script>
  /* Segmented — a choice between a few options, shown side by side,
     with a thumb that slides to the one chosen. Use it in place of a
     checkbox where both sides of the choice have a name.

         <Segmented label="Interest" bind:value={pays}
                    options={[{ value: false, label: "No interest" },
                              { value: true, label: "Accrues interest" }]} />

     It is a radiogroup: each option is a radio, and left and right
     arrows move between them. `onchange` receives the value chosen,
     for a choice that changes more than itself; `disabled` holds it at
     its value. */

  let {
    options = [],
    value = $bindable(),
    label,
    disabled = false,
    onchange,
  } = $props();

  const index = $derived(
    Math.max(
      0,
      options.findIndex((o) => o.value === value),
    ),
  );

  function select(i) {
    if (disabled || options[i].value === value) return;
    value = options[i].value;
    onchange?.(value);
  }

  function onkeydown(e, i) {
    if (e.key !== "ArrowRight" && e.key !== "ArrowLeft") return;
    e.preventDefault();
    const step = e.key === "ArrowRight" ? 1 : -1;
    const next = (i + step + options.length) % options.length;
    select(next);
    e.currentTarget.parentElement
      ?.querySelectorAll("button")
      [next]?.focus();
  }
</script>

<div
  class="qw-segmented"
  role="radiogroup"
  aria-label={label}
  aria-disabled={disabled}
  style="--n: {options.length}; --i: {index}"
>
  <span class="qw-segmented-thumb" aria-hidden="true"></span>
  {#each options as option, i (option.label)}
    <button
      type="button"
      role="radio"
      aria-checked={i === index}
      tabindex={i === index ? 0 : -1}
      {disabled}
      onclick={() => select(i)}
      onkeydown={(e) => onkeydown(e, i)}
    >
      {option.label}
    </button>
  {/each}
</div>

<style>
  .qw-segmented {
    position: relative;
    display: inline-grid;
    grid-template-columns: repeat(var(--n), minmax(0, 1fr));
    padding: 3px;
    border: 1px solid var(--rule);
    border-radius: 8px;
    background: var(--surface-sunk);
    isolation: isolate;
  }
  .qw-segmented-thumb {
    position: absolute;
    z-index: -1;
    top: 3px;
    bottom: 3px;
    left: 3px;
    width: calc((100% - 6px) / var(--n));
    border-radius: 6px;
    background: var(--surface-raised);
    box-shadow: 0 0 0 1px var(--rule), 0 1px 2px oklch(0 0 0 / 0.08);
    transform: translateX(calc(100% * var(--i)));
    transition: transform 0.18s ease;
  }
  button {
    height: 28px;
    padding: 0 14px;
    border: 0;
    border-radius: 6px;
    background: transparent;
    color: var(--fg-muted);
    font-family: var(--grotesk);
    font-size: 12.5px;
    font-weight: 500;
    line-height: 1;
    white-space: nowrap;
    cursor: pointer;
    transition: color 0.12s;
  }
  button[aria-checked="true"] {
    color: var(--fg);
  }
  button:hover:not(:disabled) {
    color: var(--fg);
  }
  button:focus-visible {
    outline: 2px solid var(--gold);
    outline-offset: 1px;
  }
  button:disabled {
    cursor: default;
  }
  .qw-segmented[aria-disabled="true"] {
    opacity: 0.6;
  }
  @media (prefers-reduced-motion: reduce) {
    .qw-segmented-thumb {
      transition: none;
    }
  }
</style>
