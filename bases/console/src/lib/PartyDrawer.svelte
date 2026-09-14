<script>
  /* Drawer-hosted detail view + create/edit form for a Party.

     Organizations carry only the base summary (display-name, status,
     timestamps) — no person identity — so read mode renders a plain
     Organization view with no Edit affordance. Persons get the full
     identity / address / identification view and the create/edit form.

     Modes (person only):
       read    pre-filled detail view with an Edit affordance.
       create  blank form. Calls create_party on Save.
       edit    pre-filled form. bank-api has no PUT for parties yet,
               so Save is a no-op that returns to the read view; we
               surface a small notice so the user knows.

     The list endpoint only returns the Party summary (party-id,
     type, display-name, status, timestamps). For persons, read mode
     fetches the detail endpoint (GET /v1/parties/{id}) for the
     identity, address, and identification fields. Email and phone
     aren't persisted, so they stay "—". */

  import { Drawer, Field, Input, Select, Button, Badge } from "@queenswood/ui";
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
  let lastName = $state("");
  let dob = $state("");
  let role = $state("customer");
  let email = $state("");
  let phone = $state("");
  let line1 = $state("");
  let line2 = $state("");
  let city = $state("");
  let postcode = $state("");
  let country = $state("United Kingdom");
  let idType = $state("Passport");
  let idNumber = $state("");

  let submitting = $state(false);
  let formError = $state(null);

  // Heuristic name split for the read view and to pre-fill the edit
  // form from a summary record. Production code would carry first /
  // last as separate fields on the Party shape.
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
      lastName = last;
      role = target.type ?? "customer";
      // Detail fields aren't on the list payload — leave blank so
      // the user knows what's missing rather than fabricating.
      dob = "";
      email = "";
      phone = "";
      line1 = "";
      line2 = "";
      city = "";
      postcode = "";
      country = "United Kingdom";
      idType = "Passport";
      idNumber = "";
    } else if (mode === "create") {
      firstName = "";
      lastName = "";
      dob = "";
      role = "customer";
      email = "";
      phone = "";
      line1 = "";
      line2 = "";
      city = "";
      postcode = "";
      country = "United Kingdom";
      idType = "Passport";
      idNumber = "";
    }
  });

  const readSplit = $derived(splitName(target?.["display-name"]));

  // Read mode fetches the full party detail (identity, address,
  // identification); the list summary the drawer is handed only carries
  // the display name. Guard against a stale response landing after the
  // user has moved to a different party.
  let detail = $state(null);
  $effect(() => {
    // Only persons carry embeddable detail; organizations are summary-only.
    if (!(open && mode === "read" && target?.type === "person")) return;
    const id = target?.["party-id"];
    if (!id) return;
    detail = null;
    get_party(id, {
      embed: ["person-identification", "address", "national-identifier"],
    }).then((r) => {
      if (r.status === 200 && r.body?.["party-id"] === id) detail = r.body;
    });
  });

  const ALPHA3 = { GBR: "United Kingdom", IRL: "Ireland", USA: "United States", HKG: "Hong Kong", DEU: "Germany", FRA: "France" };
  const ALPHA2 = { GB: "United Kingdom", IE: "Ireland", US: "United States", HK: "Hong Kong", DE: "Germany", FR: "France" };
  function fmtDob(v) {
    if (v == null || v === "") return "—";
    if (typeof v === "number") {
      const s = String(v).padStart(8, "0");
      return `${s.slice(0, 4)}-${s.slice(4, 6)}-${s.slice(6, 8)}`;
    }
    return v;
  }
  function prettyIdType(t) {
    if (t == null || t === "") return "—";
    // Tolerate however the enum serialises — a plain string, or a
    // keyword rendered as ":identifier-type-national-insurance".
    return String(t)
      .replace(/^:/, "")
      .replace(/^identifier-type-/, "")
      .replace(/-/g, " ")
      .replace(/\b\w/g, (c) => c.toUpperCase());
  }

  const addr = $derived(detail?.address ?? {});
  const idn = $derived(detail?.["national-identifier"]);
  const readFirst = $derived(detail?.["given-name"] ?? readSplit.first);
  const readLast = $derived(detail?.["family-name"] ?? readSplit.last);
  const readDob = $derived(fmtDob(detail?.["date-of-birth"]));
  const readNat = $derived(ALPHA2[detail?.nationality] ?? detail?.nationality ?? "");
  const readLine1 = $derived([addr["building-number"], addr.street].filter(Boolean).join(" "));
  const readLine2 = $derived(addr["sub-street"] ?? "");
  const readCity = $derived(addr.town ?? "");
  const readPostcode = $derived(addr.postcode ?? "");
  const readCountry = $derived(ALPHA3[addr.country] ?? addr.country ?? "");
  const readIdType = $derived(prettyIdType(idn?.type));
  const readIdNumber = $derived(idn?.value ?? "");

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
      : "Capture identity, address, and a primary identification document. Status starts as pending until a reviewer approves.",
  );

  // ISO country lookup so the form's friendly names map onto what
  // bank-api expects: alpha-3 for the address, alpha-2 for
  // nationality and the national identifier's issuing country.
  const COUNTRY_CODES = {
    "United Kingdom": { alpha2: "GB", alpha3: "GBR" },
    Ireland: { alpha2: "IE", alpha3: "IRL" },
    "United States": { alpha2: "US", alpha3: "USA" },
    "Hong Kong": { alpha2: "HK", alpha3: "HKG" },
    Germany: { alpha2: "DE", alpha3: "DEU" },
    France: { alpha2: "FR", alpha3: "FRA" },
  };

  function dobToInt(str) {
    if (!str) return null;
    return parseInt(str.replace(/-/g, ""), 10);
  }

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
      const codes = COUNTRY_CODES[country] ?? { alpha2: "GB", alpha3: "GBR" };
      const displayName = [firstName, lastName].filter(Boolean).join(" ").trim();
      const payload = {
        type: "person",
        "display-name": displayName,
        "given-name": firstName.trim(),
        "family-name": lastName.trim(),
        "date-of-birth": dobToInt(dob),
        nationality: codes.alpha2,
        address: {
          street: line1.trim(),
          town: city.trim(),
          postcode: postcode.trim(),
          country: codes.alpha3,
        },
        // bank-api currently only knows "national-insurance"; the
        // form's id-type is captured for the eventual wider schema.
        "national-identifier": {
          type: "national-insurance",
          value: idNumber.trim(),
          "issuing-country": codes.alpha2,
        },
      };
      if (line2.trim()) payload.address["sub-street"] = line2.trim();
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
        <dt>First name</dt> <dd class:empty={!readFirst}>{readFirst || "—"}</dd>
        <dt>Last name</dt>  <dd class:empty={!readLast}>{readLast || "—"}</dd>
        <dt>Date of birth</dt> <dd class="mono" class:empty={readDob === "—"}>{readDob}</dd>
        <dt>Nationality</dt> <dd class:empty={!readNat}>{readNat || "—"}</dd>
        <dt>Email</dt>      <dd class="empty">—</dd>
        <dt>Phone</dt>      <dd class="mono empty">—</dd>
      </dl>
    </section>

    <section class="drawer-section">
      <h3 class="drawer-section-title">Address</h3>
      <dl class="detail-list">
        <dt>Line 1</dt>     <dd class:empty={!readLine1}>{readLine1 || "—"}</dd>
        <dt>Line 2</dt>     <dd class:empty={!readLine2}>{readLine2 || "—"}</dd>
        <dt>City</dt>       <dd class:empty={!readCity}>{readCity || "—"}</dd>
        <dt>Postcode</dt>   <dd class="mono" class:empty={!readPostcode}>{readPostcode || "—"}</dd>
        <dt>Country</dt>    <dd class:empty={!readCountry}>{readCountry || "—"}</dd>
      </dl>
    </section>

    <section class="drawer-section">
      <h3 class="drawer-section-title">Identification</h3>
      <dl class="detail-list">
        <dt>Type</dt>       <dd class:empty={readIdType === "—"}>{readIdType}</dd>
        <dt>Number</dt>     <dd class="mono" class:empty={!readIdNumber}>{readIdNumber || "—"}</dd>
      </dl>
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
        <div class="field-row">
          <Field label="Date of birth" htmlFor="f-dob">
            <Input id="f-dob" type="date" bind:value={dob} />
          </Field>
          <Field label="Type" htmlFor="f-role">
            <Select id="f-role" bind:value={role}>
              <option value="customer">customer</option>
              <option value="director">director</option>
              <option value="beneficial-owner">beneficial-owner</option>
              <option value="signatory">signatory</option>
            </Select>
          </Field>
        </div>
        <Field label="Email" htmlFor="f-email">
          <Input id="f-email" type="email" bind:value={email} />
        </Field>
        <Field label="Phone" htmlFor="f-phone">
          <Input id="f-phone" type="tel" bind:value={phone} />
        </Field>
      </section>

      <section class="drawer-section">
        <h3 class="drawer-section-title">Address</h3>
        <Field label="Address line 1" htmlFor="f-line1">
          <Input id="f-line1" bind:value={line1} />
        </Field>
        <Field label="Address line 2 (optional)" htmlFor="f-line2">
          <Input id="f-line2" bind:value={line2} />
        </Field>
        <div class="field-row split-7030">
          <Field label="City" htmlFor="f-city">
            <Input id="f-city" bind:value={city} />
          </Field>
          <Field label="Postcode" htmlFor="f-postcode">
            <Input id="f-postcode" bind:value={postcode} />
          </Field>
        </div>
        <Field label="Country" htmlFor="f-country">
          <Select id="f-country" bind:value={country}>
            <option>United Kingdom</option>
            <option>Ireland</option>
            <option>United States</option>
            <option>Hong Kong</option>
            <option>Germany</option>
            <option>France</option>
          </Select>
        </Field>
      </section>

      <section class="drawer-section">
        <h3 class="drawer-section-title">Identification</h3>
        <div class="field-row">
          <Field label="Type" htmlFor="f-id-type">
            <Select id="f-id-type" bind:value={idType}>
              <option>Passport</option>
              <option>Driving licence</option>
              <option>National ID</option>
              <option>HKID</option>
            </Select>
          </Field>
          <Field label="Number" htmlFor="f-id-number">
            <Input id="f-id-number" bind:value={idNumber} />
          </Field>
        </div>
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
  /* Sectioned body — Identity / Address / Identification. The first
     section sits flush with the body padding; subsequent sections
     get a hairline separator and breathing room. */
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
     drawer; `split-7030` for the City/Postcode pair where the city
     deserves more room. */
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
  .field-row.split-7030 {
    grid-template-columns: 2fr 1fr;
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
