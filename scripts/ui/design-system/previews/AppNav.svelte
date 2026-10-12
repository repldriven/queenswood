<script>
  import { AppNav } from "@queenswood/ui";
  const noop = () => {};
</script>

<div class="pv-stack">
  <div class="pv-frame pv-unstick"><AppNav user={{ name: "Alex Morgan" }} onSignOut={noop} /></div>
  <div class="pv-frame pv-unstick"><AppNav user={null} onSignOut={noop} /></div>
</div>
