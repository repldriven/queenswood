// Labels for the kinds of provider an installation offers and the
// providers of each. A provider is shown by its key, capitalised, so the
// console holds no list of providers and one offered later shows as it
// is.
const KIND_LABELS = {
  payment: "Payments",
  idv: "Identity verification",
};

export function kindLabel(kind) {
  return KIND_LABELS[kind] ?? kind;
}

export function providerLabel(key) {
  return key ? key[0].toUpperCase() + key.slice(1) : "—";
}

// The default of each kind offered, as a map from kind to provider key:
// the create form's starting choice.
export function defaultChoice(kinds) {
  return Object.fromEntries(kinds.map((k) => [k.kind, k.default]));
}

// A bank's providers, a map from kind to provider key, as one line:
// "Payments on Modulr · Identity verification on Zyphe".
export function providersLine(providers) {
  return Object.entries(providers ?? {})
    .map(([kind, key]) => `${kindLabel(kind)} on ${providerLabel(key)}`)
    .join(" · ");
}
