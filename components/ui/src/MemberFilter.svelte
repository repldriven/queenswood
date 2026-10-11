<script>
  /* MemberFilter — a log rail's section listing the bank's members, each
     a checkbox, nothing ticked meaning everyone.

         <MemberFilter {members} selected={userIds}
           onChange={(ids) => (userIds = ids)} />

     `members` are `{ user-id, name, email }` as the members list returns
     them; `selected` and `onChange` deal in user ids, in the members'
     order. A member with no name shows by email. */

  import Checkbox from "./Checkbox.svelte";

  let { members = [], selected = [], onChange, label = "Member" } = $props();

  const chosen = $derived(new Set(selected));

  function toggle(id) {
    const next = new Set(chosen);
    next.has(id) ? next.delete(id) : next.add(id);
    onChange(members.map((m) => m["user-id"]).filter((u) => next.has(u)));
  }
</script>

<div class="qw-member-filter">
  <div class="top">
    <span class="label">
      {#if selected.length}
        <b>{selected.length}</b> of {members.length} {label.toLowerCase()}s
      {:else}
        {label} · all {members.length}
      {/if}
    </span>
    <button type="button" class="clear" disabled={!selected.length} onclick={() => onChange([])}>Clear</button>
  </div>
  <div class="list">
    {#each members as m (m["user-id"])}
      <Checkbox checked={chosen.has(m["user-id"])} title={m.email} onclick={() => toggle(m["user-id"])}>
        {m.name || m.email}
      </Checkbox>
    {:else}
      <span class="none">No members yet.</span>
    {/each}
  </div>
</div>

<style>
  .qw-member-filter {
    display: flex;
    flex-direction: column;
    gap: 6px;
    padding: 12px 6px 8px;
    border-bottom: 1px solid var(--rule-2);
  }
  .top {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    padding: 0 4px 0 8px;
  }
  .label {
    font-family: var(--mono);
    font-size: 10px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--fg-muted);
    white-space: nowrap;
  }
  .label b {
    color: var(--fg);
    font-weight: 500;
  }
  .clear {
    padding: 2px 4px;
    border: none;
    border-radius: 4px;
    background: none;
    font-family: var(--mono);
    font-size: 11px;
    color: light-dark(var(--pine-3), var(--pine-5));
    cursor: pointer;
  }
  .clear:hover:not(:disabled) {
    background: var(--hover-overlay);
    color: var(--fg);
  }
  .clear:disabled {
    color: var(--fg-muted);
    opacity: 0.5;
    cursor: default;
  }
  .list {
    display: flex;
    flex-direction: column;
    gap: 1px;
    max-height: 168px;
    overflow-y: auto;
  }
  .list :global(.qw-checkbox:hover) {
    background: var(--hover-overlay);
    color: var(--fg);
  }
  .none {
    padding: 4px 8px;
    font-size: 12px;
    color: var(--fg-muted);
  }
</style>
