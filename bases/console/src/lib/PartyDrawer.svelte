<script>
  /* Drawer-hosted detail view + create/edit form for a Party.

     A person is registered by name alone: their date of birth, address
     and documents they give to the identity provider, and the platform
     never holds them (ADR-0045). Organizations carry only the base
     summary, so read mode renders a plain Organization view with no
     Edit affordance.

     Modes (person only):
       read    the person's names and the bank's reference, read from
               the detail endpoint, with an Edit affordance.
       create  blank form. Calls create_party on Save.
       edit    pre-filled form. bank-api has no PUT for parties yet,
               so Save is a no-op that returns to the read view; we
               surface a small notice so the user knows. */

  import { Drawer, Field, Input, Button, Badge } from "@queenswood/ui";
  import { create_party, get_party } from "./api.mjs";

  let {
    open,
    mode = "read",
    target = null,
    onClose,
    onModeChange,
    onSaved,
  } = $props();

  const isOrg = $derived(target?.type === "organization");

  // Form state — reset whenever the drawer enters create/edit mode.
  let firstName = $state("");
  let middleNames = $state("");
  let lastName = $state("");
  let reference = $state("");

  let submitting = $state(false);
  let formError = $state(null);

  // Heuristic name split to pre-fill the edit form from a summary
  // record, which carries the display name alone.
  function splitName(displayName) {
    const tokens = (displayName ?? "").split(" ");
    return {
      first: tokens[0] ?? "",
      last: tokens.slice(1).join(" "),
    };
  }

  $effect(() => {
    if (!open) return;
    formError = null;
    if (mode === "edit" && target) {
      const { first, last } = splitName(target["display-name"]);
      firstName = first;
      middleNames = "";
      lastName = last;
      reference = target["external-reference"] ?? "";
    } else if (mode === "create") {
      firstName = "";
      middleNames = "";
      lastName = "";
      reference = "";
    }
  });

  // Read mode fetches the party's names; the list summary the drawer is
  // handed only carries the display name. Guard against a stale
  // response landing after the user has moved to a different party.
  let detail = $state(null);
  $effect(() => {
    // Only persons carry names; organizations are summary-only.
    if (!(open && mode === "read" && target?.type === "person")) return;
    const id = target?.["party-id"];
    if (!id) return;
    detail = null;
    get_party(id, { embed: ["person-identification"] }).then((r) => {
      if (r.status === 200 && r.body?.["party-id"] === id) detail = r.body;
    });
  });

  const readLegalName = $derived(detail?.["legal-name"] ?? "");
  const readReference = $derived(
    detail?.["external-reference"] ?? target?.["external-reference"] ?? "",
  );

  // Organization read view — orgs carry only the base summary.
  function fmtDate(iso) {
    if (!iso) return "—";
    return new Date(iso).toLocaleDateString(undefined, {
      year: "numeric",
      month: "short",
      day: "numeric",
    });
  }
  const readCreated = $derived(fmtDate(target?.["created-at"]));
  const readUpdated = $derived(formatRelative(target?.["updated-at"]));

  // bank-api status → Badge tone. Matches Parties.svelte.
  const TONE = {
    active: "published",
    pending: "pending",
    rejected: "rejected",
  };

  // Truncated id for the kicker; full id is shown as a `title` attr
  // on the kicker element via the Drawer primitive — short ids fit
  // inline, long ones get an ellipsis without losing the value.
  function shortId(id) {
    if (!id) return "";
    return id.length > 18 ? id.slice(0, 18) + "…" : id;
  }

  function formatRelative(iso) {
    if (!iso) return "—";
    const then = new Date(iso).getTime();
    const diff = (Date.now() - then) / 1000;
    if (diff < 60) return "just now";
    if (diff < 3600) return `${Math.floor(diff / 60)} min ago`;
    if (diff < 86400) return `${Math.floor(diff / 3600)} h ago`;
    if (diff < 86400 * 7) return `${Math.floor(diff / 86400)} d ago`;
    return new Date(iso).toLocaleDateString();
  }

  const kickerFor = $derived(
    mode === "read"
      ? `${isOrg ? "Organization" : "Your customer"} · ${shortId(target?.["party-id"])}`
      : mode === "edit"
        ? "Edit"
        : "Identify",
  );

  const titleFor = $derived(
    mode === "read"
      ? (target?.["display-name"] ?? (isOrg ? "Organization" : "Your customer"))
      : mode === "edit"
        ? "Edit customer"
        : "Onboard Customer",
  );

  const subFor = $derived(
    mode === "read"
      ? `${target?.type ?? ""} · updated ${formatRelative(target?.["updated-at"])}`
      : "Register the person by name. They give their date of birth, address and documents to the identity provider when you open a verification session. Status starts as pending until the check completes.",
  );

  function errorDetail(body) {
    if (!body) return null;
    return (
      body.detail ??
      body.message ??
      body.error ??
      (typeof body === "string" ? body : JSON.stringify(body))
    );
  }

  async function save(e) {
    e?.preventDefault?.();
    if (submitting) return;
    // bank-api has no PUT/PATCH for parties yet, so edit mode just
    // returns to the read view without a network call. The notice in
    // the form explains why.
    if (mode === "edit") {
      onModeChange?.("read");
      return;
    }
    submitting = true;
    formError = null;
    try {
      const payload = {
        type: "person",
        "display-name": [firstName, middleNames, lastName]
          .map((n) => n.trim())
          .filter(Boolean)
          .join(" "),
        "given-name": firstName.trim(),
        "family-name": lastName.trim(),
      };
      if (middleNames.trim()) payload["middle-names"] = middleNames.trim();
      if (reference.trim()) payload["external-reference"] = reference.trim();
      const res = await create_party(payload);
      if (res.status >= 200 && res.status < 300) {
        onSaved?.();
      } else {
        formError = errorDetail(res.body) ?? `Save failed (${res.status})`;
      }
    } catch (err) {
      formError = err.message;
    } finally {
      submitting = false;
    }
  }

  function cancel() {
    if (mode === "edit") onModeChange?.("read");
    else onClose?.();
  }
