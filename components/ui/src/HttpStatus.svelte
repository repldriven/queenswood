<script>
  /* HttpStatus — a response's status code with a dot coloured by its
     class: 2xx succeeded, 4xx refused (the client's to fix), 5xx failed
     (the platform's).

         <HttpStatus status={409} /> */

  import { statusClass, statusWord } from "./access-log.js";

  let { status } = $props();
</script>

<span class="qw-http-status s{statusClass(status)}" title={statusWord(status)}>{status}</span>

<style>
  .qw-http-status {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-family: var(--mono);
    font-size: 12px;
    font-weight: 500;
  }
  .qw-http-status::before {
    content: "";
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: currentColor;
  }
  .s2 {
    color: var(--ok);
  }
  .s4 {
    color: var(--gold-deep);
  }
  .s5 {
    color: var(--danger);
  }
</style>
