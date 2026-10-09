<script>
  /* Onboarding — bind a new bank to a real UK legal entity.

     One screen, three numbered sections in a single card: look up the
     company by number (the match expands in place, with no separate
     confirm), name the bank (pre-filled from the registered name), and
     choose a provider of each kind the installation offers, its default
     selected. Name and providers stay locked until an active company
     matches. Creating the bank swaps the card for a summary of what was
     chosen.

     A person who isn't a member of any bank may instead wait for an
     invitation, which swaps the card for a holding view.

     All lookups go through the backend (/v1/companies/...);
     onboarding re-confirms and snapshots the entity onto the bank.

     `fresh` is the same flow reached from inside the app, from the
     Bank page's danger zone: a person who already holds a bank
     starting another, with a way back to the console until the new
     bank exists. */

  import { tick } from "svelte";
  import { AppNav, SandboxBanner } from "@queenswood/ui";
  import { create_bank, list_providers, lookup_company } from "./api.mjs";
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
  import { defaultChoice, kindLabel, providerLabel } from "./providers.mjs";

  let { user, onComplete, onSignOut, onCancel, fresh = false } = $props();

  const ID_LABEL = "Companies House number";
  const COPIED_MS = 1400;

  let number = $state("");
  let looking = $state(false);
  let lookupError = $state(null);
  let match = $state(null);
  let bankName = $state("");
  let offered = $state([]);
  let choice = $state({});
  let providersError = $state(null);
  let creating = $state(false);
  let retrying = $state(false);
  let createError = $state(null);
  // The key a create is sent under, kept while what it would create is
  // unchanged, so pressing Create again after a failure cannot make a
  // second bank.
  let submission = null;
  let result = $state(null);
  let waiting = $state(false);
  let copied = $state(false);

  let numberInput = $state();
  let nameInput = $state();
  let changeButton = $state();
  let enterButton = $state();
  let signOutButton = $state();

  const numberValid = $derived(number.length === COMPANY_NUMBER_LENGTH);
  const matched = $derived(isActive(match));
  const canCreate = $derived(matched && bankName.trim() && !creating);
  const firstName = $derived(user?.name?.trim().split(/\s+/)[0]);
  const email = $derived(user?.email);

  async function loadProviders() {
    try {
      const res = await list_providers();
      if (res.status === 200) {
        offered = res.body?.items ?? [];
        choice = defaultChoice(offered);
      } else {
        providersError = res.body?.detail ?? `status ${res.status}`;
      }
    } catch (err) {
      providersError = err.message;
    }
  }

  $effect(() => {
    loadProviders();
  });

  async function focus(target) {
    await tick();
    target()?.focus();
  }

  function onNumberInput(e) {
    number = sanitiseNumber(e.target.value);
    e.target.value = number;
    lookupError = null;
  }

  function fillExample() {
    // Sirius Cybernetics — the H2G2 company, so a bank chartered to it
    // matches the demo customers Ford and Arthur.
    number = "SC998137";
    lookupError = null;
    focus(() => numberInput);
  }

  async function lookup() {
    if (!numberValid || looking) return;
    looking = true;
    lookupError = null;
    try {
      const res = await lookup_company(number);
      if (res.status === 200) {
        match = res.body;
        bankName = match.name ?? "";
        createError = null;
        focus(() => (isActive(match) ? nameInput : changeButton));
      } else {
        lookupError =
          res.status === 404
            ? `No company found for ${number}. Check the number and try again.`
            : (res.body?.detail ?? `Couldn't look up ${number} (status ${res.status}).`);
        focus(() => numberInput);
      }
    } catch (err) {
      lookupError = err.message;
      focus(() => numberInput);
    } finally {
      looking = false;
    }
  }

  function changeCompany() {
    match = null;
    bankName = "";
    createError = null;
    focus(() => numberInput);
  }

  async function create() {
    if (!canCreate) return;
    creating = true;
    createError = null;
    const request = {
      companyNumber: match["company-number"],
      bankName: bankName.trim(),
      providers: offered.length ? { ...choice } : undefined,
    };
    const signature = JSON.stringify(request);
    if (submission?.signature !== signature) {
      submission = { signature, key: crypto.randomUUID() };
    }
    try {
      const res = await create_bank({
        ...request,
        key: submission.key,
        onRetry: () => (retrying = true),
      });
      if (res.status === 201) {
        result = res.body;
        focus(() => enterButton);
      } else {
        createError =
          res.body?.detail ?? `Couldn't create the bank (status ${res.status}).`;
      }
    } catch (err) {
      createError = err.message;
    } finally {
      creating = false;
      retrying = false;
    }
  }

  async function copyBankId() {
    try {
      await navigator.clipboard.writeText(result["bank-id"]);
      copied = true;
      setTimeout(() => (copied = false), COPIED_MS);
    } catch {
      copied = false;
    }
  }

  function wait() {
    waiting = true;
    focus(() => signOutButton);
  }

  function unwait() {
    waiting = false;
    focus(() => numberInput);
  }
