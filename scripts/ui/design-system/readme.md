# Design system producer

Builds the files of the Queenswood Design System artifact from
`components/ui`. The artifact is what a Claude session reads as the
design system, and what a design or prototype made there is built on.
`components/ui` stays the source: nothing here is read by the console,
and nothing is written back.

## Building

```bash
just ui-design-system
```

writes `target/ui-design-system/project`, the tree the artifact holds.

```bash
just ui-design-system-check
```

builds, then loads every preview in both themes in a headless browser.
It fails on a script error or an empty card, reports content taller than
its card, and leaves contact sheets in `target/ui-design-system/check`
to read by eye.

## What is read from components/ui

- **Colours and font stacks.** Every custom property on `:root` in
  `src/tokens.css`, in order. `light-dark(a, b)` becomes a value per
  theme and `var(--x)` an alias.
- **Components.** `entry.js` bundles everything `src/index.js` exports
  into one classic script, `window.Queenswood`, and its stylesheet.
- **Source notes.** The comment that opens each component's script goes
  into that component's README as its author wrote it.

## What is kept here

- `colour-notes.json`: a usage note per colour. The build stops when
  `tokens.css` declares a colour with no note, or a note names a colour
  no longer declared.
- `scales.json`: the type styles, and the spacing, radius, shadow, size
  and z-index values. `tokens.css` declares none of these, so they were
  read from the components' styles and are named by value. A component
  that changes one does not change this file.
- `components.json`: each card's group, height, sub-components and
  guidance, in display order.
- `previews/`: one Svelte file per card. The card's live preview is this
  file compiled, and its README shows the source.
- `index.d.ts`: each component's props, as documentation.
- `brand-book.md`: the artifact's README.
- `cover.html`, `preview.css`, `assets/`: the cover, the styles every
  preview shares, and the notes beside the logo and screen uploads.

## Changing something

- **A colour.** Change `tokens.css`. Add or remove its note in
  `colour-notes.json` where the build asks.
- **A new component.** Add `previews/<Name>.svelte`, an entry in
  `components.json` and a `<Name>Props` interface in `index.d.ts`. Run
  the check and set the entry's `height` to what it reports.
- **A component's props.** Change its interface in `index.d.ts`, and its
  preview where the preview uses them.

## Publishing

A Claude session publishes: ask it to re-sync the Queenswood Design
System artifact from `target/ui-design-system/project`. It reads the
artifact's index, sends the files that differ and writes the index last.
The logo files and the console screenshots are uploads the index
records, from `site/assets` and `bases/console/public`; they are sent
again only when one changes.

`build.py` leaves two things alone when it writes the bundle, and each
breaks the previews if undone: the stylesheet is not minified, because
the minifier rewrites `light-dark()`, and type styles are named
`type-*`, because the artifact's page emits a class per style and a bare
name such as `label` collides with a component's own class.
