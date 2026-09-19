> The design handoff, kept beside the app it was ported into. It is the
> reference, not the app: the app's own sources are in `../src`. The
> entry point is `xepha-bank.html` here, and the standalone bundle is
> left out, since it inlines React and Babel.

# Handoff: Xepha — end-customer mobile banking app

## Overview
Xepha is a demonstrator retail bank (UK market) built on the Queenswood core-banking platform. This package is the end-customer mobile experience: onboarding, home/balances, account detail, opening additional products, paying a payee, moving money between own accounts, activity and profile.

## About the design files
Everything in this bundle is a **design reference written in HTML/React (Babel-in-browser)**. It is a hi-fi clickable prototype, not production code. Recreate the screens in your target stack (React Native, Flutter, SwiftUI/Kotlin, or a web framework) using its conventions; the JSX here is the source of truth for layout, copy, states and behaviour, and the CSS is the source of truth for tokens.

## Fidelity
**High-fidelity.** Colours, type, spacing, radii and copy are final. Match them.

## Files
- `Xepha Bank.html` — entry point (fonts, React/Babel CDN, script order)
- `xepha/xepha.css` — all tokens and component classes (`:root` vars at the top)
- `xepha/xepha-ui.jsx` — shared: `gbp()` formatter, `Ic` icon set (inline SVG, 24-grid, stroke 1.8–2.2), `Top`, `Field`, `Pad`, `Sheet`, `Tabs`, `Spark`, and `SEED` demo data
- `xepha/xepha-onboarding.jsx` — 7-step onboarding
- `xepha/xepha-home.jsx` — Home, Account, TxnDetail, Activity, Me, OpenAccount
- `xepha/xepha-move.jsx` — Pay (6 steps), Move (3 steps), shared `Amount` + `useAmt`
- `xepha/xepha-app.jsx` — root: state, navigation stack, mutations
- `xepha/ios-frame.jsx` — phone bezel for presentation only; do not port
- `Xepha Bank (standalone).html` — single-file bundle for viewing offline

Open `Xepha Bank.html` from a local static server (script files are fetched relatively).

## Design tokens (`xepha/xepha.css :root`)
Colours
- `--bg` #0a1230 screen background (deep navy)
- `--bg-2` #0f1a3f inputs, segmented controls, quick-action tiles
- `--card` #151f4c cards · `--card-2` #1b2758 avatars, selected segment
- `--line` rgba(255,255,255,.08) dividers · `--line-2` rgba(255,255,255,.14) strong borders
- `--fg` #f2f4fb text · `--fg-2` #c9cee6 secondary · `--muted` #8b93b8 labels/meta
- `--lime` #c8f542 accent (primary button, positive amounts, active tab, focus ring) · `--lime-2` #d9ff5c hover · `--lime-ink` #0a1230 text on lime
- `--red` #ff6b7a (reserved for errors)
- Account card gradients: current `linear-gradient(135deg,#1c2a63,#121c48)`, saver `(#1f3a2a,#122a1d)`, fixed `(#3a2a1f,#2a1d12)`; fixed-term sparkline stroke #ffb26b

Typography — Outfit (Google Fonts) 300/400/500/600; DM Mono 400/500 for sort codes, account numbers, references, OTP digits.
- h1 30px/1.12, weight 500, letter-spacing −0.02em (Welcome hero 44px; passcode/centred variants unchanged)
- h2 20px/500 · body 16px/1.35 · `.sub` 15px muted · `.hint` 13px muted · `.eyebrow` 12px uppercase, tracking .1em, 500
- Big amount `.big` 52px/1, weight 400, tracking −0.03em; currency symbol 28px muted superscript. Home total 46px; account-ready card 36px.
- Tabular numerals on all money (`font-variant-numeric: tabular-nums`).
- Money format: `£1,234.56`; negative uses U+2212 minus `−£6.85`; incoming shown with `+` and lime colour.

Spacing & shape
- Screen padding 20px sides; content top offset 62px (below status bar); bottom padding 120px so content clears the footer/tab bar.
- Radii: buttons 16px (small 12px), inputs 14px, cards 20px, account cards 22px, avatars 14px (44px square), sheets 28px top, chips pill.
- Primary button 54px tall; small 42px; icon button 40px circle; tab bar 88px (incl. 30px home-indicator inset); inputs 56px; keypad keys 60px; OTP cells 60px.
- Minimum tap target 40px; list rows ≥ 52px.

Motion
- Screen push: translateX 60px→0 + fade, 320ms cubic-bezier(.2,.8,.2,1). Back: translateX −30px→0, 280ms.
- Sheet: backdrop fade 200ms, panel translateY 60%→0, 320ms same curve.
- Success tick: scale .4→1, 500ms cubic-bezier(.2,1.4,.4,1).
- Button press: scale .98, 120ms. Input focus: border → lime, 150ms.
- Toast: slides in like a screen, auto-dismisses after 1.8s.
- ID scan line: 2px lime bar, 1.1s ease-in-out alternate between 10%–90%.

## Navigation model
- `mode`: `onboarding` | `app`.
- In app: 4 tabs (Home, Pay, Activity, Me) plus a push **stack** of modal flows: `account{id}`, `txn{id}`, `pay{from?}`, `move{from?}`, `open`. Switching tab clears the stack. `pop()` returns to the previous stack entry or the tab.
- Top bar: back chevron for pushed steps, × for the first step of a modal flow.

## Screens

