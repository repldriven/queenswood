<script>
  import { Sidenav, SidenavGroup, SidenavItem } from "@queenswood/ui";
</script>

<div class="pv-sidenav">
  <Sidenav top={0}>
    <SidenavGroup title="Manage">
      <SidenavItem href="#products" current title="Products">
        {#snippet icon()}
          <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M2 4 L14 4" /><path d="M2 8 L14 8" /><path d="M2 12 L14 12" /></svg>
        {/snippet}
        Products
      </SidenavItem>
      <SidenavItem href="#parties" title="Parties">
        {#snippet icon()}
          <svg viewBox="0 0 16 16" aria-hidden="true"><circle cx="8" cy="6" r="2.6" /><path d="M3 14c0.8-3 2.6-4.6 5-4.6s4.2 1.6 5 4.6" /></svg>
        {/snippet}
        Parties
      </SidenavItem>
      <SidenavItem href="#accounts" title="Accounts">
        {#snippet icon()}
          <svg viewBox="0 0 16 16" aria-hidden="true"><rect x="2.5" y="3.5" width="11" height="9" rx="1" /><path d="M2.5 7.5 L13.5 7.5" /><circle cx="11" cy="10" r="0.8" /></svg>
        {/snippet}
        Accounts
      </SidenavItem>
    </SidenavGroup>
    <SidenavGroup title="Compliance">
      <SidenavItem href="#policies" title="Policies">
        {#snippet icon()}
          <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 2 L13 4 V8 C13 11 10.5 13 8 14 C5.5 13 3 11 3 8 V4 Z" /><path d="M5.8 8 L7.4 9.6 L10.4 6.6" /></svg>
        {/snippet}
        Policies
      </SidenavItem>
    </SidenavGroup>
  </Sidenav>
</div>
