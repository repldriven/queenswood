<script>
  /* Badge — small status pill with a leading dot.

     Tones (semantic, not literal colors):
       draft      muted amber  — work in progress
       published  muted pine   — live
       archived   muted gray   — historical / inactive
       pending    muted violet — awaiting an external step
       rejected   muted rust   — declined by an approver
       neutral   surface-sunk  — fallback / no-state

     Policy categories share the same chroma family as the status tones:
       standard   pine   — the settled baseline tier
       restricted amber  — cautionary, narrowed permissions
       emergency  red    — break-glass / alarming

     Job run states reuse the same hue conventions (145 pine = good,
     30 rust = bad, 80 amber = in-progress, 270 violet = waiting):
       succeeded  pine   — last run completed cleanly
       failed     rust   — last run errored
       running    amber  — a run is in progress (dot pulses)
       scheduled  violet — never run yet, awaiting first fire
     (a paused schedule reuses `archived`.)

     Invitation states:
       pending    violet — awaiting the recipient (reuses `pending`)
       accepted   pine   — the recipient became a member
       expired    amber  — seven days passed unaccepted
       withdrawn  gray   — an admin took it back
       declined   rust   — the recipient turned it down

     Text is lowercased visually so callers can pass "Published" or
     "PUBLISHED" interchangeably without worrying about case. */

  let { tone = "neutral", children } = $props();
</script>

<span class="badge {tone}">{@render children?.()}</span>

<style>
  .badge {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    height: 22px;
    padding: 0 9px;
    border-radius: 999px;
    font-family: var(--grotesk);
    font-size: 11px;
    font-weight: 500;
    letter-spacing: 0.02em;
    text-transform: lowercase;
    line-height: 1;
    white-space: nowrap;
  }
  .badge::before {
    content: "";
    width: 5px;
    height: 5px;
    border-radius: 50%;
    background: currentColor;
    opacity: 0.85;
  }
  .badge.draft {
    background: light-dark(oklch(0.93 0.055 80),  oklch(0.27 0.055 80));
    color:      light-dark(oklch(0.42 0.115 70),  oklch(0.85 0.105 80));
  }
  .badge.published {
    background: light-dark(oklch(0.92 0.04 145),  oklch(0.26 0.05 145));
    color:      light-dark(oklch(0.34 0.075 145), oklch(0.82 0.06 145));
  }
  .badge.archived {
    background: light-dark(oklch(0.92 0.005 70),  oklch(0.26 0.005 70));
    color:      light-dark(oklch(0.45 0.005 70),  oklch(0.72 0.005 70));
  }
  .badge.pending {
    background: light-dark(oklch(0.92 0.04 270),  oklch(0.27 0.05 270));
    color:      light-dark(oklch(0.40 0.08 270),  oklch(0.80 0.07 270));
  }
  .badge.rejected {
    background: light-dark(oklch(0.92 0.04 30),   oklch(0.27 0.055 30));
    color:      light-dark(oklch(0.42 0.115 30),  oklch(0.82 0.105 30));
  }
  .badge.neutral {
    background: var(--surface-sunk);
    color: var(--fg-muted);
  }

  /* Policy categories — same chroma family as the status tones above. */
  .badge.standard {
    background: light-dark(oklch(0.94 0.04 145),  oklch(0.26 0.05 145));
    color:      light-dark(oklch(0.34 0.075 145), oklch(0.82 0.06 145));
  }
  .badge.restricted {
    background: light-dark(oklch(0.93 0.055 80),  oklch(0.27 0.055 80));
    color:      light-dark(oklch(0.42 0.115 70),  oklch(0.85 0.105 80));
  }
  .badge.emergency {
    background: light-dark(oklch(0.94 0.04 30),  oklch(0.27 0.055 30));
    color:      light-dark(oklch(0.45 0.12 30),  oklch(0.84 0.10 30));
  }

  /* Job run states — hue conventions shared with the status tones. */
  .badge.succeeded {
    background: light-dark(oklch(0.92 0.04 145), oklch(0.26 0.05 145));
    color:      light-dark(oklch(0.34 0.075 145), oklch(0.82 0.06 145));
  }
  .badge.failed {
    background: light-dark(oklch(0.92 0.04 30),  oklch(0.27 0.055 30));
    color:      light-dark(oklch(0.44 0.140 30), oklch(0.82 0.115 30));
  }
  .badge.running {
    background: light-dark(oklch(0.93 0.055 80), oklch(0.29 0.060 78));
    color:      light-dark(oklch(0.46 0.120 68), oklch(0.86 0.115 82));
  }
  .badge.scheduled {
    background: light-dark(oklch(0.92 0.04 270), oklch(0.27 0.05 270));
    color:      light-dark(oklch(0.40 0.08 270), oklch(0.80 0.07 270));
  }

  /* Invitation states — hue conventions shared with the status tones. */
  .badge.accepted {
    background: light-dark(oklch(0.92 0.04 145), oklch(0.26 0.05 145));
    color:      light-dark(oklch(0.34 0.075 145), oklch(0.82 0.06 145));
  }
  .badge.expired {
    background: light-dark(oklch(0.93 0.055 75), oklch(0.28 0.06 70));
    color:      light-dark(oklch(0.45 0.105 65), oklch(0.84 0.09 80));
  }
  .badge.withdrawn {
    background: light-dark(oklch(0.92 0.005 70), oklch(0.26 0.005 70));
    color:      light-dark(oklch(0.45 0.005 70), oklch(0.72 0.005 70));
  }
  .badge.declined {
    background: light-dark(oklch(0.92 0.04 30),  oklch(0.27 0.055 30));
    color:      light-dark(oklch(0.42 0.115 30), oklch(0.82 0.105 30));
  }

  /* running's dot breathes; respect reduced-motion. */
  .badge.running::before {
    animation: qw-pulse 1.3s ease-in-out infinite;
  }
  @keyframes qw-pulse {
    0%, 100% { opacity: 0.9; }
    50%      { opacity: 0.2; }
  }
  @media (prefers-reduced-motion: reduce) {
    .badge.running::before { animation: none; }
  }
</style>
