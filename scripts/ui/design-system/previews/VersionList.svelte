<script>
  import { VersionList } from "@queenswood/ui";
  const VERSIONS = [
    { id: "prv.v2", number: 2, published: true, meta: "2.75%", from: "from 1 Jun 2025", right: "4,881" },
    { id: "prv.v3", number: 3, published: true, meta: "3.10%", from: "from 1 Jan 2026", right: "4,307" },
    { id: "prv.v4", number: 4, published: true, meta: "3.40%", from: "from 1 Sep 2026", right: "GBP" },
    { id: "prv.v5", number: 5, published: false, meta: "3.55% · draft", from: null, right: "GBP" },
  ];
  let sources = $state(["prv.v2", "prv.v3"]);
  let target = $state("prv.v4");
</script>

<div class="pv-grid" style="grid-template-columns:repeat(2,minmax(0,1fr));align-items:start">
  <VersionList
    mode="multi"
    title="Instant Access Savings · 4 versions"
    action={{ label: "Select all published", onclick: () => (sources = VERSIONS.filter((v) => v.published).map((v) => v.id)) }}
    versions={VERSIONS}
    selected={sources}
    onchange={(ids) => (sources = ids)}
  />
  <VersionList
    mode="single"
    name="pv-target-version"
    title="Instant Access Savings · target"
    versions={VERSIONS}
    selected={target}
    onchange={(id) => (target = id)}
  />
</div>