### Onboarding (`xepha-onboarding.jsx`)
1. **Welcome** — radial navy-blue glow top-right; logo (lime rounded square with navy petal + wordmark "Xepha"); h1 44px "Banking that keeps up with you."; sub copy; primary "Get started", ghost "I already have an account" (→ app).
2. **Mobile number** — "+44" fixed prefix box (84px) + tel input; terms/privacy links in hint. "Send code" enabled at ≥10 digits.
3. **Verify code** — 6 OTP cells, active cell lime border. Prototype auto-fills `482913` (900ms delay then 140ms/digit) and auto-advances 500ms after completion. Real app: SMS autofill + manual entry, resend link.
4. **About you** — first/last name (side by side), DOB (mono, `DD / MM / YYYY`), postcode (mono, uppercased; hint "We'll find your address from this."). Continue requires all four (DOB ≥8 chars, postcode ≥5).
5. **ID check** — 300px dashed camera card. States: idle (camera icon) → scanning (lime scan line, button "Scanning…" disabled, 2.2s) → done (lime tint, tick, "Passport read · selfie matched", button becomes "Continue").
6. **Passcode** — 4 dots (14px, lime when filled) + numeric keypad; heading switches to "Enter it once more"; mismatch clears both silently, match advances after 300ms.
7. **Account ready** — 96px lime success disc, "You're in, {first}.", Everyday account card with £0.00 and sort code/account number; "Go to my account".

### Home
Greeting eyebrow + first name; avatar button (initials) → Me. "Total balance" eyebrow + 46px sum. Quick actions 3-up grid (Pay / Move / Open) — tile 16px radius `--bg-2`, 40px lime icon disc, 13px label. Account cards (gradient by kind) with name, type line, right-aligned 22px balance, 56px sparkline of last 7 balances. "Recent" + "See all" (→ Activity), first 4 transactions.

### Transaction row (`Txn`)
44px avatar of initials (lime if incoming), title 16px, meta 13px "{category} · {when}", right-aligned amount (lime + sign for credits). Hover: title → lime.

### Account detail
Title = account name; type eyebrow; 52px balance; row with sort code / account number in mono + copy icon (tap → toast "Account details copied"); "Pay" (primary small) and "Move money" (ghost small) buttons, chip "Interest paid monthly" on savings; transactions grouped by date with eyebrow headers.

### Transaction detail
× close; 72px avatar; merchant 20px; big amount; time sub; card of key/values (Category, Account, Reference if present, Status "Complete" in lime); ghost "Report a problem".

### Activity
h1; segmented All/In/Out; grouped list as above. Tab bar shown.

### Me
56px lime avatar, name, "Member since Sep 2026"; list rows: Personal details, Security, Cards, Statements & documents, Help (each with chevron); ghost "Sign out" (→ onboarding); FSCS footer copy 13px centred.

### Open an account
1. **Choose** — h1 "What would you like to open?"; option rows (16px radius, `--bg-2`, lime border when selected) for Everyday / Rainy Day / 1 Year Fixed with kind-coloured 44px swatch; already-held kinds dimmed to 45% with "Open" chip and disabled; bullet list of selected product's points. Continue disabled until selection.
2. **Confirm** — name + blurb; for savings, "Opening deposit from Everyday" numeric input (fixed: min £1,000 enforced); card with Rate / Fees / Protection; summary-box + terms links. Button "Open {name}".
3. **Done** — success disc; "{name} is open"; deposit sentence when > 0; "Done" pops to Home, which now shows the new card.

### Pay (`Pay`)
0. **Choose payee** — search input; "New payee" row (lime + avatar); "Recent" list with "Last paid {amount} · {date}".
1. **New payee** — name; sort code (auto-formats `00-00-00`) + account number (8 digits). "Check details" enabled when valid → 1.3s "Checking the name with their bank…" → Confirmation of Payee card "Name matches. The account is held by {name}." → button becomes "Continue". Editing any field resets the check.
2. **Amount** — chip "From {account} · {balance}"; 52px amount with keypad (`useAmt`: max 2dp, max 8 chars, leading-zero handling); reference input (max 18 chars). Button "Review", or disabled "Not enough in {account}" when over balance.
3. **Review** — "Sending" eyebrow, amount, "to {name}"; card: From, Sort code, Account, Reference, Arrives "Within seconds", Fee "Free"; scam-warning hint. Button "Send £x".
4. **Face ID** — full-screen lime face icon + label, 1.5s then proceeds.
5. **Sent** — success disc, "Sent", "£x to {name}. It should arrive within seconds.", Done.

### Move money (`Move`)
From/To picker cards with 36px kind swatch, eyebrow label, name, balance; centre swap icon button. Amount keypad. Button "Move £x" / "Not enough in …". Face-ID-style interstitial "Moving money…" 1.5s → "Moved" success. If fewer than two accounts, show the prompt card instead.

## State & mutations (`xepha-app.jsx`)
- `S = {user, accounts[], txns[], payees[]}` seeded from `SEED`.
- `send({from,payee,amt,ref})`: debit account, prepend txn (cat "Payment"), add new payee to top of list with "£x · today".
- `transfer({from,to,amt})`: debit/credit, prepend two mirrored txns (cat "Saved").
- `openAccount(product, deposit)`: append account (sort code 04-00-75, generated number), debit Everyday when deposit > 0, prepend txn.
- Timestamps use "Today, HH:MM"; date grouping key is the `date` string.

## Validation rules
- Mobile ≥ 10 digits · OTP 6 digits · DOB ≥ 8 chars · postcode ≥ 5 chars
- Sort code exactly 6 digits, account number exactly 8 · payee name > 1 char
- Amount > 0 and ≤ source balance · fixed-term deposit ≥ £1,000
- Passcode 4 digits, must match on re-entry

## Assets
No raster images. Icons are inline SVG in `Ic` (xepha-ui.jsx); logo is CSS (`.logo`). Fonts from Google Fonts: Outfit, DM Mono.
