<script>
  import { ThemeToggle, themeState, resolvedTheme } from "@queenswood/ui";
</script>

<div class="pv-row">
  <ThemeToggle />
  <span class="pv-label">pref {themeState.pref} · resolved {resolvedTheme()}</span>
</div>
