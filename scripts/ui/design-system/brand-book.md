Queenswood is open-source core banking: a console a bank's own team works in, and a marketing site. The look is a kept forest under a crown: warm paper and ink, pine green for action, gold for the brand and for focus. It is calm, dense where data is dense, and exact about money.

## Content

- Write in sentence case everywhere: "New product", "Audit log", "Your bank starts in test". Only mono kickers, eyebrows and table headers are uppercase, and CSS does that.
- Lead an action with its verb and say exactly what it does: "Create product", "Run scene", "Sign out", "Delete forever". Use the same words in the confirmation: "Migration approved".
- Address the reader as "you" and call the product "Queenswood". Use British spelling: "organisation", "capitalise".
- State facts plainly, in the present tense, without exclamation marks or emoji: "A create has no before.", "This act changed no stored fields.", "No product matches that search."
- Write a refusal as a `Callout` in the `danger` tone: the title says what to do ("Make someone else an owner first"), the body says why, and the mono line gives the status and type ("409 · :member/last-owner").
- Warn before something that cannot be undone or seen again: "You won’t see this again".
- Say why something is missing rather than leaving it out: "1 product hidden — a migration's target must be the same product type as its source (savings)."
- Write statuses lowercase, as the record holds them: draft, published, pending-incoming. `Badge` lowercases its label for you.
- Format money with its symbol, en-GB grouping and two decimals, and a true minus sign for a negative: "£4,786,460.35", "−£45,980.20". Never add amounts in different currencies together.
- Give times in UTC and dates as day, short month, year: "09:42 UTC", "30 Jul 2026".
- Set anything a person might copy in the mono face: ids ("prd.01ks5kya"), codes, amounts, API paths.
- On the marketing site, name the audience in the eyebrow ("For finance") and the thing in the heading ("General ledger and trial balance"). The hero is "Open-source core banking."

## Colour

- Build interface surfaces from the semantic tokens, which change with the theme: `surface` for the page, `surface-raised` for cards, panels and tables, `surface-sunk` for the side rail, table headers and inline code.
- Set text in `fg`, body copy in `fg-2` and secondary text in `fg-muted`.
- Draw every border as a 1px hairline: `rule` on controls, `rule-2` on cards, panels and table rows. Lay `hover-overlay` over a row or quiet button on hover.
- Use the raw palette (`bone`, `paper`, `ink`, `pine-1` to `pine-5`, `gold`, `gold-1` to `gold-5`) only where a colour must stay the same in both themes: the logo, brand artwork, the marketing site.
- Give a surface one `primary` (pine) action. Keep `brand-action` (gold) for publishing and other brand-coded acts, and `danger` (rust) for destructive ones.
- Reserve `gold` for the crown, the brand button and the focus ring. Use `gold-deep` for mono kickers and eyebrows on the light surfaces, and `gold-bright` for them on `ink`.
- Colour a status by what it means, at low chroma: pine for live or good, amber for in progress, violet for waiting, rust for refused or failed, grey for historical. Always pair the colour with the word.
- In ledger views keep warm for debit-normal and cool for credit-normal: `debit` and the asset and expense chips are warm, `credit` and the liability, equity and income chips are cool.
- Tone an amount by sign: `danger` for negative, `fg-muted` for zero, `pos` for a credit where the screen tones credits.
- Keep `sandbox-bg`, `sandbox-fg` and `sandbox-rule` for the sandbox banner alone.

## Type

- Set product text in `grotesk` (Geist): page titles in `type-page-title`, labels and buttons in `type-ui`, table cells in `type-cell`, body copy in `type-body`.
- Set the brand's voice in `serif` (Cormorant Garamond 500): the marketing hero in `type-display`, section headings in `type-section`, card titles in `type-card-title`. Keep it at 20px or larger; it loses its features below that.
- Set data in `mono` (Geist Mono): `type-code` for ids and amounts, `type-kicker` and `type-eyebrow` for the uppercase line above a title, `type-th` for table headers.
- Use weight 500 for emphasis and 400 for running text. 300 appears only in the wordmark, and 600 only at display sizes.
- Use tabular figures wherever digits line up in a column.
- Keep running text to about 60 characters: a page's sub line is at most 60ch, the site's lede 56ch, a serif section heading 18ch.

## Shape, space and depth

- Tell meaning by shape: a record's status is a rounded `Badge` (`radius-pill`); a dimension or a value is a squared mono chip (`radius-4`), as in `Phase`, `GlClass`, `GlType`, `RolePill` and `Tag`.
- Round controls with `radius-6`, panels with `radius-8` and cards with `radius-12`.
- Space with the steps the components use: `space-6` between a label and its control, `space-8` between buttons, `space-12` inside a card, `space-18` between form fields, `space-24` between a page's sections.
- Separate with hairlines and surface steps, not shadows. Only things that float cast one: `shadow-drawer`, `shadow-menu`, `shadow-toast`, and `shadow-card-hover` on a clickable card.
- Show keyboard focus as a 2px solid `gold` outline, offset 2px, on every control.
- Keep motion short and functional: 0.12s for a colour or border change, 0.22s for the drawer's slide, 0.18s for the toast. Spinners and pulses stop under reduced motion.
- Dim a disabled control to 55% opacity and give it the not-allowed cursor.

## Themes

