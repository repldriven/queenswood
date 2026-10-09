<script>
  /* AccountStatusBadge — maps a cash-account status to the matching
     Badge tone, mirroring JobStatusBadge. The lifecycle enum is
     opening | opened | closing | closed (a watcher drives the
     transitions); tolerate a `:cash-account-status-…` keyword spelling too.

       opened   → published (live)
       opening  → pending   (awaiting the open watcher)
       closing  → draft      (close in flight)
       closed   → rejected  (terminal) */

  import Badge from "./Badge.svelte";

  let { status = "opened" } = $props();

  const label = $derived(
    String(status)
      .replace(/^:/, "")
      .replace(/^cash-account-status-/, ""),
  );

  const TONE = {
    opened: "published",
    opening: "pending",
    closing: "draft",
    closed: "rejected",
  };
  const tone = $derived(TONE[label] ?? "neutral");
</script>

<Badge {tone}>{label}</Badge>
