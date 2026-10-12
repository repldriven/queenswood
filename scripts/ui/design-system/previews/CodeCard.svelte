<script>
  import { CodeCard } from "@queenswood/ui";
</script>

<CodeCard filename="~/queenswood · zsh">
<pre><span class="syn-comment"># Authed via OAuth (Keycloak). Issue an API key for machine-to-machine.</span>
<span class="syn-keyword">curl</span> -X POST https://api.queenswood.local/v1/organisations \
  -H <span class="syn-string">"Authorization: Bearer $QW_OAUTH_TOKEN"</span> \
  -H <span class="syn-string">"Content-Type: application/json"</span> \
  -d <span class="syn-string">'&#123; "name": "northwind-fs",
       "jurisdiction": "GB" &#125;'</span>

<span class="syn-comment">#</span> <span class="syn-emphasis">&#123; "id": "org_01HW7…",</span>
<span class="syn-comment">#  </span>  <span class="syn-emphasis">"status": "active" &#125;</span></pre>
</CodeCard>
