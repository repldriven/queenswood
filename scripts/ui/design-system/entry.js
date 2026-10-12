// The design system's bundle: everything @queenswood/ui exports, Svelte's
// mount, and one compiled demo per component card, as window.Queenswood.

export * from "@queenswood/ui";
import { mount, unmount, flushSync, createRawSnippet } from "svelte";

const modules = import.meta.glob("./previews/*.svelte", { eager: true, import: "default" });
const previews = Object.fromEntries(
  Object.entries(modules).map(([path, component]) => [
    path.slice("./previews/".length, -".svelte".length),
    component,
  ]),
);

const escapeHtml = (s) =>
  String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);

// A snippet from an HTML string, for a component's `children` or a named
// snippet prop when mounting from plain JavaScript.
export function snippet(html) {
  return createRawSnippet(() => ({
    render: () => `<span style="display:contents">${html}</span>`,
  }));
}

// mount(Component, { target, props }) with a string `children` taken as text.
export function render(Component, target, props = {}) {
  const p = { ...props };
  if (typeof p.children === "string") p.children = snippet(escapeHtml(p.children));
  return mount(Component, { target, props: p });
}

export { mount, unmount, flushSync, createRawSnippet, previews };
