# signoz-operator

The SigNoz Operator, vendored from its Helm chart so the CRDs land ahead
of the templates that use them: the queenswood chart's `ProviderConfig`
and `Dashboard` resources are checked against the API server before
anything is created, and upstream ships its CRDs as templates.

## Layout

- `crds/` — the eleven CRDs, rendered once from upstream's
  `templates/crds/` with its default values. Helm installs everything
  here before templates, and never templates it.
- `templates/` — upstream's operator templates, verbatim.
- `values.yaml` — upstream's, verbatim but for `crds.install: false`.

## Sourced from

Version `0.0.2` of the `signoz-operator` chart at
`https://charts.signoz.io`, operator `v0.0.2`.

## Refreshing

Download the chart at the new version, then:

```
SRC=<unpacked signoz-operator chart>
cp "$SRC"/Chart.yaml "$SRC"/values.yaml .
cp "$SRC"/templates/*.yaml "$SRC"/templates/_helpers.tpl "$SRC"/templates/NOTES.txt templates/
for f in "$SRC"/templates/crds/*.yaml; do
  n=$(basename "$f")
  helm template signoz-operator "$SRC" --show-only "templates/crds/$n" \
    | sed '/^# Source:/d' > "crds/$n"
done
```

Then set `crds.install: false` in `values.yaml` again, and the version
in the queenswood chart's `Chart.yaml` dependency. A CRD under `crds/`
is installed once and never upgraded by Helm, so a changed CRD needs
applying by hand on a cluster that already has it.