</script>

<Drawer
  {open}
  {onClose}
  kicker={kickerFor}
  title={titleFor}
  sub={subFor}
  width={560}
>
  {#if mode === "read"}
    <div class="status-row">
      <Badge tone={TONE[target?.status] ?? "neutral"}>{target?.status ?? "—"}</Badge>
    </div>

    {#if isOrg}
    <section class="drawer-section">
      <h3 class="drawer-section-title">Organization</h3>
      <dl class="detail-list">
        <dt>Organization name</dt> <dd class:empty={!target?.["display-name"]}>{target?.["display-name"] || "—"}</dd>
        <dt>Created</dt>           <dd class:empty={readCreated === "—"}>{readCreated}</dd>
        <dt>Last updated</dt>      <dd class:empty={readUpdated === "—"}>{readUpdated}</dd>
      </dl>
    </section>
    {:else}
    <section class="drawer-section">
      <h3 class="drawer-section-title">Identity</h3>
      <dl class="detail-list">
        <dt>Legal name</dt>    <dd class:empty={!readLegalName}>{readLegalName || "—"}</dd>
        <dt>Your reference</dt> <dd class="mono" class:empty={!readReference}>{readReference || "—"}</dd>
      </dl>
      <p class="notice">
        Date of birth, address and documents are held by the identity
        provider, in your account with it, never by the platform.
      </p>
    </section>
    {/if}
  {:else}
    <form id="party-form" onsubmit={save}>
      {#if mode === "edit"}
        <p class="notice" role="status">
          Editing isn't supported by the API yet. Save will close without persisting.
        </p>
      {/if}

      <section class="drawer-section">
        <h3 class="drawer-section-title">Identity</h3>
        <div class="field-row">
          <Field label="First name" htmlFor="f-firstname">
            <Input id="f-firstname" bind:value={firstName} />
          </Field>
          <Field label="Last name" htmlFor="f-lastname">
            <Input id="f-lastname" bind:value={lastName} />
          </Field>
        </div>
        <Field label="Middle names (optional)" htmlFor="f-middlenames">
          <Input id="f-middlenames" bind:value={middleNames} />
        </Field>
        <Field label="Your reference (optional)" htmlFor="f-reference">
          <Input id="f-reference" bind:value={reference} />
        </Field>
      </section>

      {#if formError}
        <p class="error" role="alert">{formError}</p>
      {/if}
    </form>
  {/if}

  {#snippet footer()}
    {#if mode === "read"}
      <div class="foot-row">
        <Button variant="ghost" onclick={() => onClose?.()}>Close</Button>
        {#if !isOrg}
          <Button variant="primary" onclick={() => onModeChange?.("edit")}>Edit</Button>
        {/if}
      </div>
    {:else}
      <div class="foot-row">
        <Button variant="ghost" onclick={cancel}>Cancel</Button>
        <Button
          variant="primary"
          type="submit"
          form="party-form"
          disabled={submitting}
        >
          {submitting ? "Saving…" : "Save"}
        </Button>
      </div>
    {/if}
  {/snippet}
</Drawer>

<style>
  /* Sectioned body. The first section sits flush with the body
     padding; subsequent sections get a hairline separator and
     breathing room. */
  .drawer-section {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }
  .drawer-section + .drawer-section {
    margin-top: 8px;
    padding-top: 22px;
    border-top: 1px solid var(--rule-2);
  }
  .drawer-section-title {
    font-family: var(--mono);
    font-size: 10px;
    letter-spacing: 0.1em;
    text-transform: uppercase;
    color: var(--gold-deep);
    margin: 0;
    font-weight: 500;
  }

  /* Read-mode detail list — label / value rows. */
  .detail-list {
    margin: 0;
    display: grid;
    grid-template-columns: 140px 1fr;
    row-gap: 10px;
    column-gap: 16px;
    align-items: baseline;
  }
  .detail-list dt {
    font-family: var(--mono);
    font-size: 11px;
    letter-spacing: 0.04em;
    text-transform: uppercase;
    color: var(--fg-muted);
    margin: 0;
    line-height: 1.5;
  }
  .detail-list dd {
    margin: 0;
    font-size: 14px;
    color: var(--fg);
    line-height: 1.5;
    overflow-wrap: anywhere;
    text-wrap: pretty;
  }
  .detail-list dd.mono {
    font-family: var(--mono);
    font-size: 13px;
  }
  .detail-list dd.empty {
    color: var(--fg-muted);
    opacity: 0.55;
  }

  /* The status badge sits just above the first section in read mode. */
  .status-row {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  /* Edit-mode form layout. Two-column field rows at the wider 560px
     drawer. */
  form {
    display: flex;
    flex-direction: column;
    gap: 18px;
  }
  .field-row {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 16px;
  }

  .notice {
    margin: 0;
    padding: 10px 12px;
    border-radius: 6px;
    background: var(--surface-sunk);
    color: var(--fg-muted);
    font-size: 13px;
    line-height: 1.4;
  }
  .error {
    margin: 0;
    padding: 10px 12px;
    border-radius: 6px;
    background: var(--surface-sunk);
    color: var(--fg);
    font-size: 13px;
  }
  .foot-row {
    display: flex;
    gap: 8px;
    justify-content: flex-end;
  }
</style>
