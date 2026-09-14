<script>
  /* Textarea — multi-line input with a live character counter under it,
     which turns rust at the limit.

         <Textarea id="reason" bind:value={reason} maxlength={500}
                   placeholder="Kept on the record." /> */

  let { value = $bindable(""), maxlength = 500, ...rest } = $props();

  const length = $derived((value ?? "").length);
</script>

<textarea class="qw-textarea" bind:value {maxlength} {...rest}></textarea>
<div class="qw-char-count" class:over={length >= maxlength}>
  {length} / {maxlength}
</div>

<style>
  .qw-textarea {
    width: 100%;
    min-height: 76px;
    padding: 10px 12px;
    border-radius: 6px;
    border: 1px solid var(--rule);
    background: var(--surface);
    color: var(--fg);
    font: inherit;
    font-size: 13.5px;
    line-height: 1.45;
    resize: vertical;
    transition: border-color 0.12s;
  }
  .qw-textarea:hover {
    border-color: light-dark(rgba(20, 15, 10, 0.18), rgba(244, 241, 234, 0.2));
  }
  .qw-textarea:focus {
    outline: 2px solid var(--gold);
    outline-offset: -1px;
    border-color: var(--gold);
  }
  .qw-textarea::placeholder {
    color: var(--fg-muted);
  }
  .qw-char-count {
    font-family: var(--mono);
    font-size: 10.5px;
    color: var(--fg-muted);
    text-align: right;
  }
  .qw-char-count.over {
    color: var(--danger);
  }
</style>
