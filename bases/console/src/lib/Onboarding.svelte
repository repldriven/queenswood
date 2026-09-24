<script>
  /* Onboarding — bind a new bank to a real UK legal entity.

     Four steps in a single centred column: enter a company number,
     look it up, confirm the matched entity, then name the bank
     (pre-filled with the registered name). A success step confirms
     the bank is provisioned and bound.

     All lookups go through the backend (/v1/companies/...);
     onboarding re-confirms and snapshots the entity onto the bank.

     `fresh` is the same flow reached from inside the app, from the
     Bank page's danger zone: a person who already holds a bank
     starting another, with a way back to the console until the new
     bank exists. */

  import { AppNav } from "@queenswood/ui";
  import { create_bank, lookup_company } from "./api.mjs";
  import {
    companyTypeLabel,
    jurisdictionLabel,
    statusLabel,
    isActive,
    fmtIncorporated,
    joinAddress,
    sanitiseNumber,
    COMPANY_NUMBER_LENGTH,
  } from "./companies.mjs";

  let { onComplete, onSignOut, onCancel, fresh = false } = $props();

  const ID_LABEL = "Companies House number";

  let step = $state(1);
  let number = $state("");
  let match = $state(null);
  let bankName = $state("");
  let result = $state(null);

  let looking = $state(false);
  let lookupError = $state(null);
  let creating = $state(false);
  let createError = $state(null);

  const numberValid = $derived(number.length === COMPANY_NUMBER_LENGTH);

  const LEDES = {
    1: "First, find your company on the official register.",
    2: "Confirm this is the legal entity your bank will be bound to.",
    3: "Give your bank its public-facing name.",
    4: "You're all set.",
  };

  function onNumberInput(e) {
    number = sanitiseNumber(e.target.value);
    lookupError = null;
  }

  function fillExample() {
    // Sirius Cybernetics — the H2G2 company, so a bank chartered to it
    // matches the demo customers Ford and Arthur.
    number = "SC998137";
    lookupError = null;
  }

  async function lookup() {
    if (!numberValid || looking) return;
    looking = true;
    lookupError = null;
    try {
      const res = await lookup_company(number);
      if (res.status === 200) {
        match = res.body;
        step = 2;
      } else {
        lookupError =
          res.body?.detail ??
          `No active company found for ${number}. Check the number and try again.`;
      }
    } catch (err) {
      lookupError = err.message;
    } finally {
      looking = false;
    }
  }

  function confirmMatch() {
    bankName = match?.["company-name"] ?? "";
    createError = null;
    step = 3;
  }

  async function create() {
    if (!bankName.trim() || creating) return;
    creating = true;
    createError = null;
    try {
      const res = await create_bank({
        companyNumber: number,
        bankName: bankName.trim(),
      });
      if (res.status === 201) {
        result = res.body;
        step = 4;
      } else {
        createError =
          res.body?.detail ?? `Couldn't create the bank (status ${res.status}).`;
      }
    } catch (err) {
      createError = err.message;
    } finally {
      creating = false;
    }
  }

  function goToConsole() {
    onComplete(result);
  }
</script>