</script>

<div class="page">
  <AppNav {onSignOut} />
  <SandboxBanner />

  <main class="wrap">
    <header>
      <span class="eyebrow">Console · {fresh ? "fresh bank" : "onboarding"}</span>
      {#if fresh}
        <h1>A fresh <em>bank.</em></h1>
      {:else if firstName}
        <h1>Welcome, <em>{firstName}.</em></h1>
      {:else}
        <h1>Welcome to <em>Queenswood.</em></h1>
      {/if}
      <p class="lede">
        {#if result}
          You're all set.
        {:else if waiting}
          You're signed in as <strong>{email}</strong>. When an owner or admin
          invites you, open the link in the invitation email to join their bank.
        {:else if fresh}
          Bind a new bank to a company, name it, and choose the providers it
          runs on.
        {:else}
          You're signed in as <strong>{email}</strong>, but you're not a member
          of any bank yet. Either create one below, or ask an owner or admin of
          an existing bank to invite you.
        {/if}
      </p>
      {#if !fresh && !result && !waiting}
        <div class="invite">
          <span class="disc">
            <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3.5" y="5.5" width="17" height="13" rx="2" /><path d="m4 7 8 6 8-6" /></svg>
          </span>
          <span class="invite-text">
            <strong>Expecting an invitation?</strong>
            <span>Skip creating a bank and join one when you're invited.</span>
          </span>
          <button type="button" class="btn line panel" onclick={wait}>Wait for an invitation</button>
        </div>
      {/if}
    </header>

    <section class="card">
      {#if waiting}
        <div class="done">
          <span class="seal wait" aria-hidden="true">
            <svg viewBox="0 0 24 24" class="envelope"><rect x="3.5" y="5.5" width="17" height="13" rx="2" /><path d="m4 7 8 6 8-6" /></svg>
          </span>
          <h2>Waiting for an <em>invitation.</em></h2>
          <p>There's nothing more to do here until you're invited.</p>
          <div class="wait-actions">
            <button class="btn solid" bind:this={signOutButton} onclick={onSignOut}>Sign out</button>
            <button class="btn line" onclick={unwait}>Create a bank instead</button>
          </div>
        </div>
      {:else if result}
        <div class="done">
          <span class="seal" aria-hidden="true">
            <svg viewBox="0 0 24 24" class="check"><path d="M6 12.5 10 16.5 18 8" /></svg>
          </span>
          <h2><em>{result.name}</em> is ready.</h2>
          <dl class="summary">
            <div><dt>Bank name</dt><dd>{result.name}</dd></div>
            <div>
              <dt>Company</dt>
              <dd>
                {match.name}
                <span class="sub">No. {match["company-number"]} · Companies House</span>
              </dd>
            </div>
            {#each Object.entries(result.providers ?? {}) as [kind, key] (kind)}
              <div><dt>{kindLabel(kind)}</dt><dd>{providerLabel(key)}</dd></div>
            {/each}
          </dl>
          <button class="btn solid enter" bind:this={enterButton} onclick={() => onComplete(result)}>Go to console</button>
          <p class="meta">
            <span>Bank ID</span>
            <code>{result["bank-id"]}</code>
            <button type="button" class="copy" onclick={copyBankId}>{copied ? "Copied" : "Copy"}</button>
          </p>
        </div>
      {:else}
        <div class="sec">
          <span class="sec-n">01</span>
          <div class="sec-body">
            {#if match}
              <p class="flabel">Company</p>
              <div class="match">
                <div class="match-head">
                  <div>
                    <h2 class="co-name">{match.name}</h2>
                    <p class="co-sub">No. {match["company-number"]} · Companies House</p>
                  </div>
                  <span class="pill" class:ok={matched}>
                    <span class="dot"></span>{statusLabel(match.status)}
                  </span>
                </div>
                <dl class="facts">
                  <div><dt>Company type</dt><dd>{companyTypeLabel(match.type)}</dd></div>
                  <div><dt>Incorporated</dt><dd>{fmtIncorporated(match["incorporated-on"])}</dd></div>
                  <div><dt>Jurisdiction</dt><dd>{jurisdictionLabel(match.jurisdiction)}</dd></div>
                  <div><dt>Company number</dt><dd class="mono">{match["company-number"]}</dd></div>
                  <div class="full"><dt>Registered office</dt><dd>{joinAddress(match["registered-office-address"])}</dd></div>
                </dl>
                <div class="match-foot">
                  <span>{matched ? "Not your company?" : "Only an active company can hold a bank."}</span>
                  <button type="button" class="link" bind:this={changeButton} onclick={changeCompany}>Use a different number</button>
                </div>
              </div>
              {#if !matched}
                <p class="error" role="alert">
                  <svg viewBox="0 0 16 16" aria-hidden="true"><circle cx="8" cy="8" r="6.5" /><path d="M8 5v3.5M8 11h.01" /></svg>
                  This company is {match.status}, so it can't be bound to a bank.
                </p>
              {/if}
            {:else}
              <label class="flabel" for="company-number">{ID_LABEL}</label>
              <div class="lookup">
                <input
                  id="company-number"
                  class="input num"
                  class:err={lookupError}
                  bind:this={numberInput}
                  value={number}
                  oninput={onNumberInput}
                  onkeydown={(e) => e.key === "Enter" && lookup()}
                  placeholder="········"
                  autocomplete="off"
                  spellcheck="false"
                />
                <button class="btn solid" disabled={!numberValid || looking} onclick={lookup}>
                  {#if looking}<span class="spin"></span>Looking up{:else}Look up{/if}
                </button>
              </div>
              {#if lookupError}
                <p class="error" role="alert">
                  <svg viewBox="0 0 16 16" aria-hidden="true"><circle cx="8" cy="8" r="6.5" /><path d="M8 5v3.5M8 11h.01" /></svg>
                  {lookupError}
                </p>
              {/if}
              <p class="hint">
                Eight characters, e.g. <code>TY046601</code> or <code>WY002122</code>.
                <button type="button" class="link" onclick={fillExample}>Try SC998137</button>.
              </p>
            {/if}
          </div>
        </div>

        <div class="sec" class:locked={!matched} aria-disabled={!matched || undefined}>
          <span class="sec-n">02</span>
          <div class="sec-body">
            <label class="flabel" for="bank-name">Bank name</label>
            <input
              id="bank-name"
              class="input"
              bind:this={nameInput}
              bind:value={bankName}
              onfocus={(e) => e.target.select()}
              onkeydown={(e) => e.key === "Enter" && create()}
              placeholder={matched ? "Bank name" : "Filled in from the company"}
              disabled={!matched}
              autocomplete="off"
            />
            <p class="hint">
              {#if matched}
                The public-facing name customers see. Pre-filled from the
                registered name; edit it if your bank trades under another.
              {:else}
                Look up your company first.
              {/if}
            </p>
          </div>
        </div>

        <div class="sec" class:locked={!matched} aria-disabled={!matched || undefined}>
          <span class="sec-n">03</span>
          <div class="sec-body">
            <p class="flabel">Providers</p>
            {#if offered.length}
              <div class="kinds">
                {#each offered as k (k.kind)}
                  <div class="kind">
                    <span class="kind-label">{kindLabel(k.kind)}</span>
                    {#if k.providers.length === 1}
                      <span class="only">{providerLabel(k.providers[0])}<span class="tag">only option</span></span>
                    {:else}
                      <div class="choices" role="radiogroup" aria-label={kindLabel(k.kind)}>
                        {#each k.providers as p (p)}
                          <label class="choice" class:on={choice[k.kind] === p}>
                            <input type="radio" name={`provider-${k.kind}`} value={p} bind:group={choice[k.kind]} disabled={!matched} />
                            {providerLabel(p)}
                            {#if p === k.default}<span class="tag">default</span>{/if}
                          </label>
                        {/each}
                      </div>
                    {/if}
                  </div>
                {/each}
              </div>
            {:else if providersError}
              <p class="hint">The providers on offer couldn't be read ({providersError}), so your bank takes the installation's defaults.</p>
            {/if}
          </div>
        </div>

        <div class="submit">
          <p class="permanent">
            <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 1.5 13 3.5V8c0 3-2.5 5-5 6-2.5-1-5-3-5-6V3.5Z" /></svg>
            <span>The company and providers are fixed once the bank exists.</span>
          </p>
          {#if createError}
            <p class="error" role="alert">
              <svg viewBox="0 0 16 16" aria-hidden="true"><circle cx="8" cy="8" r="6.5" /><path d="M8 5v3.5M8 11h.01" /></svg>
              {createError}
            </p>
          {/if}
          <button class="btn solid wide" disabled={!canCreate} onclick={create}>
            {#if retrying}<span class="spin"></span>Still working…{:else if creating}<span class="spin"></span>Provisioning…{:else}Create bank{/if}
          </button>
        </div>
      {/if}
    </section>

    {#if onCancel && !result}
      <p class="back">
        <button type="button" class="link" onclick={onCancel}>Back to the console, keeping the bank you have</button>
      </p>
    {/if}
  </main>
</div>

<style>
  .page *, .page *::before, .page *::after { box-sizing: border-box; }
  .page { min-height: 100vh; background: var(--surface); color: var(--fg); font-family: var(--grotesk); -webkit-font-smoothing: antialiased; }
  .wrap { max-width: 640px; margin: 0 auto; padding: 52px 28px 88px; }

  .eyebrow {
    font-family: var(--mono); font-size: 11px; letter-spacing: 0.2em;
    text-transform: uppercase; color: var(--fg-muted);
    display: inline-flex; align-items: center; gap: 8px;
  }
  .eyebrow::before { content: ""; width: 18px; height: 1px; background: var(--gold-deep); }
  h1 { font-family: var(--serif); font-weight: 500; font-size: 48px; line-height: 1.02; letter-spacing: -0.01em; margin: 12px 0; }
  h1 em { font-style: italic; color: var(--gold-deep); }
  .lede { font-size: 16px; line-height: 1.5; color: var(--fg-2); margin: 0 0 20px; text-wrap: pretty; }
  .lede strong { font-weight: 600; color: var(--fg); }

  /* Invitation panel */
  .invite {
    margin: 0 0 20px; display: flex; align-items: center; gap: 14px;
    padding: 14px 14px 14px 16px; border: 1px solid var(--rule);
    border-radius: 12px; background: var(--surface-raised);
    font-size: 13px; color: var(--fg-muted);
  }
  .disc {
    flex: none; width: 36px; height: 36px; border-radius: 50%;
    display: grid; place-items: center;
    background: color-mix(in oklch, var(--gold) 16%, transparent); color: var(--gold-deep);
  }
  .disc svg { width: 18px; height: 18px; fill: none; stroke: currentColor; stroke-width: 1.6; stroke-linecap: round; stroke-linejoin: round; }
  .invite-text { display: flex; flex-direction: column; gap: 2px; min-width: 0; flex: 1; line-height: 1.4; }
  .invite-text strong { font-size: 14px; font-weight: 500; color: var(--fg); }

  /* Card */
  .card {
    background: var(--surface-raised); border: 1px solid var(--rule); border-radius: 16px;
    box-shadow: 0 1px 0 var(--rule-2), 0 24px 60px -40px rgba(20, 15, 10, 0.5);
  }
  .sec { padding: 26px 30px; display: grid; grid-template-columns: 28px minmax(0, 1fr); column-gap: 14px; transition: opacity 0.2s; }
  .sec + .sec { border-top: 1px solid var(--rule-2); }
  .sec.locked { opacity: 0.42; pointer-events: none; }
  .sec-n { font-family: var(--mono); font-size: 12px; color: var(--gold-deep); padding-top: 1px; }
  .sec-body { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
  .flabel { font-size: 12px; font-weight: 600; letter-spacing: 0.16em; text-transform: uppercase; color: var(--gold-deep); margin: 0; }
  .hint { font-size: 12.5px; color: var(--fg-muted); line-height: 1.5; margin: 0; text-wrap: pretty; }
  .hint code { font-family: var(--mono); font-size: 11.5px; background: var(--surface-sunk); padding: 1px 5px; border-radius: 3px; }
  .link {
    background: none; border: none; padding: 0; color: var(--gold-deep); font: inherit;
    cursor: pointer; text-decoration: underline; text-underline-offset: 2px;
  }
  .link:hover { color: var(--fg); }
  .back { margin: 18px 0 0; text-align: center; font-size: 13px; }

  /* Inputs */
  .lookup { display: flex; gap: 8px; }
  .input {
    height: 52px; width: 100%; min-width: 0; padding: 0 14px; border-radius: 9px;
    border: 1px solid var(--rule); background: var(--surface); color: var(--fg);
    font-family: var(--grotesk); font-size: 17px;
    transition: border-color 0.12s, box-shadow 0.12s;
  }
  .input:focus { outline: none; border-color: var(--gold); box-shadow: 0 0 0 3px color-mix(in oklch, var(--gold) 26%, transparent); }
  .input::placeholder { color: var(--fg-muted); }
  .input.num { font-family: var(--mono); font-size: 21px; letter-spacing: 0.26em; text-transform: uppercase; }
  .input.num::placeholder { letter-spacing: 0.26em; }
  .input.err { border-color: var(--danger); box-shadow: 0 0 0 3px color-mix(in oklch, var(--danger) 22%, transparent); }

  /* Buttons */
  .btn {
    height: 52px; padding: 0 20px; display: inline-flex; align-items: center; justify-content: center;
    gap: 9px; border-radius: 9px; font-family: var(--grotesk); font-size: 14.5px; font-weight: 500;
    border: 1px solid transparent; cursor: pointer; white-space: nowrap;
    transition: background 0.12s, opacity 0.12s;
  }
  .btn.solid { background: var(--ink); color: var(--paper); }
  .btn.solid:hover:not(:disabled) { background: var(--ink-2); }
  .btn.line { background: transparent; border-color: var(--rule); color: var(--fg-2); }
  .btn.line:hover:not(:disabled) { background: var(--hover-overlay); color: var(--fg); }
  .btn:disabled { opacity: 0.45; cursor: not-allowed; }
  .btn.wide { width: 100%; }
  .btn.panel { height: 40px; font-size: 13.5px; flex: none; }
  .spin {
    width: 15px; height: 15px; border-radius: 50%;
    border: 2px solid color-mix(in oklch, currentColor 35%, transparent);
    border-top-color: currentColor; animation: spin 0.7s linear infinite;
  }
  @keyframes spin { to { transform: rotate(360deg); } }

  .error { display: flex; align-items: flex-start; gap: 7px; margin: 0; color: var(--danger); font-size: 13px; line-height: 1.45; }
  .error svg { width: 15px; height: 15px; flex: none; margin-top: 1px; fill: none; stroke: currentColor; stroke-width: 1.4; stroke-linecap: round; }

  /* Match card */
  .match {
    border: 1px solid var(--rule); border-radius: 12px; background: var(--surface); overflow: hidden;
    animation: rise 0.3s cubic-bezier(0.16, 0.84, 0.34, 1);
  }
  @keyframes rise { from { opacity: 0; transform: translateY(6px); } }
  .match-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; padding: 18px 18px 16px; }
  .match-head > div { min-width: 0; }
  .co-name { font-family: var(--serif); font-size: 25px; font-weight: 600; line-height: 1.08; margin: 0; overflow-wrap: break-word; }
  .co-sub { font-family: var(--mono); font-size: 12px; color: var(--fg-muted); margin: 5px 0 0; letter-spacing: 0.04em; }
  .pill {
    flex: none; display: inline-flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 500;
    padding: 4px 10px; border-radius: 999px; background: var(--surface-sunk); color: var(--fg-muted);
  }
  .pill.ok { background: color-mix(in oklch, var(--ok) 15%, transparent); color: var(--ok); }
  .pill .dot { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
  .facts { display: grid; grid-template-columns: 1fr 1fr; margin: 0; border-top: 1px solid var(--rule-2); }
  .facts > div { padding: 11px 18px; border-bottom: 1px solid var(--rule-2); }
  .facts > div:nth-child(odd):not(.full) { border-right: 1px solid var(--rule-2); }
  .facts > div.full { grid-column: 1 / -1; border-bottom: none; }
  .facts dt { font-size: 10.5px; letter-spacing: 0.12em; text-transform: uppercase; color: var(--fg-muted); margin: 0 0 3px; }
  .facts dd { margin: 0; font-size: 13.5px; line-height: 1.4; }
  .facts dd.mono { font-family: var(--mono); }
  .match-foot {
    display: flex; justify-content: space-between; align-items: center; gap: 12px;
    padding: 10px 18px; background: var(--surface-sunk); font-size: 12.5px; color: var(--fg-muted);
  }

  /* Providers */
  .kinds { display: flex; flex-direction: column; gap: 16px; }
  .kind { display: flex; flex-direction: column; gap: 8px; }
  .kind-label { font-size: 13px; color: var(--fg-muted); }
  .choices { display: flex; flex-wrap: wrap; gap: 8px; }
  .choice {
    display: inline-flex; align-items: center; gap: 8px; height: 38px; padding: 0 13px;
    border: 1px solid var(--rule); border-radius: 9px; background: var(--surface);
    font-size: 14px; color: var(--fg-2); cursor: pointer;
  }
  .choice:hover { border-color: light-dark(rgba(20, 15, 10, 0.2), rgba(244, 241, 234, 0.22)); }
  .choice.on { border-color: var(--gold); color: var(--fg); box-shadow: 0 0 0 3px color-mix(in oklch, var(--gold) 20%, transparent); }
  .choice input { accent-color: var(--gold-deep); margin: 0; }
  .choice:has(input:focus-visible) { outline: 2px solid var(--gold); outline-offset: 2px; }
  .only { font-size: 14px; color: var(--fg); display: flex; align-items: center; gap: 8px; }
  .tag { font-family: var(--mono); font-size: 10.5px; letter-spacing: 0.06em; color: var(--fg-muted); }

  /* Submit */
  .submit { padding: 22px 30px 26px; border-top: 1px solid var(--rule); display: flex; flex-direction: column; gap: 12px; }
  .permanent { display: flex; align-items: flex-start; gap: 9px; margin: 0; font-size: 13px; color: var(--fg-2); line-height: 1.45; }
  .permanent svg { width: 16px; height: 16px; color: var(--gold-deep); flex: none; margin-top: 1px; fill: none; stroke: currentColor; stroke-width: 1.3; }

  /* Success and waiting */
  .done {
    padding: 40px 30px 34px; display: flex; flex-direction: column; align-items: center;
    text-align: center; gap: 12px; animation: rise 0.34s cubic-bezier(0.16, 0.84, 0.34, 1);
  }
  .seal {
    width: 64px; height: 64px; border-radius: 50%; display: grid; place-items: center;
    background: color-mix(in oklch, var(--ok) 16%, transparent); color: var(--ok);
  }
  .seal.wait { background: color-mix(in oklch, var(--gold) 16%, transparent); color: var(--gold-deep); }
  .seal svg { width: 30px; height: 30px; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; }
  .seal svg.check { stroke-width: 2.2; }
  .seal svg.envelope { stroke-width: 1.8; }
  .done h2 { font-family: var(--serif); font-size: 32px; font-weight: 600; margin: 4px 0 0; }
  .done h2 em { font-style: italic; color: var(--gold-deep); }
  .done > p { margin: 0; font-size: 14px; color: var(--fg-2); }
  .btn.enter { margin-top: 12px; min-width: 240px; }
  .wait-actions { display: flex; gap: 10px; flex-wrap: wrap; justify-content: center; margin-top: 12px; }
  .summary {
    width: 100%; max-width: 440px; margin: 10px 0 0; text-align: left;
    border: 1px solid var(--rule); border-radius: 12px; background: var(--surface); overflow: hidden;
  }
  .summary > div { display: grid; grid-template-columns: 160px minmax(0, 1fr); gap: 14px; padding: 12px 16px; align-items: baseline; }
  .summary > div + div { border-top: 1px solid var(--rule-2); }
  .summary dt { font-size: 10.5px; letter-spacing: 0.12em; text-transform: uppercase; color: var(--fg-muted); }
  .summary dd { margin: 0; font-size: 14px; color: var(--fg); line-height: 1.4; display: flex; flex-direction: column; gap: 2px; }
  .summary .sub { font-family: var(--mono); font-size: 11.5px; color: var(--fg-muted); letter-spacing: 0.04em; }
  .done > p.meta {
    display: flex; align-items: center; gap: 8px; margin-top: 8px;
    font-family: var(--mono); font-size: 11px; color: var(--fg-muted);
  }
  .meta code { font-family: var(--mono); font-size: 12px; color: var(--fg-2); background: var(--surface-sunk); padding: 3px 8px; border-radius: 5px; }
  .copy {
    background: none; border: none; padding: 0; color: var(--gold-deep); font-family: var(--mono);
    font-size: 11px; cursor: pointer; text-decoration: underline; text-underline-offset: 2px;
  }
  .copy:hover { color: var(--fg); }

  @media (prefers-reduced-motion: reduce) {
    .match, .done { animation: none; }
  }
  @media (max-width: 560px) {
    .invite { flex-wrap: wrap; }
    .match-head { flex-wrap: wrap; }
    .btn.panel { width: 100%; }
    .facts { grid-template-columns: 1fr; }
    .facts > div:nth-child(odd):not(.full) { border-right: none; }
    .sec { padding: 22px 20px; }
    .submit { padding: 20px; }
    .summary > div { grid-template-columns: 1fr; gap: 3px; }
    h1 { font-size: 40px; }
  }
</style>