- Support light and dark. `data-theme="light"` or `data-theme="dark"` on the root element forces one; with neither the page follows the system.
- Load `components/bundle.css` after the tokens. It sets `color-scheme` per theme, which the components' own `light-dark()` values follow.
- Call `Queenswood.bootstrapTheme()` before mounting, so a remembered choice applies on the first paint. `ThemeToggle` cycles auto, light, dark.
- Keep `CodeCard`, `RawCalls` and the toast dark in both themes.

## Console layout

- Put `AppNav` across the top, 57px tall and sticky, with `SandboxBanner` under it on a sandbox installation.
- Below it lay a two-column grid, `var(--sidenav-w, 220px) minmax(0, 1fr)`, on `surface`: `Sidenav` on the left, the page on the right.
- Pad the page 28px top, 36px sides and 80px bottom, and stack its sections 24px apart.
- Group the rail as the console does: Manage (Products, Parties, Ledger, Accounts, Migrations), Operations (Jobs), Sandbox (Scenarios), Organisation (People, Policies, Audit log, Access log, Bank).
- Open every page with a `PageHeader`: the bank's name as the kicker, the page name, one line of explanation, and the page's actions.
- Show a list as a `Table`, inside a `Panel` with a `PanelHead` where it needs a title, a count or a search. Narrow it with `Chip`s, `SearchField` or `Tabs` above it.
- Create and edit in a `Drawer` with `Field`s and one block `Button` in its footer. Confirm with `toast()`. Report a refusal in a `Callout` where the person acted.
- Mount `ToastHost` once, after the shell.

## Marketing site

- Use the raw palette on a `bone` ground with `ink` text, and one `ink` section with `bone` text and `gold-bright` eyebrows.
- Open each section with a `type-eyebrow` led by a 20px rule, then a `type-section` heading. Divide sections with a `rule` hairline.
- Make the site's buttons 50px tall with a 9px radius: `ink` fill with `paper` text for the main action, a `rule` outline for the other.
- Set an italic phrase in a serif heading in `gold-deep`, as `type-emphasis`.

## Logo

- Use the `Logo` component for the crowned forest. Mark A is the default; B adds jewels, C is monoline, D has four trees and five crown teeth for tight spaces, E sits in seal rings.
- Use `assets/Logos/logo.svg` (Mark A) or `assets/Logos/favicon.svg` (Mark D) where a file is needed. Both carry their own colours and are shown with `<img>`.
- Give the mark 24px or more. Below that the crown's band reads as a slab.
- Pair it with the `Wordmark`: `grotesk` in product chrome (the app bar sets it at 14px beside a 28px mark), `serif` for mastheads.
- Never recolour the golds, redraw the trees or set the name in another face.

## Iconography

- Draw icons as inline SVG on a 16 by 16 viewBox: no fill, `stroke="currentColor"`, a stroke of 1.5 to 1.8, round caps and joins. There is no icon font or icon library; each component carries its own.
- Size an icon 14px in a button and 16px in the side rail.
- Use the glyphs the source uses: "→" at the end of a card's footer link, "⋯" for a row's menu, "·" between facts in a meta line.
- Do not use emoji.

## Components

- The components are Svelte 5, exported by `@queenswood/ui`. In a Svelte app import them and `@queenswood/ui/tokens.css`.
- Elsewhere load the tokens, then `components/bundle.css`, then `components/bundle.js`, which defines `window.Queenswood`. Mount with `Queenswood.mount(Queenswood.Button, { target, props })`.
- Pass content as a snippet: `Queenswood.snippet("<b>Save</b>")` makes one from HTML, and `Queenswood.render(Component, target, { children: "Save" })` accepts plain text.
- Read a component's README before using it: it gives the props, the Svelte source of its preview and the author's own notes.
- The library sets no `box-sizing` reset. An `Input` is 100% wide plus its padding and border, so give its container room or set `box-sizing: border-box` on the page.
- Spacing, radius, shadow, size and z-index tokens record the values the components use. The components carry those values as literals, so changing one of these tokens changes new work and not the bundle. Colour and font tokens do reach the components.

## Contrast

These pairs are as the source has them and fall short of 4.5:1 in the dark theme. Prefer a stronger pair for text that must be read.

- `fg-muted` on `surface` (4.0:1) and on `surface-raised` (3.4:1). It passes on `surface-sunk`.
- `gold-deep` kickers on any dark surface (2.2 to 3.3:1).
- `danger` as text on `surface` and `surface-raised` (3.0 to 3.6:1), and `danger-fg` on `danger` (4.0:1).
- `primary-fg` on `primary-hover` (3.6:1), and `ok` on `surface-raised` (4.2:1).

In both themes `brand-action-fg` on `brand-action-hover` is 3.3:1, and `gold` is not a text colour on the light surfaces (2.8:1).

## Not synced

- Logo marks B, C and E exist only as the `Logo` component; no file of them is in the repository.
- The console's screens (Landing, Onboarding, Scenarios and the rest of `bases/console`) are described under Console layout and shown under `assets/Screens`, but are not components here.
- The demo digital bank app is a separate brand and is left out.
- The source declares no spacing, radius, shadow or type-scale variables. Those tokens were read from the components' styles and named by value.
- Sub-components have no card of their own: `CardHeader`, `CardBody` and `CardFooter` are under Card; `Thead`, `Tbody`, `Tr`, `Th`, `Td` and `Expander` under Table; `SidenavGroup` and `SidenavItem` under Sidenav; `PanelHead` under Panel.
- Geist 700 and Cormorant Garamond 400 and 700, which the console requests from a font host, have no file here; nothing in the components uses them.