<div class="page">
  <AppNav {onSignOut} />

  <main class="wrap">
    <header class="head">
      <span class="eyebrow">Console · {fresh ? "fresh bank" : "onboarding"}</span>
      {#if fresh}
        <h1>A fresh <em>bank.</em></h1>
      {:else}
        <h1>Welcome to <em>Queenswood.</em></h1>
      {/if}
      <p class="lede">{LEDES[step]}</p>
    </header>

    {#if step < 4}
      <ol class="pips" aria-label="Progress">
        {#each ["Company", "Confirm", "Name"] as label, i (label)}
          <li class:done={step > i + 1} class:current={step === i + 1}>
            <span class="pip">{step > i + 1 ? "✓" : i + 1}</span>
            {label}
          </li>
        {/each}
      </ol>
    {/if}

    <section class="card">
      {#if step === 1}
        <div class="field">
          <span class="flabel">{ID_LABEL}</span>
          <input
            class="num-input"
            class:err={lookupError}
            value={number}
            oninput={onNumberInput}
            onkeydown={(e) => e.key === "Enter" && lookup()}
            placeholder="········"
            autocomplete="off"
            spellcheck="false"
            aria-label={ID_LABEL}
          />
          <span class="hint">
            Eight characters, e.g. <code>TY046601</code> or <code>WY002122</code>.
            We'll look it up on the live register.
            <button type="button" class="link" onclick={fillExample}>Try SC998137</button>.
          </span>
          {#if lookupError}
            <p class="error" role="alert">
              <svg viewBox="0 0 16 16" aria-hidden="true"><circle cx="8" cy="8" r="6.5" fill="none" stroke="currentColor" stroke-width="1.4" /><path d="M8 5 V9 M8 11 V11.2" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" /></svg>
              {lookupError}
            </p>
          {/if}
        </div>

        <button class="btn solid lg" disabled={!numberValid || looking} onclick={lookup}>
          {looking ? "Searching Companies House…" : "Look up company"}
        </button>
      {:else if step === 2}
        <div class="match">
          <div class="match-head">
            <div>
              <h2 class="co-name">{match["company-name"]}</h2>
              <p class="co-sub">No. {match["company-number"]} · Companies House</p>
            </div>
            <span class="pill" class:ok={isActive(match)}>
              <span class="dot"></span>{statusLabel(match["company-status"])}
            </span>
          </div>
          <dl class="grid">
            <div><dt>Company type</dt><dd>{companyTypeLabel(match.type)}</dd></div>
            <div><dt>Incorporated</dt><dd>{fmtIncorporated(match["date-of-creation"])}</dd></div>
            <div><dt>Jurisdiction</dt><dd>{jurisdictionLabel(match.jurisdiction)}</dd></div>
            <div><dt>Company number</dt><dd class="mono">{match["company-number"]}</dd></div>
            <div class="full"><dt>Registered office</dt><dd>{joinAddress(match["registered-office-address"])}</dd></div>
          </dl>
        </div>
        <p class="reassure">
          <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 1.5 L13 3.5 V8 C13 11 10.5 13 8 14 C5.5 13 3 11 3 8 V3.5 Z" fill="none" stroke="currentColor" stroke-width="1.3" /></svg>
          Your bank will be bound to this legal entity. You can't change it later.
        </p>
        {#if !isActive(match)}
          <p class="error">This company is not active, so it can't be bound to a bank.</p>
        {/if}
        <div class="actions">
          <button class="btn solid lg" disabled={!isActive(match)} onclick={confirmMatch}>Yes, this is my company</button>
          <button class="btn line" onclick={() => (step = 1)}>Search for a different number</button>
        </div>
      {:else if step === 3}
        <div class="field">
          <span class="flabel">Bank name</span>
          <input class="name-input" bind:value={bankName} onfocus={(e) => e.target.select()} placeholder="Bank name" aria-label="Bank name" />
          <span class="hint">
            Pre-filled from <strong>{match["company-name"]}</strong>. Edit if your
            bank trades under a different name — this is the public-facing name
            customers see.
          </span>
          {#if createError}<p class="error" role="alert">{createError}</p>{/if}
        </div>
        <div class="actions">
          <button class="btn solid lg" disabled={!bankName.trim() || creating} onclick={create}>
            {creating ? "Provisioning…" : "Create bank"}
          </button>
          <button class="btn line" disabled={creating} onclick={() => (step = 2)}>Back</button>
        </div>
      {:else}
        <div class="success">
          <span class="seal" aria-hidden="true">
            <svg viewBox="0 0 24 24"><path d="M6 12.5 L10 16.5 L18 8" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" /></svg>
          </span>
          <h2 class="done-title"><em>{bankName}</em> is ready.</h2>
          <p class="done-sub">Bound to {match["company-name"]} · No. {match["company-number"]}</p>
          <button class="btn solid lg" onclick={goToConsole}>Go to console</button>
          <p class="meta">tenant provisioned · {match["company-number"]}</p>
        </div>
      {/if}
    </section>
    {#if onCancel && step < 4}
      <p class="back">
        <button type="button" class="link" onclick={onCancel}>Back to the console, keeping the bank you have</button>
      </p>
    {/if}
  </main>
</div>

<style>
  .page { min-height: 100vh; background: var(--surface); color: var(--fg); font-family: var(--grotesk); }
  .wrap { max-width: 620px; margin: 0 auto; padding: 56px 32px 80px; }

  .head { margin-bottom: 26px; }
  .eyebrow {
    font-family: var(--mono); font-size: 11px; letter-spacing: 0.2em;
    text-transform: uppercase; color: var(--fg-muted);
    display: inline-flex; align-items: center; gap: 8px;
  }
  .eyebrow::before { content: ""; width: 18px; height: 1px; background: var(--gold-deep); }
  h1 { font-family: var(--serif); font-weight: 500; font-size: 44px; line-height: 1.05; letter-spacing: -0.008em; margin: 12px 0 12px; }
  h1 em { font-style: italic; color: var(--gold-deep); font-weight: 500; }
  .lede { font-size: 16px; line-height: 1.5; color: var(--fg-2); margin: 0; }

  .pips { list-style: none; display: flex; gap: 22px; padding: 0; margin: 0 0 18px; }
  .pips li { display: inline-flex; align-items: center; gap: 8px; font-size: 12px; color: var(--fg-muted); }
  .pips li.current { color: var(--fg); }
  .pip {
    width: 20px; height: 20px; border-radius: 50%; display: inline-flex;
    align-items: center; justify-content: center; font-size: 11px;
    border: 1px solid var(--rule); color: var(--fg-muted);
  }
  .pips li.current .pip { border-color: var(--gold); color: var(--gold-deep); }
  .pips li.done .pip { background: var(--gold); border-color: var(--gold); color: var(--surface); }

  .card {
    background: var(--surface-raised); border: 1px solid var(--rule);
    border-radius: 16px; padding: 30px;
    display: flex; flex-direction: column; gap: 20px;
    animation: rise 0.34s cubic-bezier(0.16, 0.84, 0.34, 1);
  }
  @keyframes rise { from { transform: translateY(8px); opacity: 0; } to { transform: none; opacity: 1; } }
  @media (prefers-reduced-motion: reduce) { .card { animation: none; } }

  .field { display: flex; flex-direction: column; gap: 8px; }
  .flabel { font-size: 12px; font-weight: 600; letter-spacing: 0.16em; text-transform: uppercase; color: var(--gold-deep); }
  .hint { font-size: 12.5px; color: var(--fg-muted); line-height: 1.5; }
  .hint code { font-family: var(--mono); font-size: 11px; background: var(--surface-sunk); padding: 1px 5px; border-radius: 3px; }
  .link { background: none; border: none; padding: 0; color: var(--gold-deep); font: inherit; cursor: pointer; text-decoration: underline; }
  .back { margin: 18px 0 0; text-align: center; font-size: 13px; }

  .num-input {
    font-family: var(--mono); height: 52px; font-size: 22px; letter-spacing: 0.28em;
    text-transform: uppercase; text-align: center; border: 1px solid var(--rule);
    border-radius: 9px; background: var(--surface); color: var(--fg); padding: 0 14px;
  }
  .num-input:focus { outline: none; border-color: var(--gold); box-shadow: 0 0 0 3px color-mix(in oklch, var(--gold) 26%, transparent); }
  .num-input.err { border-color: var(--danger); box-shadow: 0 0 0 3px color-mix(in oklch, var(--danger) 22%, transparent); }
  .num-input::placeholder { letter-spacing: 0.28em; color: var(--fg-muted); }
  .name-input { height: 48px; font-size: 17px; border: 1px solid var(--rule); border-radius: 9px; background: var(--surface); color: var(--fg); padding: 0 14px; font-family: var(--grotesk); }
  .name-input:focus { outline: none; border-color: var(--gold); box-shadow: 0 0 0 3px color-mix(in oklch, var(--gold) 26%, transparent); }

  .error { display: flex; align-items: center; gap: 7px; margin: 0; color: var(--danger); font-size: 13px; }
  .error svg { width: 15px; height: 15px; flex: 0 0 auto; }

  /* Match card */
  .match { border: 1px solid var(--rule); border-radius: 12px; padding: 22px; background: var(--surface); }
  .match-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 18px; }
  .co-name { font-family: var(--serif); font-size: 28px; font-weight: 600; margin: 0; line-height: 1.1; }
  .co-sub { font-family: var(--mono); font-size: 12.5px; color: var(--fg-muted); margin: 4px 0 0; }
  .pill { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; padding: 4px 10px; border-radius: 999px; background: var(--surface-sunk); color: var(--fg-muted); white-space: nowrap; }
  .pill.ok { background: light-dark(oklch(0.92 0.04 145), oklch(0.27 0.05 145)); color: light-dark(oklch(0.4 0.08 145), oklch(0.82 0.06 145)); }
  .pill .dot { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
  .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0; margin: 0; }
  .grid > div { padding: 12px 0; border-top: 1px solid var(--rule-2); }
  .grid > div.full { grid-column: 1 / -1; }
  .grid dt { font-size: 11px; letter-spacing: 0.08em; text-transform: uppercase; color: var(--fg-muted); margin: 0 0 4px; }
  .grid dd { margin: 0; font-size: 14px; color: var(--fg); }
  .grid dd.mono { font-family: var(--mono); }
  .reassure { display: flex; align-items: center; gap: 9px; margin: 0; font-size: 13px; color: var(--fg-2); }
  .reassure svg { width: 17px; height: 17px; color: var(--gold-deep); flex: 0 0 auto; }

  .actions { display: flex; flex-direction: column; gap: 10px; }

  /* Buttons */
  .btn { height: 44px; padding: 0 18px; display: inline-flex; align-items: center; justify-content: center; gap: 8px; border-radius: 9px; font-size: 14px; font-weight: 500; border: 1px solid transparent; cursor: pointer; font-family: var(--grotesk); transition: background 0.12s, border-color 0.12s, opacity 0.12s; }
  .btn.lg { height: 52px; width: 100%; }
  .btn.solid { background: var(--ink); color: var(--paper); }
  .btn.solid:hover { background: var(--ink-2); }
  .btn.line { background: transparent; border-color: var(--rule); color: var(--fg-2); }
  .btn.line:hover { background: var(--hover-overlay); color: var(--fg); }
  .btn:disabled { opacity: 0.5; cursor: not-allowed; }

  /* Success */
  .success { display: flex; flex-direction: column; align-items: center; text-align: center; gap: 14px; padding: 12px 0; }
  .seal { width: 64px; height: 64px; border-radius: 50%; display: inline-flex; align-items: center; justify-content: center; background: light-dark(oklch(0.92 0.05 145), oklch(0.3 0.06 145)); color: var(--ok); animation: pop 0.36s cubic-bezier(0.16, 0.84, 0.34, 1); }
  .seal svg { width: 30px; height: 30px; }
  @keyframes pop { from { transform: scale(0.7); opacity: 0; } to { transform: none; opacity: 1; } }
  @media (prefers-reduced-motion: reduce) { .seal { animation: none; } }
  .done-title { font-family: var(--serif); font-size: 30px; font-weight: 600; margin: 0; }
  .done-title em { font-style: italic; color: var(--gold-deep); }
  .done-sub { font-size: 14px; color: var(--fg-2); margin: 0; }
  .meta { font-family: var(--mono); font-size: 11px; color: var(--fg-muted); margin: 4px 0 0; }
</style>
