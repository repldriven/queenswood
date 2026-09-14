<script>
  /* RolePill — a member's or invitation's role, toned by reach up the
     ladder: owner gold, admin pine, developer violet, viewer neutral.

         <RolePill role="admin" />

     Mono, lowercase, square-cornered, so it reads as a value rather
     than a status (which is what the round Badge is for). */

  let { role } = $props();
</script>

<span class="qw-role {role}">{role}</span>

<style>
  .qw-role {
    display: inline-flex;
    align-items: center;
    height: 20px;
    padding: 0 8px;
    border-radius: 4px;
    font-family: var(--mono);
    font-size: 11px;
    letter-spacing: 0.03em;
    text-transform: lowercase;
    white-space: nowrap;
    border: 1px solid var(--rule);
    background: var(--surface-sunk);
    color: var(--fg-muted);
  }
  .qw-role.owner {
    background: light-dark(oklch(0.94 0.055 82), oklch(0.30 0.06 75));
    color: light-dark(oklch(0.44 0.115 68), oklch(0.88 0.10 85));
    border-color: light-dark(oklch(0.86 0.075 78), oklch(0.40 0.07 72));
  }
  .qw-role.admin {
    background: light-dark(oklch(0.93 0.04 145), oklch(0.27 0.05 145));
    color: light-dark(oklch(0.34 0.075 145), oklch(0.84 0.06 145));
    border-color: light-dark(oklch(0.86 0.05 145), oklch(0.36 0.055 145));
  }
  .qw-role.developer {
    background: light-dark(oklch(0.93 0.035 270), oklch(0.28 0.045 270));
    color: light-dark(oklch(0.40 0.08 270), oklch(0.82 0.07 270));
    border-color: light-dark(oklch(0.87 0.04 270), oklch(0.37 0.05 270));
  }
  .qw-role.viewer {
    background: light-dark(oklch(0.93 0.004 70), oklch(0.27 0.004 70));
    color: light-dark(oklch(0.45 0.005 70), oklch(0.74 0.005 70));
    border-color: var(--rule);
  }
</style>
